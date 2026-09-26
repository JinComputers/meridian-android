package org.meridianvpn.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Цвета.
 *
 * ДВА НАБОРА, И ЭТО НЕ НЕДОСМОТР.
 *
 * Исходные числа сняты из design/logo.png разбором пикселей: заливка
 * 11141A, контур 5B8AFF, меридианы 454E69. Они лежат в
 * res/values/colors.xml и идут в значок приложения — там сфера стоит на
 * собственном тёмном фоне 0A0C11, и на нём логотип верен как есть.
 *
 * На экране фон теперь ЧИСТО ЧЁРНЫЙ (задача 51.1), и логотипные числа
 * там не работают: заливка 11141A от чёрного почти неотличима, сфера
 * превращается в синее кольцо с дырой. Поэтому на экране три
 * отступления, каждое минимальное:
 *
 *   заливка   11141A -> 171C26   светлее, чтобы силуэт читался на чёрном;
 *   контур    5B8AFF -> 6F98FF   ярче, иначе на чёрном он глухой;
 *   меридианы 454E69 -> 525D80   ярче ровно настолько же, сколько заливка,
 *                                чтобы их отношение к телу сферы осталось
 *                                тем же, что в логотипе.
 *
 * Тон не тронут ни у одного: сдвинута только светлота. Логотип остаётся
 * основой, экран — его прочтением на чёрном.
 */
object Brand {
    // --- как в логотипе. Значок приложения берёт эти же числа из XML ---

    /** Заливка сферы в логотипе. */
    val sphereLogo = Color(0xFF11141A)

    /** Контур и точки в логотипе. */
    val edgeLogo = Color(0xFF5B8AFF)

    /** Меридианы в логотипе. */
    val meridianLogo = Color(0xFF454E69)

    // --- для чёрного экрана ---

    /** Фон приложения. Именно чёрный: на OLED он не горит. */
    val space = Color(0xFF000000)

    /** Заливка сферы на чёрном. */
    val sphere = Color(0xFF171C26)

    /** Контур и точки на чёрном. */
    val edge = Color(0xFF6F98FF)

    /** Меридианы на чёрном. */
    val meridian = Color(0xFF525D80)

    /** Контур и точки в белом режиме (туннель на релее, белые списки). */
    val edgeWhite = Color(0xFFFFFFFF)

    // ПРИГЛУШЁННАЯ ПЛАНЕТА — пока туннеля нет.
    //
    // Планета — единственный показатель состояния на главном экране, и
    // цвет несёт то же, что и движение: серая — связи нет, синяя —
    // есть. До этого разница между «отключено» и «подключено» была
    // только в движении, а движение идёт и во время подъёма — то есть
    // на глаз состояния не различались вовсе.
    //
    // Значения взяты из макета iOS (кот 4), чтобы клиенты выглядели
    // одинаково: серая планета, а спутник на ней синий и в обоих
    // состояниях — он и есть то, что показывает работу.
    //
    // ЗАЛИВКУ СДЕЛАЛИ ЧЁРНОЙ (23.09, дебаг), а контур и меридианы
    // остались серыми — так тело сферы сливается с фоном (Brand.space,
    // тоже чёрный), и на покое/подъёме видна только сетка контура и
    // меридианов, без заливки-«блина» под ними.

    /** Контур и полюса, пока связи нет. */
    val edgeOff = Color(0xFF3A4358)

    /** Меридианы, пока связи нет. */
    val meridianOff = Color(0xFF272D3A)

    /** Основной текст. Не чисто белый: на чёрном он режет глаз. */
    val text = Color(0xFFD7DCE8)

    /** Второстепенный текст и значки. */
    val dim = Color(0xFF7C859E)
}

