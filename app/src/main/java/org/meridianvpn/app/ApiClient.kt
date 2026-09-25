package org.meridianvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Транспорт до нашего API.
 *
 * Про ручки не знает НИЧЕГО. Всё, что он умеет, — сходить по пути и
 * вернуть разобранное тело; какие бывают пути и что в телах, знает
 * Api.kt. Ради этого разделения файл и отдельный: добавление ручки не
 * должно требовать ни строчки здесь.
 *
 * ОБХОД АДРЕСОВ. Молчание первого адреса — это НЕ «API недоступен», это
 * «идём ко второму». Недоступность объявляется, только когда молчат все.
 * Последний удачный адрес запоминается и в следующий раз пробуется
 * первым — тем же устройством, что и память путей в лестнице транспорта.
 *
 * ДОВЕРИЕ ПОСТРОЕНО ТОЛЬКО НА ПИНЕ, и это не упрощение, а единственный
 * работающий вариант. Сертификат службы САМОПОДПИСАННЫЙ (CN=meridian-api,
 * проверено с работающего порта 24.08.2026), в системном хранилище его
 * нет, и обычная проверка цепочки отвергнет его всегда. В его SAN лежит
 * только IP-адрес, доменного имени там нет вовсе, поэтому и проверка по
 * имени для адреса jinelectronics.ru не прошла бы тоже.
 *
 * Остаётся пин — и его ДОСТАТОЧНО: он привязывает соединение к
 * конкретному нашему ключу, что строже и цепочки, и имени. Подделать
 * его нельзя, не имея приватной части.
 *
 * Цена такого решения, чтобы она была названа: вместе с цепочкой мы
 * перестаём проверять и срок годности сертификата. Для закреплённого
 * ключа это не потеря — просрочка сама по себе ничего не значит, когда
 * ключ и так известен поимённо. Утечка ключа лечится переходом на
 * запасной пин, ради чего он и заведён.
 */
object ApiClient {

    /** Версия договора, которую понимает эта сборка. */
    const val CONTRACT = "v1"

    private const val PORT = 21947

    /**
     * Один адрес API: куда стучаться, каким портом и как проверять TLS.
     *
     * `pinned = true` — доверие только по закреплённому ключу (`ApiPins`),
     * сертификат самоподписанный, цепочки доверия нет и не будет.
     * `pinned = false` — обычное системное доверие: у адреса настоящий
     * сертификат от публичного удостоверяющего центра, проверять его
     * закреплённым ключом нельзя (ключ там свой, не `bYxJ...`) и не нужно
     * (уже подтверждён цепочкой).
     */
    private data class Address(val host: String, val port: Int = PORT, val pinned: Boolean = true)

    /**
     * Адреса API по порядку.
     *
     * Домен первым, IP вторым: имя может не разрешиться, адрес — нет.
     *
     * ТРЕТИЙ — cdn.jincomputers.win, добавлен 23.09 (Кот1: traefik по SNI
     * заводит настоящий Let's Encrypt на отдельном терминаторе перед
     * meridian-api, порт обычный 443, не 21947). Это ЕДИНСТВЕННЫЙ адрес с
     * `pinned = false` — у него есть цепочка доверия, а закреплённый ключ
     * `bYxJ...` тут в принципе не подойдёт: сертификат другой, настоящий
     * CA-выпуск, а не самоподписанный. Применить к нему пин — повторить
     * инцидент 14.09 наоборот (тогда пин ждал самоподписанный, а получил
     * LE; здесь пин ждал бы LE-ключ, которого нет и не может быть заранее
     * известен — LE меняет ключ при каждом продлении).
     *
     * ПЕРВЫЕ ДВА адреса обязаны отдавать ОДИН И ТОТ ЖЕ закреплённый ключ.
     * На 24.08.2026 так и есть, проверено обоим и снова 23.09 после
     * восстановления сертификата.
     */
    private val ADDRESSES = listOf(
        Address("jinelectronics.ru"),
        Address("138.249.246.89"),
        Address("cdn.jincomputers.win", port = 443, pinned = false),
    )

    /**
     * Срок на установление соединения с ОДНИМ адресом.
     *
     * 2500 мс, и число не круглое не случайно. Живой сервер по мобильной
     * сети отвечает быстрее секунды: TCP это один обмен, TLS 1.3 — ещё
     * один, около двух RTT. Но если первый SYN потеряется — на мобильной
     * сети дело обычное, — ядро повторит его примерно через секунду.
     * Значит честный, но неудачливый адрес укладывается в
     * RTT + 1000 + RTT, и при плохих 500 мс RTT это ровно две секунды.
     * Меньше 2000 мс мы начали бы отбрасывать работающие адреса; 2500 —
     * те же две секунды с небольшим запасом.
     */
    private const val CONNECT_MS = 2500

