package org.meridianvpn.app

import android.content.Context
import org.json.JSONObject

/**
 * Параметры подключения от службы: `/v1/params`.
 *
 * НАСТОЯЩИЙ ОТВЕТ СЛУЖБЫ, снятый с живой машины 26.08:
 *
 *   {"status":"answered","generation":1,"ttl":21600,"now":1787773157,
 *    "entries":[
 *      {"id":"entry-1","host":"GATEWAY_IP","port":443,"dtls":true},
 *      {"id":"entry-2","host":"GATEWAY_IP","port":56004,"dtls":true},
 *      {"id":"entry-3","host":"GATEWAY_IP","port":56005,"dtls":false}
 *    ],
 *    "relay":{"enabled":true},"contract":"v1"}
 *
 * ОТДЕЛЬНОГО ПОЛЯ С АДРЕСОМ ШЛЮЗА НЕТ ВОВСЕ — адрес приходит в КАЖДОЙ
 * записи полем host. Это прямой ответ кота 2 на мой вопрос, и из-за
 * него в движке у ступени появилось своё поле host.
 *
 * Мой прежний договор из задачи 47 (внутренний документ, не публикуется) этими
 * байтами отменён: там список звался direct, у записи было имя, а
 * адрес шлюза лежал отдельным полем. Расхождение называю, а не
 * замалчиваю: документ надо переписать, и это отдельная работа.
 *
 * ПОРЯДОК — ПОДСКАЗКА, А НЕ РАСПИСАНИЕ. Поправка кота 1: приложение
 * поднимает узлы ПАРАЛЛЕЛЬНО и берёт первого готового. Поэтому список
 * обязан оставаться ПОЛНЫМ: на МТС живёт только 56005, а у другого
 * человека именно он может быть мёртв. Выкидывать записи по своему
 * разумению нельзя ни одну.
 *
 * ---
 *
 * ТРИ ПРАВИЛА, слова кота 2, и каждое закрывает свой способ остаться
 * без связи на ровном месте.
 *
 * 1. НЕ СМОГЛИ СПРОСИТЬ — РАБОТАЕМ ПО ПОСЛЕДНЕМУ УДАЧНОМУ ОТВЕТУ,
 *    сколько бы времени ни прошло. Истёкший ttl значит «пора
 *    обновить», а НЕ «список больше не годен». Иначе сутки без сети
 *    превращались бы в невозможность подключиться вообще, причём ровно
 *    тогда, когда связь нужнее всего.
 *
 * 2. ЗАШИТЫЕ ЗАПАСНЫЕ — ТОЛЬКО ЕСЛИ СОХРАНЁННОГО НЕТ ВОВСЕ. Они
 *    заведомо старше любого ответа службы; подставлять их поверх
 *    сохранённого значило бы откатывать человека к прошлогодним
 *    адресам при первой же неудаче запроса.
 *
 * 3. ПУСТОЙ СПИСОК НЕ БЫВАЕТ ОТВЕТОМ. Пустой список узлов или его
 *    отсутствие — считаем, что ответа не было, и сохранённое НЕ
 *    затираем. Это защита от одной опечатки на сервере, которая иначе
 *    оставила бы без связи всех разом.
 */
object Params {

    /**
     * Запасной срок свежести, если служба его не назвала.
     *
     * Обычно ttl приходит В ОТВЕТЕ (живой ответ: 21600, шесть часов), и
     * берём мы его оттуда: срок — дело службы, а не наше. Эта величина
     * нужна ровно на случай ответа без ttl.
     */
    private const val TTL_FALLBACK_MS = 6L * 60L * 60L * 1000L

    /**
     * Пол между запросами по поводу «не сработал ни один узел».
     *
     * Без него мигающая сеть превращается в поток запросов: каждая
     * неудачная попытка подключения — новый запрос к службе.
     */
    private const val FAIL_FLOOR_MS = 10L * 60L * 1000L

