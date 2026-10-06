package org.meridianvpn.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Обновление приложения — ТОЛЬКО В СБОРКЕ ДЛЯ ПРЯМОЙ РАЗДАЧИ.
 *
 * Этого файла нет в варианте play, и не по осторожности: самообновление
 * мимо магазина — прямое нарушение его правил, а разрешение
 * REQUEST_INSTALL_PACKAGES в бандле вызвало бы вопросы на ревью. Как и
 * с оплатой, недостижимая ветка оставила бы строки в dex, и найти их
 * можно обычным grep — а значит найдёт и ревью.
 *
 * НАВЯЗЧИВОСТИ ЗДЕСЬ НЕТ И НЕ БУДЕТ. Ни уведомлений в шторке, ни
 * всплывающих окон, ни баннеров. Единственное место, где обновление
 * вообще упоминается, — строка версии в настройках.
 *
 * ЧЕГО ЭТА ШТУКА НЕ УМЕЕТ, и это надо знать заранее:
 *
 *   GitHub в России доступен неровно. Недоступен — строка скажет
 *   "проверить не удалось", и всё. Настоящий канал раздачи — телеграм,
 *   а это удобство поверх него, а не замена;
 *
 *   отката нет. Android не даёт ставить поверх сборку с меньшим
 *   versionCode. Сломанная версия чинится только следующей;
 *
 *   всё держится на неизменности ключа подписи. Другой ключ — и ни
 *   один установленный клиент больше не обновится никогда.
 */
object Update {

    // ------------------------------------------------------------------
    // Откуда берём
    // ------------------------------------------------------------------

    /**
     * Откуда берём сборки. Разное для релиза и отладки, см. Seed.kt.
     *
     * Релиз — открытый репозиторий раздачи, без исходников. Отладка —
     * закрытый репозиторий владельца: отладочная сборка несёт личные
     * ссылки VK и подробный лог, и выкладывать её открыто нельзя.
     */
    private fun repo(): String = updateRepo()

    /**
     * Устойчивый адрес описания последнего релиза — ОТКРЫТЫЙ путь.
     *
     * НЕ через api.github.com: у него предел 60 запросов в час НА
     * АДРЕС, а за операторским NAT адрес общий на многих людей —
     * предел выбрали бы чужие. Путь releases/latest/download/ GitHub
     * перенаправляет на последний релиз сам, без API и без пределов.
     */
    private fun manifestUrl(): String =
        "https://github.com/${repo()}/releases/latest/download/latest.json"

    /**
     * ЗАКРЫТЫЙ репозиторий — только через API.
     *
     * Простой адрес releases/download для закрытого репозитория не
     * работает: файлы там отдаются лишь по идентификатору вложения и
     * только с токеном. Предел в 60 запросов в час тут не страшен —
     * этим путём ходит один телефон владельца, а не весь парк.
     */
    private fun apiLatestUrl(): String =
        "https://api.github.com/repos/${repo()}/releases/latest"

    private fun apiAssetUrl(id: Long): String =
        "https://api.github.com/repos/${repo()}/releases/assets/$id"

    private const val CONNECT_MS = 8000
    private const val READ_MS = 15000

    // ПРОВЕРКА ВЕРСИИ — КОРОТКО (жалоба владельца 03.10: до двух минут и
    // «read timed out»). Три запроса подряд по 8+15 с складывались в
    // минуты на медленной мобильной сети. Теперь срок на каждый шаг
    // короче, а вся проверка ограничена общим сроком. Скачивание сборки
    // этих сроков не касается — там прежние CONNECT_MS/READ_MS.
    private const val CHECK_CONNECT_MS = 5000
    private const val CHECK_READ_MS = 8000
    private const val CHECK_TOTAL_MS = 20_000L

    /** Сеть, через которую идут запросы проверки; null — сеть по умолчанию. */
    @Volatile private var checkNet: android.net.Network? = null

    /** Тихая проверка — не чаще раза в сутки. */
    private const val QUIET_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private const val PREFS = "update"
    private const val K_LAST_CHECK = "last_check"
    private const val K_DISMISSED = "banner_dismissed"

    /**
     * Когда проверку ПЫТАЛИСЬ сделать, удачно или нет.
     *
     * Отдельно от K_LAST_CHECK, и вот зачем. Тот пишется ТОЛЬКО при
     * успехе, то есть после неудачи суточные ворота не закрываются
     * вовсе — а quietCheck зовётся из LaunchedEffect строки обновлений,
     * то есть при каждой перерисовке экрана настроек.
     *
     * 08.09 в логе владельца это дало три неудачи за три секунды сразу
     * после отключения туннеля: DNS ещё не поднялся, GitHub не
     * разрешался, а мы били в него снова и снова.
     */
    private const val K_LAST_TRY = "last_try"

    /**
     * Пауза после НЕУДАЧНОЙ попытки.
     *
     * Пятнадцать минут: и по серверу не бьём, и не наказываем человека
     * сутками ожидания за то, что в момент проверки не было сети.
     * Ровно тот случай, что 08.09: через минуту после подъёма туннеля
     * DNS уже работал.
     */
    private const val FAIL_RETRY_MS = 15L * 60L * 1000L

