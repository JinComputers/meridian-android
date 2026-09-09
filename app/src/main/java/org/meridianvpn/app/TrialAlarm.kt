package org.meridianvpn.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Напоминания о конце триала: за час и за десять минут.
 *
 * ПОЧЕМУ НЕ ТОЧНЫЙ БУДИЛЬНИК. Точные будильники с Android 12 требуют
 * SCHEDULE_EXACT_ALARM, а в Play это разрешение отдельно обосновывают и
 * дают его по сути только будильникам и календарям. Напоминание о конце
 * триала под это не подходит, и просить его — значит нарваться на отказ
 * на ревью.
 *
 * Поэтому setAndAllowWhileIdle: разрешения не требует и срабатывает даже
 * в Doze, но в окно обслуживания, а не секунда в секунду. Отсюда прямое
 * следствие, которое надо знать: напоминание «за десять минут» может
 * прийти и за три минуты, и через пять минут ПОСЛЕ конца триала. Час
 * такой сдвиг переживает, десять минут — нет.
 *
 * Точный текст поэтому считается в момент срабатывания, а не при
 * постановке: если будильник опоздал, человек прочтёт правду.
 *
 * Будильники живут в системе и переживают выгрузку процесса, но НЕ
 * переживают перезагрузку — отсюда приёмник BOOT_COMPLETED.
 */
object TrialAlarm {

    private const val CHANNEL_ID = "meridian_trial"
    private const val NOTIFICATION_ID = 3

    const val ACTION_REMIND = "org.meridianvpn.app.TRIAL_REMIND"

    private const val HOUR_MS = 60L * 60L * 1000L
    private const val TEN_MIN_MS = 10L * 60L * 1000L

    /** Разные коды, иначе второй будильник затрёт первый. */
    private const val RQ_HOUR = 11
    private const val RQ_TEN = 12

    /**
     * Ставит оба напоминания заново.
     *
     * Звать не жалко: старые будильники с теми же кодами заменяются, а
     * не копятся. Зовём при старте приложения, при начале триала и
     * после перезагрузки.
     */
    fun arm(ctx: Context) {
        val app = ctx.applicationContext
        cancel(app)

        Access.refresh()
        if (Access.state.value != Access.State.TRIAL) return

        val ends = Access.endsAt()
        if (ends == 0L) return

        val now = System.currentTimeMillis()
        var armed = 0
        if (set(app, RQ_HOUR, ends - HOUR_MS, now)) armed++
        if (set(app, RQ_TEN, ends - TEN_MIN_MS, now)) armed++
        if (armed > 0) TunnelLog.add("напоминаний о конце триала поставлено: $armed")
    }

    fun cancel(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = app.getSystemService(AlarmManager::class.java) ?: return
        for (rq in intArrayOf(RQ_HOUR, RQ_TEN)) {
            mgr.cancel(pending(app, rq))
        }
    }

    private fun set(ctx: Context, rq: Int, at: Long, now: Long): Boolean {
        if (at <= now) return false
        return try {
            ctx.getSystemService(AlarmManager::class.java)
                ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(ctx, rq))
            true
        } catch (e: Throwable) {
            TunnelLog.add("напоминание не поставилось: ${e.message}")
            false
        }
    }

    private fun pending(ctx: Context, rq: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rq,
            Intent(ctx, Receiver::class.java).setAction(ACTION_REMIND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * Показывает напоминание.
     *
     * Текст считается ЗДЕСЬ, по часам, а не по тому, какой будильник
     * сработал: будильник неточный и мог опоздать.
     */
    fun remind(ctx: Context) {
        Access.attach(ctx)
        Access.refresh()

        val st = Access.state.value
        if (st == Access.State.KEYED) return

        // ГДЕ БРАТЬ КЛЮЧ — СВОЁ У КАЖДОГО ВАРИАНТА СБОРКИ.
        //
        // Здесь была прямая утечка: обе строки называли бота, и обе
        // попадали в сборку для Play. Уведомление, зовущее человека
        // покупать вне Play, — то же нарушение, что и кнопка, только
        // его не видно на экране, пока не сработает будильник.
        //
        // Нашлось это НЕ чтением кода, а поиском по собранному APK:
        // задача 60.3 требовала проверять пакет, и требовала не зря.
        val where = purchaseHint()
        val text = when (st) {
            Access.State.TRIAL ->
                "Пробный период кончается: осталось ${Access.leftText()}. $where"
            Access.State.OVER ->
                "Пробный период закончился. $where"
            else -> return
        }

        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Триал",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            mgr.notify(
                NOTIFICATION_ID,
                Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("Meridian")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_meridian_notify)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
            TunnelLog.add("напоминание о триале показано: $text")
        } catch (e: Throwable) {
            TunnelLog.add("напоминание не показалось: ${e.message}")
        }
    }

    /**
     * Приёмник будильников и перезагрузки.
     *
     * После перезагрузки будильники теряются, поэтому ставим заново.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            TunnelLog.attach(ctx)
            Access.attach(ctx)
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED -> arm(ctx)
                ACTION_REMIND -> {
                    remind(ctx)
                    // Второй будильник мог не пережить выгрузку — ставим
                    // оставшиеся заново.
                    arm(ctx)
                }
            }
        }
    }
}
