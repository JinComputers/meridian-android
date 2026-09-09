package org.meridianvpn.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Подписка через Google Play. ТОЛЬКО В ВАРИАНТЕ PLAY.
 *
 * Файл собирается лишь в play: в direct-сборке нет ни этого кода, ни
 * самой библиотеки — она подключена через playImplementation. Это то же
 * правило, что и с Purchase.kt: не флаг в коде, а отсутствие кода.
 *
 * ЦЕПОЧКА ЦЕЛИКОМ:
 *
 *   человек платит  → Google выдаёт токен покупки
 *   токен           → наш API
 *   API             → ключ доступа
 *   ключ            → Access.acceptKey(), дальше как обычный ключ
 *
 * ПОЧЕМУ КЛЮЧ, А НЕ ПРЯМОЕ РАЗРЕШЕНИЕ. Туннель поднимается по паролю,
 * который проверяет шлюз. Токен покупки шлюзу ничего не говорит, и
 * учить его проверять покупки Google значило бы тащить в шлюз чужой
 * протокол. Обмен токена на ключ делает сервер — один раз и в одном
 * месте.
 *
 * ВАЖНОЕ СВОЙСТВО ЭТОГО КЛЮЧА: человек его НЕ ВИДИТ и переввести не
 * сможет. Отсюда два следствия, каждое проверяется отдельно:
 *   ключ обязан переживать переустановку — он в копируемом файле
 *   настроек (res/xml/backup_rules.xml);
 *   восстановление на новом телефоне идёт БЕЗ участия человека — см.
 *   restore().
 */
object Billing {

    /**
     * Идентификатор подписки в Play Console.
     *
     * ПОДТВЕРЖДЁН ВЛАДЕЛЬЦЕМ 27.08 по консоли: товар один, называется
     * "1", и у него ТРИ основных тарифных плана — "1", "6" и "12", по
     * числу месяцев. Все три активны.
     *
     * Имя товара выглядит странно, но менять его нельзя: идентификатор
     * товара в Play неизменяем после создания, а покупки привязаны
     * именно к нему.
     */
    private const val SUBSCRIPTION_ID = "1"

    /** Состояние моста, для экрана. */
    enum class State {
        /** Ещё не связывались с Play. */
        IDLE,

        /** Идёт разговор с Play или с нашим сервером. */
        BUSY,

        /** Play на этом устройстве недоступен: нет сервисов, старая версия. */
        UNAVAILABLE,

        /** Готовы показать окно оплаты. */
        READY,
    }

    @Volatile
    private var client: BillingClient? = null