    /**
     * Токен GitHub для закрытого репозитория отладочных сборок.
     *
     * В СБОРКЕ ЕГО НЕТ И НЕ БУДЕТ. Владелец вставляет его руками один
     * раз, и он ложится сюда — в закрытое хранилище приложения, туда
     * же, где живёт ключ доступа. Разбор — в Seed.kt отладочного
     * набора.
     *
     * В ЛОГ НЕ ПОПАДАЕТ НИКОГДА, как и пароль подключения.
     */
    private const val K_TOKEN = "gh_token"

    private const val ACTION_INSTALLED = "org.meridianvpn.app.INSTALL_RESULT"

    // ------------------------------------------------------------------
    // Состояние для экрана
    // ------------------------------------------------------------------

    enum class Stage {
        /** Ничего не происходит. Строка версии как обычно. */
        IDLE,

        /** Идёт проверка. */
        CHECKING,

        /** Проверили: установлена последняя. Показывается недолго. */
        LATEST,

        /** Есть новее. Строка выделяется. */
        AVAILABLE,

        /** Качаем. */
        DOWNLOADING,

        /** Скачали и проверили, ждём нажатия "установить". */
        READY,

        /** Не вышло. Подробности — в problem. */
        FAILED,
    }

    val stage = mutableStateOf(Stage.IDLE)

    /** Версия, которая доступна. Пусто, если нечего ставить. */
    val newVersion = mutableStateOf("")

    /** Сколько скачано, для строки. */
    val gotBytes = mutableStateOf(0L)
    val totalBytes = mutableStateOf(0L)

    /** Что сказать человеку при неудаче. Короткой строкой. */
    val problem = mutableStateOf("")

    /**
     * Версия, уведомление о которой человек закрыл на главном экране.
     *
     * ЗАКРЫТИЕ ПРИВЯЗАНО К ВЕРСИИ, А НЕ К ФАКТУ. «Больше не показывать»
     * без имени версии означало бы, что человек, отмахнувшийся один раз,
     * не узнает и о следующих обновлениях — то есть одно нажатие тихо
     * отключает оповещение навсегда. Закрыл 0.1.134 — про 0.1.135
     * скажем снова.
     *
     * В настройках строка версии при этом НЕ гаснет: там она и должна
     * ждать, пока человек сам соберётся (просьба владельца 14.09).
     */
    val dismissed = mutableStateOf("")

    /** Человек закрыл уведомление о текущей доступной версии. */
    fun dismissBanner() {
        val v = newVersion.value
        if (v.isEmpty()) return
        dismissed.value = v
        appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putString(K_DISMISSED, v)?.apply()
        TunnelLog.add("обновление: уведомление о $v закрыто, в настройках останется")
    }

    /**
     * Показывать ли уведомление на главном экране.
     *
     * Скачивание и готовность НЕ ПРЯЧУТСЯ закрытием: человек уже нажал
     * «обновить», и спрятать полосу загрузки после его же нажатия
     * значило бы потерять единственное место, где видно, чем дело
     * кончилось.
     */
    fun bannerVisible(): Boolean = when (stage.value) {
        Stage.AVAILABLE -> newVersion.value.isNotEmpty() &&
            dismissed.value != newVersion.value
        Stage.DOWNLOADING, Stage.READY -> true
        else -> false
    }

    private val main = Handler(Looper.getMainLooper())

    private class Info(
        val code: Long,
        val name: String,
        val sha256: String,
        val size: Long,
        val url: String,
    )

    @Volatile
    private var found: Info? = null

    @Volatile
    private var ready: File? = null

    @Volatile
    private var busy = false

    // ------------------------------------------------------------------
    // Проверка
    // ------------------------------------------------------------------

