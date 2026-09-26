package org.meridianvpn.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Лог на экране, переживающий перезапуск процесса.
 *
 * Потолок в MAX_LINES — не украшение, а инвариант, перенесённый с роутера:
 * лог без ограничения однажды съел 118 МБ за 19 минут. На телефоне это
 * будет память процесса, что ещё неприятнее. Потолок остаётся.
 *
 * Отдельно и НАМНОГО меньше — хвост в PERSIST_LINES строк, который
 * переживает гибель процесса. Он нужен ровно затем, зачем понадобился:
 * первый запуск после установки не оставил ни единой строки, потому что
 * лог жил только в памяти, а владелец нажал кнопку заново. Хвост пишется
 * пачками, а не на каждую строку: SharedPreferences не рассчитан на
 * запись по десять раз в секунду.
 */
object TunnelLog {
    /**
     * Тег для системного лога.
     *
     * Снимать так:
     *     adb logcat -s Meridian:I
     *
     * Фильтр по пакету для этого не годится: он показывает шум
     * отрисовки и ни одной нашей строки, потому что раньше мы в
     * системный лог не писали вовсе.
     */
    private const val TAG = "Meridian"

    private const val MAX_LINES = 200

    /**
     * Сколько строк вытеснено потолком за этот запуск.
     *
     * ЗАЧЕМ СЧИТАТЬ ТО, ЧЕГО УЖЕ НЕТ. 27.08 я разобрал лог владельца и
     * доложил «восемь подключений подряд без единого отказа». Кот 1 в то
     * же время видел у себя два десятка провалов прямой ступени, часть —
     * в те же минуты. Разошлись мы не в толковании, а в исходных данных:
     * в лог помещается около двухсот строк, одна попытка с отказом
     * стоит под сотню, и до меня доезжали ПОСЛЕДНИЕ две-три попытки.
     * Успешные короче неудачных — значит выживают охотнее, и выборка
     * смещена в сторону успеха систематически, а не случайно.
     *
     * Потолок остаётся: он инвариант, снимать его нельзя. Но молчать о
     * том, что лог неполон, — значит выдавать хвост за целое. Число
     * ниже это молчание и снимает.
     */
    private var droppedLines = 0

    /** Сколько строк не доехало до конца лога. Ноль — лог целый. */
    fun dropped(): Int = droppedLines

    /**
     * Потолок длины строки ДЛЯ LOGCAT. В лог приложения строка идёт
     * целиком.
     *
     * Разбор — в write(). Коротко: длинные строки там синхронно
     * придерживают поток движка, и подключение из-за этого замедлялось
     * на секунды.
     */
    private const val LOGCAT_MAX = 400

    /** Сколько строк переживает перезапуск. Малое число намеренно. */
    private const val PERSIST_LINES = 80

    private const val PREFS = "meridian_log"
    private const val KEY_TAIL = "tail"
    private const val FLUSH_DELAY_MS = 400L

