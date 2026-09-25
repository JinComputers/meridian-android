package org.meridianvpn.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Российские приложения и сайты мимо VPN — предложение ОДИН РАЗ (просьба
 * владельца 25.09; сначала были только банки, теперь — переключатель
 * «RU-адреса напрямую» целиком, RuDirect.kt).
 *
 * Многие банки, госуслуги и маркетплейсы не работают через VPN, а сам
 * человек не догадывается, что это лечится настройкой. Если на телефоне
 * есть приложения из RuDirect.RU_APPS и переключатель выключен — спрашиваем
 * один раз; «Да» включает переключатель. В окне — настоящие названия
 * найденных приложений, не наш список.
 */
private const val PREFS = "meridian_banks"
private const val K_ASKED = "asked"

/** Установленные российские приложения, если переключатель ещё выключен. */
private fun appsToOffer(ctx: Context): List<String> =
    if (RuDirect.enabled()) emptyList() else RuDirect.installedApps(ctx)

private fun label(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Throwable) {
    pkg
}

private fun asked(ctx: Context): Boolean = try {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ASKED, false)
} catch (e: Throwable) {
    true // не прочитали — лучше не спросить, чем спрашивать каждый раз
}

private fun markAsked(ctx: Context) {
    try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_ASKED, true).apply()
    } catch (e: Throwable) {
    }
}

@Composable
fun BankOffer() {
    val ctx = LocalContext.current
    // Не поверх вопроса об уведомлениях: два окна подряд — одно лишнее.
    // Тот вопрос задаётся один раз, после него очередь банков.
    val notifPending = Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED && !Access.notifAsked()
    var banks by remember { mutableStateOf(if (asked(ctx)) emptyList() else appsToOffer(ctx)) }
    if (banks.isEmpty() || notifPending) return

    val shown = banks.take(5).joinToString(", ") { label(ctx, it) }
    val names = if (banks.size > 5) "$shown и ещё ${banks.size - 5}" else shown
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Российские сервисы мимо VPN?") },
        text = {
            Text(
                "Банки, госуслуги и маркетплейсы часто не работают через VPN. " +
                    "Пустить мимо туннеля российские сайты и приложения ($names)? " +
                    "Изменить можно в «Настройки → RU-адреса напрямую»."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                markAsked(ctx)
                RuDirect.setEnabled(true)
                if (TunnelState.connected.value) {
                    Notice.say("Российские сервисы пойдут мимо VPN со следующего подключения")
                }
                banks = emptyList()
            }) { Text("Да, мимо VPN") }
        },
        dismissButton = {
            TextButton(onClick = {
                markAsked(ctx)
                TunnelLog.add("RU-адреса напрямую: предложение отклонено")
                banks = emptyList()
            }) { Text("Не нужно") }
        },
    )
}