    /**
     * Тихая проверка при открытии настроек.
     *
     * Раз в сутки, молча, и НИЧЕГО не показывает при неудаче: человек
     * сюда пришёл не за этим. Смысл один — чтобы жирная строка про
     * новую версию появилась сама, а не только после ручного нажатия.
     */
    fun quietCheck(ctx: Context) {
        appCtx = ctx.applicationContext
        if (busy || stage.value == Stage.AVAILABLE || stage.value == Stage.READY) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = p.getLong(K_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        // Часы могли уехать назад — тогда тоже проверяем, а не ждём
        // вечно. Условие "прошло меньше суток" ловит только нормальный
        // ход времени.
        if (last != 0L && now - last in 0..QUIET_INTERVAL_MS) return

        // ПОПЫТКУ ЗАСЕКАЕМ ЗДЕСЬ, А НЕ В run(), и до самой попытки.
        //
        // Здесь — потому что ручную проверку по кнопке это трогать не
        // должно: человек нажал, значит хочет сейчас, и никакие паузы
        // ему не указ. Ворота нужны только тихой проверке.
        //
        // До попытки — потому что смысл отметки в том, что мы уже
        // пробовали, а не в том, чем кончилось.
        val tried = p.getLong(K_LAST_TRY, 0L)
        if (tried != 0L && now - tried in 0..FAIL_RETRY_MS) return
        p.edit().putLong(K_LAST_TRY, now).apply()
        run(ctx, quiet = true)
    }

    /**
     * Служба сказала, что есть версия свежее. Бесплатно, вместе с
     * проверкой ключа, по уже пинованному каналу.
     *
     * ЗДЕСЬ МЫ УЗНАЁМ ТОЛЬКО "ЧТО", НО НЕ "ОТКУДА". Адрес сборки и её
     * сумма лежат в описании релиза на GitHub, и без него скачивать
     * нечего и нечем. Поэтому found остаётся пустым: за описанием
     * сходим, когда человек нажмёт.
     *
     * Работающую загрузку и уже проверенную сборку не трогаем: они
     * старше этой подсказки по достоверности.
     */
    fun hint(code: Long, name: String) {
        if (busy) return
        when (stage.value) {
            Stage.DOWNLOADING, Stage.READY -> return
            else -> Unit
        }
        // РАНЬШЕ ЗДЕСЬ БЫЛ ТИХИЙ ВЫХОД, и он всё и съедал.
        //
        // appCtx ставился ТОЛЬКО в quietCheck, то есть при открытии
        // настроек. Пока человек туда не заходил, подсказка приходила и
        // молча пропадала — а в логе не было ни строки, чтобы это
        // заметить. Теперь контекст ставит attach() при создании
        // службы и экрана, но проверка остаётся: если её всё же нет,
        // об этом надо СКАЗАТЬ, а не промолчать.
        val ctx = appCtx
        if (ctx == null) {
            TunnelLog.add("обновление: подсказка пришла раньше, чем подключился контекст")
            return
        }
        val mine = installedCode(ctx)
        if (code <= mine) {
            TunnelLog.add("обновление: у службы версия $code, у нас $mine — новее нет")
            return
        }
        TunnelLog.add("обновление: есть версия $name ($code), у нас $mine")
        post {
            newVersion.value = name
            stage.value = Stage.AVAILABLE
        }
    }

    /**
     * Контекст — при создании службы и экрана, а не при открытии
     * настроек.
     *
     * Подсказка о версии приходит вместе с проверкой ключа, то есть в
     * любой момент и без всякого экрана. Пока контекст ставился в
     * quietCheck, всякая подсказка до первого захода в настройки
     * пропадала молча.
     */
    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
        // Закрытое уведомление переживает перезапуск. Иначе «позже»
        // означало бы «до следующего открытия приложения», то есть
        // почти ничего.
        if (dismissed.value.isEmpty()) {
            dismissed.value = ctx
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(K_DISMISSED, "") ?: ""
        }
    }

    /**
     * Контекст приложения для подсказки: она приходит из сети, где
     * никакого экрана рядом нет. Ставится при первой проверке и при
     * открытии настроек — обоих хватает.
     */
    @Volatile
    private var appCtx: Context? = null

    /** Человек нажал на строку версии. */
    fun checkNow(ctx: Context) {
        if (busy) return
        run(ctx, quiet = false)
    }

