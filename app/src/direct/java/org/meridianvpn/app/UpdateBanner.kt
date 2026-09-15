package org.meridianvpn.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Уведомление о новой версии на главном экране — ПРЯМАЯ РАЗДАЧА.
 *
 * ЭТО ОТМЕНА ПРЕЖНЕГО РЕШЕНИЯ, и об этом надо сказать прямо, а не
 * сделать вид, что так было всегда. В UpdateRow стояло: «Ни уведомлений,
 * ни баннеров, ни всплывающих окон: про новые версии люди узнают из
 * канала, а здесь — если сами посмотрят». Владелец 14.09 решил иначе, и
 * довод у него сильный: канал читают не все, а строку версии внизу
 * настроек не открывает почти никто. Человек оставался на старой сборке,
 * не зная, что вышла новая, — и ловил починенные баги.
 *
 * ЧЕГО ЗДЕСЬ НЕТ НАМЕРЕННО:
 *
 * Всплывающего окна. Оно перехватывает нажатие и требует ответа прямо
 * сейчас — а человек открыл приложение, чтобы включить туннель, а не
 * чтобы обсуждать обновления. Полоса под блоком доступа видна, но ничего
 * не перехватывает.
 *
 * Автоматической установки. Скачивание на мобильной сети и подмена
 * работающего приложения без спроса — не то, что делают молча.
 *
 * В СБОРКЕ ДЛЯ PLAY ЭТОГО ФАЙЛА НЕТ ВОВСЕ — не флаг, а отсутствие кода,
 * как и с покупкой (см. соседний UpdateRow.kt и app/src/play). Там
 * обновлениями занимается магазин, и своё уведомление рядом с его
 * уведомлением было бы вторым голосом о том же.
 */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current

    // ТИХАЯ ПРОВЕРКА ПРИ ОТКРЫТИИ ГЛАВНОГО, а не только настроек.
    //
    // Без неё уведомление ждало бы двух редких событий: захода в
    // настройки или очередной проверки ключа. Настройки открывают раз в
    // месяц, ключ проверяется раз в сутки — то есть человек узнавал бы
    // о версии позже всех.
    //
    // Лишней нагрузки это не даёт: внутри те же ворота «не чаще раза в
    // сутки», общие с настройками, и своя нить. Молчит при неудаче.
    LaunchedEffect(Unit) { Update.quietCheck(ctx) }

    if (!Update.bannerVisible()) return

    val stage = Update.stage.value
    val version = Update.newVersion.value

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when (stage) {
                Update.Stage.DOWNLOADING -> {
                    val got = Update.gotBytes.value / (1024 * 1024)
                    val all = Update.totalBytes.value / (1024 * 1024)
                    "Качаю $version · $got из $all МБ"
                }

                Update.Stage.READY -> "Версия $version скачана"
                else -> "Вышла версия $version"
            },
            color = Brand.edge,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )

        // ВО ВРЕМЯ СКАЧИВАНИЯ КНОПОК НЕТ. Нажимать нечего: «обновить»
        // уже нажато, а «позже» отняло бы у человека единственное место,
        // где видно, сколько осталось.
        if (stage != Update.Stage.DOWNLOADING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // Тот же порядок, что по нажатию на строку
                        // версии в настройках: сначала скачать, потом
                        // поставить. Второго вопроса «точно качать?» нет
                        // — человек уже нажал.
                        if (stage == Update.Stage.READY) Update.install(ctx)
                        else Update.download(ctx)
                    },
                ) {
                    Text(if (stage == Update.Stage.READY) "Установить" else "Обновить")
                }
                // «Позже» есть ТОЛЬКО пока не скачано. После скачивания
                // прятать нечего: файл уже лежит, и не поставить его —
                // худший из исходов.
                if (stage == Update.Stage.AVAILABLE) {
                    TextButton(
                        modifier = Modifier.weight(1f),
                        onClick = { Update.dismissBanner() },
                    ) { Text("Позже") }
                }
            }
        }
    }
}
