package org.meridianvpn.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Записка о падении.
 *
 * Зачем: падение убивает процесс, а лог живёт в его памяти. Владелец
 * видел молчаливый перезапуск и считал, что всё работает, — причина
 * пропадала вместе с процессом, и доставать её приходилось через adb.
 *
 * Записка мала намеренно: класс исключения, сообщение и три верхних
 * кадра. Полный отчёт всё равно пишет система, дублировать его незачем.
 */
object CrashLog {
    private const val PREFS = "meridian_crash"
    private const val KEY_LAST = "last"
    private const val FRAMES = 3

    private var installed = false

    /**
     * Ставит обработчик необработанных исключений.
     *
     * Зовётся из onCreate и активности, и службы: неизвестно, что
     * родится первым. Повторный вызов ничего не делает.
     *
     * Прежний обработчик ОБЯЗАТЕЛЬНО вызывается следом — иначе система
     * перестанет писать свой отчёт и убивать процесс, а приложение
     * зависнет в неопределённом состоянии.
     */
    @Synchronized
    fun install(ctx: Context) {
        if (installed) return
        installed = true

        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                save(app, thread.name, error)
            } catch (e: Throwable) {
                // Записка не имеет права помешать штатной обработке.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(ctx: Context, threadName: String, error: Throwable) {
        val when_ = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US).format(Date())
        val frames = error.stackTrace.take(FRAMES).joinToString(" | ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        val text = "$when_, поток $threadName: ${error.javaClass.simpleName}: ${error.message} @ $frames"

        // Под нашим тегом тоже: полный отчёт системы приходится искать
        // отдельно, а здесь падение окажется в той же ленте, что и всё
        // остальное, ровно на своём месте по времени.
        android.util.Log.i("Meridian", "ПАДЕНИЕ: $text")

        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST, text)
            .commit() // именно commit: процесс сейчас умрёт, apply не успеет
    }

    /** Записка о прошлом падении, или null. */
    fun last(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, null)

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
    }
}