/**
 * Планета из логотипа. Она же кнопка подключения.
 *
 * ПОКОЙ. Пока туннель погашен, сфера неподвижна и выглядит ровно как
 * логотип: два меридиана пересекают горизонталь на 0.29 и 0.62 радиуса
 * — числа взяты из разбора пикселей исходного файла.
 *
 * Достигается это не отдельной картинкой, а начальной фазой. Три
 * плоскости меридианов, разнесённые на 60°, при фазе 73° дают на
 * проекции |cos| = 0.29, 0.68 и 0.97; третий почти сливается с
 * контуром — ровно то, что видно в логотипе. Так покой и движение
 * рисует один и тот же код, и они не могут разойтись.
 *
 * ДВИЖЕНИЕ. Как только человек нажал, фаза растёт и меридианы едут по
 * сфере, а по контуру бежит спутник — синей точкой, той же, что на
 * полюсах.
 *
 * ТРИ СОСТОЯНИЯ РАЗЛИЧАЮТСЯ ДВУМЯ ПРИЗНАКАМИ СРАЗУ, и это не
 * излишество:
 *
 *   покой      — серая, неподвижная, спутника нет;
 *   подъём     — серая, спутник бежит БЫСТРО;
 *   туннель    — синяя, спутник идёт СПОКОЙНО.
 *
 * Раньше признак был один — движение, — а движется планета и во время
 * подъёма. На глаз «поднимаюсь» и «поднято» не различались вовсе, и
 * человек не понимал, дошло ли дело до связи. Теперь цвет отвечает за
 * «связь есть», скорость — за «работа идёт», и ни один из двух
 * признаков не несёт двойной нагрузки.
 *
 * Числа скоростей и серые цвета взяты из макета iOS кота 4: два
 * клиента одного продукта обязаны выглядеть одинаково.
 *
 * Анимация живёт только пока moving = true: LaunchedEffect с этим
 * ключом отменяется в покое, и кадры перестают запрашиваться совсем.
 * Неподвижная планета не стоит ни одного кадра.
 */