    /**
     * Срок на ответ после того, как соединение встало.
     *
     * 3500 мс. Сама служба отвечает за миллисекунды, но за ней стоит
     * база на Aeza, а служба — в Беларуси, и этот участок нам не виден.
     * Здесь запас нужнее скорости: соединение уже установлено, значит
     * адрес живой, и бросать его из-за медленной базы жалко.
     */
    private const val READ_MS = 3500

    /**
     * Срок на ВЕСЬ обход, сколько бы адресов ни было.
     *
     * Это главное число. Без него три мёртвых адреса складываются в
     * 3 × 6 = 18 секунд ожидания на глазах у человека, а с четвёртым
     * станет 24. Потолок на обход целиком делает ожидание независимым
     * от длины списка.
     *
     * Десять секунд выбраны из того, сколько человек ждёт дальше: проход
     * лестницы транспорта — до 25 секунд (engine/session.go, ladderBudget).
     * Десять сверху удлиняют худший случай на 40%, тридцать — удваивают.
     * Первое неприятно, второе выглядит как зависание.
     */
    private const val WALK_BUDGET_MS = 10_000L

    /**
     * ПОВТОРОВ ВНУТРИ ОДНОГО АДРЕСА НЕТ, и это не упущение.
     *
     * Договор (47.4) требует до трёх попыток на вызов. Обход трёх
     * адресов и есть эти три попытки — просто каждая идёт к другому
     * адресу, что заведомо полезнее, чем стучаться в тот же самый.
     * Повторы сверх обхода дали бы девять заходов и ровно то ожидание,
     * ради которого потолок выше и вводился.
     */

    private const val PREFS = "meridian_api"
    private const val K_LAST_OK = "last_ok"

    private var appCtx: Context? = null

    fun attach(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    /** Итог обращения. Четыре исхода, и путать их нельзя. */
    sealed class Result {
        /**
         * Служба ответила по нашему договору.
         *
         * `code` — код HTTP. Он нужен потому, что «ответила» и
         * «согласилась» — разные вещи: /v1/trial при исчерпанном
         * пределе отвечает 429, но с телом по договору, где
         * status: answered и пустой key. Без кода мы прочли бы это как
         * успех с непонятно пустым ключом.
         */
        data class Ok(val body: JSONObject, val code: Int) : Result()

        /**
         * Служба ответила отказом. Это ЗНАНИЕ, а не сбой.
         *
         * `code` — код HTTP. Нужен ровно одной ручке: /v1/trial
         * отличает по нему предел выдачи (429) от прочих отказов.
         * Разбирать по коду что-то ещё не надо: смысл отказа несёт тело.
         */
        data class Refused(val error: String, val retryAfter: Int, val code: Int) : Result()

        /** Договор чужой. Тело не разобрано намеренно. */
        data class Incompatible(val theirs: String) : Result()

        /** Молчат все адреса. Только здесь «API недоступен». */
        data class Unavailable(val why: String) : Result()
    }

    /**
     * Сходить по пути. Единственный вход в сеть.
     *
     * ЗВАТЬ ТОЛЬКО С ФОНОВОГО ПОТОКА — блокирует до WALK_BUDGET_MS.
     *
     * @param path например "/v1/key"
     * @param query параметры запроса; общий конверт добавляется сам
     * @param post слать телом, а не строкой запроса. Сегодняшние ручки
     *             работают через GET, но ручка, сделанная завтра, может
     *             захотеть POST — тогда достаточно этого флага.
     */
    fun call(
        path: String,
        query: Map<String, String> = emptyMap(),
        post: Boolean = false,
    ): Result {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Не IOException: это не сбой сети, а ошибка в нашем коде,
            // и прятать её в «нет сети» нельзя.
            throw IllegalStateException("ApiClient.call с главного потока")
        }
        val ctx = appCtx ?: return Result.Unavailable("клиент не подключён к контексту")

        val params = envelope(ctx) + query
        val deadline = System.currentTimeMillis() + WALK_BUDGET_MS
        var lastWhy = "адресов нет"

        for (addr in ordered()) {
            if (System.currentTimeMillis() >= deadline) {
                TunnelLog.add("API: срок обхода исчерпан, оставшиеся адреса не пробовал")
                break
            }
            when (val r = one(addr, path, params, post)) {
                is Result.Unavailable -> {
                    // Молчание ОДНОГО адреса — не отказ. Идём дальше.
                    lastWhy = r.why
                    TunnelLog.add("API: ${addr.host} не ответил ($lastWhy), пробую следующий")
                }
                else -> {
                    rememberGood(addr.host)
                    return r
                }
            }
        }
        TunnelLog.add("API недоступен: молчат все адреса ($lastWhy)")
        return Result.Unavailable(lastWhy)
    }

