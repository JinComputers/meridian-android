package org.meridianvpn.app

import android.content.Context

/**
 * Память последнего РАБОЧЕГО адреса шлюза, полученного резолвом имени
 * Config.EDGE_HOST.
 *
 * НЕ PathMemory. Та память — по сети и со счётчиками-«стриками» для
 * решения, какую ступень лестницы пробовать первой; здесь же — одна
 * строка без сети и без счётчиков, смешивать незачем, только путаница.
 *
 * СЕТИ ЗДЕСЬ НЕТ ВООБЩЕ, и это не оплошность. Сам резолв (DoH, системный
 * DNS) живёт в движке (engine/resolve.go) — он общий для Android и iOS,
 * и дублировать его на Kotlin нельзя. Единственное, что не может жить в
 * движке, — память между перезапусками процесса: SharedPreferences здесь,
 * UserDefaults у iOS, каждая платформа своя.
 *
 * Записывается ТОЛЬКО после подтверждённого Engine.start() — раньше
 * означало бы «резолвился», а не «работал», и один неверный кэш убил бы
 * следующую попытку раньше времени.
 */
object EdgeCache {
    private const val PREFS = "meridian_edge"
    private const val K_LAST_GOOD = "last_good_ip"

    fun get(ctx: Context): String? = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_LAST_GOOD, null)
    } catch (e: Throwable) {
        null
    }

    fun set(ctx: Context, addr: String) {
        if (addr.isEmpty() || addr == get(ctx)) return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_LAST_GOOD, addr).apply()
        } catch (e: Throwable) {
            // Память адреса — удобство, а не условие работы.
        }
    }
}
