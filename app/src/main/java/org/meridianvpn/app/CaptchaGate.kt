package org.meridianvpn.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import engine.CaptchaSolver
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мост между движком и экраном капчи.
 *
 * Движок зовёт solve() из своей goroutine и БЛОКИРУЕТСЯ, пока человек
 * не решит капчу или пока не выйдет срок. Экран, решив, кладёт токен
 * сюда, и движок продолжает с того же места.
 *
 * ПРО ФОН — это главная трудность задачи.
 *
 * При проверке хеша с экрана активность на виду, и startActivity
 * срабатывает. А при подключении цепочка работает внутри фоновой
 * службы, и с Android 10 запуск активности из фона системой запрещён:
 * молча ничего не произойдёт.
 *
 * Поэтому делаем оба хода сразу: пробуем открыть экран напрямую И
 * вешаем уведомление. Если приложение на виду — капча появится сама;
 * если нет — человек увидит уведомление и откроет её нажатием. Третьего
 * надёжного способа на сегодняшнем Android нет.
 */
object CaptchaGate {
    private const val CHANNEL_ID = "meridian_captcha"
    private const val NOTIFICATION_ID = 2

    /** Сколько ждём человека. То же число, что в движке. */
    private const val WAIT_MINUTES = 3L

    /** Адрес капчи, ожидающей решения. Пусто — ничего не ждём. */
    @Volatile
    var pendingUri: String? = null
        private set

    private val busy = AtomicBoolean(false)

    /**
     * Почему последний показ кончился ничем.
     *
     * Движок спрашивает это отдельным вызовом сразу после solve(), и
     * только когда тот вернул пусто. Через gomobile сторона Java умеет
     * возвращать лишь простые типы, и уложить в одну строку и токен, и
     * причину значило бы снова завести строковую типизацию.
     *
     * Слова — закрытый список, он же в engine/captcha.go рядом с
     * константами captchaReason*. Менять только вместе.
     */
    @Volatile
    private var lastReason = REASON_CLOSED

    private const val REASON_CLOSED = "closed"
    private const val REASON_TIMEOUT = "timeout"
    private const val REASON_BUSY = "busy"

    /**
     * Очередь на один элемент вместо защёлки: экран может отдать
     * результат раньше, чем движок дойдёт до ожидания, и защёлка такой
     * случай проглотила бы.
     */
    private var answer = ArrayBlockingQueue<String>(1)

    /**
     * Зовётся ИЗ ДВИЖКА, не с главного потока. Блокирует.
     *
     * Возвращает success_token или пустую строку, если человек не решил.
     */
    fun solve(ctx: Context, uri: String): String {
        if (!busy.compareAndSet(false, true)) {
            // Две капчи разом — такого быть не должно, но если случится,
            // показывать человеку два экрана подряд нельзя.
            TunnelLog.add("капча: экран уже открыт, вторую не показываю")
            lastReason = REASON_BUSY
            return ""
        }
        try {
            answer = ArrayBlockingQueue(1)
            pendingUri = uri

            notify(ctx)
            openScreen(ctx)

            // poll возвращает null ТОЛЬКО по сроку. Пустая строка —
            // это ответ экрана «закрыли, не решив», и различать их надо:
            // человек, закрывший окно, и человек, не успевший, ведут
            // себя по-разному.
            val answered = answer.poll(WAIT_MINUTES, TimeUnit.MINUTES)
            if (answered == null) {
                lastReason = REASON_TIMEOUT
                TunnelLog.add("капча: ответа нет за $WAIT_MINUTES мин — считаю нерешённой")
                return ""
            }
            if (answered.isEmpty()) {
                lastReason = REASON_CLOSED
            }
            return answered
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            lastReason = REASON_CLOSED
            return ""
        } finally {
            pendingUri = null
            busy.set(false)
            cancelNotification(ctx)
        }
    }

    /**
     * Готовый решатель для движка.
     *
     * Контекст берём именно приложения: экран капчи живёт минутами и
     * переживёт и активность, и службу, из которой его позвали.
     */
    fun solver(ctx: Context): CaptchaSolver {
        val app = ctx.applicationContext
        return object : CaptchaSolver {
            override fun solve(redirectURI: String): String = CaptchaGate.solve(app, redirectURI)
            override fun reason(): String = lastReason
        }
    }

    /** Экран отдаёт результат. Пустая строка — закрыли, не решив. */
    fun deliver(token: String) {
        answer.offer(token)
    }

    private fun openScreen(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(ctx, CaptchaActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            // Из фона система запускать активности не даёт. Это не сбой,
            // а ожидаемый исход — уведомление уже висит.
            TunnelLog.add("капча: открыть экран из фона нельзя, жду нажатия на уведомление")
        }
    }

    private fun notify(ctx: Context) {
        try {
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Капча VK",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, CaptchaActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            mgr.notify(
                NOTIFICATION_ID,
                Notification.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("Meridian: нужна проверка")
                    .setContentText("VK просит подтвердить, что вы не робот — нажмите")
                    .setSmallIcon(R.drawable.ic_meridian_notify)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (e: Throwable) {
            TunnelLog.add("капча: уведомление не показалось: ${e.message}")
        }
    }

    private fun cancelNotification(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (e: Throwable) {
            // Неважно.
        }
    }
}