    private fun run(ctx: Context, quiet: Boolean) {
        busy = true
        if (!quiet) post { stage.value = Stage.CHECKING; problem.value = "" }
        val app = ctx.applicationContext
        thread(name = "meridian-update") {
            try {
                sweep(app)
                val info = fetchManifestFast(app)
                val installed = installedCode(app)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(K_LAST_CHECK, System.currentTimeMillis()).apply()
                if (info.code <= installed) {
                    found = null
                    post {
                        newVersion.value = ""
                        // При тихой проверке молчим совсем: "это
                        // последняя" человек не спрашивал.
                        stage.value = if (quiet) Stage.IDLE else Stage.LATEST
                    }
                } else {
                    found = info
                    post {
                        newVersion.value = info.name
                        stage.value = Stage.AVAILABLE
                    }
                }
            } catch (e: Throwable) {
                found = null
                post {
                    if (quiet) {
                        stage.value = Stage.IDLE
                    } else {
                        // ПРИЧИНУ ГОВОРИМ, А НЕ ПРЯЧЕМ.
                        //
                        // Раньше здесь стояла одна общая фраза, и она
                        // скрыла настоящую ошибку: заголовок запроса был
                        // неверный, GitHub отдавал не то, разбор падал —
                        // а человек читал «проверить не удалось» и
                        // искал причину в сети. Полдня на ровном месте.
                        //
                        // Строка короткая и без внутренностей: код
                        // ответа или короткое сообщение исключения.
                        problem.value = e.message?.take(60)?.ifBlank { null }
                            ?: "проверить не удалось"
                        TunnelLog.add("обновление: проверка не удалась — ${e.message}")
                        stage.value = Stage.FAILED
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Описание последнего релиза.
     *
     * Соединение ОБЫЧНОЕ, с системным хранилищем корней. Наш
     * TrustManager из ApiClient сюда не годится категорически: он
     * доверяет только двум пинам, а сертификаты GitHub меняются — в
     * день их смены приложение перестало бы обновляться навсегда.
     *
     * Маршрут не выбираем: наше приложение и так выведено из туннеля
     * раздельным туннелированием (SplitTunnel), и запрос пойдёт по
     * настоящей сети. Значит GitHub видит настоящий адрес человека даже
     * при поднятом туннеле — свойство известное, записано в проекте.
     */
    /**
     * Проверка с общим сроком и сначала ЧЕРЕЗ ТУННЕЛЬ.
     *
     * Само приложение выведено из туннеля, и запрос шёл по голой
     * мобильной сети, где GitHub бывает очень медленным. Если VPN поднят,
     * пробуем его сеть явно (Network.openConnection); не вышло — та же
     * проверка по обычной сети. Через туннель GitHub видит адрес шлюза.
     */
    private fun fetchManifestFast(ctx: Context): Info {
        // СНАЧАЛА ЗЕРКАЛО НА НАШЕМ API (03.10): GitHub на мобильной сети
        // бывает недоступен совсем. Описание то же, байт в байт; сумму и
        // размер сборки проверяем сами, так что зеркалу верить не нужно.
        // Отладочная сборка зеркалом не пользуется (там закрытый
        // репозиторий), но щупает его — чтобы по логу было видно, доходит ли.
        val m = mirrorManifest()
        if (m != null && !updatePrivate()) {
            TunnelLog.add("обновление: проверено через зеркало, версия ${m.name}")
            return m
        }
        val t0 = System.currentTimeMillis()
        val info = try {
            withDeadline(CHECK_TOTAL_MS, null)
        } catch (e: Throwable) {
            // ПОСЛЕДНЯЯ СТУПЕНЬ — ЧЕРЕЗ ТУННЕЛЬ (поручение владельца 04.10, кот 2):
            // при белых списках молчат и GitHub, и зеркало, а туннель жив.
            // Только релизная сборка: в туннеле лежит открытый релиз.
            if (updatePrivate() || !vpnUp(ctx)) throw e
            TunnelLog.add("обновление: обычные пути молчат (${e.message}) — пробую через туннель")
            tunnelManifest() ?: throw e
        }
        TunnelLog.add("обновление: проверено за ${System.currentTimeMillis() - t0} мс")
        return info
    }

    // ------------------------------------------------------------------
    // Через туннель: http://10.77.77.1:8080/apps/android/ (кот 2, 04.10)
    // ------------------------------------------------------------------

    private const val TUNNEL_HOST = "10.77.77.1"
    private const val TUNNEL_PORT = 8080
    private const val TUNNEL_DIR = "/apps/android/"
    private const val TUNNEL_URL = "http://$TUNNEL_HOST:$TUNNEL_PORT$TUNNEL_DIR"

    private fun vpnUp(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        cm.allNetworks.any {
            cm.getNetworkCapabilities(it)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (e: Throwable) { false }

    /**
     * Голый HTTP/1.0 по сокету. Не HttpURLConnection: тот запрещает
     * незашифрованный HTTP без network_security_config, а заводить его ради
     * одного адреса внутри туннеля незачем. Подлинность держится не на
     * транспорте, а на sha256 из описания, сверяемом до установки; адрес
     * 10.77.77.1 достижим только изнутри нашего туннеля.
     */
    private fun tunnelGet(path: String, from: Long = 0): Pair<java.io.InputStream, java.net.Socket> {
        val s = java.net.Socket()
        try {
            s.connect(java.net.InetSocketAddress(TUNNEL_HOST, TUNNEL_PORT), CHECK_CONNECT_MS)
            s.soTimeout = READ_MS
            s.getOutputStream().write(
                ("GET $path HTTP/1.0\r\nHost: $TUNNEL_HOST\r\nUser-Agent: meridian-app\r\n" +
                    (if (from > 0) "Range: bytes=$from-\r\n" else "") + "\r\n")
                    .toByteArray(Charsets.US_ASCII)
            )
            val input = java.io.BufferedInputStream(s.getInputStream())
            fun line(): String {
                val b = java.io.ByteArrayOutputStream()
                while (true) {
                    val c = input.read()
                    if (c < 0 || c == '\n'.code) break
                    if (c != '\r'.code) b.write(c)
                    if (b.size() > 8192) throw IllegalStateException("заголовок слишком длинный")
                }
                return b.toString("US-ASCII")
            }
            val status = line()
            val st = status.split(' ').getOrNull(1)
            if (st != "200" && !(from > 0 && st == "206")) {
                throw IllegalStateException("туннель: ответ «${status.take(40)}»")
            }
            while (line().isNotEmpty()) { /* заголовки не нужны */ }
            return input to s
        } catch (e: Throwable) {
            try { s.close() } catch (x: Throwable) { }
            throw e
        }
    }

    /** Описание через туннель в формате кота 2: version, build, file, sha256, size. */
    private fun tunnelManifest(): Info? = try {
        val (input, s) = tunnelGet(TUNNEL_DIR + "latest.json")
        val body = try { input.readBytes(64 * 1024).toString(Charsets.UTF_8) } finally { s.close() }
        val j = JSONObject(body)
        val file = j.getString("file")
        if (file.isEmpty() || file.contains('/') || file.contains("..")) {
            throw IllegalStateException("негодное имя файла")
        }
        val info = Info(
            code = j.getLong("build"),
            name = j.getString("version"),
            sha256 = j.getString("sha256").lowercase(),
            size = j.getLong("size"),
            url = TUNNEL_URL + file,
        )
        if (info.sha256.length != 64) throw IllegalStateException("длина sha256 не та")
        TunnelLog.add("обновление: через туннель версия ${info.name} (${info.code})")
        info
    } catch (e: Throwable) {
        TunnelLog.add("обновление: через туннель не вышло (${e.message})")
        null
    }

    private const val MIRROR_PATH = "/v1/update/android/"
    private const val MIRROR_TOTAL_MS = 10_000L

    /** Описание с зеркала; null — зеркало молчит или ответило негодным. */
    private fun mirrorManifest(): Info? {
        var res: Info? = null
        val t0 = System.currentTimeMillis()
        val t = thread(name = "meridian-update-mirror") {
            try {
                val c = ApiClient.openMirror(MIRROR_PATH + "latest.json", "application/json",
                    CHECK_CONNECT_MS, CHECK_READ_MS) ?: return@thread
                val body = try {
                    c.inputStream.use { it.readBytes(64 * 1024) }.toString(Charsets.UTF_8)
                } finally {
                    c.disconnect()
                }
                res = parseManifest(body, PUBLIC_REPO)
            } catch (e: Throwable) {
                TunnelLog.add("зеркало: описание негодно (${e.message})")
            }
        }
        t.join(MIRROR_TOTAL_MS)
        if (t.isAlive) {
            t.interrupt()
            TunnelLog.add("зеркало: не ответило за ${MIRROR_TOTAL_MS / 1000} с")
            return null
        }
        res?.let {
            TunnelLog.add("зеркало: версия ${it.name} (${it.code}) за ${System.currentTimeMillis() - t0} мс")
        }
        return res
    }

    /** Открытый релизный репозиторий — его и только его отдаёт зеркало. */
    private const val PUBLIC_REPO = "JinComputers/meridian-android"

    private fun withDeadline(ms: Long, net: android.net.Network?): Info {
        var res: Info? = null
        var err: Throwable? = null
        val t = thread(name = "meridian-update-check") {
            checkNet = net
            try { res = fetchManifest() } catch (e: Throwable) { err = e }
        }
        t.join(ms)
        if (t.isAlive) {
            t.interrupt()
            throw IllegalStateException("GitHub не ответил за ${ms / 1000} с")
        }
        err?.let { throw it }
        return res ?: throw IllegalStateException("пустой ответ")
    }

    private fun fetchManifest(): Info {
        val body = if (updatePrivate()) {
            if (token() == null) {
                throw IllegalStateException("нет токена для закрытого репозитория")
            }
            get(
                apiAssetUrl(assetId("latest.json")),
                64 * 1024,
                "application/octet-stream",
                auth = true,
            )
        } else {
            get(manifestUrl(), 64 * 1024, "application/json")
        }.toString(Charsets.UTF_8)
        return parseManifest(body, repo())
    }

    private fun parseManifest(body: String, repo: String): Info {
        val j = JSONObject(body)
        val info = Info(
            code = j.getLong("version_code"),
            name = j.getString("version_name"),
            sha256 = j.getString("sha256").lowercase(),
            size = j.getLong("size"),
            url = j.getString("url"),
        )
        // Адрес скачивания обязан вести в НАШ репозиторий. Описание
        // лежит там же, где сборка, и подменивший одно подменит другое,
        // но перенаправить скачивание на чужой сервер — самый дешёвый
        // способ, и закрыть его стоит одной строкой.
        if (!info.url.startsWith("https://github.com/$repo/releases/download/")) {
            throw IllegalStateException("адрес сборки ведёт не в наш репозиторий")
        }
        if (info.sha256.length != 64) throw IllegalStateException("длина sha256 не та")
        return info
    }

    // ------------------------------------------------------------------
    // Токен закрытого репозитория
    // ------------------------------------------------------------------

    /** Есть ли сохранённый токен. Сам токен наружу не отдаём. */
    fun hasToken(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_TOKEN, null).isNullOrBlank()

    /** Сохранить или стереть токен. Пустая строка стирает. */
    fun setToken(ctx: Context, t: String) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (t.isBlank()) e.remove(K_TOKEN) else e.putString(K_TOKEN, t.trim())
        e.apply()
        // Сам токен не пишем — как и пароль подключения.
        TunnelLog.add(if (t.isBlank()) "обновление: токен стёрт" else "обновление: токен сохранён")
    }

    private fun token(): String? {
        val ctx = appCtx ?: return null
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // Сеть
    // ------------------------------------------------------------------

    /**
     * Соединение с GitHub. ПЕРЕНАПРАВЛЕНИЯ РАЗБИРАЕМ САМИ.
     *
     * Причина ровно одна и она обязательна: вложения закрытого
     * репозитория API отдаёт перенаправлением на хранилище файлов, а
     * туда заголовок с токеном нести НЕЛЬЗЯ — хранилище считает его
     * второй, чужой попыткой авторизации и отвечает отказом.
     * Стандартное следование за перенаправлением тащит заголовки на
     * новый адрес и ломает скачивание.
     *
     * Поэтому: сами читаем Location, сами открываем следующий адрес и
     * токен дальше НЕ несём.
     */
    private fun isCheckThread(): Boolean =
        Thread.currentThread().name == "meridian-update-check"

    private fun open(url: String, accept: String, auth: Boolean, from: Long = 0): HttpURLConnection {
        var current = url
        var carry = auth
        repeat(5) {
            val net = if (isCheckThread()) checkNet else null
            val c = (if (net != null) net.openConnection(URL(current)) else URL(current).openConnection())
                as HttpURLConnection
            c.connectTimeout = if (isCheckThread()) CHECK_CONNECT_MS else CONNECT_MS
            c.readTimeout = if (isCheckThread()) CHECK_READ_MS else READ_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", accept)
            // API GitHub отвечает отказом на запрос без имени клиента.
            // Android своё имя подставляет сам, но полагаться на это
            // незачем: строка ничего не стоит и ни о чём не говорит
            // постороннему.
            c.setRequestProperty("User-Agent", "meridian-app")
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            if (carry) token()?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            val code = c.responseCode
            if (code in 301..308) {
                val next = c.getHeaderField("Location")
                c.disconnect()
                if (next.isNullOrEmpty()) throw IllegalStateException("перенаправление без адреса")
                current = next
                carry = false
                return@repeat
            }
            if (code != 200 && !(from > 0 && code == 206)) {
                // ПРИЧИНУ ГОВОРИТ САМ GITHUB, и она в теле ответа.
                //
                // Раньше мы это тело выбрасывали и писали свой домысел
                // «нет доступа к репозиторию» — а GitHub в том же ответе
                // пишет ровно, чего не хватило: «Resource not accessible
                // by personal access token», «API rate limit exceeded»,
                // «Bad credentials». Это три разные починки, и гадать
                // между ними по коду 403 бессмысленно.
                //
                // Тело у ошибок API короткое, читаем его целиком.
                val said = try {
                    c.errorStream?.use { it.readBytes(8 * 1024) }
                        ?.toString(Charsets.UTF_8)
                        ?.let { JSONObject(it).optString("message") }
                        ?.takeIf { it.isNotBlank() }
                } catch (e: Throwable) {
                    null
                }
                c.disconnect()
                throw IllegalStateException(
                    if (said != null) "GitHub: $said (код $code)" else "ответ $code"
                )
            }
            return c
        }
        throw IllegalStateException("слишком много перенаправлений")
    }

    /**
     * ACCEPT ЗДЕСЬ ОБЯЗАТЕЛЬНО ПАРАМЕТРОМ, а не постоянной строкой.
     *
     * На этом я и обжёгся. Раньше тут всегда стоял application/json, и
     * для описания релиза это верно, а для СКАЧИВАНИЯ ВЛОЖЕНИЯ — нет:
     * с json GitHub отдаёт описание самого вложения, а не файл. Разбор
     * потом не находил version_code, падал, и наружу выходило общее
     * «проверить не удалось» — то есть ошибка была наша, а выглядела
     * как недоступный GitHub.
     */
    private fun get(url: String, limit: Int, accept: String, auth: Boolean = false): ByteArray {
        val c = open(url, accept, auth)
        try {
            return c.inputStream.use { it.readBytes(limit) }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Идентификатор вложения по имени — только для закрытого пути.
     *
     * У открытого репозитория вложения берутся прямым адресом, и API
     * там не нужен вовсе.
     */
    private fun assetId(name: String): Long {
        val body = get(apiLatestUrl(), 512 * 1024, "application/vnd.github+json", auth = true).toString(Charsets.UTF_8)
        val assets = JSONObject(body).optJSONArray("assets")
            ?: throw IllegalStateException("в релизе нет вложений")
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == name) return a.optLong("id")
        }
        throw IllegalStateException("в релизе нет файла $name")
    }

    private fun java.io.InputStream.readBytes(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n <= 0) break
            if (out.size() + n > limit) throw IllegalStateException("ответ слишком велик")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Скачивание
    // ------------------------------------------------------------------

    /**
     * Качаем сразу, и на мобильной сети тоже — так решил владелец.
     * Второго подтверждения нет: человек уже нажал.
     */
    fun download(ctx: Context) {
        if (busy) return
        busy = true
        val app = ctx.applicationContext
        post {
            stage.value = Stage.DOWNLOADING
            gotBytes.value = 0L
            totalBytes.value = found?.size ?: 0L
            problem.value = ""
        }
        thread(name = "meridian-update-get") {
            try {
                // ОПИСАНИЕ РЕЛИЗА МОГЛО И НЕ ЧИТАТЬСЯ. Подсказка службы
                // говорит "новая версия есть", но ни адреса, ни суммы у
                // неё нет — они только на GitHub. Значит сходим сейчас.
                val info = found ?: fetchManifest().also {
                    found = it
                    post { totalBytes.value = it.size }
                }
                val file = fetchApk(app, info)
                verify(app, file, info)
                ready = file
                post { stage.value = Stage.READY }
            } catch (e: Throwable) {
                ready = null
                post {
                    problem.value = e.message ?: "скачать не удалось"
                    stage.value = Stage.FAILED
                }
            } finally {
                busy = false
            }
        }
    }

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "update").apply { mkdirs() }

    /**
     * Качаем во временный файл и считаем хеш на лету.
     *
     * Готовое имя появляется только после успешной проверки: иначе
     * недокачанный файл однажды выдадут за целый.
     */
    private fun fetchApk(ctx: Context, info: Info): File {
        val part = File(dir(ctx), "meridian-${info.code}.part")
        part.delete()
        val name = info.url.substringAfterLast('/')

        // ИСТОЧНИКИ ПО ОЧЕРЕДИ, С ДОКАЧКОЙ (жалоба клиента 06.10: из приложения
        // в разы медленнее браузера и пару раз срыв). Раньше первым шло наше
        // зеркало — один поток с машины API, — а срыв начинал всё с нуля.
        // Теперь первым GitHub (тот же CDN, что у браузера), потом зеркало;
        // оборвалось — следующая попытка докачивает с места обрыва (Range).
        class Src(val label: String, val get: (Long) -> Pair<java.io.InputStream, () -> Unit>?)
        val http = { c: HttpURLConnection? ->
            c?.let { conn -> conn.inputStream to { conn.disconnect() } }
        }
        val sources = ArrayList<Src>()
        when {
            info.url.startsWith(TUNNEL_URL) -> sources += Src("через туннель") { from ->
                val (input, s) = tunnelGet(TUNNEL_DIR + name, from)
                input to { s.close() }
            }
            updatePrivate() -> sources += Src("с GitHub") { from ->
                http(open(apiAssetUrl(assetId(name)), "application/octet-stream", auth = true, from = from))
            }
            else -> {
                sources += Src("с GitHub") { from ->
                    http(open(info.url, "application/octet-stream", auth = false, from = from))
                }
                sources += Src("с зеркала") { from ->
                    http(ApiClient.openMirror(MIRROR_PATH + name, "application/octet-stream",
                        CONNECT_MS, READ_MS, from))
                }
            }
        }

        var lastErr: Throwable? = null
        for (attempt in 0 until DOWNLOAD_ATTEMPTS) {
            val src = sources[attempt % sources.size]
            val from = if (part.exists()) part.length() else 0L
            if (from >= info.size) break
            try {
                val got = src.get(from) ?: throw IllegalStateException("не ответил")
                TunnelLog.add("обновление: качаю ${src.label}" + if (from > 0) " с ${from / 1024} КБ" else "")
                try {
                    appendStream(part, got.first, from, info.size)
                } finally {
                    try { got.second() } catch (x: Throwable) { }
                }
                if (part.length() >= info.size) break
            } catch (e: Throwable) {
                lastErr = e
                TunnelLog.add("обновление: ${src.label} оборвалось на ${part.length() / 1024} КБ (${e.message})")
            }
        }
        if (part.length() != info.size) {
            part.delete()
            throw IllegalStateException(lastErr?.message ?: "скачалось не целиком")
        }
        return verifyPart(ctx, part, info)
    }

    private const val DOWNLOAD_ATTEMPTS = 6

    /**
     * Дописывает поток в файл с позиции from. Потолок по размеру: иначе
     * подменённый ответ может забить кэш телефона целиком.
     */
    private fun appendStream(part: File, input: java.io.InputStream, from: Long, size: Long) {
        java.io.RandomAccessFile(part, "rw").use { f ->
            f.seek(from)
            val buf = ByteArray(64 * 1024)
            var pos = from
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    f.write(buf, 0, n)
                    pos += n
                    if (pos > size) throw IllegalStateException("сборка больше заявленной")
                    val done = pos
                    post { gotBytes.value = done }
                }
            } finally {
                f.setLength(pos)
            }
        }
    }

    /** Сумма файла целиком против описания; годный — переименовываем. */
    private fun verifyPart(ctx: Context, part: File, info: Info): File {
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val sum = digest.digest().joinToString("") { "%02x".format(it) }
        if (sum != info.sha256) {
            part.delete()
            throw IllegalStateException("сборка не совпала с описанием")
        }
        val done = File(dir(ctx), "meridian-${info.code}.apk")
        done.delete()
        if (!part.renameTo(done)) throw IllegalStateException("файл не переименовался")
        return done
    }

    // ------------------------------------------------------------------
    // Проверка скачанного
    // ------------------------------------------------------------------

    /**
     * ГЛАВНАЯ ПРОВЕРКА ЗДЕСЬ — ПОДПИСЬ, а не хеш.
     *
     * Хеш не доказывает ничего: и сборка, и её описание лежат в одном
     * репозитории, и тот, кто подменит одно, подменит и второе — сумма
     * сойдётся. Единственное, что связывает сборку с нами, — ключ
     * подписи, и он лежит там, куда никакой код не дотягивается.
     *
     * Сравниваем не с зашитым отпечатком, а С СОБОЙ ЖЕ: подпись
     * установленного приложения против подписи скачанного. Зашивать
     * отпечаток в код незачем — при смене ключа его пришлось бы менять
     * в двух местах, и одно из них однажды забудут.
     *
     * Система тоже откажется ставить чужую подпись поверх нашей, но её
     * отказ выглядит как невнятная ошибка установщика. Наш — как
     * понятная строка, и человек не успевает нажать "установить" на
     * подменённом файле.
     */
    private fun verify(ctx: Context, file: File, info: Info) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val got = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: fail(file, "скачанный файл не похож на приложение")

        if (got.packageName != ctx.packageName) {
            fail(file, "сборка от другого приложения")
        }
        val gotCode = if (Build.VERSION.SDK_INT >= 28) got.longVersionCode
        else @Suppress("DEPRECATION") got.versionCode.toLong()
        if (gotCode != info.code) {
            fail(file, "версия сборки не та, что заявлена")
        }

        val mine = pm.getPackageInfo(ctx.packageName, flags)
        if (certsOf(got) != certsOf(mine) || certsOf(got).isEmpty()) {
            fail(file, "ПОДПИСЬ НЕ НАША — сборка подменена, установка отменена")
        }
    }

    private fun fail(file: File, why: String): Nothing {
        file.delete()
        throw IllegalStateException(why)
    }

    /** Отпечатки сертификатов подписи, отсортированные. */
    private fun certsOf(pi: android.content.pm.PackageInfo): List<String> {
        val raw: Array<android.content.pm.Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptyList()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pi.signatures
        } ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        return raw.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            .sorted()
    }

    // ------------------------------------------------------------------
    // Установка
    // ------------------------------------------------------------------

    /** Разрешено ли нам вообще ставить приложения. */
    fun canInstall(ctx: Context): Boolean = try {
        ctx.packageManager.canRequestPackageInstalls()
    } catch (e: Throwable) {
        false
    }

    /**
     * Отправляет в настройки, где включается установка из этого
     * приложения. Именно с package: — иначе откроется общий список, и
     * человек будет искать нас среди всех.
     */
    fun askInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            post { problem.value = "не вышло открыть настройки установки" }
        }
    }

