package org.meridianvpn.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
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
        // «ТОЛЬКО РЕЛЕЙ» — для отладки скорости (просьба владельца 26.09):
        // лестница без прямых путей и потоков, туннель встаёт только на
        // релее. Применяется при следующем подключении.
        val relayOnly = TransportSetting.mode.value == Config.TransportMode.RELAY_ONLY
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Только релей", color = Brand.text, fontSize = 16.sp)
                Text(
                    "отладка скорости, применится при следующем подключении",
                    color = Brand.dim,
                    fontSize = 12.sp,
                )
            }
            GlassSwitch(
                checked = relayOnly,
                onCheckedChange = {
                    TransportSetting.set(
                        ctx,
                        if (it) Config.TransportMode.RELAY_ONLY else Config.TransportMode.AUTO,
                    )
                },
            )
        }

        // ОДНА СТУПЕНЬ ВМЕСТО ЛЕСТНИЦЫ — для проверки входов с котом 1 (03.10).
        // Без релея. Живёт до перезапуска приложения.
        var only by remember { mutableStateOf(onlyNodeId) }
        for (id in listOf("m3")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Только ступень $id", color = Brand.text, fontSize = 16.sp)
                    Text(
                        "проверка входа, без релея; сбросится при перезапуске",
                        color = Brand.dim,
                        fontSize = 12.sp,
                    )
                }
                GlassSwitch(
                    checked = only == id,
                    onCheckedChange = {
                        only = if (it) id else null
                        onlyNodeId = only
                        // Свежий список узлов: j1–j5 пришли в /v1/params 03.10.
                        if (it) Params.keyChanged()
                    },
                )
            }
        }

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
