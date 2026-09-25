package org.meridianvpn.app

import android.content.Context
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import java.io.DataInputStream
import java.net.InetAddress

/**
 * «RU-адреса напрямую» — российские адреса и приложения мимо VPN (просьба
 * владельца 25.09).
 *
 * ЗАЧЕМ. Банки, госуслуги, маркетплейсы и многие российские сайты не
 * работают или работают хуже с зарубежного адреса. Через туннель им
 * ходить незачем: VPN нужен для заблокированного, а не для своего.
 *
 * ДВА СЛОЯ, и оба нужны:
 *   1. АДРЕСА. Маршрут по умолчанию туннеля — это 0.0.0.0/0 МИНУС
 *      российские блоки (реестр RIPE, страна RU; assets/ru_ipv4.bin,
 *      генератор tools/ru_ipv4.py). Трафик ЛЮБОГО приложения к российскому
 *      адресу идёт мимо туннеля: браузер на ozon.ru, яндекс, госуслуги.
 *   2. ПРИЛОЖЕНИЯ. Российские приложения из списка RU_APPS целиком мимо
 *      туннеля (addDisallowedApplication). Слой нужен банкам: многие из них
 *      проверяют сам факт VPN на телефоне, и одних маршрутов им мало —
 *      приложение, выведенное из туннеля, VPN не видит вовсе.
 *
 * ПО ДОМЕНАМ Android маршрутизировать не умеет: у VpnService только
 * адреса и приложения. Сайт российской компании на зарубежном CDN пойдёт
 * через туннель. Список доменов для настольных клиентов и роутеров — в
 * docs/ru-direct.md.
 *
 * ВЕРСИИ ANDROID.
 *   13+ (API 33): excludeRoute — 0.0.0.0/0 и тысячи исключений, все блоки
 *        из файла (/22 и крупнее, ~98 % российских адресов).
 *   8–12: исключений нет, маршруты строятся ДОПОЛНЕНИЕМ до RU. Чтобы их
 *        не вышло десять тысяч, берём только блоки /18 и крупнее (~66 %
 *        российских адресов, ~2100 маршрутов).
 * Если система не примет маршруты разом — MeridianVpnService собирает
 * туннель без них и говорит об этом (строка «не применились»).
 */
object RuDirect {

    private const val PREFS = "meridian_rudirect"
    private const val K_ON = "on"
    private const val ASSET = "ru_ipv4.bin"

    /** Порог длины префикса для Android 8–12, см. заголовок. */
    private const val LEGACY_MAX_PREFIX = 18

    /** Для экрана настроек. */
    val on = mutableStateOf(false)

