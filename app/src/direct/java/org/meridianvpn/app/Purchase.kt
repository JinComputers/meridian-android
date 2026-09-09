package org.meridianvpn.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * Предложение купить ключ — ВАРИАНТ ПРЯМОЙ РАЗДАЧИ.
 *
 * Этот файл собирается ТОЛЬКО в вариант direct. В сборке для Play его
 * нет вовсе — там лежит одноимённый файл из app/src/play, и ни ссылки
 * на бота, ни слова «Telegram» в её dex не попадает физически.
 *
 * Так сделано не из осторожности, а потому что иначе не проверить:
 * недостижимая ветка оставляет строки в сборке, и найти их можно
 * обычным grep по dex — а значит найдёт и ревью Play.
 */
@Composable
fun PurchaseOffer(modifier: Modifier = Modifier, onNote: (String) -> Unit) {
    val ctx = LocalContext.current
    Button(
        modifier = modifier.fillMaxWidth(),
        onClick = { onNote(openBot(ctx)) },
    ) { Text("Купить ключ") }
}

private const val BOT_URL = "https://t.me/jincomputers_bot"
private const val BOT_NAME = "jincomputers_bot"

/**
 * Открывает бота в Telegram, а не в браузере.
 *
 * Сначала tg://resolve — его перехватывает и сам Telegram, и его
 * сборки. Нет Telegram — падает ActivityNotFoundException, и тогда уже
 * обычная ссылка в браузер.
 *
 * Проверять наличие Telegram через queryIntentActivities не нужно:
 * попытка с перехватом исключения обходится без раздела <queries>.
 */
private fun openBot(ctx: Context): String {
    val direct = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$BOT_NAME"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(direct)
        return ""
    } catch (e: ActivityNotFoundException) {
        // Telegram не установлен — идём в браузер.
    } catch (e: Throwable) {
        return "открыть не вышло: ${e.message}"
    }
    return try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(BOT_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        ""
    } catch (e: Throwable) {
        "открыть не вышло, ссылка: $BOT_URL"
    }
}

/**
 * Где взять ключ — одной фразой, для уведомлений.
 *
 * Отдельно от кнопки, потому что уведомление о конце пробного периода
 * приходит, когда приложения на экране нет, и текст там свой.
 *
 * ВАРИАНТ ПРЯМОЙ РАЗДАЧИ: называем бота прямо.
 */
fun purchaseHint(): String = "Ключ — у бота в Telegram."

/**
 * «Уже есть ключ?» нажали.
 *
 * ВАРИАНТ ПРЯМОЙ РАЗДАЧИ: восстанавливать нечего. Покупок у Google
 * здесь нет и быть не может — библиотека биллинга в эту сборку не
 * входит вовсе.
 *
 * Функция существует ради одной подписи на оба варианта: общий экран
 * зовёт её, не зная, какая сборка.
 */
@Suppress("UNUSED_PARAMETER")
suspend fun tryRestorePurchase(ctx: android.content.Context): String = ""

/**
 * ВВОД КЛЮЧА РУКАМИ ЕСТЬ — это единственный способ получить доступ в
 * прямой раздаче. Ключ выдаёт бот, человек переносит его в приложение.
 *
 * Одноимённая пара в app/src/play говорит обратное, и разбор — там.
 */
const val KEY_ENTRY = true

/** Надпись на кнопке восстановления доступа. */
const val RECOVER_LABEL = "Уже есть ключ?"

/** Ответ, когда восстанавливать нечем. В прямой раздаче недостижим. */
const val RECOVER_EMPTY = ""

/**
 * Куда идти с отключённым ключом — одной фразой.
 *
 * Отдельно от purchaseHint(), потому что случаи разные: там «где
 * купить», здесь «с кем разбираться». Уместна эта фраза ровно в одном
 * случае — человек ввёл ключ РУКАМИ и знает, откуда он. Пробному
 * периоду и подписке поддержка не поможет, им нужен другой текст.
 *
 * ВАРИАНТ ПРЯМОЙ РАЗДАЧИ: тот же бот, что и выдал ключ.
 */
fun supportHint(): String = "Напишите боту в Telegram."
