package org.meridianvpn.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Одна строка списка. Значок уже разобран: рисовать его на главном потоке дорого. */
private data class AppRow(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val icon: ImageBitmap?,
)

/**
 * Экран раздельного туннелирования.
 *
 * ЧТО ВИДНО, А ЧТО НЕТ. Список берётся запросом приложений с ярлыком
 * запуска, а не через QUERY_ALL_PACKAGES. Причина не техническая:
 * QUERY_ALL_PACKAGES в Play — отдельно обосновываемое разрешение, и за
 * него снимают, если обоснование не примут. Ярлычный запрос обходится
 * разделом <queries> в манифесте и никаких разрешений не требует.
 *
 * Цена: приложения БЕЗ ярлыка сюда не попадут — службы, часть системных
 * компонентов. Для раздельного туннелирования это почти всегда неважно,
 * но знать об этом надо.
 */
@Composable
fun SplitScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var withSystem by remember { mutableStateOf(false) }

    val mode = SplitTunnel.mode.value
    val chosen = SplitTunnel.chosen.value
    val connected = TunnelState.connected.value

    LaunchedEffect(Unit) {
        // Удалённые из системы уходят из списка молча (52.7).
        SplitTunnel.prune(ctx)
        rows = withContext(Dispatchers.IO) { loadApps(ctx) }
        loading = false
    }

    // ПОРЯДОК СВЕРХУ ВНИЗ: заголовок, список, всё управление.
    //
    // Управление переехало ВНИЗ целиком, вместе с поиском. Список тут в
    // сотню строк, и пока переключатели стояли над ним, к ним
    // приходилось прокручиваться обратно — а трогают их чаще, чем
    // кажется: выбрал приложения, потом сообразил, что режим не тот.
    //
    // Внизу они ещё и там, куда достаёт большой палец. Поиск отправился
    // туда же: набирать его с клавиатурой, закрывающей пол-экрана,
    // удобнее рядом с ней, а не под заголовком.
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Text("Раздельное туннелирование", color = Brand.text, fontSize = 17.sp)
        }

        if (loading) {
            Text(
                "читаю список приложений…",
                color = Brand.dim,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        val q = query.trim().lowercase()
        val shown = rows.filter { r ->
            (withSystem || !r.system) &&
                (q.isEmpty() || r.label.lowercase().contains(q) || r.pkg.lowercase().contains(q))
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            items(shown, key = { it.pkg }) { r ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { SplitTunnel.toggle(r.pkg) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (r.icon != null) {
                        Image(
                            bitmap = r.icon,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(r.label, color = Brand.text, fontSize = 15.sp)
                        Text(r.pkg, color = Brand.dim, fontSize = 11.sp)
                    }
                    Checkbox(
                        checked = r.pkg in chosen,
                        onCheckedChange = { SplitTunnel.toggle(r.pkg) },
                    )
                }
                HorizontalDivider(color = Brand.sphere)
            }
        }

        // --- НИЖНЯЯ ПАНЕЛЬ УПРАВЛЕНИЯ --------------------------------
        //
        // Отделена от списка заливкой, а не только чертой: список под
        // ней прокручивается, и без фона строки просвечивали бы сквозь
        // управление.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brand.sphere)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Предупреждение о живом туннеле (52.6). Список применяется
            // в establish(), у поднятого туннеля его не поменять — это
            // свойство Android. Рвать работающий туннель без спроса
            // нельзя, поэтому решает человек.
            if (connected && SplitTunnel.changedSinceConnect()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Туннель уже поднят — изменения применятся при следующем подключении",
                        color = Brand.edge,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReconnect) { Text("Сейчас") }
                }
            }

            // ПОИСК БЕЗ ПОДПИСИ СВЕРХУ И БЕЗ РАМКИ ПОЛНОЙ ВЫСОТЫ.
            //
            // OutlinedTextField с label занимает около 56 точек: сама
            // строка плюс место под подпись, которая уезжает наверх при
            // наборе. В нижней панели это дорого — место отбирается у
            // списка приложений, ради которого экран и существует.
            //
            // Подсказка внутри поля говорит ровно то же и исчезает,
            // когда в ней больше нет нужды. Высота — 40 точек.
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brand.space)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                singleLine = true,
                textStyle = TextStyle(color = Brand.text, fontSize = 14.sp),
                cursorBrush = SolidColor(Brand.edge),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("Поиск по названию или пакету", color = Brand.dim, fontSize = 14.sp)
                    }
                    inner()
                },
            )

            // Режимы В ОДИН РЯД, две половины бок о бок.
            //
            // Стояли столбиком, пока подписи были длинными («Только
            // выбранные — через туннель»): в ряд они переносились на
            // две-три строки, и панель занимала треть экрана. Подписи
            // укоротили до «Через туннель» / «Мимо туннеля» — два
            // коротких режима встают в одну строку и отдают списку
            // приложений ещё одну строку высоты.
            //
            // ОТКУДА БРАЛИСЬ ПРОМЕЖУТКИ ВОКРУГ КРУЖКОВ. RadioButton у
            // Material3 сам обкладывает себя до 48 точек — обязательный
            // размер цели касания. Снимаем требование и держим высоту
            // сами; целью касания остаётся ВСЯ половина: clickable висит
            // на Row с weight(1f), 40 точек на неё — больше самого кружка.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (m in SplitTunnel.Mode.entries) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .clickable { SplitTunnel.setMode(m) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = { SplitTunnel.setMode(m) })
                            // Пояснений под режимами нет (54.3): названия
                            // сами говорят, что происходит с выбранными.
                            Text(SplitTunnel.title(m), color = Brand.text, fontSize = 14.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ПОДПИСЬ ЗДЕСЬ НУЖНА. Значок не скажет «системные»: это
                // не действие, а признак отбора, и угадать его по
                // картинке нельзя. Правило 51.6 про кнопки действия.
                Switch(checked = withSystem, onCheckedChange = { withSystem = it })
                Text("системные", color = Brand.dim, fontSize = 13.sp)

                Text(
                    "выбрано ${chosen.size}",
                    color = Brand.dim,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                if (chosen.isNotEmpty()) {
                    TextButton(onClick = { SplitTunnel.clear() }) { Text("сбросить") }
                }
            }

            // КНОПКА НАЗЫВАЕТСЯ «ГОТОВО», А НЕ «СОХРАНИТЬ», и это не
            // придирка к слову.
            //
            // Сохранять нечего: каждое нажатие на приложение и на режим
            // записывается сразу, а предупреждение о живом туннеле
            // считается от уже записанного. Кнопка «Сохранить» обещала
            // бы, что до неё ничего не применилось, — и человек, выйдя
            // назад без неё, решил бы, что отменил. Он бы ошибся, а цена
            // такой ошибки — банк в туннеле вместо банка мимо него.
            //
            // Нужен настоящий черновик с отменой — это отдельная работа,
            // и делать её надо целиком, а не переименованием кнопки.
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.edge),
            ) { Text("Готово") }
        }
    }
}