    private const val PREFS = "params"
    private const val K_BODY = "body"
    private const val K_GOT_AT = "got_at"
    private const val K_ASKED_AT = "asked_at"

    @Volatile
    private var appCtx: Context? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /**
     * Последний удачный ответ целиком, как пришёл.
     *
     * Целиком и как есть — намеренно: разбирать его будем при чтении, и
     * если завтра в ответе появится поле, о котором сегодняшняя сборка
     * не знает, оно доживёт до сборки, которая знает. Разложить ответ
     * по своим полям при сохранении значило бы потерять всё лишнее.
     */
    fun saved(): String? = appCtx?.let { prefs(it).getString(K_BODY, null) }

    /** Когда получен последний удачный ответ. 0 — не было ни одного. */
    fun savedAt(): Long = appCtx?.let { prefs(it).getLong(K_GOT_AT, 0L) } ?: 0L

    /**
     * Срок вышел — ПОРА ОБНОВИТЬ. Не «список негоден»: правило 1.
     *
     * Отдельным именем, чтобы это различие нельзя было потерять при
     * чтении кода: `stale` читается как «протух», а он не протух.
     */
    fun timeToRefresh(): Boolean {
        val at = savedAt()
        if (at == 0L) return true
        val age = System.currentTimeMillis() - at
        // Часы могли уехать назад — это тоже повод спросить, а не ждать
        // вечно отрицательного возраста.
        return age !in 0..ttlMs()
    }

    /** Срок свежести из последнего ответа, в миллисекундах. */
    private fun ttlMs(): Long {
        val s = saved() ?: return TTL_FALLBACK_MS
        return try {
            val t = JSONObject(s).optLong("ttl", 0L)
            if (t > 0L) t * 1000L else TTL_FALLBACK_MS
        } catch (e: Throwable) {
            TTL_FALLBACK_MS
        }
    }

    /** Есть ли хоть что-то сохранённое. По правилу 2 — от этого зависит всё. */
    fun haveSaved(): Boolean = !saved().isNullOrEmpty()

    // ------------------------------------------------------------------
    // Два повода спросить
    // ------------------------------------------------------------------

    /** Повод 1: вышел срок. */
    fun refreshIfDue() {
        if (!timeToRefresh()) return
        fetch("вышел срок")
    }

    /**
     * Повод 2: не сработал НИ ОДИН узел.
     *
     * Самый ценный повод: он означает, что сохранённый список
     * перестал работать целиком, и другого способа узнать новый у нас
     * нет. Пол в десять минут — чтобы мигающая сеть не превратила это
     * в поток запросов.
     */
    fun refreshAfterAllFailed() {
        val ctx = appCtx ?: return
        val last = prefs(ctx).getLong(K_ASKED_AT, 0L)
        val now = System.currentTimeMillis()
        if (last != 0L && now - last in 0..FAIL_FLOOR_MS) {
            TunnelLog.add("параметры: спрашивал недавно, жду до десяти минут")
            return
        }
        fetch("не сработал ни один узел")
    }

    // ------------------------------------------------------------------
    // Запрос
    // ------------------------------------------------------------------

