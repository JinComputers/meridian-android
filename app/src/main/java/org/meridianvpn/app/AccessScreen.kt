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

        // ПЕРЕНОС КЛЮЧА ВИДЕН И ПРИ ЖИВОМ КЛЮЧЕ, и это не перестраховка.
        //
        // Случай, ради которого ветка заведена: человек купил новый
        // телефон, и Android перенёс на него настройки вместе с ключом —
        // так устроено намеренно, см. <device-transfer> в
        // res/xml/data_extraction_rules.xml. Ключ на месте, состояние
        // KEYED, а служба говорит «занят другим устройством»: ANDROID_ID
        // на новом телефоне другой.
        //
        // Без этой ветки он упёрся бы в объяснение БЕЗ ДВЕРИ: при KEYED
        // блок предложения не показывается нигде, а значит и кнопки
        // переноса внутри него человек не увидит никогда.
        //
        // Условие — дополнение к строке выше, а не дубль: там, где
        // показан AccessOffer, кнопка уже внутри него.
        if ((state == Access.State.TRIAL || state == Access.State.KEYED) &&
            Access.rebindable.value.isNotEmpty()
        ) {
            TransferKey(onDone = onConnect)
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

        // ПЕРЕНОС КЛЮЧА — ровно там, где человек упёрся в чужую
        // привязку, и больше нигде. Условие одно и живёт в Access:
        // сравнивать текст на экране со строкой было бы привязкой к
        // формулировке, которую завтра поправят.
        if (Access.rebindable.value.isNotEmpty()) {
            TransferKey(onDone = { typing = false; typed = ""; onConnect() })
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
 * Перенос ключа на это устройство.
 *
 * Появляется ровно при Bound.OTHER (условие — Access.rebindable) и
 * больше нигде. Трёх случаев, из-за которых человек сюда попадает, —
 * другой телефон, сброс к заводским, отладочная сборка против
 * релизной, — клиент не различает: все три приходят одним вердиктом и
 * лечатся одной кнопкой.
 *
 * ПОДТВЕРЖДЕНИЕ ОБЯЗАТЕЛЬНО, И ЭТО НЕ ВЕЖЛИВОСТЬ. Перенос ТРАТИТ
 * попытку из годового счёта и УБИВАЕТ прежний ключ. Обе цены человеку
 * невидимы, пока их не назвать, а нажатие необратимо.
 *
 * ЧТО ОБЕЩАЕМ ПРО СТАРЫЙ ТЕЛЕФОН — «при следующем подключении», и
 * формулировка выстрадана. Сначала обещали «сразу»: шлюз действительно
 * вытесняет прежнее соединение за миллисекунды. Потом «в течение
 * нескольких минут»: у шлюза дедлайн пять минут без пакета. Оба неверны
 * для ротации. Наш же keepalive уходит каждые 20 секунд
 * (engine/session.go:26), то есть пятиминутный дедлайн при живом
 * приложении не наступает НИКОГДА, и старый телефон может держать
 * туннель часами. Врать об этом нельзя: человек проверит и увидит.
 */
@Composable
private fun TransferKey(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    // Попытки кончились — кнопку прячем, а объяснение оставляем. Гасить
    // её через Access.rebindable нельзя: вместе с кнопкой пропало бы и
    // «почему», и человек остался бы перед пустым местом.
    var hidden by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hidden) {
            if (!confirming) {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = { confirming = true },
                ) { Text("Перенести на это устройство") }
            } else {
                Text(
                    // ПРО СУДЬБУ НОВОГО КЛЮЧА ГОВОРИМ ПО ПРИЗНАКУ СЛУЖБЫ,
                    // и это исправление лжи, выпущенной в 0.1.135.
                    //
                    // Там стояло «Новый ключ придёт вам в бот» — обещание
                    // без оговорки. Замер кота 2 по базе 14.09: из 139
                    // ключей 94 НЕ привязаны к аккаунту в боте, и 13 из
                    // них пользовались за последние 30 суток, то есть
                    // примерно половина живого парка. Этим людям
                    // сообщение не придёт никогда, а мы им его обещали.
                    //
                    // СКАЗАТЬ НАДО ДО НАЖАТИЯ, А НЕ ПОСЛЕ. Признак
                    // recoverable кот 2 положил и в /v1/key именно по
                    // этой просьбе: нажатие необратимо и тратит попытку
                    // из четырёх за год, а предупреждение после нажатия —
                    // это предупреждение тогда, когда решение принято.
                    //
                    // ТРЕТЬЕ, ЧТО РАЗБИЛО МОЙ ПЕРВЫЙ ТЕКСТ. recoverable
                    // родился как «есть владелец в боте», но кот 2 сразу
                    // расширил его через ИЛИ покупкой Play (17.09), а
                    // готовит и третье условие для сайта — то есть true
                    // означает «есть КАКОЙ-ТО источник, кроме телефона»,
                    // а не именно бот. «Придёт вам в бот» для Play-
                    // подписчика было бы ложью симметричной прежней:
                    // у него бота нет вовсе, он восстановится через
                    // Billing.restore(). Различаем по Access.sourceOfKey():
                    // это НАШЕ знание, откуда взялся ключ, оно не зависит
                    // от recoverable и не устареет вместе с его смыслом.
                    //
                    // Четвёртый случай (null) — служба поля не прислала.
                    // Читать его как false нельзя: до выката поля так
                    // выглядели бы ВСЕ, и мы пугали бы 45 человек, у
                    // которых всё в порядке. Не знаем — говорим
                    // осторожнее, а не страшнее.
                    "Ключ станет новым, а прежний перестанет действовать. " +
                        "Второй телефон отключится при следующем подключении. " +
                        when {
                            Access.rebindRecoverable.value == true &&
                                Access.sourceOfKey() == Access.SOURCE_SUB ->
                                "Новый ключ подхватится сам через подписку."

                            Access.rebindRecoverable.value == true ->
                                "Новый ключ придёт вам в бот."

                            Access.rebindRecoverable.value == false ->
                                "Новый ключ останется только на этом устройстве."

                            else -> "Новый ключ сохранится здесь, а в бот " +
                                "придёт, если ключ к нему привязан."
                        },
                    color = Brand.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = {
                            // Ключ берём из Access, а не из поля ввода:
                            // сюда попадают оба пути — и ввод руками, и
                            // перепроверка уже сохранённого.
                            val k = Access.rebindable.value
                            busy = true
                            note = "переношу…"
                            scope.launch {
                                val a = withContext(Dispatchers.IO) { Api.unbind(k) }
                                busy = false
                                confirming = false
                                note = applyTransfer(a, onDone) { hidden = true }
                            }
                        },
                    ) { Text(if (busy) "Минуту…" else "Перенести") }
                    TextButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = { confirming = false },
                    ) { Text("Отмена") }
                }
            }
        }

        if (note.isNotEmpty()) {
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Что делать с ответом на перенос.
 *
 * ПРИЗНАК УСПЕХА — ТОЛЬКО вердикт, и он уже разобран в Api.unbind:
 * сюда приходит Done и при «перенесли», и при «уже ваш». Ключ в обоих
 * случаях настоящий и его надо сохранить; отличается только фраза.
 *
 * @param onDone   зовётся при успехе — закрыть ввод и подключаться
 * @param onHidden зовётся, когда кнопку надо убрать навсегда
 * @return строка для человека
 */
private fun applyTransfer(
    a: Api.UnbindAnswer,
    onDone: () -> Unit,
    onHidden: () -> Unit,
): String = when (a) {
    is Api.UnbindAnswer.Done -> {
        // Сохраняем СРАЗУ: человек нажал кнопку и обязан немедленно
        // увидеть исход. Подтверждение подключением придёт следом — как
        // и при вводе ключа руками.
        Access.acceptKey(a.key, a.expiresAt)
        // ОСТАТОК ПОПЫТОК НАЗЫВАЕМ, если служба не сказала сама.
        //
        // Перенос — ресурс редкий и скрытый: четыре за год и неделя
        // выдержки между ними. Человек, потративший один из четырёх и не
        // узнавший об этом, обнаружит счёт только упершись в отказ —
        // через месяцы и без всякой связи с тем, что он тогда нажал.
        //
        // Только в запасном тексте: если служба прислала своё сообщение,
        // оно главнее, и дописывать к нему своё значило бы объяснять
        // одно событие дважды.
        Notice.say(
            a.message.ifEmpty {
                if (a.rotated) {
                    "Ключ перенесён на этот телефон. " +
                        "Переносов осталось ${a.attemptsLeft}"
                } else {
                    "Ключ и так ваш"
                }
            }
        )
        // ПРЕДУПРЕЖДЕНИЕ ПРО ЕДИНСТВЕННЫЙ ЭКЗЕМПЛЯР — ОТДЕЛЬНОЙ СТРОКОЙ
        // И ПОВЕРХ ТЕКСТА СЛУЖБЫ, а не вместо него.
        //
        // Это единственное место, где мы спорим с message службы, и спор
        // осознанный: её текст говорит про перенос, а это — про то, что
        // ключа больше нигде нет. Разные утверждения, и второе человек
        // обязан прочитать, что бы ни прислала служба.
        //
        // «Сохраните его» здесь НЕ ПИШЕМ: показать ключ владелец пока не
        // разрешил, а совет сохранить то, чего человек не видит, — совет
        // без двери. Говорим то, что он может сделать: не терять этот
        // телефон и не стирать приложение.
        onDone()
        if (a.rotated && a.recoverable == false) {
            TunnelLog.event("новый ключ нигде, кроме этого телефона, не сохранён")
            // ПРИЧИНУ («не привязан к боту») НЕ НАЗЫВАЕМ — recoverable
            // с 17.09 это ИЛИ из нескольких источников (бот, покупка
            // Play, готовится сайт), и ложным окажется называть только
            // один из них: у сайтового ключа, скажем, бота не было и не
            // будет никогда, и фраза про бот вводила бы в заблуждение
            // не меньше прежнего «придёт вам в бот» без оговорки.
            // Достаточно факта, который верен всегда, — что источника
            // нет вовсе.
            "Новый ключ есть только на этом телефоне — больше нигде " +
                "он не сохранён. Не стирайте приложение, не сделав " +
                "резервную копию."
        } else {
            ""
        }
    }

    is Api.UnbindAnswer.Cooldown ->
        a.message.ifEmpty { "перенести можно будет " + afterText(a.retryAfter) }

    is Api.UnbindAnswer.Exhausted -> {
        onHidden()
        a.message.ifEmpty { "переносы на этот ключ кончились — напишите в поддержку" }
    }

    is Api.UnbindAnswer.BadKey -> {
        onHidden()
        a.message.ifEmpty { "этот ключ больше не действует" }
    }

    // Молчание службы НЕ отбирает доступ и НЕ хоронит ключ — правило
    // одно на весь клиент. Кнопку оставляем: повторить можно.
    is Api.UnbindAnswer.NoAnswer -> "сейчас не получилось (${a.why}) — попробуйте позже"
}

/**
 * «через 2 дня», «через 4 ч», «через 15 мин». Для выдержки переноса.
 *
 * Своя, а не KeyVerdict.ageText(): та говорит в прошедшем времени
 * («3 ч назад») и суток не знает вовсе, а выдержка переноса считается
 * днями.
 */
private fun afterText(sec: Int): String {
    val m = sec / 60
    return when {
        m >= 60 * 24 -> "через ${m / (60 * 24)} дн."
        m >= 60 -> "через ${m / 60} ч"
        m >= 1 -> "через $m мин"
        else -> "сейчас"
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

                // Один разбор на оба случая — ввод и перепроверку. Семь
                // вердиктов кота 2 объясняются в одном месте, иначе они
                // рано или поздно объяснятся по-разному.
                //
                // Ключ, занятый другим устройством, ЗАПОМИНАЕМ: под него
                // ниже появится кнопка переноса. Заменённый (ROTATED)
                // сюда не попадёт — noteRebindable его отсеивает сам.
                Access.noteRebindable(key, v)
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
