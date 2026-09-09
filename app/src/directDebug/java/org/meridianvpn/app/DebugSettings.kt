package org.meridianvpn.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Отладочные настройки. ТОЛЬКО В СБОРКЕ directDebug.
 *
 * Набор исходников выбран не случайно. Нужна связка «прямая раздача И
 * отладка»: код обновления живёт только в direct, а токен нужен только
 * в отладке. Проверкой BuildConfig.DEBUG это не решается — она оставила
 * бы строки в релизном dex, а мы это уже проходили с ссылками VK.
 *
 * ЗДЕСЬ ВВОДИТСЯ ТОКЕН ЗАКРЫТОГО РЕПОЗИТОРИЯ. В сборке его нет: он
 * ложится в закрытое хранилище приложения и переживает установку
 * поверх. Отзывается одной кнопкой на GitHub.
 *
 * Токен не показывается на экране после сохранения и не пишется в лог —
 * то же правило, что и для пароля подключения.
 */
@Composable
fun DebugRows() {
    val ctx = LocalContext.current
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(Update.hasToken(ctx)) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ПОЛЕ ПРОПАДАЕТ, КОГДА ТОКЕН СОХРАНЁН.
        //
        // Раньше оно оставалось на экране, и это сбивало: непонятно,
        // сохранилось ли, и не надо ли вставить ещё раз. Поле для
        // ввода того, что уже введено, — это вопрос без ответа.
        //
        // Заменить токен можно: «стереть» возвращает поле обратно.
        if (saved) {
            Text("токен GitHub сохранён", color = Brand.dim, fontSize = 12.sp)
            TextButton(onClick = {
                Update.setToken(ctx, "")
                saved = false
                value = ""
            }) { Text("стереть токен") }
        } else {
            Text("токен GitHub не задан", color = Brand.dim, fontSize = 12.sp)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("Токен для отладочных обновлений") },
            )
            TextButton(
                enabled = value.isNotBlank(),
                onClick = {
                    Update.setToken(ctx, value)
                    saved = Update.hasToken(ctx)
                    value = ""
                },
            ) { Text("сохранить токен") }
        }
    }
}