    /**
     * Зовётся ТОЛЬКО с фонового потока: ApiClient.call сам роняет
     * вызов с главного, и это правильно.
     */
    private fun fetch(why: String) {
        val ctx = appCtx ?: return
        prefs(ctx).edit().putLong(K_ASKED_AT, System.currentTimeMillis()).apply()
        val key = Access.password()
        // ОБЪЯВЛЯЕМ, ЧТО УМЕЕМ (требование кота 2). Без этого параметра
        // служба по договору отдаёт только udp-записи — так держится
        // обратная совместимость: старому клиенту незнакомый транспорт
        // просто не присылают, а не надеются, что он «пропустит».
        val query = HashMap<String, String>()
        query["transports"] = "udp,tcp,tls"
        if (key.isNotEmpty()) query["key"] = key
        when (val r = ApiClient.call("/v1/params", query)) {
            is ApiClient.Result.Ok -> accept(ctx, r.body, why)
            is ApiClient.Result.Refused ->
                // ПРАВИЛО 1. Отказ службы — не приговор списку: работаем
                // по сохранённому, сколько бы ему ни было.
                TunnelLog.add("параметры: служба отказала (${r.error}) — работаю по сохранённым")

            is ApiClient.Result.Incompatible ->
                TunnelLog.add("параметры: договор ${r.theirs} мне незнаком — работаю по сохранённым")

            is ApiClient.Result.Unavailable ->
                TunnelLog.add("параметры: не дозвонился (${r.why}) — работаю по сохранённым")
        }
    }

    /**
     * ПРАВИЛО 3 ЖИВЁТ ЗДЕСЬ, и это самое важное место во всём файле.
     *
     * Ответ без узлов — это НЕ пустой список, это отсутствие ответа.
     * Разница огромна: сохранить пустой список значит оставить без
     * связи всех, у кого сборка сходит за параметрами, — одной
     * опечаткой на сервере.
     *
     * Отказы службы (503 и 429) приходят БЕЗ entries вовсе, 400 и 405
     * — той же формы. То есть эта одна проверка закрывает их все
     * разом, отдельного разбора кодов не нужно.
     */
    private fun accept(ctx: Context, body: JSONObject, why: String) {
        if (!hasNodes(body)) {
            TunnelLog.add("параметры: в ответе нет ни одного узла — считаю, что ответа не было")
            return
        }
        prefs(ctx).edit()
            .putString(K_BODY, body.toString())
            .putLong(K_GOT_AT, System.currentTimeMillis())
            .apply()
        TunnelLog.add("параметры обновлены ($why)")
    }

    private fun hasNodes(body: JSONObject): Boolean =
        (body.optJSONArray("entries")?.length() ?: 0) > 0

    // ------------------------------------------------------------------
    // Чтение
    // ------------------------------------------------------------------

    /**
     * Один узел из ответа службы.
     *
     * id — устойчивое имя записи, и ТОЛЬКО ОНО годится для памяти
     * путей: буква зависит от места в списке, а место служба может
     * переставить в любой момент. Человеку и в лог по-прежнему идёт
     * буква, id наружу не показывается.
     */
    data class Node(
        val id: String,
        val host: String,
        val port: Int,
        val dtls: Boolean,
        /** "udp" (умолчание), "tcp" или "tls". Ступени 3-4 кота 2. */
        val transport: String,
        /** SNI для tls-ступени; для прочих пусто. Привязан к IP. */
        val sni: String,
    )

