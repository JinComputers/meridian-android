package org.meridianvpn.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Строка версии — ВАРИАНТ ДЛЯ PLAY, обычный текст.
 *
 * Обновлениями здесь занимается магазин, и трогать это нельзя:
 * самообновление мимо него — прямое нарушение правил. Поэтому в этой
 * сборке нет ни кода проверки, ни разрешения REQUEST_INSTALL_PACKAGES,
 * ни единого упоминания GitHub — их нет ФИЗИЧЕСКИ, а не спрятано за
 * недостижимой веткой. Недостижимая ветка оставила бы строки в dex, и
 * найти их можно обычным grep — значит найдёт и ревью.
 *
 * Функция существует ради одной подписи на оба варианта: общий экран
 * зовёт её, не зная, какая сборка. Так же сделано с покупкой.
 */
@Composable
fun UpdateRow(modifier: Modifier) {
    val ctx = LocalContext.current
    Text(
        appVersion(ctx),
        color = Brand.dim,
        fontSize = 12.sp,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/**
 * Подсказка о свежей версии из ответа службы — ВАРИАНТ ДЛЯ PLAY.
 *
 * Не делает ничего, и это правильно: обновлениями здесь занимается
 * магазин, а показать подсказку было бы прямым подталкиванием мимо
 * него. Служба, если приложение назовёт себя как dist=play, полей и не
 * пришлёт вовсе — но полагаться на это нельзя: параметр необязательный,
 * а сборка может оказаться старее службы.
 */
@Suppress("UNUSED_PARAMETER")
fun updateHint(code: Long, name: String) = Unit

/**
 * Подключить обновление к контексту — ВАРИАНТ ДЛЯ PLAY.
 *
 * Не делает ничего: обновлениями здесь занимается магазин.
 */
@Suppress("UNUSED_PARAMETER")
fun updateAttach(ctx: android.content.Context) = Unit

/**
 * Отладочных настроек в сборке для Play нет — ни в отладке, ни в
 * релизе. Токен нужен только для отладочных обновлений, а их здесь не
 * бывает: обновлениями занимается магазин.
 */
@Composable
fun DebugRows() = Unit