    /**
     * Соединение с Play.
     *
     * BillingClient требует явного подключения и умеет разрываться сам,
     * поэтому проверяем состояние на каждом обращении, а не заводим
     * один раз при старте.
     */
    private suspend fun connected(ctx: Context): BillingClient? {
        val existing = client
        if (existing != null && existing.isReady) return existing

        val c = BillingClient.newBuilder(ctx.applicationContext)
            // С версии 8 бесаргументный enablePendingPurchases() убран:
            // библиотека требует прямо назвать, для каких товаров
            // отложенные покупки разрешены.
            //
            // У нас подписка, и одноразовых товаров нет вовсе — но
            // включаем именно этот набор, потому что метод обязателен, а
            // пустой набор библиотека не примет. Появятся одноразовые —
            // строка уже готова.
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .setListener { _, _ ->
                // Покупки забираем сами через queryPurchasesAsync: так
                // один и тот же путь работает и для новой покупки, и
                // для восстановления на новом телефоне. Два разных пути
                // разошлись бы при первой же правке.
            }
            .build()
        client = c

        return suspendCancellableCoroutine { cont ->
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) {
                    if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                        cont.resume(c)
                    } else {
                        TunnelLog.add("Play: соединение не установлено, код ${r.responseCode}")
                        cont.resume(null)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    // Не возобновляем здесь: onBillingSetupFinished уже
                    // вызывался или вызовется. Двойное возобновление
                    // корутины — падение.
                }
            })
        }
    }

    /** Есть ли что показывать в окне оплаты. */
    suspend fun details(ctx: Context): ProductDetails? {
        val c = connected(ctx) ?: return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()
        val result = c.queryProductDetails(params)
        val list = result.productDetailsList
        if (list.isNullOrEmpty()) {
            TunnelLog.add("Play: товар $SUBSCRIPTION_ID не найден в консоли")
            return null
        }
        return list.first()
    }

    /**
     * Показывает окно оплаты.
     *
     * Результат сюда не возвращается: покупка приезжает асинхронно, и
     * забирать её надо тем же restore(), что и на новом телефоне.
     */
    fun launch(activity: Activity, details: ProductDetails, offerToken: String): Boolean {
        val c = client ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            )
            .build()
        val r = c.launchBillingFlow(activity, params)
        return r.responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * Забирает покупки у Google и меняет токен на ключ.
     *
     * ОДИН ПУТЬ НА ДВА СЛУЧАЯ — и новая покупка, и восстановление на
     * новом телефоне идут сюда же. Google отдаёт ТОТ ЖЕ токен, наш
     * сервер по идемпотентности отдаёт ТОТ ЖЕ ключ, и человек не вводит
     * ничего.
     *
     * Зовётся при первом запуске и по нажатию кнопки восстановления: второе —
     * на случай, когда человек не знает, что покупка у него есть.
     *
     * Возвращает строку для человека. Пусто — говорить нечего.
     */
    suspend fun restore(ctx: Context): String {
        val c = connected(ctx) ?: return ""
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val result = c.queryPurchasesAsync(params)
        val alive = result.purchasesList.firstOrNull {
            it.purchaseState == Purchase.PurchaseState.PURCHASED
        } ?: return ""

        // ПОДТВЕРЖДЕНИЕ ПОКУПКИ ДЕЛАЕТ СЕРВЕР, не мы.
        //
        // Здесь был acknowledgePurchase — убран по слову кота 2. Решение
        // разумное: сервер и так ходит в Play Developer API, чтобы
        // проверить токен, и подтвердить оттуда надёжнее, чем с
        // телефона, который могут выключить между оплатой и запуском.
        //
        // ЦЕНА ОШИБКИ ЗДЕСЬ ВЫСОКАЯ, и её надо назвать: неподтверждённую
        // покупку Google отменяет через ТРОЕ СУТОК и возвращает деньги,
        // а человек всё это время считает, что заплатил. Раз клиент
        // больше не подтверждает, это стало обязанностью сервера
        // целиком.
        return exchange(alive.purchaseToken)
    }

    /**
     * Меняет токен покупки на ключ доступа.
     *
     *   POST /v1/purchase?token=<purchaseToken>
     *
     * ФОРМА СВЕРЕНА С СЕРВЕРОМ, не угадана: кот 2 читает `token`
     * (`purchase_token` принимает как псевдоним), и код шлёт именно
     * `token`. Прежний путь /v1/play был угадан неверно, правильный —
     * /v1/purchase.
     *
     * DEVICE_ID ЗДЕСЬ НЕ ШЛЁТСЯ, и это не упущение (слово кота 2):
     *   - от повторной выдачи защищает сам токен покупки, он уникален;
     *     отпечаток устройства, что нужен пробному, покупке не нужен;
     *   - платный ключ привязывается к устройству САМ, при подключении,
     *     а не в момент покупки. Привязать при покупке значило бы
     *     прибить оплатившего к телефону, с которого он платил: сменил
     *     телефон — остался без доступа, хотя заплатил;
     *   - лишний параметр, который сервер игнорирует, — будущее
     *     расхождение: однажды решат, что раз шлётся, значит на что-то
     *     влияет. Прежний комментарий писал форму с `device_id`, и это
     *     вводило в заблуждение обе стороны — здесь оно исправлено.
     *
     * Токена авторизации у ручки нет. Параметры только в строке
     * запроса: тела служба не читает ни у одной ручки.
     *
     * ЧЕТЫРЕ ОСОБЕННОСТИ, каждая проверяется отдельно:
     *
     *   key       — ключ доступа, как у /v1/trial;
     *   renewed   — срок сдвинулся или ключ ожил. ЕДИНСТВЕННЫЙ признак,
     *               по которому понятно, что показанную дату пора
     *               обновить;
     *   rejected  — вердикт, который бывает ТОЛЬКО у этой ручки:
     *               покупка есть, но доступа по ней не будет;
     *   expires_at нулём здесь НЕ БЫВАЕТ. Ноль означал бы бессрочный
     *               ключ за месячную оплату, и сервер такой вызов
     *               отвергает сам. Если ноль всё-таки придёт — это
     *               ошибка на его стороне, и мы её называем, а не
     *               молча принимаем.
     *
     * При status: unavailable полей о ключе нет вообще — этот случай
     * разбирает ApiClient, сюда он приходит как Unavailable.
     *
     * Молчать в этом месте нельзя: без внятной строки человек, который
     * заплатил и не получил доступ, решит, что его обманули.
     */
    private suspend fun exchange(token: String): String {
        TunnelLog.event("подписка оплачена, запрашиваю доступ")
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ApiClient.call("/v1/purchase", mapOf("token" to token), post = true)
        }
        return when (r) {
            is ApiClient.Result.Ok -> handleGrant(r.body)

            is ApiClient.Result.Refused -> {
                TunnelLog.event("подписка: сервер отказал")
                r.error
            }

            // МОЛЧАНИЕ НЕ ОТБИРАЕТ ОПЛАЧЕННОЕ. Попробуем ещё раз при
            // следующем запуске: покупка у Google никуда не денется.
            is ApiClient.Result.Incompatible,
            is ApiClient.Result.Unavailable -> {
                TunnelLog.event("подписка есть, доступ пока не получен — попробую позже")
                "сервер недоступен, попробую позже"
            }
        }
    }

    /** Разбор удачного ответа /v1/purchase. */
    private fun handleGrant(body: org.json.JSONObject): String {
        // Вердикт rejected бывает только здесь. Признак его — само
        // слово, а не отсутствие ключа: отказ и пустой ключ надо
        // различать, иначе непонятая форма ответа выглядела бы отказом.
        if (body.optString("verdict") == "rejected") {
            val why = body.optString("message").ifEmpty { "покупка отклонена" }
            TunnelLog.event("подписка отклонена сервером")
            return why
        }

        val key = body.optString("key")
        if (key.isEmpty()) {
            TunnelLog.add("Play: ключа в ответе нет. Ответ: $body")
            TunnelLog.event("оплата принята, но доступ не выдан — напишите в поддержку")
            return "оплата принята, но доступ не выдан"
        }

        val until = Api.readExpiresPublic(body)
        if (until <= 0L) {
            // По договору такого не бывает: ноль означал бы бессрочный
            // ключ за месячную оплату. Не молчим — но и не отказываем:
            // ключ рабочий, а срок покажем как неизвестный.
            TunnelLog.add("Play: срок подписки $until, а по договору он всегда положительный")
        }

        val renewed = body.optBoolean("renewed", false)

        // ЕСЛИ НИЧЕГО НЕ ИЗМЕНИЛОСЬ — МОЛЧИМ.
        //
        // restore() зовётся при каждом показе экрана доступа, и без этой
        // проверки человек видел бы «Подписка активна» каждый раз, когда
        // просто открыл приложение.
        //
        // renewed именно для этого и заведён: он единственный говорит,
        // что дата сдвинулась.
        if (!renewed && Access.sameKey(key)) return ""

        Access.acceptKey(key, until, source = Access.SOURCE_SUB)
        TunnelLog.event(if (renewed) "подписка продлена" else "доступ по подписке получен")
        return ""
    }
}

