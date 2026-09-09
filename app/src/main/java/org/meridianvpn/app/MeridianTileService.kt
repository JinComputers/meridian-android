package org.meridianvpn.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Плитка быстрых настроек: включение и выключение туннеля одним касанием
 * из шторки, без открытия приложения.
 *
 * Это ожидаемый от VPN паттерн. Всю тяжёлую работу делает служба и
 * TunnelState — плитка лишь показывает состояние и дёргает те же
 * интенты, что кнопка на экране, чтобы два пути не разошлись.
 *
 * ЖИВОЕ ОБНОВЛЕНИЕ. Пока шторка открыта, состояние меняет служба; она
 * зовёт TunnelState.setConnected, а тот — requestListeningState, и
 * система снова зовёт onStartListening здесь. Сама плитка за состоянием
 * не следит: следит TunnelState, плитка лишь рисует.
 */
class MeridianTileService : TileService() {

    // Плитка может подняться в свежем процессе: доступ читаем из
    // хранилища, иначе password() вернёт пусто и мы зря откроем
    // приложение. attach идемпотентен.
    override fun onCreate() {
        super.onCreate()
        TunnelLog.attach(this)
        Access.attach(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()

        // Подъём идёт — второе нажатие ни к чему: планета и плитка
        // одинаково ждут исхода.
        if (TunnelState.busy.value) return

        if (TunnelState.connected.value) {
            startService(
                Intent(this, MeridianVpnService::class.java)
                    .setAction(MeridianVpnService.ACTION_DISCONNECT)
            )
            return
        }

        // Подключение. Два случая плитка сама не решает и передаёт
        // приложению: разрешение VPN ещё не выдано (системный диалог
        // может показать только активность) и доступа нет вовсе (триал
        // кончился, ключа нет — тут нужен экран покупки, не плитка).
        val consentNeeded = VpnService.prepare(this) != null
        if (consentNeeded || Access.password().isEmpty()) {
            openApp()
            return
        }

        // Даём немедленное видимое следствие, как нажатие на планету.
        TunnelState.setBusy(true)
        startService(
            Intent(this, MeridianVpnService::class.java)
                .setAction(MeridianVpnService.ACTION_CONNECT)
        )
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        tile.state = when {
            TunnelState.connected.value -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        // Подзаголовок — короткое состояние. Доступен с Android 10.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                TunnelState.busy.value -> "Подключаюсь…"
                TunnelState.connected.value -> "Подключено"
                else -> "Отключено"
            }
        }
        tile.updateTile()
    }

    /**
     * Открывает приложение и закрывает шторку.
     *
     * startActivityAndCollapse на Android 14 принимает PendingIntent
     * (перегрузка с Intent там бросает исключение), раньше — Intent.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /**
         * Просит систему перерисовать плитку. Зовётся из TunnelState при
         * смене состояния, чтобы в открытой шторке плитка не отставала.
         * Безопасно, даже если плитку никто не добавил.
         */
        fun requestRefresh(ctx: android.content.Context) {
            try {
                requestListeningState(
                    ctx, ComponentName(ctx, MeridianTileService::class.java)
                )
            } catch (e: Throwable) {
                // Плитки может не быть на этой прошивке — не беда.
            }
        }
    }
}
