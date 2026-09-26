package org.meridianvpn.app

import android.content.Context

/**
 * Российские приложения (банки, госуслуги, маркетплейсы и т. п.), которые
 * идут МИМО туннеля по умолчанию (решение владельца 26.09).
 *
 * Многие банки проверяют сам факт VPN на телефоне, и приложение, выведенное
 * из туннеля, VPN не видит вовсе. Включено всегда, без переключателя и без
 * вопроса: это дефолт для новых клиентов (SplitTunnel.apply добавляет эти
 * приложения в исключения поверх списка человека).
 *
 * Прежний переключатель «RU-адреса напрямую» (маршруты по блокам RIPE) убран:
 * на телефоне он выгоды не давал, а мешал зарубежным сервисам. Документ
 * docs/ru-direct.md остаётся справкой для настольных клиентов и роутеров.
 */
object RuDirect {

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
}
