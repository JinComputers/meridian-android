package org.meridianvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import engine.Engine
import engine.Logger
import kotlin.random.Random

/**
 * Фоновая проверка ссылок на ВК-звонки (просьба владельца 25.09).
 *
 * ЗАЧЕМ. Ссылки умирают (звонок завершили, ссылку отозвали), и человек
 * узнаёт об этом в худший момент — когда попал в белые списки и не может
 * подключиться. Проверяем заранее и предупреждаем, пока связь ещё есть.
 *
 * КАК РЕДКО И КАК БЕРЕЖНО. Проверка — полный проход цепочки VK, то есть
 * настоящий расход ссылки и повод для капчи. Поэтому:
 *   - не чаще раза в сутки (CHECK_EVERY_MS);
 *   - ссылки по одной, с той же паузой, что на экране ссылок;
 *   - до первой живой: одна живая уже значит «обход работает», остальные
 *     проверять незачем;
 *   - капчу НЕ показываем (решатель null): фоновая проверка не вправе
 *     дёргать человека. Капча — не смерть, такая ссылка не в счёт;
 *   - не во время подъёма туннеля: цепочка за кредами идёт там же.
 *
 * ПРЕДУПРЕЖДАЕМ, ТОЛЬКО КОГДА МЁРТВЫ ВСЕ. «Капча», «релея нет», сбой —
 * не приговор ссылке, и тревога по ним была бы ложной.
 *
 * Ссылок нет вовсе — молчим: обход нужен не всем, а тем, кто попал в
 * белые списки, подсказку даёт причина отказа (failureAdvice).
 */
object LinkWatch {

    private const val PREFS = "meridian_linkwatch"
    private const val K_LAST = "last_at"
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000
    private const val PAUSE_MIN_MS = 3000L
    private const val PAUSE_MAX_MS = 6000L

    private const val CHANNEL_ID = "meridian_fail" // тот же канал, что у причины отказа
    private const val NOTIFICATION_ID = 5

    const val WARN = "Ссылки для обхода белых списков больше не работают — " +
        "добавьте новую ссылку на звонок ВКонтакте в «Настройки → Обход белых списков»"

    /** Зовётся с фонового потока. Сама решает, пора ли. */
    fun maybeCheck(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = p.getLong(K_LAST, 0L)
        if (last != 0L && now - last in 0..CHECK_EVERY_MS) return

        val all = HashStore.items.toList()
        if (all.isEmpty()) return
        if (TunnelState.busy.value) return

        p.edit().putLong(K_LAST, now).apply()
        TunnelLog.add("ссылки VK: фоновая проверка раз в сутки, ссылок ${all.size}")

        val logger = object : Logger {
            override fun log(line: String) = TunnelLog.add(line)
        }
        val deviceId = Access.deviceId(ctx)
        // Сначала те, что не мертвы по прошлой проверке: шанс найти живую
        // с первой попытки выше, и расход ссылок меньше.
        val order = all.sortedBy { if (it.status == HashStore.ST_DEAD) 1 else 0 }

        for ((i, e) in order.withIndex()) {
            if (TunnelState.busy.value) {
                TunnelLog.add("ссылки VK: начался подъём туннеля — фоновую проверку прерываю")
                return
            }
            val status = try {
                Engine.checkHash(e.hash, deviceId, null, null, logger)
            } catch (t: Throwable) {
                "ошибка: ${t.message}"
            }
            val mapped = when (status) {
                "живой" -> HashStore.ST_ALIVE
                "капча" -> HashStore.ST_CAPTCHA
                "мёртвый" -> HashStore.ST_DEAD
                "релея нет" -> HashStore.ST_NO_TURN
                else -> status
            }
            HashStore.setStatus(e.hash, mapped)
            if (mapped == HashStore.ST_ALIVE) {
                TunnelLog.add("ссылки VK: живая есть — обход работает")
                return
            }
            if (i < order.size - 1) {
                Thread.sleep(PAUSE_MIN_MS + Random.nextLong(PAUSE_MAX_MS - PAUSE_MIN_MS))
            }
        }

        val allDead = HashStore.items.isNotEmpty() &&
            HashStore.items.all { it.status == HashStore.ST_DEAD }
        TunnelLog.add(
            if (allDead) "ссылки VK: все мёртвые — предупреждаю"
            else "ссылки VK: живых не нашлось, но не все мёртвые (капча/сбой) — молчу"
        )
        if (allDead) warn(ctx)
    }

    private fun warn(ctx: Context) {
        Notice.problem(WARN)
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Причина отказа подключения",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            mgr.notify(
                NOTIFICATION_ID,
                Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("Meridian")
                    .setContentText(WARN)
                    .setStyle(Notification.BigTextStyle().bigText(WARN))
                    .setSmallIcon(R.drawable.ic_meridian_notify)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (t: Throwable) {
            TunnelLog.add("предупреждение о ссылках не показалось: ${t.message}")
        }
    }
}
