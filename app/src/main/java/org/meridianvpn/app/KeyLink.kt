package org.meridianvpn.app

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

/**
 * Ключ по ссылке из бота: meridian://key/<ключ> (просьба владельца 25.09).
 *
 * Человек нажимает ссылку в Telegram — открывается приложение, и ключ уже
 * подставлен: самый частый шаг, на котором ошибаются (скопировать ключ
 * целиком, без пробела и обрезки), исчезает.
 *
 * ПРИМЕНЯЕТСЯ ТОЛЬКО ПОСЛЕ НАЖАТИЯ «ПРИМЕНИТЬ». Ссылку может прислать кто
 * угодно, и молча подменить ключ по чужой ссылке нельзя: на экране
 * показываем последние знаки ключа и спрашиваем. Дальше тот же путь, что у
 * ручного ввода: Api.checkKey → applyVerdict (AccessScreen.KeyLinkPrompt).
 *
 * ТОЛЬКО ПРЯМАЯ РАЗДАЧА. Фильтр ссылки объявлен в
 * app/src/direct/AndroidManifest.xml, а здесь дополнительно отсекается
 * KEY_ENTRY: в сборке для Play ключ, купленный мимо магазина, вводить
 * нельзя по его правилам — и по ссылке тоже.
 *
 * КЛЮЧ НЕ ПИШЕТСЯ В ЛОГ НИКОГДА — ни целиком, ни частью.
 */
object KeyLink {

    /** Ключ из последней ссылки, ждущий подтверждения. Пусто — ничего. */
    val pending = mutableStateOf("")

    /** Разобрать интент активности. Чужие интенты игнорирует. */
    fun take(intent: Intent?) {
        if (!KEY_ENTRY) return
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (uri.scheme != "meridian" || uri.host != "key") return
        // meridian://key/<ключ> или meridian://key?k=<ключ>
        val raw = uri.lastPathSegment ?: uri.getQueryParameter("k") ?: return
        val k = raw.trim()
        if (k.length < 8) {
            TunnelLog.add("ссылка с ключом: ключ слишком короткий — пропускаю")
            return
        }
        pending.value = k
        TunnelLog.add("ссылка с ключом получена, жду подтверждения")
    }

    /** Показ на экране: только последние четыре знака, чтобы узнать ключ, не раскрывая его. */
    fun masked(k: String): String =
        "…" + k.takeLast(4)

    fun clear() {
        pending.value = ""
    }
}
