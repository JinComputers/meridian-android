package org.meridianvpn.app

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Пул хешей VK — ссылок на звонки, из которых добываются креды TURN.
 *
 * Хеши создаёт сам человек и добавляет сюда: в одно-кнопочном
 * приложении другого способа их получить нет.
 *
 * ХЕШ — СЕКРЕТ. По нему можно присоединиться к звонку. На экране он
 * показывается целиком (человек должен узнавать свои), в лог уходит
 * обрезанным, а в выгрузку лога не попадает вовсе — см. LogExport.
 *
 * ХРАНЕНИЕ переживает обновление приложения, но НЕ переживает удаление:
 * SharedPreferences стираются вместе с приложением. Отсюда и заметка на
 * экране с кнопкой «скопировать все» — чтобы человек мог сохранить их у
 * себя до того, как это станет важно.
 */
object HashStore {
    private const val PREFS = "meridian_hashes"
    private const val KEY = "hashes"

    /** Статусы. Пустой — не проверялся. */
    const val ST_NEW = ""
    const val ST_ALIVE = "живой"
    const val ST_CAPTCHA = "капча"
    const val ST_DEAD = "мёртвый"

    /**
     * Звонок есть, а адреса релея VK к нему не дал.
     *
     * ОТДЕЛЬНЫЙ СТАТУС, А НЕ ОТТЕНОК СМЕРТИ. Раньше это состояние
     * попадало в ST_DEAD, а мёртвые исключаются из пула НАВСЕГДА — то
     * есть рабочая ссылка хоронилась по признаку, который её мёртвой
     * не делает.
     *
     * Из пула такая ссылка НЕ исключается: релея может не оказаться
     * один раз, а в следующий он найдётся.
     */
    const val ST_NO_TURN = "релея нет"

    data class Entry(
        val hash: String,
        val status: String = ST_NEW,
        /** Время последней проверки, миллисекунды. 0 — не проверялся. */
        val checkedAt: Long = 0L,
    )

    val items = mutableStateListOf<Entry>()

    private var appCtx: Context? = null
    private var loaded = false

    /**
     * Поднимает пул. При первом запуске засевает его из Config.VK_HASHES.
     *
     * Засев временный: он нужен, пока экрана не было, и чтобы цепочка
     * проверилась уже сейчас. Когда пул непуст, Config не смотрится
     * вовсе — человек управляет списком с экрана.
     */
    @Synchronized
    fun attach(ctx: Context) {
        if (loaded) return
        loaded = true
        appCtx = ctx.applicationContext

        items.addAll(read())
        // ЗАСЕВ ТОЛЬКО В ОТЛАДКЕ (56.4). Ссылки на звонки человек
        // заводит свои: чужие в его сборке — это чужой расход и чужая
        // капча. В отладочной сборке засев остаётся, иначе цепочку
        // нечем проверять с чистой установки.
        if (items.isEmpty()) {
            // seedHashes() своя у каждого типа сборки: в отладке отдаёт
            // список из Config, в релизе — пустой. Проверки
            // BuildConfig.DEBUG здесь БОЛЬШЕ НЕТ намеренно: она
            // оставляла ссылки внутри релизного APK, см. Seed.kt.
            val seeded = seedHashes().mapNotNull { normalize(it) }.distinct()
            if (seeded.isNotEmpty()) {
                items.addAll(seeded.map { Entry(it) })
                write()
                TunnelLog.add("пул хешей засеян из настроек: ${seeded.size}")
            }
        }
    }

    /**
     * Хеши, отложенные из-за капчи, — ДО КОНЦА РАБОТЫ ПРИЛОЖЕНИЯ.
     *
     * Не сохраняется намеренно. Капча означает не «ссылка сломана», а
     * «человек не стал её решать»: это свойство источника обращений, а
     * не хеша. Хоронить рабочую ссылку за это неправильно, но и лезть в
     * неё второй раз подряд незачем — экран капчи откроется снова.
     *
     * Ровно так же поступает движок: cache.state[hash] = hashCaptcha
     * живёт в памяти процесса и до перезапуска.
     */
    private val captchaHold = HashSet<String>()

    /**
     * Хеши для лестницы.
     *
     * Мёртвые не отдаём никогда, отложенные из-за капчи — до перезапуска
     * приложения.
     */
    fun usable(): List<String> =
        items.filter { it.status != ST_DEAD && it.hash !in captchaHold }.map { it.hash }

