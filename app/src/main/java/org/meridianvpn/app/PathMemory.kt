package org.meridianvpn.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.net.Inet4Address

/**
 * Память удачного пути.
 *
 * Ключ — СЕТЬ, а не устройство: дома и в дороге рабочие ступени разные,
 * и запоминать «что сработало на этом телефоне» бессмысленно.
 *
 * SSID в ключ НЕ входит намеренно. Его чтение требует разрешения на
 * геолокацию, а VPN-приложение, спрашивающее геолокацию, — это лишний
 * разговор на ревью в Play. Без SSID ключ грубее: две домашние сети с
 * одинаковой подсетью 192.168.1.0/24 сольются в один ключ. Цена ошибки
 * при этом мала — не та ступень окажется первой в списке, и лестница
 * просто пойдёт дальше.
 */
object PathMemory {
    private const val PREFS = "meridian_paths"

    /**
     * Счётчик подряд проигранных релею попыток на этой сети.
     *
     * Зачем: на сети, где прямой путь закрыт наглухо (443/56004/56005
     * мертвы у оператора), каждый подъём впустую тратит ~5 с на заведомо
     * провальный прямой перебор, прежде чем уйти на релей. Счётчик даёт
     * это заметить и на такой сети идти сразу на релей.
     *
     * ПОЧЕМУ СЧЁТЧИК, А НЕ ФЛАГ «ПЛОХАЯ СЕТЬ». Сеть может поменять
     * конфигурацию — прямой откроется обратно. Одной неудачи мало
     * (правило владельца: «не надо сразу отправлять на релей»), и даже
     * решив пропускать, мы всё равно раз в PROBE_EVERY попыток ЩУПАЕМ
     * прямой — иначе ожившую сеть никогда бы не заметили.
     *
     * SKIP_AFTER намеренно НЕ маленький. На сети, где прямой держится
     * через раз (монета ~50 %), три-четыре провала подряд редки, и мы
     * не отнимаем у человека быстрый прямой путь там, где он работает
     * половину времени. Пропуск включается только там, где прямой
     * закрыт по-настоящему: пять неудач подряд на монете — это 3 %, а на
     * мёртвом прямом — всегда.
     *
     * Пороги БОЛЬШЕ НЕ КОНСТАНТЫ здесь: они приходят из настройки
     * сервера (Params.directSkipAfterFails / directProbeEveryAttempts) и
     * передаются параметрами. Умолчания — там же, в Params, и равны
     * прежним 5 и 4. Так порог можно двигать по логам тестировщиков без
     * пересборки.
     */
    private const val STREAK_SUFFIX = "#relayStreak"

    /**
     * Счётчик подряд ОНЕМЕВШИХ потоковых ступеней (TCP/TLS) на этой сети.
     *
     * Зачем отдельный счётчик. 03.09 на сети с белыми списками поток
     * проходил проверку целиком — AUTH, обе метки с эхом, — туннель
     * вставал за секунду, а через восемь секунд сторож объявлял его
     * немым: отправлено 93, принято 0. Контрольный AUTH новым сокетом
     * при этом проходил всегда. Оператор пропускает начало соединения и
     * убивает его, как только пошёл настоящий трафик.
     *
     * Беда была не в этом, а в том, что НЕМОТА НЕ ОСТАВЛЯЛА СЛЕДА.
     * Победу потока мы намеренно не запоминаем (память держим для
     * UDP-прямых), и forgetMutePath на потоке оказывался пустышкой:
     * следующая попытка строила ту же лестницу, поток снова проходил
     * проверку, туннель снова немел. Круг замыкался, и до релея —
     * который на той же сети работал, 541 отправлено / 504 принято —
     * лестница не доходила НИКОГДА.
     *
     * Порог здесь МЕНЬШЕ, чем у пропуска UDP, и вот почему. Проигрыш
     * гонки — слабая улика: прямой мог просто оказаться медленнее.
     * Немой туннель — улика твёрдая: путь поднялся и не понёс трафик,
     * другого толкования нет. И цена ошибки другая: каждый круг немоты
     * стоит человеку восьми секунд сторожа плюс пауза переподключения,
     * то есть около двадцати, — вчетверо дороже проигранной гонки.
     */
    private const val MUTE_SUFFIX = "#muteStreak"

