package org.meridianvpn.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import engine.Engine
import engine.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Экран ссылок на звонки VK.
 *
 * Ссылки создаёт сам человек и добавляет их сюда: в одно-кнопочном
 * приложении другого способа их получить нет.
 *
 * ОДНА КНОПКА НА ДВА ДЕЙСТВИЯ. В поле есть ссылка — добавляем. Поле
 * пустое — проверяем все по очереди. Два действия не спорят между
 * собой: добавить нечего, когда поле пусто, а проверять всё, когда
 * человек только что вписал ссылку, он не просил.
 */
@Composable
fun HashScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onClick = onBack)
            Text("Обход белых списков", color = Brand.text, fontSize = 20.sp)
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Ссылка на звонок") },
            placeholder = { Text("https://vk.com/call/join/…") },
        )

        // ПОДПИСЬ ЗДЕСЬ НУЖНА, и это осознанное исключение из правила
        // 51.6. Кнопка делает РАЗНОЕ в зависимости от того, пусто ли
        // поле; никакой значок не может означать «добавить или, если
        // добавлять нечего, проверить все» — а цена ошибки высокая:
        // проверка это настоящий расход ссылок.
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && (input.isNotBlank() || HashStore.items.isNotEmpty()),
            onClick = {
                if (input.isNotBlank()) {
                    note = HashStore.add(input)
                    input = ""
                } else {
                    busy = true
                    scope.launch {
                        checkAll(context) { note = it }
                        busy = false
                    }
                }
            }
        ) {
            Text(
                when {
                    busy -> "Проверяю…"
                    input.isNotBlank() -> "Добавить"
                    else -> "Проверить все"
                }
            )
        }

        if (note.isNotEmpty()) {
            Text(note, color = Brand.text, fontSize = 13.sp)
        }

        HowTo(ctx = context)

        HorizontalDivider(color = Brand.sphere)

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(HashStore.items, key = { it.hash }) { e ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Целиком: человек должен узнавать свои. В лог
                        // ссылка уходит обрезанной, а в выгрузку не
                        // попадает вовсе.
                        Text(e.hash, color = Brand.text, fontSize = 11.sp)
                        Text(
                            statusText(e) + ", " + HashStore.checkedText(e),
                            color = Brand.dim,
                            fontSize = 12.sp,
                        )
                    }
                    // Крестик без подписи: значок понятен сам по себе.
                    DeleteButton(onClick = { HashStore.remove(e.hash) })
                }
                HorizontalDivider(color = Brand.sphere)
            }
        }
    }
}

/**
 * Как получить ссылку. Свёрнута по умолчанию.
 *
 * Свёрнута намеренно: нужна она ровно один раз, при первом заведении
 * ссылок, а место на экране занимала бы всегда.
 */
@Composable
private fun HowTo(ctx: Context) {
    var open by remember { mutableStateOf(false) }

    Text(
        text = (if (open) "▾ " else "▸ ") + "Как получить ссылки",
        color = Brand.edge,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp),
    )
    if (!open) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Первый шаг нажимаемый: человеку всё равно идти в VK, и
        // отправить его туда прямо отсюда дешевле, чем заставлять
        // искать значок на рабочем столе.
        Text(
            "1. Открыть VK и перейти в сообщения",
            color = Brand.edge,
            fontSize = 13.sp,
            modifier = Modifier.clickable { openVk(ctx) },
        )
        for (line in listOf(
            "2. Справа вверху нажать на трубку, затем там же на трубку с плюсом",
            "3. Выбрать «Создать звонок», начать звонок от любого имени",
            "4. Нажать «Поделиться ссылкой», внизу «Поделиться» — копировать " +
                "ссылку звонка. Приложение можно закрыть",
            "5. Вставить ссылку на звонок в поле выше",
        )) {
            Text(line, color = Brand.dim, fontSize = 13.sp)
        }
    }
}

/**
 * Открывает VK: сначала приложение, при неудаче браузер.
 *
 * Три попытки по убыванию точности:
 *   1. vk://vk.com/im — ведёт сразу в сообщения, если схему кто-то ловит;
 *   2. запуск установленного приложения по имени пакета. Работает
 *      благодаря разделу <queries> в манифесте, заведённому для
 *      раздельного туннелирования: без него мы приложений не видим;
 *   3. обычная ссылка в браузер.
 *
 * Пакетов два: обычный VK и отдельный мессенджер, у разных людей стоит
 * то одно, то другое.
 */
private fun openVk(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("vk://vk.com/im"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    } catch (e: Throwable) {
        // Схему никто не ловит — идём дальше.
    }
    for (pkg in listOf("com.vkontakte.android", "com.vk.im")) {
        val launch = try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Throwable) {
            null
        }
        if (launch != null) {
            try {
                ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Throwable) {
                // Следующий пакет.
            }
        }
    }
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://vk.com/im"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Throwable) {
        TunnelLog.add("открыть VK не вышло: ${e.message}")
    }
}

private fun statusText(e: HashStore.Entry): String =
    if (e.status == HashStore.ST_NEW) "не проверялась" else e.status

/**
 * Троттлинг между ссылками при проверке всех.
 *
 * Те же 3–6 секунд, что в движке между добычей комплектов кредов
 * (engine/slots.go, credsThrottleMin/Max). Число не выдумано заново
 * намеренно: ограничение ставит VK, а не мы, и оно одно на всё
 * приложение независимо от того, откуда мы к нему пришли.
 */
private const val THROTTLE_MIN_MS = 3000L
private const val THROTTLE_MAX_MS = 6000L

/**
 * Проверяет ВСЕ ссылки по очереди.
 *
 * КАЖДАЯ ПРОВЕРКА — ПОЛНЫЙ ПРОХОД ЦЕПОЧКИ VK, то есть настоящий расход
 * ссылки. Поэтому: по одной, с паузой между ними, и с показом, сколько
 * осталось, — человек должен видеть, что процесс идёт и сколько он ещё
 * будет тратить.
 */
private suspend fun checkAll(ctx: Context, say: (String) -> Unit) {
    val all = HashStore.all()
    if (all.isEmpty()) {
        say("проверять нечего")
        return
    }
    val logger = object : Logger {
        override fun log(line: String) = TunnelLog.add(line)
    }
    val deviceId = Access.deviceId(ctx)
    var alive = 0

    for ((i, hash) in all.withIndex()) {
        say("проверяю ${i + 1} из ${all.size}, осталось ${all.size - i - 1}")
        val status = withContext(Dispatchers.IO) {
            try {
                // Protector = null: проверяют при погашенном туннеле,
                // исключать сокет не из чего.
                //
                // Решатель капчи здесь проще, чем при подключении:
                // приложение на виду, экран откроется сразу.
                Engine.checkHash(hash, deviceId, null, CaptchaGate.solver(ctx), logger)
            } catch (e: Throwable) {
                "ошибка: ${e.message}"
            }
        }
        val mapped = when (status) {
            "живой" -> HashStore.ST_ALIVE
            "капча" -> HashStore.ST_CAPTCHA
            "мёртвый" -> HashStore.ST_DEAD
            "релея нет" -> HashStore.ST_NO_TURN
            else -> status
        }
        if (mapped == HashStore.ST_ALIVE) alive++
        HashStore.setStatus(hash, mapped)

        if (i < all.size - 1) {
            val pause = THROTTLE_MIN_MS + Random.nextLong(THROTTLE_MAX_MS - THROTTLE_MIN_MS)
            say("проверено ${i + 1} из ${all.size}, пауза ${pause / 1000} с")
            delay(pause)
        }
    }
    say("готово: живых $alive из ${all.size}")
}
