package org.meridianvpn.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Блок доступа. Показывается под кнопкой подключения.
 *
 * Ввод ключа доступен во ВСЕХ состояниях, кроме уже подтверждённого
 * ключа: человек мог купить его и не начиная триала.
 */
@Composable
fun AccessBlock(
    modifier: Modifier = Modifier,
    onConnect: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val state = Access.state.value
    // note/busy — только для кнопки пробного периода ниже. Покупка и ввод
    // ключа со своим состоянием уехали в AccessOffer, чтобы жить в двух
    // местах (главный и настройки), не двоя логику.
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            Access.State.NO_TRIAL -> {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        note = "спрашиваю пробный ключ…"
                        scope.launch {
                            val answer = withContext(Dispatchers.IO) { Api.startTrial() }
                            busy = false
                            note = applyTrial(answer, onConnect)
                        }
                    },
                ) { Text(if (busy) "Минуту…" else "Начать 3 дня бесплатно") }
            }

            // Во время активного триала на главном НИЧЕГО: и остаток, и
            // предложение купить/ввести ключ уехали в настройки (55.3 +
            // просьба владельца 13.09). Планета показывает состояние сама.
            Access.State.TRIAL -> Unit

            Access.State.OVER -> {
                // Вторая строка не украшение: без неё человек будет
                // искать кнопку «начать сутки» и не найдёт её.
                Text(
                    "Пробный период закончился. Пробный период даётся " +
                        "один раз на устройство.",
                    color = Brand.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            // СРОК ДОСТУПА УЕХАЛ В НАСТРОЙКИ (55.3). На главном экране
            // при живом ключе не остаётся ничего: планета показывает
            // состояние сама, а дата нужна раз в месяц.
            Access.State.KEYED -> Unit
        }

        // ПОКУПКА И ВВОД КЛЮЧА НА ГЛАВНОМ — ТОЛЬКО КОГДА ТРИАЛА НЕТ.
        //
        // Не начат (NO_TRIAL) или кончился (OVER) — предлагаем здесь. Во
        // время активного триала предложение живёт в НАСТРОЙКАХ рядом с
        // остатком (просьба владельца 13.09): главный экран в триале
        // должен быть чист. Кончится триал — вернётся на главный само,
        // потому что state сменится на OVER.
        //
        // При живом ключе (KEYED) не предлагаем нигде: как только доступ
        // есть, кнопки молчат — верно и для play, где Google на «купить»
        // при активной подписке ответил бы «вы уже подписаны».
        if (state == Access.State.NO_TRIAL || state == Access.State.OVER) {
            AccessOffer(onConnect = onConnect)
        }

        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Покупка ключа и ввод уже имеющегося — один блок, живущий в ДВУХ местах:
 * на главном, пока доступа нет (NO_TRIAL/OVER), и в настройках во время
 * активного триала (просьба владельца 13.09).
 *
 * Вынесен отдельным composable намеренно: логика ввода ключа — проверка
 * вердикта, привязка, подключение — не должна двоиться. Два места с одним
 * смыслом расходятся, мы это уже проходили.
 *
 * Своё состояние (typing/typed/note/busy): у двух вхождений оно
 * независимо, и это правильно — ввод в настройках и ввод на главном не
 * связаны.
 *
 * Сама кнопка покупки живёт в разных наборах исходников:
 *   app/src/direct/.../Purchase.kt — ссылка на бота;
 *   app/src/play/.../Purchase.kt   — покупка через Play.
 * Ветвления по флагу здесь нет намеренно: оно оставило бы строки про
 * внешнюю оплату в сборке для Play, а их видно в dex обычным grep.
 */
@Composable
fun AccessOffer(
    modifier: Modifier = Modifier,
    onConnect: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    // Подсказка ПОД БЛОКОМ, не итог: «ключ слишком короткий», «спрашиваю
    // у сервера…». Итог события идёт в Notice под планетой — иначе одна
    // фраза видна дважды разным кеглем.
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PurchaseOffer(onNote = { note = it })

        if (!typing) {
            TextButton(
                enabled = !busy,
                onClick = {
                    // СНАЧАЛА СПРАШИВАЕМ У GOOGLE, потом показываем поле
                    // ввода. Человек мог поменять телефон и искать, куда
                    // ввести ключ, — а вводить нечего, доступ оплачен и
                    // восстановится сам (62.3). В прямой раздаче это
                    // пустышка: покупок Google там нет, вызов вернёт пусто.
                    busy = true
                    scope.launch {
                        val said = withContext(Dispatchers.IO) { tryRestorePurchase(ctx) }
                        busy = false
                        when {
                            said.isNotEmpty() -> note = said
                            // ПОЛЕ ВВОДА — ТОЛЬКО ТАМ, ГДЕ КЛЮЧ ЕСТЬ ЧЕМ
                            // ВЫДАТЬ. Признак задан вариантом сборки.
                            KEY_ENTRY -> typing = true
                            else -> note = RECOVER_EMPTY
                        }
                    }
                },
            ) { Text(if (busy) "Проверяю…" else RECOVER_LABEL) }
        } else if (KEY_ENTRY) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Ключ") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = {
                        val k = typed.trim()
                        if (k.length < 8) {
                            note = "ключ слишком короткий"
                        } else {
                            busy = true
                            note = "спрашиваю у сервера…"
                            scope.launch {
                                val answer = withContext(Dispatchers.IO) { Api.checkKey(k) }
                                busy = false
                                note = applyVerdict(answer, k) {
                                    typed = ""
                                    typing = false
                                    onConnect()
                                }
                            }
                        }
                    },
                ) { Text(if (busy) "Проверяю…" else "Проверить") }
                TextButton(
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = { typing = false; typed = "" },
                ) { Text("Отмена") }
            }
        }

        // Почему ключ перестал годиться. Строка приходит от службы —
        // тем же текстом, что увидят люди на других клиентах.
        val problem = Access.problem.value
        if (problem.isNotEmpty()) {
            Text(problem, color = Brand.edge, style = MaterialTheme.typography.bodyMedium)
        }

        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Что делать с ответом API про ключ.
 *
 * ЗДЕСЬ ВСЯ СУТЬ ЗАДАЧИ 49.4. До неё «Проверить» означало «попробовать
 * подключиться», и человек с опечаткой в ключе видел «не удалось
 * подключиться» — то есть его посылали чинить сеть вместо ключа.
 *
 * Теперь утверждений ДВА, и они разные:
 *
 *   API говорит, что ключ ЕСТЬ, не истёк и не занят чужим устройством;
 *   туннель говорит, что ключ РАБОТАЕТ.
 *
 * Второе не следует из первого: запись в базе могла разойтись с тем,
 * что знает шлюз. Поэтому после согласия API мы всё равно подключаемся,
 * и сохраняет ключ по-прежнему только вставший туннель
 * (Access.confirmPending).
 *
 * Когда API не ответил, остаётся ровно старое поведение — проверка
 * подключением. Молчание сервера не повод отказать человеку во вводе
 * его собственного ключа.
 *
 * @param connect зовётся, если дело дошло до подключения
 * @return строка для человека
 */
