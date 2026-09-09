package org.meridianvpn.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Режим транспорта, переключаемый с экрана.
 *
 * ОТЛАДОЧНОЕ. Перед публикацией убрать вместе со второй кнопкой:
 * обычному человеку выбирать транспорт руками незачем, это работа
 * лестницы.
 *
 * Нужен затем, что проверять релейное плечо пересборкой приложения на
 * каждый опыт — слишком дорого. Значение переживает перезапуск, иначе
 * после падения режим молча возвращался бы к обычному, и опыт шёл бы не
 * тот, что задумывался.
 */
object TransportSetting {
    private const val PREFS = "meridian_debug"
    private const val KEY = "transport"

    val mode = mutableStateOf(Config.TRANSPORT_MODE)

    fun attach(ctx: Context) {
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return
        mode.value = try {
            Config.TransportMode.valueOf(saved)
        } catch (e: Throwable) {
            // Значение из прошлой сборки могло исчезнуть из перечисления.
            Config.TRANSPORT_MODE
        }
    }

    /** По кругу: авто → только прямой → только релей → авто. */
    fun cycle(ctx: Context) {
        val next = when (mode.value) {
            Config.TransportMode.AUTO -> Config.TransportMode.DIRECT_ONLY
            Config.TransportMode.DIRECT_ONLY -> Config.TransportMode.RELAY_ONLY
            Config.TransportMode.RELAY_ONLY -> Config.TransportMode.AUTO
        }
        mode.value = next
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, next.name)
            .apply()
        TunnelLog.add("режим транспорта переключён: $next")
    }

    /** Человеческое имя для кнопки. */
    fun label(): String = when (mode.value) {
        Config.TransportMode.AUTO -> "Транспорт: авто"
        Config.TransportMode.DIRECT_ONLY -> "Транспорт: только прямой"
        Config.TransportMode.RELAY_ONLY -> "Транспорт: только релей"
    }
}