    /**
     * Немота ПРЯМОГО пути — отдельно от немоты потока (MUTE_SUFFIX) и от
     * проигрыша гонки (STREAK_SUFFIX).
     *
     * Разбор в MeridianVpnService: прямой UDP поднимается и работает, а
     * через 10-40 минут немеет — оператор переназначил NAT-адрес посреди
     * сессии, шлюз (различает клиентов по адресу-источнику) перестал
     * узнавать. До 12.09 этот случай не копился НИГДЕ: STREAK_SUFFIX
     * растёт только когда прямой не смог ПОДНЯТЬСЯ, а тут он поднимается
     * прекрасно и умирает потом. Нужен свой счётчик, как у потока.
     */
    private const val DIRECT_MUTE_SUFFIX = "#dirMuteStreak"

    /**
     * Ключ сети: транспорт плюс что-то, отличающее одну сеть от другой.
     *
     * Для мобильной — имя оператора (разрешений не требует), для Wi-Fi —
     * подсеть локального адреса.
     */
    fun networkKey(ctx: Context): String = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        if (net == null) {
            "нет-сети"
        } else {
            val caps = cm.getNetworkCapabilities(net)
            when {
                caps == null -> "неизвестно"

                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    val tm = ctx.getSystemService(TelephonyManager::class.java)
                    val op = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "оператор?"
                    "CELLULAR/$op"
                }

                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    val props = cm.getLinkProperties(net)
                    val subnet = props?.linkAddresses
                        ?.firstOrNull { it.address is Inet4Address }
                        ?.let { subnetOf(it.address.address, it.prefixLength) }
                        ?: "подсеть?"
                    "WIFI/$subnet"
                }

                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "иной"
            }
        }
    } catch (e: Throwable) {
        // Ключ сети — удобство, а не необходимость. Уронить им
        // подключение было бы обменом хорошего на плохое.
        "ключ-не-собран"
    }

    /** Запомненная ступень для этой сети, или null. */
    fun remembered(ctx: Context, key: String): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)

    fun remember(ctx: Context, key: String, candidate: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(key, candidate)
            .apply()
    }

    fun forget(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .remove(key + STREAK_SUFFIX)
            .apply()
    }

    /**
     * Забыть ТОЛЬКО запомненную ступень, счётчик побед релея и потока не
     * трогая: он и учит лестницу идти мимо UDP на такой сети, а forget()
     * обнулил бы его ровно тогда, когда он растёт.
     */
    fun forgetRung(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key)
            .apply()
    }

    /** Сколько раз подряд на этой сети победил релей, а не прямой. */
    fun relayStreak(ctx: Context, key: String): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key + STREAK_SUFFIX, 0)

    /** Ещё раз победил релей — счётчик вверх. */
    fun noteRelayWon(ctx: Context, key: String) {
        val n = relayStreak(ctx, key) + 1
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key + STREAK_SUFFIX, n).apply()
    }

    /**
     * Счётчик в ноль. Зовём, когда прямой ожил (победил в гонке) — и
     * когда на пропуске не встал даже релей: значит стратегия «сразу на
     * релей» не работает, в следующий раз пробуем всё заново.
     */
    fun clearRelayStreak(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key + STREAK_SUFFIX).apply()
    }

    /**
     * Пропускать ли прямой путь на этой сети СЕЙЧАС.
     *
     * true — прямой закрыт подряд достаточно раз И это не пробный заход.
     * Пробный заход (кратный PROBE_EVERY) прямой всё-таки пробует, чтобы
     * заметить ожившую сеть.
     */
    fun skipDirect(ctx: Context, key: String, skipAfter: Int, probeEvery: Int): Boolean {
        val n = relayStreak(ctx, key)
        return n >= skipAfter && n % probeEvery != 0
    }

    /** Сколько раз подряд поток на этой сети поднялся и онемел. */
    fun streamMuteStreak(ctx: Context, key: String): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key + MUTE_SUFFIX, 0)

    /** Ещё один немой поток — счётчик вверх. */
    fun noteStreamMute(ctx: Context, key: String) {
        val n = streamMuteStreak(ctx, key) + 1
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key + MUTE_SUFFIX, n).apply()
    }

    /**
     * Счётчик в ноль. Зовём, когда поток отработал и НЕ онемел, — и
     * когда на пропуске потока не встал даже релей: значит стратегия
     * «мимо потока» не работает, в следующий раз пробуем всё заново.
     */
    fun clearStreamMute(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key + MUTE_SUFFIX).apply()
    }

    /**
     * Пропускать ли потоковые ступени на этой сети СЕЙЧАС.
     *
     * Устроено ровно как skipDirect: порог плюс пробный заход раз в
     * probeEvery попыток, чтобы заметить сеть, где поток снова ожил.
     */
    fun skipStreams(ctx: Context, key: String, skipAfter: Int, probeEvery: Int): Boolean {
        val n = streamMuteStreak(ctx, key)
        return n >= skipAfter && n % probeEvery != 0
    }

    /** Пробный заход: пропуск потока включён, но сейчас щупаем поток. */
    fun probingStreams(ctx: Context, key: String, skipAfter: Int, probeEvery: Int): Boolean {
        val n = streamMuteStreak(ctx, key)
        return n >= skipAfter && n % probeEvery == 0
    }

    /** Пробный заход: пропуск уже включён, но сейчас щупаем прямой. */
    fun probingDirect(ctx: Context, key: String, skipAfter: Int, probeEvery: Int): Boolean {
        val n = relayStreak(ctx, key)
        return n >= skipAfter && n % probeEvery == 0
    }

    // --- НЕМОТА ПРЯМОГО ПУТИ (зеркало немоты потока) ---

    /** Сколько раз подряд прямой UDP на этой сети поднялся и онемел. */
    fun directMuteStreak(ctx: Context, key: String): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key + DIRECT_MUTE_SUFFIX, 0)

    /** Ещё один немой прямой — счётчик вверх. Переживает forget(). */
    fun noteDirectMute(ctx: Context, key: String) {
        val n = directMuteStreak(ctx, key) + 1
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(key + DIRECT_MUTE_SUFFIX, n).apply()
    }

    /**
     * Счётчик в ноль. Зовём, когда прямой отработал и НЕ онемел, — и
     * когда на пропуске прямого не встал даже релей: значит стратегия
     * «мимо прямого» не работает, в следующий раз пробуем всё заново.
     */
    fun clearDirectMute(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key + DIRECT_MUTE_SUFFIX).apply()
    }

    /**
     * Пропускать ли прямой из-за НЕМОТЫ на этой сети СЕЙЧАС.
     *
     * Устроено ровно как skipStreams: порог плюс пробный заход раз в
     * probeEvery попыток, чтобы заметить сеть, где прямой снова ожил.
     */
    fun skipDirectMute(ctx: Context, key: String, skipAfter: Int, probeEvery: Int): Boolean {
        val n = directMuteStreak(ctx, key)
        return n >= skipAfter && n % probeEvery != 0
    }

    // probingDirectMute убрана: проба немого прямого теперь на свежем
    // подключении (clearDirectMute в startTunnel), а не по модулю
    // счётчика — тот в пропуске не растёт, и модуль бы не сработал.

    /** "192.168.1.0/24" из адреса и длины префикса. */
    private fun subnetOf(addr: ByteArray, prefix: Int): String {
        if (addr.size != 4 || prefix !in 0..32) return "подсеть?"
        val masked = addr.copyOf()
        for (i in 0 until 4) {
            val bitsLeft = prefix - i * 8
            val mask = when {
                bitsLeft >= 8 -> 0xFF
                bitsLeft <= 0 -> 0x00
                else -> (0xFF shl (8 - bitsLeft)) and 0xFF
            }
            masked[i] = (masked[i].toInt() and mask).toByte()
        }
        val text = masked.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return "$text/$prefix"
    }
}
