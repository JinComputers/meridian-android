package org.meridianvpn.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Улики раньше интерфейса: первый запуск после установки не
        // оставил ни строки именно потому, что хранить их было негде.
        CrashLog.install(this)
        TunnelLog.attach(this)
        TunnelState.attach(this)
        TransportSetting.attach(this)
        HashStore.attach(this)
        SplitTunnel.attach(this)
        Access.attach(this)
        ApiClient.attach(this)
        Params.attach(this)
        // Обновление подключается ЗДЕСЬ ТОЖЕ: подсказка о свежей версии
        // приходит вместе с проверкой ключа, а её запускает экран, а не
        // служба. Без этого подсказка приходила бы раньше контекста.
        updateAttach(this)
        CrashLog.last(this)?.let {
            TunnelLog.add("ПРОШЛЫЙ ЗАПУСК УПАЛ: $it")
            CrashLog.clear(this)
        }

        // Будильники живут в системе, но теряются при перезагрузке.
        // Приёмник BOOT_COMPLETED их восстанавливает; это здесь —
        // подстраховка на случай, если он не сработал.
        TrialAlarm.arm(this)

        // ПОВОДЫ 1 и 2: раз в сутки и когда до конца срока меньше трёх
        // суток. Решает сам Access — здесь только точка входа.
        //
        // Отдельным потоком: внутри поход в сеть, а мы в onCreate на
        // главном. Экран при этом не ждёт ничего: он рисуется по
        // сохранённым числам, а придёт ответ — состояние обновится само.
        thread(name = "meridian-keycheck") {
            try {
                Access.refreshKey()
                // И, если сутки идут местные, — попробовать догнать
                // серверный ключ. Иначе местный отсчёт и серверный ключ
                // разошлись бы навсегда.
                Access.upgradeLocalTrial()
            } catch (e: Throwable) {
                TunnelLog.add("перепроверка ключа сорвалась: ${e.message}")
            }
        }

        // ОТОБРАЖЕНИЕ ОТ КРАЯ ДО КРАЯ — ВРУЧНУЮ, БЕЗ enableEdgeToEdge().
        //
        // enableEdgeToEdge() внутри зовёт Window.setStatusBarColor и
        // setNavigationBarColor. С Android 15 (SDK 35) они устарели, и
        // Play помечает сам вызов в байткоде — даже под guard'ом по
        // версии он там есть. setDecorFitsSystemWindows этих вызовов не
        // делает, а прозрачность баров и светлые значки уже заданы темой
        // (res/values/themes.xml: statusBarColor/navigationBarColor =
        // transparent, windowLightStatusBar = false). Отступы под бары
        // раздаёт Scaffold через innerPadding — как и раньше.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            // ОДНА ТЕМА, ЧЁРНАЯ (51.1). Не тёмно-серая: на OLED чёрный
            // пиксель не горит, и для приложения, открытого пока идёт
            // туннель, это расход батареи, а не оформление.
            //
            // Цвета заданы явно, а не взяты из умолчаний darkColorScheme():
            // там фон тёмно-серый, то есть ровно то, чего мы избегаем.
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Brand.space,
                    surface = Brand.space,
                    onBackground = Brand.text,
                    onSurface = Brand.text,
                    onSurfaceVariant = Brand.dim,
                    primary = Brand.edge,
                    onPrimary = Brand.space,
                    outline = Brand.dim,
                )
            ) {
                var screen by remember { mutableStateOf(Screen.TUNNEL) }
                val ctx = LocalContext.current

                // СИСТЕМНАЯ КНОПКА «НАЗАД».
                //
                // Наши нарисованные стрелки работали, а системная — нет:
                // из настроек она сворачивала приложение целиком, потому
                // что для системы у нас одна активность и один экран.
                //
                // enabled = true всегда, в том числе на главном экране, и
                // это не описка. На главном мы намеренно НЕ делаем ничего:
                // нажатие «назад» там означало бы выход, а VPN сворачивают
                // кнопкой «домой». Проглотить нажатие — единственный
                // способ этого не допустить.
                BackHandler {
                    screen = when (screen) {
                        Screen.TUNNEL -> Screen.TUNNEL
                        Screen.SETTINGS -> Screen.TUNNEL
                        // Разделы настроек возвращают в настройки, а не
                        // на главный: человек пришёл оттуда.
                        Screen.HASHES, Screen.LOGS, Screen.SPLIT -> Screen.SETTINGS
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Brand.space,
                ) { inner ->
                    val pad = Modifier.padding(inner)
                    when (screen) {
                        Screen.TUNNEL -> TunnelScreen(
                            modifier = pad,
                            onSettings = { screen = Screen.SETTINGS },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            modifier = pad,
                            onHashes = { screen = Screen.HASHES },
                            onLogs = { screen = Screen.LOGS },
                            onSplit = { screen = Screen.SPLIT },
                            onBack = { screen = Screen.TUNNEL },
                            // Ключ, введённый из настроек во время триала,
                            // применяется переподъёмом на новом ключе.
                            // Полную лестницу разрешений здесь не гоняем:
                            // в активном триале разрешение VPN уже выдано,
                            // и хватает старта службы — как ветка connect
                            // без диалога. Первый подъём (с диалогом) всё
                            // равно шёл через главный экран.
                            onConnect = {
                                ctx.startService(
                                    Intent(ctx, MeridianVpnService::class.java)
                                        .setAction(MeridianVpnService.ACTION_CONNECT)
                                )
                            },
                        )
                        Screen.SPLIT -> SplitScreen(
                            modifier = pad,
                            onBack = { screen = Screen.SETTINGS },
                            onReconnect = {
                                // Человек сам решил порвать работающий
                                // туннель ради новой настройки. Гасим и
                                // поднимаем заново: список применяется
                                // только в establish().
                                TunnelLog.add("список приложений изменён — переподключаюсь по просьбе")
                                startService(
                                    Intent(this, MeridianVpnService::class.java)
                                        .setAction(MeridianVpnService.ACTION_RECONNECT)
                                )
                            },
                        )
                        Screen.HASHES -> HashScreen(
                            modifier = pad,
                            onBack = { screen = Screen.SETTINGS },
                        )
                        Screen.LOGS -> LogScreen(
                            modifier = pad,
                            onBack = { screen = Screen.SETTINGS },
                            onShare = { TunnelLog.add(LogExport.share(this)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TunnelScreen(modifier: Modifier = Modifier, onSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val connected = TunnelState.connected.value
    val access = Access.state.value
    val scope = rememberCoroutineScope()

    // Остаток триала считается по часам, а часы идут сами. Без
    // подтягивания строка «осталось» замерла бы на том значении, с
    // которым экран открылся.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            Access.refresh()
        }
    }

    // VpnService.prepare() возвращает Intent, если разрешение ещё не выдано.
    // Диалог показывает система, подделать его нельзя — в этом и смысл.
    val prepare = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Первая встреча идёт ИМЕННО этим путём: служба стартует не
            // из нажатия, а из ответа системного диалога. Отмечаем, чтобы
            // потом было видно, каким путём пришли.
            TunnelLog.add("разрешение на VPN выдано, запускаю службу")
            context.startService(
                Intent(context, MeridianVpnService::class.java)
                    .setAction(MeridianVpnService.ACTION_CONNECT)
            )
        } else {
            TunnelLog.add("разрешение на VPN не выдано")
            // Отказ в разрешении — тоже определённый исход. Без этого
            // планета крутилась бы, хотя подключаться уже никто не
            // собирается.
            TunnelState.setBusy(false)
        }
    }

    // Подключение вынесено в отдельную лямбду: кроме кнопки его зовёт
    // проверка ключа — она и есть подключение.
    val connect: () -> Unit = {
        // Лог НЕ стираем: раньше здесь был clear(), и он уносил улики
        // предыдущей неудачной попытки ровно в тот момент, когда человек
        // пробует ещё раз. Вместо этого размечаем границу.
        TunnelLog.separator("подключение")
        // Нажатие обязано дать видимое следствие немедленно: подъём
        // лестницы занимает до двадцати пяти секунд, и всё это время
        // человек иначе смотрит на неподвижную планету.
        TunnelState.setBusy(true)
        val intent = VpnService.prepare(context)
        if (intent != null) {
            TunnelLog.add("разрешение на VPN ещё не выдано, показываю диалог")
            prepare.launch(intent)
        } else {
            context.startService(
                Intent(context, MeridianVpnService::class.java)
                    .setAction(MeridianVpnService.ACTION_CONNECT)
            )
        }
    }

    NotificationsAsk()

    // ПЛАНЕТА ПО ЦЕНТРУ ЭКРАНА (54.3). Пустоты сверху и снизу равные,
    // поэтому она остаётся в середине независимо от того, сколько
    // строк занял блок доступа.
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Планета нажимается, только пока доступ есть.
        //
        // Уже поднятый туннель — исключение: если сутки кончились
        // посреди работы, отключить его человек обязан мочь, иначе он
        // не выключит то, что работает.
        //
        // Access.checking() — проверка введённого ключа: доступа ещё
        // нет, но подключиться надо, иначе ключ нечем проверить.
        val canConnect = connected ||
            access == Access.State.TRIAL ||
            access == Access.State.KEYED ||
            Access.checking()

        // ПЛАНЕТА — она же кнопка. Нажатие подключает и отключает.
        //
        // Без доступа она неподвижна и не нажимается: что делать
        // дальше, объясняет блок под ней, а не отсутствующая кнопка.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Planet(
                connected = connected,
                busy = TunnelState.busy.value,
                // ПОКА ПОДНИМАЕМСЯ — НЕ НАЖИМАЕТСЯ.
                //
                // Планета вращается, а нажатие не проходит: подъём
                // занимает секунды, и человек, не видя отклика, жал
                // ещё раз. Из лога 25.08 — шесть заходов в одну
                // секунду, каждый со своим потоком и своим AUTH.
                //
                // Служба такие повторы теперь отбрасывает сама, но
                // не давать их вовсе честнее: кнопка, которая
                // принимает нажатие и ничего не делает, врёт.
                //
                // При поднятом туннеле нажатие нужно — им отключают.
                enabled = canConnect && (connected || !TunnelState.busy.value),
                onClick = {
                    if (connected) {
                        context.startService(
                            Intent(context, MeridianVpnService::class.java)
                                .setAction(MeridianVpnService.ACTION_DISCONNECT)
                        )
                    } else {
                        connect()
                    }
                },
            )
        }

        AccessBlock(onConnect = connect)

        // Уведомление о новой версии (просьба владельца 14.09). В сборке
        // для Play — пусто, и не веткой, а отсутствием кода: см.
        // UpdateBanner.kt в наборах direct и play.
        //
        // МЕСТО ВЫБРАНО, А НЕ НАЙДЕНО: под блоком доступа, а не над
        // планетой. Планета — то, ради чего приложение открывают, и
        // двигать её ради объявления нельзя. Здесь полоса видна сразу,
        // но ничего не перехватывает и не смещает.
        UpdateBanner()

        // Строка состояния. В обычной работе ПУСТА — см. Notice.
        val notice = Notice.text.value
        if (notice.isNotEmpty()) {
            Text(
                text = notice,
                color = if (Notice.bad.value) Brand.edge else Brand.dim,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }

        // ПЕРЕКЛЮЧАТЕЛЯ ТРАНСПОРТА ЗДЕСЬ БОЛЬШЕ НЕТ (задача 50.6).
        // Режим постоянно AUTO. Сами RELAY_ONLY и DIRECT_ONLY живы в
        // Config.kt и в TransportSetting — они нужны для отладки, и
        // добраться до них можно, поменяв TRANSPORT_MODE и пересобрав.

        // ЧТО УБРАНО С ЭТОГО ЭКРАНА И ПОЧЕМУ:
        //
        //   кнопка «Проверить API» (51.3) — отладка закончена;
        //   лог (51.4) — уехал в настройки;
        //   «Отключено» / «Туннель активен» (54.3) — состояние
        //     показывает сама планета: стоит или вращается, и вторая
        //     подпись про то же самое только спорила бы с ней;
        //   версия (54.3) — уехала вниз раздела настроек, смотрят на
        //     неё раз в жизни.
        //
        // Осталось ровно три вещи: планета, доступ, шестерёнка.

        Spacer(modifier = Modifier.weight(1f))

        // Настройки: ссылки VK и логи. Значок без подписи — шестерёнка
        // не нуждается в объяснении.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            GearButton(onClick = onSettings)
        }
    }
}

