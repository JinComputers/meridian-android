package org.meridianvpn.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Стеклянный» дизайн (эталон — веб-панель роутера кота 2, 30.09): тёмно-синий
 * фон с мягкой подсветкой, полупрозрачные карточки с тонкой светлой рамкой,
 * кнопки-таблетки.
 */
object Glass {
    val bgTop = Color(0xFF0A0D1C)
    val bgBottom = Color(0xFF05060A)
    val glowBlue = Color(0x1A2E4BD8)
    val glowViolet = Color(0x165A2E9C)

    val cardFill = Color(0x0AFFFFFF)
    val cardBorder = Color(0x1FFFFFFF)
    val pillFillTop = Color(0xFF1C2130)
    val pillFillBottom = Color(0xFF0E1119)
    val pillBorder = Color(0x2EFFFFFF)

    val cardShape = RoundedCornerShape(26.dp)
    val pillShape = RoundedCornerShape(50)
}

/** Фон экрана: вертикальный градиент и две подсветки (синяя слева сверху, фиолетовая справа). */
@Composable
fun GlassBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Glass.bgTop, Glass.bgBottom)))
            .background(
                Brush.radialGradient(
                    listOf(Glass.glowBlue, Color.Transparent),
                    center = Offset(0f, 0f),
                    radius = 1400f,
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(Glass.glowViolet, Color.Transparent),
                    center = Offset(1400f, 900f),
                    radius = 1200f,
                )
            ),
        content = content,
    )
}

/** Стеклянная карточка: полупрозрачная заливка, тонкая светлая рамка, крупное скругление. */
fun Modifier.glassCard(): Modifier = this
    .clip(Glass.cardShape)
    .background(GlassVolume.cardFill)
    .border(1.dp, GlassVolume.cardBorder, Glass.cardShape)

/** Кнопка-таблетка: стеклянная заливка градиентом и светлая рамка. */
@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .glassPress(enabled = enabled) { onClick() }
            .height(height)
            .clip(Glass.pillShape)
            .background(GlassVolume.fill)
            .border(1.dp, GlassVolume.border, Glass.pillShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Brand.dim,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}

/**
 * Сегментный переключатель-таблетка (как «Чёрный | Белый» и «VK | DION» на
 * панели): выбранный сегмент — светлая стеклянная таблетка, остальные прозрачные.
 */
@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(Glass.pillShape)
            .background(Color(0x1A000000))
            .border(1.dp, Glass.cardBorder, Glass.pillShape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, title ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .glassPress(enabled = enabled) { onSelect(i) }
                    .clip(Glass.pillShape)
                    .then(
                        if (on) Modifier
                            .background(GlassVolume.fill)
                            .border(1.dp, GlassVolume.border, Glass.pillShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    color = if (on) Color.White else Brand.dim,
                    fontSize = 15.sp,
                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal,
                )
            }
        }
    }
}

/** Карточка на всю ширину с внутренним отступом. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxWidth().glassCard().padding(16.dp)) { content() }
}

/** Круглая стеклянная кнопка-значок (назад, поделиться, настройки). */
fun Modifier.glassCircle(): Modifier = this
    .clip(androidx.compose.foundation.shape.CircleShape)
    .background(GlassVolume.fill)
    .border(1.dp, GlassVolume.border, androidx.compose.foundation.shape.CircleShape)

/**
 * Объём как на эталоне: рамка светлая сверху и гаснет книзу, заливка со
 * светлым отсветом у верхнего края и темнеет к низу. Равномерная заливка
 * выглядела плоско.
 */
object GlassVolume {
    // Полупрозрачная: кнопка в тон фону, чуть светлее, а не тёмное пятно.
    val fill = Brush.verticalGradient(
        0f to Color(0x30FFFFFF),
        0.18f to Color(0x17FFFFFF),
        1f to Color(0x0AFFFFFF),
    )
    val border = Brush.verticalGradient(
        0f to Color(0x66FFFFFF),
        0.5f to Color(0x24FFFFFF),
        1f to Color(0x0FFFFFFF),
    )
    val cardFill = Brush.verticalGradient(
        0f to Color(0x14FFFFFF),
        0.25f to Color(0x08FFFFFF),
        1f to Color(0x03FFFFFF),
    )
    val cardBorder = Brush.verticalGradient(
        0f to Color(0x40FFFFFF),
        0.6f to Color(0x14FFFFFF),
        1f to Color(0x0AFFFFFF),
    )
}

/**
 * Нажатие с анимацией: кнопка плавно «вдавливается» (96%) и возвращается.
 * Заменяет clickable у стеклянных кнопок.
 */
fun Modifier.glassPress(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val pressed by source.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.6f,
            stiffness = 700f,
        ),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = source,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
        ) { onClick() }
}

/**
 * Акцентная кнопка-таблетка (эталон — «Добавить и проверить» на Маке): тёмно-синяя
 * заливка с отсветом сверху, синяя светящаяся рамка, голубой полужирный текст.
 * Для главного действия на экране: «Обновить», «Установить».
 */
@Composable
fun GlassAccentButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .glassPress(enabled = enabled) { onClick() }
            .height(height)
            .clip(Glass.pillShape)
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF2A4C82),
                    0.2f to Color(0xFF1B355E),
                    1f to Color(0xFF0E1E3A),
                )
            )
            .border(
                1.dp,
                Brush.verticalGradient(
                    0f to Color(0xCC5B8FE8),
                    0.6f to Color(0x663F72CC),
                    1f to Color(0x333F72CC),
                ),
                Glass.pillShape,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color(0xFFA9CBFF) else Brand.dim,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
    }
}

/**
 * Стеклянный переключатель (эталон — панель кота 2): дорожка-таблетка со светлой
 * рамкой; включён — дорожка зелёная с отсветом, белый шарик справа; выключен —
 * тёмное стекло, приглушённый шарик слева. Шарик переезжает плавно.
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val offset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "switch",
    )
    Box(
        modifier = modifier
            .glassPress(enabled = enabled) { onCheckedChange(!checked) }
            .size(width = 50.dp, height = 30.dp)
            .clip(Glass.pillShape)
            .background(
                if (checked) Brush.verticalGradient(
                    0f to Color(0xFF4FB477),
                    0.25f to Color(0xFF2F8F57),
                    1f to Color(0xFF1C6B3F),
                ) else GlassVolume.fill
            )
            .border(1.dp, GlassVolume.border, Glass.pillShape),
    ) {
        Box(
            modifier = Modifier
                .padding(start = offset, top = 2.dp)
                .size(width = 26.dp, height = 26.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (checked) Color.White else Color(0xFF8A92A8)),
        )
    }
}

/**
 * Стеклянная галочка: скруглённый квадрат со светлой рамкой; отмечена —
 * синяя акцентная заливка и белая галка.
 */
@Composable
fun GlassCheck(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    androidx.compose.foundation.Canvas(
        modifier = modifier
            .glassPress { onCheckedChange(!checked) }
            .size(26.dp)
            .clip(shape)
            .background(
                if (checked) Brush.verticalGradient(
                    0f to Color(0xFF6F98FF),
                    1f to Color(0xFF3F66C8),
                ) else GlassVolume.fill
            )
            .border(1.dp, GlassVolume.border, shape),
    ) {
        if (checked) {
            val w = size.minDimension
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.26f, w * 0.52f)
                lineTo(w * 0.43f, w * 0.69f)
                lineTo(w * 0.75f, w * 0.33f)
            }
            drawPath(
                p, Color.White,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = w * 0.11f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                ),
            )
        }
    }
}