/** Размер значка в пикселях. Ограничен намеренно: их тут сотня-две. */
private const val ICON_PX = 96

/**
 * Читает список приложений. ТОЛЬКО С ФОНОВОГО ПОТОКА.
 *
 * Своё приложение из списка исключается совсем (52.5): выбрать его
 * нельзя, потому что оно обязано остаться вне туннеля при любом режиме.
 * Показать и не дать нажать было бы честнее на вид, но породило бы
 * вопрос «почему», на который в интерфейсе нет места.
 */
private fun loadApps(ctx: Context): List<AppRow> {
    val pm = ctx.packageManager
    val self = ctx.packageName
    return try {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != self }
            .map { info ->
                AppRow(
                    pkg = info.packageName,
                    label = try {
                        pm.getApplicationLabel(info).toString()
                    } catch (e: Throwable) {
                        info.packageName
                    },
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = iconOf(pm, info),
                )
            }
            .sortedBy { it.label.lowercase() }
    } catch (e: Throwable) {
        TunnelLog.add("список приложений не прочитался: ${e.message}")
        emptyList()
    }
}

/**
 * Значок приложения в вид, понятный Compose.
 *
 * Через Bitmap вручную, а не библиотекой загрузки картинок: ради одного
 * преобразования тянуть зависимость незачем, а Drawable сам по себе
 * Compose не рисует.
 */
private fun iconOf(pm: PackageManager, info: ApplicationInfo): ImageBitmap? = try {
    val d = info.loadIcon(pm)
    val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    d.setBounds(0, 0, ICON_PX, ICON_PX)
    d.draw(canvas)
    bmp.asImageBitmap()
} catch (e: Throwable) {
    null
}