/**
 * Запрос разрешения на уведомления с объяснением.
 *
 * С Android 13 POST_NOTIFICATIONS больше не выдаётся при установке. Без
 * него пропадают ТРИ вещи сразу, и первая из них обязательная:
 *
 *  - уведомление о работающем туннеле. Для службы переднего плана оно
 *    не украшение, а требование системы: VPN обязан быть виден;
 *  - экран капчи VK при подключении в фоне — открыть активность из
 *    фоновой службы нельзя, туда ведёт только нажатие на уведомление;
 *  - напоминания о конце пробного периода.
 *
 * Спрашиваем ОДИН раз за всё время и запоминаем это. Система всё равно
 * перестаёт показывать диалог после двух отказов, а мозолить глаза
 * каждым запуском незачем — дальше только через настройки.
 */
@Composable
private fun NotificationsAsk() {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(notificationsMissing(ctx) && !Access.notifAsked()) }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        TunnelLog.add(
            if (granted) "разрешение на уведомления выдано"
            else "разрешение на уведомления НЕ выдано — не будет ни значка туннеля, " +
                "ни напоминаний, ни капчи в фоне"
        )
    }

    if (!show) return

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Разрешите уведомления") },
        text = {
            Text(
                "Уведомление нужно, чтобы показывать, что туннель работает — " +
                    "этого требует система от любого VPN. Через него же приходят " +
                    "проверка VK и напоминание о конце пробного периода."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                Access.markNotifAsked()
                show = false
                if (Build.VERSION.SDK_INT >= 33) {
                    ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }) { Text("Разрешить") }
        },
        dismissButton = {
            TextButton(onClick = {
                Access.markNotifAsked()
                show = false
                TunnelLog.add("разрешение на уведомления не запрошено — отказ до диалога системы")
            }) { Text("Не сейчас") }
        },
    )
}

/** Нужно ли вообще спрашивать: до Android 13 разрешение выдаётся при установке. */
private fun notificationsMissing(ctx: Context): Boolean = try {
    Build.VERSION.SDK_INT >= 33 &&
        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
} catch (e: Throwable) {
    false
}

/**
 * Версия сборки для показа на экране.
 *
 * Берётся у системы, а не из BuildConfig: так не нужно включать
 * генерацию BuildConfig ради одной строки, и показывается ровно то,
 * что реально установлено на устройстве, — а это и был вопрос.
 */
fun appVersion(ctx: android.content.Context): String = try {
    val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    (pi.versionName ?: "?") + " (" + pi.longVersionCode + ")"
} catch (e: Throwable) {
    "?"
}

/** Экраны приложения. Стека нет — его тут не из чего строить. */
private enum class Screen { TUNNEL, SETTINGS, HASHES, LOGS, SPLIT }
