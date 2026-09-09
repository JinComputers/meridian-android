package org.meridianvpn.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Строка версии — ВАРИАНТ ПРЯМОЙ РАЗДАЧИ, нажимаемая.
 *
 * Это единственное место во всём приложении, где обновление вообще
 * упоминается. Ни уведомлений, ни баннеров, ни всплывающих окон: про
 * новые версии люди узнают из канала, а здесь — если сами посмотрят.
 *
 * Нажатие делает следующий по смыслу шаг, а не одно и то же:
 * проверить → скачать → установить. Отдельных кнопок нет намеренно,
 * иначе в углу настроек заводится пульт управления из трёх штук.
 */
@Composable
fun UpdateRow(modifier: Modifier) {
    val ctx = LocalContext.current

    // Тихая проверка при открытии настроек, не чаще раза в сутки.
    // Молчит при неудаче: человек зашёл сюда не за этим.
    LaunchedEffect(Unit) { Update.quietCheck(ctx) }

    val stage = Update.stage.value
    val fresh = stage == Update.Stage.AVAILABLE ||
        stage == Update.Stage.DOWNLOADING ||
        stage == Update.Stage.READY

    Text(
        text = line(ctx, stage),
        color = if (fresh) Brand.edge else Brand.dim,
        fontSize = 12.sp,
        fontWeight = if (fresh) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier
            .fillMaxWidth()
            .clickable { tap(ctx, stage) }
            .padding(vertical = 4.dp),
        textAlign = TextAlign.Center,
    )
}

private fun line(ctx: android.content.Context, stage: Update.Stage): String {
    val me = appVersion(ctx)
    return when (stage) {
        Update.Stage.IDLE -> me
        Update.Stage.CHECKING -> "$me · проверяю…"
        Update.Stage.LATEST -> "$me · это последняя"
        Update.Stage.AVAILABLE -> "$me → ${Update.newVersion.value} · обновить"
        Update.Stage.DOWNLOADING -> {
            val got = Update.gotBytes.value / (1024 * 1024)
            val all = Update.totalBytes.value / (1024 * 1024)
            "качаю ${Update.newVersion.value} · $got из $all МБ"
        }

        Update.Stage.READY -> "${Update.newVersion.value} готова · установить"
        Update.Stage.FAILED -> Update.problem.value.ifEmpty { "проверить не удалось" }
    }
}

private fun tap(ctx: android.content.Context, stage: Update.Stage) {
    when (stage) {
        // Качаем сразу, и на мобильной сети тоже: человек уже нажал,
        // второй вопрос — это ещё одно нажатие ни за чем.
        Update.Stage.AVAILABLE -> Update.download(ctx)
        Update.Stage.READY -> Update.install(ctx)
        Update.Stage.DOWNLOADING -> Unit // идёт, ждём
        else -> Update.checkNow(ctx)
    }
}

/**
 * Подсказка о свежей версии из ответа службы — ПРЯМАЯ РАЗДАЧА.
 *
 * Приходит бесплатно, вместе с проверкой ключа, по уже пинованному
 * каналу. Тем и ценна: GitHub в России доступен неровно, и без этой
 * подсказки человек мог не узнать про обновление вовсе.
 *
 * Она говорит только ЧТО обновление есть. Откуда его брать — по-прежнему
 * знает только описание релиза на GitHub, и за ним мы пойдём, когда
 * человек нажмёт.
 */
fun updateHint(code: Long, name: String) = Update.hint(code, name)

/**
 * Подключить обновление к контексту — ПРЯМАЯ РАЗДАЧА.
 *
 * Зовётся оттуда же, откуда attach остальных: при создании службы и
 * экрана. Раньше контекст ставился только при открытии настроек, и
 * подсказка о версии до первого захода туда пропадала молча.
 */
fun updateAttach(ctx: android.content.Context) = Update.attach(ctx)