    /**
     * Ставит проверенную сборку поверх нынешней.
     *
     * PackageInstaller, а не ACTION_VIEW с FileProvider: приходит
     * результат, и его видно в логе. Для правки, которую мне нечем
     * проверить, это важнее краткости.
     *
     * Установка ИДЁТ ПОВЕРХ, без удаления: настройки, ключ доступа и
     * память путей переживают её. Держится это на одном — на том, что
     * ключ подписи не менялся.
     */
    fun install(ctx: Context) {
        val file = ready ?: return
        val app = ctx.applicationContext
        if (!canInstall(app)) {
            askInstallPermission(ctx)
            return
        }
        try {
            registerResult(app)
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(app.packageName)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("meridian", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    app, id, Intent(ACTION_INSTALLED).setPackage(app.packageName), flags
                )
                session.commit(pi.intentSender)
            }
        } catch (e: Throwable) {
            post {
                problem.value = "установка не началась: ${e.message}"
                stage.value = Stage.FAILED
            }
        }
    }

    @Volatile
    private var receiverUp = false

    /**
     * Приёмник результата установки.
     *
     * Заводится на месте, а не в манифесте: он нужен ровно на время
     * установки и никому снаружи. Первый ответ системы почти всегда —
     * STATUS_PENDING_USER_ACTION с готовым намерением: его надо
     * запустить, и только тогда человек увидит экран установщика.
     * Пропустить этот шаг — значит получить "ничего не происходит".
     */
    private fun registerResult(app: Context) {
        if (receiverUp) return
        receiverUp = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val status = i.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val next = if (Build.VERSION.SDK_INT >= 33) {
                            i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        }
                        next?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            if (next != null) app.startActivity(next)
                        } catch (e: Throwable) {
                            post { problem.value = "установщик не открылся" }
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        // Сюда обычно не доходим: система перезапускает
                        // приложение. Строка на случай, если дойдём.
                        TunnelLog.event("обновление установлено")
                    }

                    else -> {
                        val why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        TunnelLog.event("обновление не установилось: ${why ?: status}")
                        post {
                            problem.value = "установить не удалось"
                            stage.value = Stage.FAILED
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_INSTALLED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(r, filter)
        }
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------

    private fun installedCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (e: Throwable) {
        Long.MAX_VALUE // не знаем свою версию — не предлагаем ничего
    }

    /**
     * Подметает за собой.
     *
     * Каждая сборка — 27 МБ, и без уборки кэш телефона зарастает по
     * файлу на версию. Стираем всё, кроме той сборки, что готова к
     * установке прямо сейчас.
     */
    private fun sweep(ctx: Context) {
        try {
            val keep = ready?.absolutePath
            dir(ctx).listFiles()?.forEach {
                if (it.absolutePath != keep) it.delete()
            }
        } catch (e: Throwable) {
            // Не убралось — не беда, места это стоит, а не работы.
        }
    }

    private fun post(block: () -> Unit) = main.post(block)
}