    private var appCtx: Context? = null

    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext
        on.value = try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ON, false)
        } catch (e: Throwable) {
            false
        }
    }

    fun enabled(): Boolean = on.value

    fun setEnabled(v: Boolean) {
        on.value = v
        val ctx = appCtx ?: return
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ON, v).apply()
        } catch (e: Throwable) {
        }
        TunnelLog.add("RU-адреса напрямую: " + if (v) "включено" else "выключено")
    }

    /**
     * Российские приложения, которые целиком идут мимо туннеля.
     *
     * Имена пакетов сверены по живым страницам Google Play / RuStore (сбор
     * 25.09, каждое имя видено в адресе страницы магазина). ru.mts.bank —
     * старое имя МТС Банка, у давно установивших. Ошибка в имени безвредна:
     * такого пакета просто не найдётся. Список общий с настольными
     * клиентами и роутерами — docs/ru-direct.md.
     */
    val RU_APPS: List<String> = listOf(
        // Банки, инвестиции, платежи
        "ru.sberbankmobile",
        "ru.sberbank.spasibo",
        "ru.sberbank.sberkids",
        "com.idamob.tinkoff.android",
        "ru.tinkoff.investing",
        "ru.tinkoff.sme",
        "ru.vtb24.mobilebanking.android",
        "ru.vtb.invest",
        "ru.alfabank.mobile.android",
        "ru.alfadirect.app",
        "ru.gazprombank.android.mobilebank.app",
        "ru.raiffeisennews",
        "logo.com.mbanking",
        "ru.sovcomcard.halva.v1",
        "ru.sovcombank.investor",
        "ru.lewis.dbo",
        "ru.mts.bank",
        "ru.mts.pay",
        "ru.letobank.Prometheus",
        "ru.rshb.dbo",
        "com.openbank",
        "ru.bspb",
        "ru.bankuralsib.mb.android",
        "ru.akbars.mobile",
        "ru.ozon.fintech.finance",
        "com.yandex.bank",
        "ru.nspk.mirpay",
        "ru.nspk.sbpay",
        // Госуслуги и официальное
        "ru.rostel",
        "ru.gosuslugi.goskey",
        "ru.gosuslugi.auto",
        "ru.sigma.gisgkh",
        "ru.gosuslugi.culture",
        "ru.mos.app",
        "ru.altarix.mos.pgu",
        "ru.fns.lkfl",
        "com.octopod.russianpost.client.android",
        "ru.rzd.pass",
        // Маркетплейсы и магазины
        "com.wildberries.ru",
        "ru.ozon.app.android",
        "ru.beru.android",
        "ru.megamarket.marketplace",
        "com.avito.android",
        "com.lamoda.lite",
        "ru.dns.shop.android",
        "ru.filit.mvideo.b2c",
        "ru.mvm.eldo",
        "ru.sbcs.store",
        "ru.vkusvill",
        "ru.perekrestok.app",
        "ru.pyaterochka.app.browser",
        "ru.magnit.express.android",
        "com.kazanexpress.ke_app",
        "ru.instamart",
        "goldapple.ru.goldapple.customers",
        "ru.detmir.dmbonus",
        "com.icemobile.lenta.prod",
        "ru.lenta.lentochka",
        "com.deliveryclub",
        // Яндекс
        "com.yandex.searchapp",
        "ru.yandex.searchplugin",
        "com.yandex.aliceapp",
        "com.yandex.browser",
        "ru.yandex.taxi",
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexnavi",
        "ru.yandex.music",
        "ru.kinopoisk",
        "ru.yandex.mail",
        "ru.foodfox.client",
        "com.yandex.lavka",
        "ru.yandex.disk",
        // Связь
        "ru.mts.mymts",
        "ru.megafon.mlk",
        "ru.beeline.services",
        "ru.tele2.mytele2",
        "com.dartit.RTcabinet",
        "ru.onlime.my.app",
        "ru.rt.smarthome",
        // Соцсети, видео, сервисы
        "com.vkontakte.android",
        "com.vk.im",
        "com.vk.vkvideo",
        "ru.ok.android",
        "ru.mail.mailapp",
        "ru.oneme.app",
        "ru.zen.android",
        "ru.dublgis.dgismobile",
        "ru.rutube.app",
        "ru.ivi.client",
        "ru.more.play",
        "ru.rt.video.app.mobile",
        "gpm.tnt_premier",
        "ru.hh.android",
        "ru.auto.ara",
        "ru.cian.main",
    )

    /** Установленные из RU_APPS. */
    fun installedApps(ctx: Context): List<String> =
        RU_APPS.filter { SplitTunnel.installed(ctx, it) }

    /**
     * Маршрут по умолчанию туннеля: 0.0.0.0/0, а при ru — минус российские
     * блоки (способ — по версии Android, см. заголовок).
     */
    fun addRoutes(builder: VpnService.Builder, ctx: Context, ru: Boolean) {
        if (!ru) {
            builder.addRoute("0.0.0.0", 0)
            return
        }
        val blocks = load(ctx)
        if (blocks.isEmpty()) {
            TunnelLog.add("RU-адреса напрямую: список адресов не прочитан — весь IPv4 через туннель")
            builder.addRoute("0.0.0.0", 0)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            builder.addRoute("0.0.0.0", 0)
            for (b in blocks) builder.excludeRoute(IpPrefix(toInet(b.addr), b.len))
            TunnelLog.add("RU-адреса напрямую: исключено российских блоков ${blocks.size}")
        } else {
            val legacy = blocks.filter { it.len <= LEGACY_MAX_PREFIX }
            val routes = complement(legacy)
            for (r in routes) builder.addRoute(toInet(r.addr), r.len)
            TunnelLog.add(
                "RU-адреса напрямую (Android до 13): российских блоков ${legacy.size}, " +
                    "маршрутов через туннель ${routes.size}"
            )
        }
    }

    private class Block(val addr: Long, val len: Int)

    @Volatile
    private var cache: List<Block>? = null

    private fun load(ctx: Context): List<Block> {
        cache?.let { return it }
        val out = ArrayList<Block>()
        try {
            DataInputStream(ctx.assets.open(ASSET).buffered()).use { inp ->
                val magic = ByteArray(4).also { inp.readFully(it) }
                if (!(magic[0] == 'R'.code.toByte() && magic[1] == 'U'.code.toByte() &&
                        magic[2] == '4'.code.toByte() && magic[3] == 1.toByte())
                ) return emptyList()
                val date = ByteArray(8).also { inp.readFully(it) }
                val n = inp.readInt()
                repeat(n) {
                    val a = inp.readInt().toLong() and 0xffffffffL
                    val l = inp.readUnsignedByte()
                    out.add(Block(a, l))
                }
                TunnelLog.add("RU-адреса: реестр RIPE от ${String(date, Charsets.US_ASCII)}, блоков $n")
            }
        } catch (e: Throwable) {
            TunnelLog.add("RU-адреса: файл списка не прочитан (${e.message})")
            return emptyList()
        }
        cache = out
        return out
    }

    /** Дополнение до набора блоков в пространстве IPv4, минимальными префиксами. */
    private fun complement(blocks: List<Block>): List<Block> {
        val out = ArrayList<Block>()
        var cur = 0L
        for (b in blocks.sortedBy { it.addr }) {
            val start = b.addr
            val end = b.addr + (1L shl (32 - b.len)) - 1
            if (start > cur) cover(cur, start - 1, out)
            if (end + 1 > cur) cur = end + 1
        }
        if (cur <= 0xffffffffL) cover(cur, 0xffffffffL, out)
        return out
    }

    /** Покрыть [from, to] выровненными префиксами. */
    private fun cover(from: Long, to: Long, out: MutableList<Block>) {
        var a = from
        while (a <= to) {
            var len = 32
            // Наибольший выровненный блок, начинающийся в a и не выходящий за to.
            while (len > 0) {
                val size = 1L shl (32 - (len - 1))
                if (a % size != 0L || a + size - 1 > to) break
                len--
            }
            out.add(Block(a, len))
            a += 1L shl (32 - len)
        }
    }

    private fun toInet(a: Long): InetAddress = InetAddress.getByAddress(
        byteArrayOf((a shr 24).toByte(), (a shr 16).toByte(), (a shr 8).toByte(), a.toByte())
    )
}