    /** Итог отправки лога в поддержку (POST /v1/logs, договор кота 1, 25.09). */
    sealed class LogResult {
        /** Принят. ticket — номер обращения для человека, «M-XXXXX». */
        data class Ticket(val ticket: String) : LogResult()

        /** Слишком часто: 429, повторить через retryAfter секунд. */
        data class TooOften(val retryAfter: Int) : LogResult()

        /** Не приняли (413 — велик, 503 — хранилище полно, иное) или не дозвонились. */
        data class Failed(val why: String) : LogResult()
    }

    /**
     * Отправка лога поддержке. Те же адреса, тот же пин и та же сеть мимо
     * туннеля, что у остальных запросов, — поэтому отдельной функцией, а не
     * через call(): у call() параметры только в строке запроса и тела нет
     * по договору, а здесь тело — сам лог.
     *
     * Тело — text/plain, сжатое gzip. Заголовки (договор кота 1):
     * X-Meridian-Platform, X-Meridian-Version, X-Meridian-Key-Label (первые 8
     * hex sha256 ключа; без ключа не шлём), X-Meridian-Device (4 знака).
     *
     * ЗВАТЬ ТОЛЬКО С ФОНОВОГО ПОТОКА.
     */
    fun postLog(gz: ByteArray, headers: Map<String, String>): LogResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("ApiClient.postLog с главного потока")
        }
        var lastWhy = "адресов нет"
        for (addr in ordered()) {
            var conn: HttpsURLConnection? = null
            val t0 = System.currentTimeMillis()
            try {
                val url = URL("https://${addr.host}:${addr.port}/v1/logs")
                val net = underlying()
                conn = (net?.openConnection(url) ?: url.openConnection()) as HttpsURLConnection
                if (addr.pinned) {
                    conn.sslSocketFactory = pinnedFactory()
                    conn.hostnameVerifier = pinnedVerifier(addr.host)
                }
                conn.connectTimeout = CONNECT_MS
                // Тело до ~сотни КБ после сжатия: на медленной сети
                // READ_MS мало, даём втрое.
                conn.readTimeout = READ_MS * 3
                conn.useCaches = false
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.setRequestProperty("Accept", "application/json")
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                conn.setFixedLengthStreamingMode(gz.size)
                conn.outputStream.use { it.write(gz) }

                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                rememberGood(addr.host)
                // Что ответил сервер и как быстро — в лог, чтобы по нему было видно,
                // ПОЧЕМУ приём не сработал (404 — ручки нет, 5xx — сбой и т. д.).
                TunnelLog.add(
                    "лог в поддержку: ${addr.host}:${addr.port} ответил $code за " +
                        "${System.currentTimeMillis() - t0} мс" +
                        (if (code == 200) "" else ", тело: " + text.take(120).replace("\n", " "))
                )
                return when (code) {
                    200 -> {
                        val t = try { JSONObject(text).optString("ticket") } catch (e: Throwable) { "" }
                        if (t.isNotEmpty()) LogResult.Ticket(t)
                        else LogResult.Failed("ответ без номера обращения")
                    }
                    429 -> LogResult.TooOften(conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 600)
                    413 -> LogResult.Failed("лог слишком большой")
                    503 -> LogResult.Failed("приём логов временно недоступен")
                    else -> LogResult.Failed("служба ответила $code")
                }
            } catch (e: Throwable) {
                lastWhy = e.message ?: e.javaClass.simpleName
                TunnelLog.add(
                    "лог в поддержку: ${addr.host}:${addr.port} не дозвонился за " +
                        "${System.currentTimeMillis() - t0} мс (${e.javaClass.simpleName}: $lastWhy)"
                )
            } finally {
                try {
                    conn?.disconnect()
                } catch (e: Throwable) {
                }
            }
        }
        return LogResult.Failed("сервер недоступен ($lastWhy)")
    }

    /** Один заход к одному адресу. */
    private fun one(
        addr: Address,
        path: String,
        params: Map<String, String>,
        post: Boolean,
    ): Result {
        val host = addr.host
        var conn: HttpsURLConnection? = null
        try {
            val qs = params.entries.joinToString("&") {
                enc(it.key) + "=" + enc(it.value)
            }
            // ПАРАМЕТРЫ ТОЛЬКО В СТРОКЕ ЗАПРОСА — единственное место,
            // откуда служба их читает. Тело не отправляется никогда, см.
            // ниже про requestMethod: раньше слали ещё и тело «на всякий
            // случай», оно не читалось, то есть работало случайно, и
            // убрано. Прежний комментарий здесь говорил «уходят и телом»
            // — он противоречил и коду, и разбору кота 2.
            //
            // Лишние параметры службы игнорируют, поэтому шлём весь
            // конверт: /v1/purchase возьмёт из него token и пройдёт мимо
            // остального.
            val url = "https://$host:${addr.port}$path?$qs"

            // ЧЕРЕЗ КАКУЮ СЕТЬ. Это не украшение, а условие работы.
            //
            // При поднятом туннеле сетью по умолчанию для приложения
            // становится сам туннель, и обычный openConnection() уводит
            // запрос ВНУТРЬ него: сокет уходит с адреса 10.77.77.x, до
            // белорусской машины оттуда дороги нет, а имя не разрешается
            // вовсе — DNS в туннеле тоже наш.
            //
            // Network.openConnection() закрывает ОБА пути сразу: и сокет
            // привязывается к этой сети, и разрешение имени идёт по её
            // серверам. Ровно то, что в договоре записано словами
            // «ходить надо до подключения, вне туннеля».
            //
            // protect() здесь не нужен и не применим: в движке мы сами
            // создаём сокет и потому можем исключить его дескриптор, а
            // тут сокет создаёт стек HTTP, и управлять им можно только
            // выбором сети.
            //
            // Сети нет — идём как раньше. Это случай погашенного
            // туннеля, когда исключать не из чего.
            val net = underlying()
            conn = (net?.openConnection(URL(url)) ?: URL(url).openConnection())
                as HttpsURLConnection
            // ПИН — ТОЛЬКО ДЛЯ АДРЕСОВ С САМОПОДПИСАННЫМ СЕРТИФИКАТОМ.
            // У адреса с pinned=false (cdn.jincomputers.win) — настоящая
            // цепочка от публичного удостоверяющего центра; обычное
            // системное доверие уже проверяет её, а закреплённый ключ
            // здесь просто чужой и всегда провалит сверку.
            if (addr.pinned) {
                conn.sslSocketFactory = pinnedFactory()
                conn.hostnameVerifier = pinnedVerifier(host)
            }
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")

            // ТОКЕНА ЗДЕСЬ НЕТ И НЕ БУДЕТ. Наружные ручки его не
            // проверяют, и это решение кота 2, а не упущение: секрет,
            // лежащий в APK, — не секрет, его достанут в первый день.
            //
            // Заголовок X-Api-Token, который я сюда завёл наугад, убран
            // вместе с параметром. Неиспользуемая заготовка с угаданным
            // именем опаснее её отсутствия: ею однажды воспользуются.

            // ТЕЛО НЕ ОТПРАВЛЯЕТСЯ НИКОГДА, даже при POST.
            //
            // Служба читает параметры ТОЛЬКО из строки запроса: и
            // /v1/key (GET), и /v1/trial (POST с пустым телом). Раньше
            // я слал ещё и тело — «на всякий случай, вдруг читает
            // оттуда». Оно не читалось, то есть работало случайно, и
            // выглядело бы причиной, если бы что-то сломалось.
            //
            // POST здесь означает только одно: ручка создаёт ключ, и на
            // GET отвечает 405.
            conn.requestMethod = if (post) "POST" else "GET"

            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            return interpret(host, code, text)
        } catch (e: IOException) {
            // Сюда же приходит и провал пина: и доверитель, и проверка
            // имени роняют рукопожатие, а это IOException. Так и надо —
            // для человека это «не дозвонились», и это правда: сервер,
            // который не тот, всё равно что сервер, которого нет.
            return Result.Unavailable(e.message ?: e.javaClass.simpleName)
        } catch (e: Throwable) {
            return Result.Unavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Throwable) {
                // Неважно.
            }
        }
    }

    /**
     * Разбор общей части ответа. Живёт здесь и только здесь.
     *
     * ПРО ПРИЗНАК УСПЕХА. Договор описывает его как `ok: true`, а
     * работающая служба отдаёт `status: "answered"`. Расхождение
     * названо в отчёте по задаче 49 и не выбрано молча: пока оно не
     * сведено, признаём ОБА. Когда стороны договорятся, лишняя половина
     * убирается одной строкой отсюда.
     */
    private fun interpret(host: String, code: Int, text: String): Result {
        val json = try {
            JSONObject(text)
        } catch (e: Throwable) {
            // ОТВЕТ ЕСТЬ, НО ОН НЕ JSON. Так сегодня выглядят 405 и 400:
            // служба отвечает простым текстом.
            //
            // Это НЕ «адрес не ответил», и в обход адресов такое уходить
            // не должно. Сюда мы попадаем уже ПОСЛЕ рукопожатия с
            // закреплённым ключом — значит на том конце наш сервер, и
            // следующий адрес ответит ровно тем же. Раньше это было
            // Unavailable, и один неверно составленный запрос выглядел
            // как «молчат все адреса».
            //
            // Человеку сырой текст не показываем: там бывает «только
            // GET», и это наша ошибка, а не его.
            val brief = text.take(160).replace('\n', ' ').trim()
            TunnelLog.add("API $host: ответ не по договору (код $code): $brief")
            return Result.Refused("служба ответила не по договору", 0, code)
        }

        val theirs = json.optString("contract", "")
        if (theirs != CONTRACT) {
            // Тело НЕ разбираем дальше ни на одно поле: одинаковое имя в
            // другом договоре может значить другое (47.6).
            TunnelLog.add("API: договор «$theirs», а я понимаю «$CONTRACT» — нужно обновить приложение")
            return Result.Incompatible(theirs)
        }

        val minCode = json.optInt("min_app_code", 0)
        if (minCode > 0 && minCode > appCode()) {
            // Сообщение, а не запрет. Решение владельца, задача 48.4.
            TunnelLog.add("API: вышла новая версия приложения (нужна $minCode, у нас ${appCode()})")
        }

        // ПРИЗНАК УСПЕХА ОДИН — status. Здесь стояло ещё
        // `|| json.optBoolean("ok", false)`, и оно не срабатывало
        // никогда: кот 2 замерил по коду службы, что поля ok она не
        // шлёт нигде. Осталось от бумажного договора, который называл
        // признак успеха иначе, чем живая служба.
        //
        // НЕ ПУТАТЬ со словом "ok" в словаре вердиктов (Api.VERDICTS):
        // там это ЗНАЧЕНИЕ поля verdict, оно живое и приходит каждый
        // день — «API: ключ — ok» в логах владельца. Убрано только
        // одноимённое поле конверта.
        val status = json.optString("status", "")
        val answered = status == "answered"
        if (!answered) {
            val err = json.optString("error").ifEmpty {
                json.optString("message").ifEmpty { "без причины" }
            }
            val retry = json.optInt("retry_after", 0)

            // «Недоступна база» и «служба отказала» — разные вещи, и в
            // логе они должны читаться по-разному. При unavailable
            // вердикта нет вовсе и полей о ключе нет вовсе: хоронить
            // нечего, и строка не должна выглядеть приговором ключу.
            if (status == "unavailable") {
                TunnelLog.add("API $host: проверить не удалось, база недоступна ($err)")
            } else {
                TunnelLog.add("API $host: отказ «$err»" + if (retry > 0) ", повтор через $retry с" else "")
            }
            return Result.Refused(err, retry, code)
        }
        return Result.Ok(json, code)
    }

    /**
     * Сеть ПОД туннелем: любая не-VPN с выходом в интернет.
     *
     * Берём не активную сеть, а перебираем все: как только туннель
     * поднят, активной становится он сам, и activeNetwork вернёт именно
     * его — то есть ровно то, чего мы избегаем.
     *
     * Проверенная (VALIDATED) предпочтительнее, но и непроверенная
     * лучше туннеля: до нашей машины она хотя бы ведёт.
     *
     * allNetworks помечен устаревшим с Android 12, но работает и удалён
     * не был. Замена ему — держать подписку NetworkCallback, то есть
     * заводить объект с жизненным циклом ради одного вопроса, который
     * задаётся раз в несколько минут. Когда метод действительно уберут,
     * готовый наблюдатель уже есть — NetworkWatch, его и подключим.
     */
    @Suppress("DEPRECATION") // allNetworks, см. ниже
    private fun underlying(): Network? = try {
        val cm = appCtx?.getSystemService(ConnectivityManager::class.java)
        var fallback: Network? = null
        var best: Network? = null
        if (cm != null) {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    best = n
                    break
                }
                if (fallback == null) fallback = n
            }
        }
        best ?: fallback
    } catch (e: Throwable) {
        null
    }

    // ------------------------------------------------------------------
    // TLS: доверие только по пину
    // ------------------------------------------------------------------

    @Volatile
    private var factory: SSLSocketFactory? = null

    /**
     * Фабрика сокетов, доверяющая ТОЛЬКО закреплённому ключу.
     *
     * Системное хранилище корней здесь не участвует вовсе: сертификат
     * службы самоподписанный, и любая цепочка от него всё равно никуда
     * не ведёт. Решение принимает пин.
     *
     * Это НЕ «доверитель, принимающий всё». Несовпадение пина бросает
     * CertificateException, и рукопожатие не состоится. Ветки «принять
     * любой, если не совпало» здесь нет и появиться не должно.
     */
    private fun pinnedFactory(): SSLSocketFactory {
        factory?.let { return it }
        synchronized(this) {
            factory?.let { return it }
            val tm = object : X509TrustManager {
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain == null || chain.isEmpty()) {
                        throw CertificateException("сервер не прислал сертификата")
                    }
                    if (!ApiPins.matches(chain)) {
                        throw CertificateException("ключ сервера не совпал с закреплённым")
                    }
                }

                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    // Мы никогда не сервер. Если сюда попали — что-то не то.
                    throw CertificateException("проверка клиента здесь неуместна")
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(tm), SecureRandom())
            val f = ctx.socketFactory
            factory = f
            return f
        }
    }

    /**
     * Вторые ворота: тот же пин, но уже на готовом соединении.
     *
     * Доверитель выше решает всё сам, и этот проверяющий строго говоря
     * избыточен. Оставлен намеренно: если однажды кто-то подменит
     * фабрику сокетов на обычную, закрепление не должно отключиться
     * молча — оно должно упасть здесь.
     *
     * Проверки по ИМЕНИ здесь нет, и это осознанно. В сертификате
     * службы доменного имени нет вовсе (SAN содержит только IP), так что
     * обычная проверка имени провалила бы обращение к jinelectronics.ru
     * при полностью исправном сервере. Пин строже имени и заменяет его.
     */
    private fun pinnedVerifier(host: String) = javax.net.ssl.HostnameVerifier { _, session ->
        val ok = ApiPins.matches(
            try {
                session?.peerCertificates
            } catch (e: Throwable) {
                null
            }
        )
        if (!ok) {
            TunnelLog.add("API: ключ $host не совпал с закреплённым — считаю адрес недоступным")
        }
        ok
    }

    // ------------------------------------------------------------------

    /**
     * Общая часть каждого запроса. Больше клиенту предложить нечего.
     *
     * `app_version` и прочее служба сегодня не читает, но и не мешает:
     * лишние параметры она игнорирует. Отправляем их сразу, чтобы к
     * появлению привязки устройства не пришлось трогать ни одну ручку.
     */
    private fun envelope(ctx: Context): Map<String, String> = mapOf(
        "contract" to CONTRACT,
        "device_id" to Access.deviceId(ctx),
        "platform" to "android",
        "app_version" to appVersionName(),
        "app_code" to appCode().toString(),
        "android_sdk" to Build.VERSION.SDK_INT.toString(),
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun appCode(): Int = try {
        val ctx = appCtx ?: return 0
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
    } catch (e: Throwable) {
        0
    }

    private fun appVersionName(): String = try {
        val ctx = appCtx ?: return "?"
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Throwable) {
        "?"
    }

    /** Последний удачный адрес первым, остальные в обычном порядке. */
    private fun ordered(): List<Address> {
        val good = lastGood()
        val goodAddr = ADDRESSES.find { it.host == good } ?: return ADDRESSES
        return listOf(goodAddr) + ADDRESSES.filter { it.host != good }
    }

    private fun lastGood(): String? = try {
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString(K_LAST_OK, null)
    } catch (e: Throwable) {
        null
    }

    private fun rememberGood(host: String) {
        if (lastGood() == host) return
        try {
            appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(K_LAST_OK, host)?.apply()
            TunnelLog.add("API: запомнил рабочий адрес $host")
        } catch (e: Throwable) {
            // Память адреса — удобство, а не условие работы.
        }
    }
}
