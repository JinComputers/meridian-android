package org.meridianvpn.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Вынос лога наружу: в буфер обмена и файлом через «Поделиться».
 *
 * ОТЛАДОЧНОЕ, убрать перед публикацией вместе с кнопками на экране и
 * переключателем транспорта.
 *
 * Появилось затем, что логи ходили снимками экрана: в переписку их
 * влезает около сотни строк, а нужного места в них обычно нет.
 */
object LogExport {

    /**
     * Порог, выше которого буфер обмена не используем.
     *
     * Точного предела на размер ClipData Android не документирует. Что
     * есть на самом деле: содержимое буфера едет через Binder, а у него
     * транзакция ограничена примерно мегабайтом на процесс, и при
     * переполнении прилетает TransactionTooLargeException — причём
     * ограничение общее с другими транзакциями, так что реальный запас
     * меньше номинального.
     *
     * 256 КБ — консервативная граница с четырёхкратным запасом.
     *
     * На практике до неё не дойти: на экране живёт не больше
     * TunnelLog.MAX_LINES = 200 строк плюс до 80 восстановленных, то
     * есть порядка тридцати килобайт. Проверка стоит не потому, что
     * ожидается срабатывание, а потому что молча обрезанный лог хуже
     * отсутствующего: по нему делают выводы.
     */
    private const val CLIPBOARD_LIMIT = 256 * 1024

    /**
     * Весь лог одной строкой: и хвост прошлого запуска, и записка о
     * падении.
     *
     * Хеши затираются ПЕРЕД выдачей наружу. Движок и так пишет их
     * обрезанными, но здесь заслон второй и независимый: лог уходит в
     * мессенджер, а хеш — ссылка на звонок, по ней можно
     * присоединиться. Полагаться на то, что никто никогда не залогирует
     * хеш целиком, нельзя — а вот вычеркнуть известные можно наверняка.
     */
    fun text(): String {
        // ПЕРВОЙ СТРОКОЙ — ЧЕСТНОСТЬ О ПОЛНОТЕ.
        //
        // Лог держит около двухсот строк, одна неудачная попытка стоит
        // под сотню. Значит в выдачу попадают последние две-три попытки,
        // а успешные короче неудачных и выживают охотнее — выборка
        // смещена в сторону успеха, и смещена систематически.
        //
        // 27.08 я на этом ошибся: доложил «восемь подключений подряд без
        // отказа», тогда как отказы были, просто вытеснены. Строка ниже
        // такой вывод сделать больше не даст.
        val lost = TunnelLog.dropped()
        val head = if (lost > 0) {
            "──────── ЛОГ НЕПОЛОН: вытеснено строк $lost, " +
                "видны только последние попытки ────────\n"
        } else {
            ""
        }
        var body = head + TunnelLog.lines.joinToString("\n")
        for (h in HashStore.all()) {
            if (h.length > 8) {
                body = body.replace(h, h.take(4) + "…" + h.takeLast(4))
            }
        }
        return body
    }

    /**
     * Копирует в буфер. Возвращает строку для показа человеку.
     *
     * Если лог не влезает — честно говорит об этом и отправляет к
     * «Поделиться», а не копирует обрезок.
     */
    fun copy(ctx: Context): String {
        val body = text()
        if (body.length > CLIPBOARD_LIMIT) {
            return "лог ${body.length / 1024} КБ — больше предела буфера, жми «Поделиться»"
        }
        return try {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
                ?: return "буфер обмена недоступен"
            cm.setPrimaryClip(ClipData.newPlainText("Meridian", body))
            "скопировано: ${TunnelLog.lines.size} строк, ${body.length / 1024} КБ"
        } catch (e: Throwable) {
            "скопировать не вышло: ${e.message}"
        }
    }

    /**
     * Отдаёт лог файлом через стандартный Intent.
     *
     * Именно файлом, а не текстом в EXTRA_TEXT: текст пошёл бы тем же
     * Binder-ом с теми же ограничениями, а файл мессенджер возьмёт
     * целиком и покажет получателю как вложение.
     */
    fun share(ctx: Context): String = try {
        val dir = File(ctx.cacheDir, "logs")
        dir.mkdirs()
        // Старые выгрузки не копим: каждая новая заменяет предыдущие,
        // иначе кэш приложения растёт молча и без предела.
        dir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "meridian-$stamp.txt")
        file.writeText(text())

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Meridian, лог $stamp")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Отправить лог")
        // Из не-активити контекста иначе не запустить, а кнопка живёт в
        // Compose и контекст ей достаётся какой есть.
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(chooser)
        "готовлю файл: ${file.name}, ${file.length() / 1024} КБ"
    } catch (e: Throwable) {
        "поделиться не вышло: ${e.message}"
    }
}