@Composable
fun Planet(
    connected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    enabled: Boolean = true,
    busy: Boolean = false,
    white: Boolean = false,
    onClick: () -> Unit = {},
) {
    // Меридианы едут и пока поднимаемся, и когда подняты. Спутник —
    // только когда туннель РЕАЛЬНО работает: движение означает «иду»,
    // спутник означает «дошёл», и смешивать их нельзя.
    val moving = connected || busy

    // Секунды с начала движения. Ноль в покое.
    var t by remember { mutableStateOf(0f) }

    LaunchedEffect(moving) {
        if (!moving) {
            t = 0f
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t = (now - start) / 1_000_000_000f }
        }
    }

    // РЯБИ ПРИ НАЖАТИИ ЗДЕСЬ НЕТ, и это не оплошность.
    //
    // Обычный clickable подсвечивает область нажатия, а область у нас
    // квадратная — Canvas. Вокруг круглой планеты вспыхивал квадрат, и
    // выглядело это поломкой, а не откликом.
    //
    // Круглую рябь можно было бы завести своей Indication, но она и не
    // нужна: отклик на нажатие даёт сама планета — она немедленно
    // начинает вращаться. Это и честнее квадрата, и заметнее.
    val presses = remember { MutableInteractionSource() }

    // ДОСТУПНОСТЬ (TalkBack). Планета — единственная кнопка подключения,
    // и незрячий пользователь обязан слышать И что это, И в каком она
    // состоянии, И что даст нажатие. Рисунок Canvas сам по себе для
    // TalkBack пуст.
    val state = when {
        !enabled -> "недоступно"
        busy -> "подключаюсь"
        connected -> "подключено"
        else -> "отключено"
    }
    val clickLabel = when {
        connected -> "отключить"
        busy -> "остановить подключение"
        else -> "подключить"
    }

    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                contentDescription = "Планета Meridian, кнопка подключения"
                stateDescription = state
            }
            .clickable(
                interactionSource = presses,
                indication = null,
                enabled = enabled,
                onClickLabel = clickLabel,
            ) { onClick() }
    ) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)

        // 0.42 от меньшей стороны. Остаток нужен спутнику: он бежит ПО
        // САМОМУ контуру, и его радиус выходит за круг наружу.
        val r = this.size.minDimension * 0.42f

        // ЦВЕТ ГОВОРИТ ТО ЖЕ, ЧТО И ДВИЖЕНИЕ. Планета горит синим
        // только когда туннель РЕАЛЬНО стоит; во время подъёма она
        // такая же чёрная внутри, как в покое. Разбор — у Brand.space.
        val sphereColor = if (connected) Brand.sphere else Brand.space
        // БЕЛЫЙ РЕЖИМ (просьба владельца 26.09): туннель держится только на
        // релее — значит, работают белые списки. Белыми делаем ТОЛЬКО контур,
        // точки и спутник; внутри сфера и меридианы прежние — целиком белая
        // планета била бы по глазам.
        val whiteNow = connected && white
        val edgeColor = when {
            whiteNow -> Brand.edgeWhite
            connected -> Brand.edge
            else -> Brand.edgeOff
        }
        val meridianColor = if (connected) Brand.meridian else Brand.meridianOff

        val edgeWidth = r * 0.045f
        val meridianWidth = r * 0.022f
        val dot = r * 0.075f

        // Фаза покоя. 1.274 рад = 73°, см. заголовок.
        val phase = REST_PHASE + t * MERIDIAN_SPEED

        // --- тело сферы ----------------------------------------------
        drawCircle(sphereColor, r, c)

        // --- меридианы ------------------------------------------------
        // Три плоскости через 60°. На проекции меридиан — эллипс с той
        // же высотой и шириной r * |cos(долгота)|.
        for (i in 0 until 3) {
            val rx = r * abs(cos(phase + i * SIXTY_DEG))
            if (rx < meridianWidth) continue // встал ребром, рисовать нечего
            drawOval(
                color = meridianColor,
                topLeft = Offset(c.x - rx, c.y - r),
                size = Size(rx * 2f, r * 2f),
                style = Stroke(width = meridianWidth),
            )
        }

        // --- контур ---------------------------------------------------
        drawCircle(edgeColor, r, c, style = Stroke(width = edgeWidth))

        // --- точки на полюсах ----------------------------------------
        drawCircle(edgeColor, dot, Offset(c.x, c.y - r))
        drawCircle(edgeColor, dot, Offset(c.x, c.y + r))

        // --- спутник: бежит ПО КОНТУРУ, всегда поверх ----------------
        //
        // РАНЬШЕ ОРБИТА БЫЛА НАКЛОННЫМ ЭЛЛИПСОМ, и половину оборота
        // спутник проходил ЗА планетой — порядок отрисовки менялся по
        // знаку синуса. Теперь орбита совпадает с контуром, «за» и
        // «перед» на ней не существует, и разделение на две половины
        // потеряло смысл: спутник всегда на виду.
        //
        // ВИДЕН И ВО ВРЕМЯ ПОДЪЁМА, и это главное изменение. Прежде он
        // означал «дошёл» и появлялся только на поднятом туннеле; за
        // подъём отвечали одни меридианы. Теперь оба состояния
        // различает СКОРОСТЬ: быстрый бег — идём, спокойный — стоим.
        // Скорость на глаз заметнее, чем наличие точки, и снимает с
        // цвета всю нагрузку.
        if (moving) {
            val speed = if (connected) SAT_SPEED_UP else SAT_SPEED_BUSY
            val orbit = t * speed
            val co = cos(orbit)
            val so = sin(orbit)
            val sat = Offset(c.x + r * co, c.y + r * so)

            // --- вспышка на встрече с полюсом ------------------------
            //
            // Орбита совпадает с контуром, а полюсные точки лежат на нём
            // же — значит дважды за оборот спутник проходит РОВНО через
            // точку. Момент этой встречи и подсвечиваем.
            //
            // Близость к полюсу считаем через |cos| орбиты, а не через
            // остаток от деления: у полюсов косинус обращается в ноль и
            // вблизи них равен самому углу до полюса. Дешевле, точнее в
            // самой точке и без возни с приведением угла.
            val toPole = abs(co)
            if (toPole < FLASH_WINDOW) {
                // k линейно растёт к встрече, свечение — по квадрату:
                // острый пик в момент совпадения и мягкий хвост. Линейное
                // выглядело бы включённой лампой, а не вспышкой.
                val k = 1f - toPole / FLASH_WINDOW
                val g = k * k
                val glow = dot * (2.4f + 2.2f * g)
                // Полюс тот, к которому идём: южный при sin > 0.
                val at = Offset(c.x, if (so > 0f) c.y + r else c.y - r)

                // Радиальный градиент, а не набор колец: спад плавный, и
                // рисуется одним вызовом. Размытия (BlurMaskFilter) здесь
                // намеренно нет — оно тянет за собой родной Paint и на
                // части устройств отключает аппаратное ускорение.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f * g),
                            Color.White.copy(alpha = 0.30f * g),
                            Color.Transparent,
                        ),
                        center = at,
                        radius = glow,
                    ),
                    radius = glow,
                    center = at,
                )
                // Ядро вспышки: сами точки на миг становятся белыми —
                // это и есть «шарики соединились».
                drawCircle(Color.White.copy(alpha = g), dot * 0.9f, at)
            }

            drawCircle(if (whiteNow) Brand.edgeWhite else Brand.edge, dot * 0.85f, sat)
        }
    }
}

/** 73° — фаза, при которой сфера выглядит как в логотипе. */
private const val REST_PHASE = 1.274f

private const val SIXTY_DEG = 1.0472f

/** Радиан в секунду. Полный оборот примерно за 19 секунд. */
private const val MERIDIAN_SPEED = 0.33f

/**
 * Радиан в секунду. Оборот спутника примерно за 8 секунд.
 *
 * Скорость на поднятом туннеле: спокойный ход, «всё идёт своим чередом».
 */