private fun applyVerdict(answer: Api.KeyAnswer, key: String, connect: () -> Unit): String {
    when (answer) {
        is Api.KeyAnswer.Verdict -> {
            val v = answer.v
            // Служба присылает готовый русский текст в поле message.
            // Берём его, а не свой: иначе одно и то же событие
            // объясняется в двух местах и рано или поздно по-разному.
            // Свои строки — запасные, на случай пустого message.
            if (!v.usable()) {
                // ПЛОХОЙ ВЕРДИКТ СО СТАРЫХ ДАННЫХ НИЧЕГО НЕ РЕШАЕТ.
                // По договору в кэш кладётся только «ok», так что этого
                // не бывает; если пришло — ошибка службы, и отказывать
                // человеку по ней нельзя. Ведём себя как при молчании:
                // проверяем подключением.
                if (!v.trustworthyRefusal()) {
                    TunnelLog.add(
                        "API: плохой вердикт со СТАРЫХ данных (${v.ageText()}) — " +
                            "не верю ему, проверяю подключением"
                    )
                    Access.setPending(key)
                    if (Access.checking()) connect()
                    return "сервер ответил старыми данными — проверяю подключением"
                }

                // Один разбор на оба случая — ввод и перепроверку. Шесть
                // вердиктов кота 2 объясняются в одном месте, иначе они
                // рано или поздно объяснятся по-разному.
                //
                // Отвязка (/v1/unbind) на сервере ещё не написана,
                // поэтому кнопки при чужой привязке пока нет — только
                // объяснение.
                return Access.keyProblemText(v)
            }
            // Ключ годен по мнению API. Сохраняем СРАЗУ — человек ввёл
            // ключ и обязан немедленно увидеть исход, а не гадать.
            // Второе утверждение, «работает», придёт от туннеля следом.
            Access.acceptKey(key, v.expiresAt, v.staleAge)
            // Текст службы точнее нашего: он называет срок. Ставим его
            // в строку состояния вместо общего «Ключ принят».
            if (v.message.isNotEmpty()) Notice.say(v.message)
            connect()
            return ""
        }

        is Api.KeyAnswer.Refused -> {
            // Служба отказала — ключ тут ни при чём, и вести себя надо
            // так, будто её и не спрашивали.
            val note = Access.setPending(key)
            if (Access.checking()) connect()
            val wait = if (answer.retryAfter > 0) ", повтор через ${answer.retryAfter} с" else ""
            return "сервер не проверил («${answer.error}»$wait) — проверяю подключением. $note"
        }

        is Api.KeyAnswer.NoAnswer -> {
            val note = Access.setPending(key)
            if (Access.checking()) connect()
            return "сервер недоступен — проверяю подключением. $note"
        }
    }
}