    private val stamp = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)
    private val main = Handler(Looper.getMainLooper())

    val lines = mutableStateListOf<String>()

    /** Хвост для записи на диск. Трогается ТОЛЬКО с главного потока. */
    private val tail = ArrayDeque<String>()

    private var appCtx: Context? = null
    private var loaded = false

    private val flush = Runnable { writeTail() }

    /**
     * Подключает хранилище и поднимает хвост прошлого запуска.
     *
     * Зовётся из onCreate и активности, и службы: какой из них родится
     * первым, заранее неизвестно, а повторный вызов безвреден.
     */
    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        main.post {
            if (appCtx == null) appCtx = app
            if (loaded) return@post
            loaded = true

            val saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TAIL, null)
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                .orEmpty()

            if (saved.isNotEmpty()) {
                // Сами восстановленные строки в системный лог не
                // дублируем: они уже там были в прошлом запуске, и
                // повтор только запутает. Отмечаем лишь границу.
                Log.i(TAG, "──────── поднят хвост прошлого запуска, строк ${saved.size} ────────")
                lines.add("──────── прошлый запуск ────────")
                lines.addAll(saved)
                tail.addAll(saved)
                trimTail()
                lines.add("──────── этот запуск ────────")
            }
        }
    }

    /**
     * ТЕХНИЧЕСКАЯ строка. В релизе НЕ ПОПАДАЕТ НИКУДА.
     *
     * Сюда пишет движок и вся наша внутренняя кухня: порты, ступени,
     * обфускация, размеры на проводе, шаги цепочки VK. Человеку это
     * отдавать нельзя — по такому логу разбирается устройство
     * протокола, а лог человек пересылает кому угодно.
     *
     * ОТСЕКАЕТСЯ У ИСТОЧНИКА, А НЕ ФИЛЬТРОМ ПО СЛОВАМ. Список
     * запрещённых слов пришлось бы дополнять при каждой новой строке в
     * движке, и однажды его забыли бы дополнить. Здесь же наоборот:
     * чтобы строка попала к человеку, её надо ЯВНО объявить событием
     * через event(). Молчание — состояние по умолчанию.
     */
    fun add(line: String) {
        if (!BuildConfig.DEBUG) return
        write(line)
    }

    /**
     * КЛИЕНТСКОЕ событие. Видно всегда, в том числе в релизе.
     *
     * Что сюда можно, а что нельзя — задача 61.4 и 61.5. Коротко:
     * человеческими словами о том, ЧТО случилось, и ни слова о том,
     * КАК оно устроено внутри.
     *
     * Пути называются буквами (см. Paths): нам они говорят, каким
     * плечом встал туннель, постороннему — ничего.
     */
    fun event(line: String) {
        write(line)
    }

    private fun write(line: String) {
        val text = "${stamp.format(Date())}  $line"
        // В системный лог — СИНХРОННО и ДО отправки на главный поток.
        // Именно поэтому строка переживёт падение, случившееся раньше,
        // чем главный поток успел что-либо сохранить.
        // В РЕЛИЗЕ В LOGCAT НЕ ПИШЕМ. Оттуда лог читает любое
        // приложение с отладочным доступом и любой, кто подключит
        // телефон кабелем; там же оседают обрезанные ссылки VK и
        // подробности подключения. Внутри приложения лог остаётся —
        // человек отдаёт его сам кнопкой «поделиться», когда захочет.
        //
        // ДЛИНУ ДЛЯ LOGCAT ОБРЕЗАЕМ, и это не косметика.
        //
        // Разбор ответов VK даёт строки в полторы тысячи знаков, а
        // Log.i синхронный: система бьёт длинную строку на куски и при
        // потоке таких строк придерживает пишущего. Держит она при этом
        // не поток Kotlin, а поток движка — вызов пришёл из Go через
        // JNI, — и вместе с ним встаёт планировщик Go целиком.
        //
        // Видно это было так: 27.08 обычный сон в Go на 1,5 секунды
        // занял 4,4, при том что эхо в той же сессии возвращалось за
        // 80 мс. То есть подключение замедляла НАША СОБСТВЕННАЯ ЗАПИСЬ
        // В ЛОГ, а выглядело это как медленная лестница.
        //
        // В лог приложения строка идёт ЦЕЛИКОМ: обрезка только для
        // logcat, где она и мешала. Первых знаков хватает, чтобы
        // опознать событие в записке о падении, ради которой запись и
        // сделана синхронной.
        if (BuildConfig.DEBUG) {
            Log.i(TAG, if (text.length <= LOGCAT_MAX) text else text.take(LOGCAT_MAX) + "…")
        }
        main.post {
            lines.add(text)
            while (lines.size > MAX_LINES) {
                lines.removeAt(0)
                droppedLines++
            }
            tail.addLast(text)
            trimTail()
            scheduleFlush()
        }
    }

    /**
     * Разделитель между сессиями.
     *
     * Пришёл на замену clear(): стирать лог при подключении означало
     * терять улики ровно того запуска, который не сработал.
     */
    fun separator(title: String) {
        // В релизе в системный лог не пишем — как и в write().
        if (BuildConfig.DEBUG) Log.i(TAG, "──────── $title ────────")
        main.post {
            val text = "──────── $title ────────"
            lines.add(text)
            while (lines.size > MAX_LINES) {
                lines.removeAt(0)
                droppedLines++
            }
            tail.addLast(text)
            trimTail()
            // Разделитель пишем на диск немедленно: он размечает границу
            // попытки, и потерять его — значит потерять смысл хвоста.
            main.removeCallbacks(flush)
            writeTail()
        }
    }

    /** Стирает и экран, и хвост. Только по явной команде человека. */
    fun clear() {
        main.post {
            lines.clear()
            tail.clear()
            main.removeCallbacks(flush)
            writeTail()
        }
    }

    private fun trimTail() {
        while (tail.size > PERSIST_LINES) {
            tail.removeFirst()
        }
    }

    private fun scheduleFlush() {
        main.removeCallbacks(flush)
        main.postDelayed(flush, FLUSH_DELAY_MS)
    }

    private fun writeTail() {
        val ctx = appCtx ?: return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TAIL, tail.joinToString("\n"))
                .apply()
        } catch (e: Throwable) {
            // Лог — не то, ради чего стоит ронять приложение.
        }
    }
}

object TunnelState {
    private val main = Handler(Looper.getMainLooper())

    /**
     * Контекст для обновления плитки быстрых настроек. Ставится в
     * attach; пусто — плитку просто не дёргаем.
     */
    @Volatile
    private var appCtx: Context? = null

    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    val connected = mutableStateOf(false)

    /**
     * БЕЛЫЙ РЕЖИМ: туннель поднят через релей (ссылки VK введены, прямые
     * пути закрыты — то есть работает обход белых списков). Только вместе с
     * connected; по этому признаку планета рисуется белым контуром.
     */
    val white = mutableStateOf(false)

    /**
     * Туннель ПОДНИМАЕТСЯ: человек нажал, но лестница ещё идёт.
     *
     * Нужно ровно для одного — чтобы нажатие на планету сразу дало
     * видимое следствие. Подъём занимает до двадцати пяти секунд, и без
     * этого признака планета всё это время стоит неподвижно, как будто
     * нажатие не сработало.
     */
    val busy = mutableStateOf(false)

    fun setBusy(value: Boolean) {
        main.post {
            busy.value = value
            refreshTile()
        }
    }

    /**
     * Любой определённый исход снимает «поднимаюсь»: и успех, и провал.
     * Иначе застрявшее true оставило бы планету вращаться навсегда.
     */
    fun setConnected(value: Boolean, whiteMode: Boolean = false) {
        main.post {
            connected.value = value
            white.value = value && whiteMode
            busy.value = false
            refreshTile()
        }
    }

    /** Плитка в открытой шторке не должна отставать от экрана. */
    private fun refreshTile() {
        appCtx?.let { MeridianTileService.requestRefresh(it) }
    }
}