private const val SAT_SPEED_UP = 0.78f

/**
 * Радиан в секунду. Оборот примерно за 2,4 секунды — втрое быстрее.
 *
 * Скорость во время подъёма. Разница втрое видна без сравнения, бок о
 * бок эти состояния всё равно не увидеть: одно сменяет другое.
 *
 * Оба числа — из макета iOS кота 4 (2,4 с и 8 с), чтобы два клиента
 * вели себя одинаково.
 */
private const val SAT_SPEED_BUSY = 2.62f

/**
 * Ширина окна вспышки на полюсе, в единицах |cos| орбиты.
 *
 * Вблизи полюса косинус почти равен углу до него, так что 0.30 — это
 * примерно ±0,3 радиана вокруг встречи. По времени окно выходит разным
 * и это правильно: на поднятом туннеле вспышка длится около 0,8 с и
 * читается как спокойный маяк, во время подъёма — около 0,23 с, то есть
 * быстрый проблеск. Всё ускоряется вместе со спутником.
 *
 * Встречи две за оборот: раз в 4 с на поднятом, раз в 1,2 с на подъёме.
 */
private const val FLASH_WINDOW = 0.30f

/**
 * Шестерёнка настроек.
 *
 * Рисуется здесь, а не берётся значком из библиотеки: набор
 * material-icons — это отдельная зависимость, а ради одной фигуры из
 * круга и восьми зубцов её тянуть незачем.
 */
@Composable
fun GearButton(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Brand.meridian,
    onClick: () -> Unit,
) {
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = "Настройки" }
            .clickable(onClickLabel = "открыть") { onClick() }
    ) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val unit = this.size.minDimension

        // Считаем ОТ КРАЙНЕЙ ТОЧКИ, а не от кольца. Дальше всех уходит
        // кончик зубца: он равен r * 1.45 плюс половина толщины линии.
        // Раньше при r = 0.30 стороны это давало 0.485 от половины поля
        // при доступных 0.5 — то есть зубцы едва не касались края и на
        // части экранов срезались. Теперь крайняя точка — 0.40.
        val r = unit * 0.24f
        val ring = r * 0.42f
        val tooth = ring * 0.8f

        drawCircle(tint, r, c, style = Stroke(width = ring))

        // Восемь зубцов — короткие лучи наружу.
        for (i in 0 until 8) {
            val a = i * 0.7854f // 45°
            val from = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
            val to = Offset(c.x + cos(a) * (r * 1.45f), c.y + sin(a) * (r * 1.45f))
            drawLine(tint, from, to, strokeWidth = tooth, cap = StrokeCap.Round)
        }
    }
}

// ----------------------------------------------------------------------
// Значки кнопок
//
// Правило оформления с задачи 51.6: кнопки без подписей, маленькими
// значками. Рисуются здесь, в Canvas, а не берутся из material-icons —
// это отдельная зависимость, а нам нужны стрелка, крестик и ещё пара
// фигур из отрезков.
//
// Подпись остаётся там, где значок соврал бы. Такие места названы в
// отчёте по задаче 51 поимённо, а не оставлены на усмотрение.
// ----------------------------------------------------------------------

/**
 * Общая обвязка: квадратная область нажатия и своя фигура внутри.
 *
 * ПРО ОБРЕЗАНИЕ КРАЁВ (задача 55.2). Canvas режет всё, что вышло за его
 * границы, и обрезается это молча. Поэтому радиус фигуры считается
 * отсюда и с запасом:
 *
 *   RADIUS_FRACTION = 0.26 от меньшей стороны;
 *   ни одна линия не толще STROKE_MAX = 0.34 радиуса, значит наружу
 *   она выступает не больше чем на половину этого, 0.17 радиуса;
 *   итого крайняя точка — 1.17 радиуса, то есть 0.304 стороны от
 *   центра при доступных 0.5. Запаса треть.
 *
 * Плотность экрана здесь ни при чём и никогда не была: размер приходит
 * в Dp, Canvas отдаёт уже пиксели, все числа считаются от них. Ломалось
 * другое — см. STROKE_MAX и скруглённые концы у каждого значка.
 */
@Composable
fun IconBtn(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    enabled: Boolean = true,
    tint: Color = Brand.dim,
    draw: androidx.compose.ui.graphics.drawscope.DrawScope.(Color, Float) -> Unit,
) {
    Canvas(modifier = modifier.size(size)) {
        draw(
            if (enabled) tint else tint.copy(alpha = 0.35f),
            this.size.minDimension * RADIUS_FRACTION,
        )
    }
}