    /**
     * Узлы из сохранённого ответа. Пусто — сохранённого нет или он
     * испорчен, и тогда по правилу 2 в дело идут зашитые запасные.
     *
     * СПИСОК ОТДАЁТСЯ ЦЕЛИКОМ И В ТОМ ЖЕ ПОРЯДКЕ. Порядок — подсказка
     * о предпочтении, а узлы поднимаются параллельно; выкидывать или
     * переставлять записи по своему разумению нельзя.
     */
    fun nodes(): List<Node> {
        val s = saved() ?: return emptyList()
        return try {
            val a = JSONObject(s).optJSONArray("entries") ?: return emptyList()
            val out = ArrayList<Node>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val host = o.optString("host")
                val port = o.optInt("port", 0)
                if (host.isEmpty() || port <= 0) continue
                out.add(
                    Node(
                        id = o.optString("id").ifEmpty { "$host:$port" },
                        host = host,
                        port = port,
                        dtls = o.optBoolean("dtls", false),
                        // Умолчание "udp": запись без transport — старая
                        // udp-запись, как и договорились с котом 2.
                        transport = o.optString("transport").ifEmpty { "udp" },
                        sni = o.optString("sni"),
                    )
                )
            }
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * Разрешён ли релей.
     *
     * Умолчание — ДА: ответ без поля relay не должен отнимать запасной
     * путь. Отнять его служба может только сказав это прямо.
     */
    fun relayEnabled(): Boolean {
        val s = saved() ?: return true
        return try {
            JSONObject(s).optJSONObject("relay")?.optBoolean("enabled", true) ?: true
        } catch (e: Throwable) {
            true
        }
    }

    /**
     * Конечный адрес релея: поле `relay.target` (решение владельца,
     * 23.09). Только IPv4-литерал без порта, например "GATEWAY_IP";
     * порт — Config.RELAY_GATEWAY_PORT, как и раньше. Пусто — поля нет,
     * релей идёт на адрес из резолва edge, как раньше.
     *
     * Проверку на вменяемость (имя, порт, приватный адрес) делает движок
     * в Ladder.SetRelayTarget и просто не принимает негодное.
     */
    fun relayTarget(): String {
        val s = saved() ?: return ""
        return try {
            JSONObject(s).optJSONObject("relay")?.optString("target", "")?.trim() ?: ""
        } catch (e: Throwable) {
            ""
        }
    }

    // ------------------------------------------------------------------
    // Настройка лестницы от сервера: объект `ladder`.
    //
    // Договор с котом 2, живые байты 27.08:
    //   "ladder":{"direct_probe_every_attempts":4,
    //             "direct_skip_after_fails":5,"second_round_ms":3000}
    //
    // ДВА ПРАВИЛА, и оба держим со своей стороны.
    //
    // 1. УМОЛЧАНИЕ КАЖДОГО ПОЛЯ = НЫНЕШНЕЕ ПОВЕДЕНИЕ. Пропущенное поле
    //    берёт встроенную константу ниже, равную тому, что зашито
    //    сейчас. Значит ошибка на сервере (пропуск поля) не может
    //    ничего выключить — только вернуть к работающему. Состояние
    //    человека читается по одному сегодняшнему ответу, а не по
    //    истории: клиент `generation` не сравнивает вовсе.
    //
    // 2. ВТОРОЙ ЗАБОР НА ЧИСЛА. Даже придя от службы валидными, значения
    //    проходят проверку на вменяемость: вне разумных пределов —
    //    зажимаем к умолчанию и пишем в лог. То же правило, по которому
    //    мы не применяем молча то, в чём не уверены.
    // ------------------------------------------------------------------

    private const val SECOND_ROUND_MS_DEFAULT = 3000
    private const val SKIP_AFTER_DEFAULT = 5
    private const val PROBE_EVERY_DEFAULT = 4

    /** Срок гонки на втором круге, мс. Для движка (ladder.setSecondRoundMs). */
    fun secondRoundMs(): Int =
        ladderInt("second_round_ms", SECOND_ROUND_MS_DEFAULT, 500, 15000)

    /** После скольких проигрышей релею подряд идём сразу на релей. */
    fun directSkipAfterFails(): Int =
        ladderInt("direct_skip_after_fails", SKIP_AFTER_DEFAULT, 1, 1000)

    /** Как часто на «плохой» сети всё же щупаем прямой путь. */
    fun directProbeEveryAttempts(): Int =
        ladderInt("direct_probe_every_attempts", PROBE_EVERY_DEFAULT, 1, 1000)

    private fun ladderInt(name: String, default: Int, min: Int, max: Int): Int {
        val s = saved() ?: return default
        return try {
            val ladder = JSONObject(s).optJSONObject("ladder") ?: return default
            if (!ladder.has(name)) return default
            val v = ladder.optInt(name, default)
            if (v in min..max) {
                v
            } else {
                TunnelLog.add(
                    "настройка: $name=$v вне пределов $min..$max — беру умолчание $default"
                )
                default
            }
        } catch (e: Throwable) {
            default
        }
    }
}
