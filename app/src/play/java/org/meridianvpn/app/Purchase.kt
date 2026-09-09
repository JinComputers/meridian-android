package org.meridianvpn.app

import android.app.Activity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.launch

/**
 * Предложение купить — ВАРИАНТ ДЛЯ GOOGLE PLAY.
 *
 * ЗДЕСЬ НЕТ И НЕ ДОЛЖНО ПОЯВИТЬСЯ НИ ОДНОГО УПОМИНАНИЯ ВНЕШНЕЙ ОПЛАТЫ.
 * Ни ссылки, ни названия мессенджера, ни намёка «купить можно в другом
 * месте»: политика Play запрещает и намёк, и за это снимают приложение,
 * а не просят исправить.
 *
 * Файл собирается только в вариант play. Одноимённый файл варианта
 * direct лежит в app/src/direct и в эту сборку не попадает вовсе.
 */
@Composable
fun PurchaseOffer(modifier: Modifier = Modifier, onNote: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<ProductDetails?>(null) }
    var busy by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }

    // Спрашиваем Play один раз при показе блока: что за товар, есть ли
    // он вообще и сколько стоит.
    LaunchedEffect(Unit) {
        if (checked) return@LaunchedEffect
        checked = true
        details = Billing.details(ctx)
    }

    // ТРИ ТАРИФА, ТРИ КНОПКИ. Товар в консоли один ("1"), а основных
    // планов у него три: "1", "6" и "12" месяцев.
    //
    // Раньше здесь бралось ПЕРВОЕ предложение из списка, молча. С одним
    // планом это работало, с тремя означало бы: человек видит одну цену
    // и не знает, что есть другие, а какая именно ему досталась —
    // решает порядок в ответе Play, то есть случай.
    //
    // Порядок в списке не переставляем и ни одного не прячем: цену и
    // срок называет Play, наше дело — показать всё, что он дал.
    val offers = details?.subscriptionOfferDetails.orEmpty()

    if (details == null) {
        Button(
            modifier = modifier.fillMaxWidth(),
            enabled = false,
            onClick = {},
        ) { Text("Подписка недоступна") }
    }

    for (offer in offers) {
        // Цена ПЕРВОЙ фазы: у обычного плана она одна, у плана со
        // вступительной ценой первая и есть та, которую заплатят
        // сейчас. Обещать вторую было бы обманом.
        val price = offer.pricingPhases.pricingPhaseList.firstOrNull()?.formattedPrice
        val term = termOf(offer.basePlanId)
        Button(
            modifier = modifier.fillMaxWidth(),
            // Пока Play не ответил — кнопки нет вовсе. Молчаливой
            // кнопки, которая ничего не делает, здесь быть не должно.
            enabled = !busy,
            onClick = {
                val d = details ?: return@Button
                val activity = ctx as? Activity ?: return@Button
                busy = true
                if (!Billing.launch(activity, d, offer.offerToken)) {
                    busy = false
                    onNote("не удалось открыть окно оплаты")
                } else {
                    // Результат придёт не сюда: покупку забирает
                    // restore() тем же путём, что и на новом телефоне.
                    busy = false
                }
            },
        ) {
            Text(if (price != null) "$term — $price" else term)
        }
    }

    if (details == null && checked) {
        Text(
            // Надпись подставляется КОНСТАНТОЙ, а не повторяется
            // строкой: текст и кнопка обязаны говорить одно и то же, а
            // два места с одним смыслом рано или поздно расходятся —
            // здесь они уже разошлись, когда кнопку переименовали.
            "Подписка сейчас недоступна. Если доступ у вас уже оплачен, " +
                "нажмите «$RECOVER_LABEL» — он восстановится сам.",
            color = Brand.dim,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Восстановление при ПЕРВОМ ПОКАЗЕ блока.
    //
    // Человек мог поставить приложение на новый телефон: покупка у
    // Google на месте, ключа у нас нет. Спрашиваем Google сами, ничего
    // не требуя от человека.
    LaunchedEffect(Unit) {
        scope.launch {
            val said = Billing.restore(ctx)
            if (said.isNotEmpty()) onNote(said)
        }
    }
}

/**
 * Где взять ключ — одной фразой, для уведомлений.
 *
 * ВАРИАНТ ДЛЯ PLAY: ни слова о том, что купить можно где-то ещё.
 * Отправляем человека в само приложение, и это правда — покупка там.
 */
fun purchaseHint(): String = "Откройте приложение, чтобы продлить доступ."

/**
 * «Уже есть ключ?» нажали — сначала спросим Google.
 *
 * Задача 62.3, второй случай: человек может не знать, что покупка у
 * него есть. Например, поменял телефон и просто ищет, куда ввести
 * ключ, — а вводить нечего, доступ уже оплачен.
 *
 * Возвращает строку для человека или пусто, если покупок нет и надо
 * показывать обычное поле ввода.
 */
suspend fun tryRestorePurchase(ctx: android.content.Context): String =
    Billing.restore(ctx)

/**
 * ВВОДА КЛЮЧА РУКАМИ В ЭТОЙ СБОРКЕ НЕТ.
 *
 * Доступ в Play бывает ровно двух видов: пробный период на трое суток
 * и подписка, оформленная через Play. Третьего не предусмотрено, и
 * поле ввода здесь только вводило в заблуждение.
 *
 * ПОЧЕМУ УБРАНО, А НЕ ОСТАВЛЕНО ПРО ЗАПАС. База ключей у нас общая с
 * прямой раздачей, и ключ из бота эта сборка принимала — а через трое
 * суток он отваливался, как пробный период. Человек при этом видел
 * «ключ принят» и считал, что заплатил не зря. Принять чужую валюту и
 * молча отобрать доступ хуже, чем не принимать её вовсе.
 *
 * Уже введённые ключи остаются рабочими: стирать их нельзя, иначе мы
 * отберём доступ у тех, кто ключ таки ввёл. Убран только СПОСОБ
 * ввести новый.
 *
 * Кнопка при этом остаётся и делает своё главное дело — спрашивает
 * Google, не оплачен ли доступ уже. Ради этого случая она и заводилась:
 * человек сменил телефон и ищет, куда ввести ключ, а вводить нечего.
 */
const val KEY_ENTRY = false

/** Надпись на кнопке: в Play восстанавливают покупку, а не ключ. */
const val RECOVER_LABEL = "Восстановить покупку"

/** Ответ, когда покупок у Google не нашлось. */
const val RECOVER_EMPTY = "Покупок не найдено. Оформить подписку можно кнопкой выше."

/**
 * Куда идти с отключённым ключом — одной фразой.
 *
 * ВАРИАНТ ДЛЯ PLAY: внешних контактов не называем. Ни бота, ни
 * Telegram, ни почты — в этой сборке их нет физически, и проверяется
 * это обычным grep по dex, как и для оплаты.
 *
 * Связь с разработчиком у Play своя, на странице приложения, и туда
 * человек попадает без наших подсказок.
 */
fun supportHint(): String = "Свяжитесь с разработчиком на странице приложения в Google Play."

/**
 * Название срока по идентификатору основного плана.
 *
 * Планы в консоли названы числом месяцев: "1", "6", "12". Показывать
 * человеку голое число нельзя — «1» ни о чём не говорит.
 *
 * НЕЗНАКОМЫЙ ПЛАН НЕ ПРЯЧЕМ. Появится в консоли четвёртый, а сборка о
 * нём не знает — покажем как есть, и человек хотя бы увидит цену.
 * Спрятать кнопку значило бы отнять у него товар, который мы же и
 * выставили на продажу.
 */
private fun termOf(basePlanId: String): String = when (basePlanId) {
    "1" -> "На месяц"
    "6" -> "На полгода"
    "12" -> "На год"
    else -> "Тариф $basePlanId"
}