/** Радиус фигуры от меньшей стороны. Запас на толщину линий — в IconBtn. */
private const val RADIUS_FRACTION = 0.26f

/** Толще этого линии в значках не бывают. Доля радиуса. */
private const val STROKE_MAX = 0.34f

/**
 * Назад: стрелка влево.
 *
 * КОНЦЫ СКРУГЛЕНЫ, и это не украшение. В кончике стрелки сходятся три
 * линии; с обычными обрубленными концами каждая обрывается ровно в
 * точке схождения, и на их стыке остаётся выемка — стрелка выглядит
 * срезанной по кончику. Скруглённый конец достраивает полукруг
 * радиусом в половину толщины, и три линии сливаются в остриё.
 *
 * Тот же приём у всех значков ниже, где линии сходятся под углом.
 */
@Composable
fun BackButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconBtn(modifier = modifier.clickable { onClick() }) { tint, r ->
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * STROKE_MAX
        val cap = StrokeCap.Round
        drawLine(tint, Offset(c.x + r, c.y), Offset(c.x - r, c.y), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x - r, c.y), Offset(c.x - r * 0.1f, c.y - r * 0.7f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x - r, c.y), Offset(c.x - r * 0.1f, c.y + r * 0.7f), strokeWidth = w, cap = cap)
    }
}

/** Поделиться: стрелка вверх из основания. */
@Composable
fun ShareButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconBtn(modifier = modifier.clickable { onClick() }) { tint, r ->
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * 0.30f
        val cap = StrokeCap.Round
        drawLine(tint, Offset(c.x, c.y + r * 0.55f), Offset(c.x, c.y - r), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x, c.y - r), Offset(c.x - r * 0.55f, c.y - r * 0.4f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x, c.y - r), Offset(c.x + r * 0.55f, c.y - r * 0.4f), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x - r, c.y + r * 0.3f), Offset(c.x - r, c.y + r), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x + r, c.y + r * 0.3f), Offset(c.x + r, c.y + r), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x - r, c.y + r), Offset(c.x + r, c.y + r), strokeWidth = w, cap = cap)
    }
}

/**
 * Удалить: крестик. На мелком размере он читается лучше корзины.
 *
 * Радиус здесь УМЕНЬШЕН на корень из двух: у крестика концы лежат по
 * диагонали, и расстояние до них от центра в 1.41 раза больше, чем у
 * фигур, построенных по осям. Без поправки именно он упирался бы в
 * край первым.
 */
@Composable
fun DeleteButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconBtn(modifier = modifier.clickable { onClick() }) { tint, r0 ->
        val r = r0 * 0.707f
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * 0.30f
        val cap = StrokeCap.Round
        drawLine(tint, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), strokeWidth = w, cap = cap)
        drawLine(tint, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), strokeWidth = w, cap = cap)
    }
}

/** Ссылки VK: звено цепи, двумя дугами. */
@Composable
fun LinkIcon(modifier: Modifier = Modifier, tint: Color = Brand.edge) {
    IconBtn(modifier = modifier, tint = tint) { c1, r ->
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * 0.26f
        drawCircle(c1, r * 0.55f, Offset(c.x - r * 0.45f, c.y), style = Stroke(width = w))
        drawCircle(c1, r * 0.55f, Offset(c.x + r * 0.45f, c.y), style = Stroke(width = w))
    }
}

/** Приложения: четыре плитки сеткой. */
@Composable
fun AppsIcon(modifier: Modifier = Modifier, tint: Color = Brand.edge) {
    IconBtn(modifier = modifier, tint = tint) { c1, r ->
        val c = Offset(size.width / 2f, size.height / 2f)
        val side = r * 0.7f
        val gap = r * 0.18f
        for (dx in intArrayOf(-1, 1)) {
            for (dy in intArrayOf(-1, 1)) {
                drawRect(
                    color = c1,
                    topLeft = Offset(
                        c.x + dx * (gap + side) - side / 2f,
                        c.y + dy * (gap + side) - side / 2f,
                    ),
                    size = Size(side, side),
                    style = Stroke(width = r * 0.22f),
                )
            }
        }
    }
}

/** Логи: три строки текста. */
@Composable
fun LogIcon(modifier: Modifier = Modifier, tint: Color = Brand.edge) {
    IconBtn(modifier = modifier, tint = tint) { c1, r ->
        val c = Offset(size.width / 2f, size.height / 2f)
        val w = r * 0.26f
        for (i in -1..1) {
            val y = c.y + i * r * 0.6f
            val right = if (i == 1) c.x + r * 0.3f else c.x + r
            drawLine(c1, Offset(c.x - r, y), Offset(right, y), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}