    /** Все хеши — для затирания в выгрузке лога. */
    fun all(): List<String> = items.map { it.hash }

    /**
     * Добавляет из введённой строки. Возвращает, что сообщить человеку.
     *
     * Принимает и полную ссылку, и голый хеш, и несколько сразу.
     */
    fun add(input: String): String {
        val parsed = parseMany(input)
        if (parsed.isEmpty()) return "не разобрал ни одного хеша"

        val known = items.map { it.hash }.toHashSet()
        val fresh = parsed.filter { known.add(it) }
        if (fresh.isEmpty()) return "все ${parsed.size} уже в списке"

        items.addAll(fresh.map { Entry(it) })
        write()
        return if (fresh.size == parsed.size) "добавлено: ${fresh.size}"
        else "добавлено ${fresh.size}, пропущено дублей ${parsed.size - fresh.size}"
    }

    fun remove(hash: String) {
        items.removeAll { it.hash == hash }
        write()
    }

    /**
     * Сбрасывает все статусы в «не проверялся».
     *
     * Нужно, когда вердикты оказались недостоверными: до задачи 28
     * хеш объявлялся мёртвым при любой неудаче на шаге 2, и живые хеши
     * оказались похоронены. Молча переписывать сохранённое неправильно,
     * поэтому сброс — по кнопке.
     *
     * Пригодится и дальше: статус со временем устаревает сам.
     */
    fun resetStatuses() {
        captchaHold.clear()
        for (i in items.indices) {
            items[i] = items[i].copy(status = ST_NEW, checkedAt = 0L)
        }
        write()
    }

    fun setStatus(hash: String, status: String) {
        val i = items.indexOfFirst { it.hash == hash }
        if (i < 0) return
        // Статус капчи сохраняем — человеку полезно видеть, чем кончилась
        // проверка, — но из пула хеш убираем только на эту сессию.
        if (status == ST_CAPTCHA) captchaHold.add(hash) else captchaHold.remove(hash)
        items[i] = items[i].copy(status = status, checkedAt = System.currentTimeMillis())
        write()
    }

    fun checkedText(e: Entry): String =
        if (e.checkedAt == 0L) "не проверялся"
        else SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date(e.checkedAt))

    /**
     * Разбор нескольких хешей из одной строки.
     *
     * Разделители те же, что у роутерного клиента (спецификация-2 B.8,
     * ParseHashes): запятая, точка с запятой, перевод строки, таб,
     * пробел. Дубли отбрасываются молча.
     */
    fun parseMany(input: String): List<String> {
        val out = LinkedHashSet<String>()
        for (part in input.split(',', ';', '\n', '\r', '\t', ' ')) {
            normalize(part)?.let { out.add(it) }
        }
        return out.toList()
    }

    /**
     * Одна ссылка или хеш → хеш.
     *
     * Правила из normalizeVKJoinHash (спецификация-2, B.8):
     * вырезать хеш из /call/join/<хеш>; принять голый хеш, если строка
     * не начинается с http(s); отсечь всё после ?, # и /.
     *
     * Человек копирует из VK ссылку целиком, а не хеш, — поэтому разбор
     * тут, а не на нём.
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null

        if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
            val marker = "/call/join/"
            val at = s.indexOf(marker, ignoreCase = true)
            if (at < 0) return null
            s = s.substring(at + marker.length)
        }

        // Отсекаем хвост: параметры, якорь, продолжение пути.
        for (stop in charArrayOf('?', '#', '/')) {
            val at = s.indexOf(stop)
            if (at >= 0) s = s.substring(0, at)
        }
        s = s.trim()
        return s.ifEmpty { null }
    }

    private fun read(): List<Entry> {
        val ctx = appCtx ?: return emptyList()
        return try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    hash = o.getString("h"),
                    status = o.optString("s", ST_NEW),
                    checkedAt = o.optLong("t", 0L),
                )
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun write() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            for (e in items) {
                arr.put(
                    JSONObject()
                        .put("h", e.hash)
                        .put("s", e.status)
                        .put("t", e.checkedAt)
                )
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (e: Throwable) {
            TunnelLog.add("пул хешей не сохранился: ${e.message}")
        }
    }
}