/**
 * Что делать с ответом ручки пробных ключей.
 *
 * ТРИ ИСХОДА, И ОНИ РАЗНЫЕ — задача 58.3.
 *
 * Выдан — сохраняем как обычный ключ доступа и подключаемся: пробный
 * ключ ничем не отличается от купленного, кроме срока и того, что
 * сервер помнит устройство.
 *
 * Отказ — служба высказалась, и человеку надо это показать. Локальный
 * триал при этом НЕ начинаем: сервер сказал «нет», и начать сутки в
 * обход его слова значило бы обмануть и его, и человека.
 *
 * Молчание — падаем на локальный якорь, как и раньше. Доступ не
 * запрещаем: наша неспособность спросить не повод наказывать человека.
 * Это то же правило, что и с перепроверкой ключа.
 */
private fun applyTrial(answer: Api.TrialAnswer, connect: () -> Unit): String {
    when (answer) {
        is Api.TrialAnswer.Granted -> {
            Access.acceptKey(answer.key, answer.expiresAt, source = Access.SOURCE_TRIAL)
            if (answer.repeat) {
                // Перебиваем строку, которую поставил acceptKey: про
                // повтор человеку знать полезнее, чем про начало.
                Notice.say("Это тот же пробный ключ, что уже выдавался")
            }
            connect()
            // ПУСТАЯ СТРОКА НАМЕРЕННО. Об исходе говорит строка
            // состояния под планетой, и вторая надпись здесь же была
            // тем же текстом другим кеглем.
            return ""
        }

        is Api.TrialAnswer.Refused -> {
            // ТЕКСТ ВСЕГДА СЛУЖБЫ, включая предел выдачи: её
            // «попробуйте через час» точнее любого нашего «позже»,
            // потому что срок знает она.
            //
            // Признак rateLimited нужен не для текста, а для того,
            // чтобы не повторять попытку сразу и не жечь этим лимит:
            // локальный триал мы тут НЕ начинаем.
            //
            // Отказ НЕ по пределу выдачи — значит период кончился:
            // запираем триал, чтобы кнопка «Начать 3 дня» после конца
            // периода не показывалась. Предел (429) не запираем — он
            // временный.
            if (!answer.rateLimited) Access.trialEnded()
            return answer.text
        }

        is Api.TrialAnswer.NoAnswer -> {
            // Запасной путь: локальный якорь на сутки. Он же работал до
            // появления ручки.
            Access.startTrial()
            return "сервер недоступен — сутки отсчитываю сам (${answer.why})"
        }
    }
}
