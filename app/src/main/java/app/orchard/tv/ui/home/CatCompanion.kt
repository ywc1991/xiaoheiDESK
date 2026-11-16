package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The desk cat's palette: the owner's black cat, rounded off for a TV screen. */
private object CatLook {
    val Fur = Color(0xFF1B1C1F)
    val FurSoft = Color(0xFF35373E)
    val FurEdge = Color(0xFF0A0B0D)
    val EarInner = Color(0xFF5A4049)
    val Eye = Color(0xFFE8C64E)
    val EyeRim = Color(0xFF96751C)
    val Pupil = Color(0xFF0E0F12)
    val EyeGlint = Color(0xFFFFFFFF)
    val Nose = Color(0xFFCE8E96)
    val Whisker = Color(0xD9F2F2F7)
    val Mouth = Color(0xB3F2F2F7)
    val Collar = Color(0xFFDCAF4C)
}

private val CatLines = listOf(
    "喵～", "摸摸我呀", "今天也陪着你", "要出去玩吗?",
    "呼噜呼噜…", "看什么呢?", "喵呜!", "最喜欢这里了",
)

/**
 * A focusable desk cat drawn with Canvas, living on the wallpaper with no network or state outside
 * this composable. Idle it breathes, blinks and swishes its tail; focus lights a soft halo and
 * perks the ears; OK makes it hop and say a short line in a bubble. Keeps the real cat's amber eyes,
 * upright ears and white whiskers in a chunky shape that reads at TV distance.
 *
 * [enabled] mirrors Home's gridEnabled, so the cat steps aside under overlays and while an app is
 * being moved. [onFocused] lets Home clear the corner-panel peek when the remote lands here.
 */
@Composable
fun CatCompanion(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit = {},
    size: Dp = 138.dp,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current

    // Pure phase drivers for the idle motion; frozen at their midpoint when motion is reduced.
    val infinite = rememberInfiniteTransition(label = "cat-idle")
    val breath by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = Motion.EaseInOut), RepeatMode.Reverse),
        label = "cat-breath",
    )
    val tail by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = Motion.EaseInOut), RepeatMode.Reverse),
        label = "cat-tail",
    )
    val blink by infinite.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4600), RepeatMode.Restart),
        label = "cat-blink",
    )

    // Hop is an Animatable rather than a boolean so a repeated press always replays from the top:
    // snap to "up", then spring back down with a little overshoot.
    val hop = remember { Animatable(0f) }
    var pressTick by remember { mutableIntStateOf(0) }
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pressTick) {
        if (pressTick == 0) return@LaunchedEffect
        line = CatLines[(pressTick - 1) % CatLines.size]
        if (!reduced) {
            hop.snapTo(1f)
            hop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 300f))
        }
        delay(1900)
        line = null
    }

    val presence by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(180, easing = Motion.EaseOut), label = "cat-presence",
    )

    Pressable(
        onClick = { pressTick++ },
        enabled = enabled,
        focusedScale = 1.08f,
        focusRequester = focusRequester,
        modifier = modifier
            .alpha(presence)
            .onFocusChanged { onFocused(it.isFocused) },
    ) { state ->
        Box(contentAlignment = Alignment.TopCenter) {
            val bubble = line
            if (bubble != null) {
                androidx.compose.foundation.text.BasicText(
                    bubble,
                    style = TextStyle(
                        fontFamily = app.orchard.tv.ui.theme.InterText,
                        fontWeight = FontWeight.W600,
                        fontSize = app.orchard.tv.ui.theme.Type.Caption.fontSize,
                        color = Color(0xFFF7F7FA),
                    ),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                0,
                                (-(30f + hop.value * 14f) * density.density).roundToInt(),
                            )
                        }
                        .drawBehind {
                            val padX = 11.dp.toPx()
                            val padY = 6.dp.toPx()
                            val w = this.size.width + padX * 2
                            val h = this.size.height + padY * 2
                            drawRoundRect(
                                color = Color(0xE61B1C1F),
                                topLeft = Offset(-padX, -padY),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(14.dp.toPx()),
                            )
                            drawRoundRect(
                                color = Color(0x33FFFFFF),
                                topLeft = Offset(-padX, -padY),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(14.dp.toPx()),
                                style = Stroke(width = 1f),
                            )
                        },
                )
            }
            Canvas(
                Modifier
                    .size(size)
                    .offset {
                        IntOffset(0, (-(hop.value * 24f) * density.density).roundToInt())
                    },
            ) {
                drawCat(
                    focused = state.focused,
                    pressed = state.pressed,
                    breathe = if (reduced) 0.5f else breath,
                    tail = if (reduced) 0.5f else tail,
                    eyeOpen = if (reduced) 1f else eyelid(blink),
                )
            }
        }
    }
}

/** Eyelid height: 1 fully open, ~0.07 closed. One quick blink per cycle of [p] (0..1). */
private fun eyelid(p: Float): Float = when {
    p < 0.90f -> 1f
    p < 0.935f -> 1f - ((p - 0.90f) / 0.035f) * 0.93f
    p < 0.965f -> 0.07f
    p < 1f -> 0.07f + ((p - 0.965f) / 0.035f) * 0.93f
    else -> 1f
}.coerceIn(0.07f, 1f)

private fun DrawScope.drawCat(focused: Boolean, pressed: Boolean, breathe: Float, tail: Float, eyeOpen: Float) {
    val k = size.minDimension / 100f // draw in a local 100x100 space
    translate(size.width / 2f, size.height / 2f) {
        scale(k) {
            if (focused) {
                drawCircle(color = Color(0x2EFFFFFF), radius = 47f)
                drawCircle(color = Color(0x77FFFFFF), radius = 46f, style = Stroke(width = 1.3f))
            }
            val squash = 1f + (breathe - 0.5f) * 0.04f
            scale(1f, squash) {
                // Tail behind the body, swishing.
                rotate((tail - 0.5f) * 24f) {
                    val tailPath = Path().apply {
                        moveTo(20f, 20f)
                        cubicTo(42f, 32f, 48f, 4f, 34f, -4f)
                        cubicTo(26f, -9f, 19f, 1f, 26f, 6f)
                    }
                    drawPath(tailPath, CatLook.Fur, style = Stroke(width = 9f))
                    drawPath(tailPath, CatLook.FurEdge, style = Stroke(width = 1f))
                }
                // Body.
                ellipse(CatLook.Fur, Offset(0f, 21f), 26f, 25f)
                ellipse(CatLook.FurSoft, Offset(-8f, 15f), 8f, 13f, 0.30f)
                drawArc(
                    CatLook.Collar, 15f, 150f, false,
                    topLeft = Offset(-20f, 7f), size = Size(40f, 30f), style = Stroke(width = 2.6f),
                )
                // Ears.
                val earL = Path().apply { moveTo(-25f, -17f); lineTo(-27f, -41f); lineTo(-6f, -26f); close() }
                val earR = Path().apply { moveTo(25f, -17f); lineTo(27f, -41f); lineTo(6f, -26f); close() }
                drawPath(earL, CatLook.Fur); drawPath(earR, CatLook.Fur)
                val earLi = Path().apply { moveTo(-22f, -20f); lineTo(-23f, -35f); lineTo(-11f, -26f); close() }
                val earRi = Path().apply { moveTo(22f, -20f); lineTo(23f, -35f); lineTo(11f, -26f); close() }
                drawPath(earLi, CatLook.EarInner); drawPath(earRi, CatLook.EarInner)
                // Head.
                drawCircle(CatLook.FurEdge, radius = 29.5f)
                drawCircle(CatLook.Fur, radius = 28.5f)
                ellipse(CatLook.FurSoft, Offset(-9f, -3f), 9f, 7f, 0.28f)
                // Eyes.
                val eyeCy = -6f
                for (ex in listOf(-11.5f, 11.5f)) {
                    if (eyeOpen > 0.12f) {
                        val ry = 8.2f * eyeOpen
                        drawCircle(CatLook.EyeRim, radius = 8.7f, center = Offset(ex, eyeCy))
                        ellipse(CatLook.Eye, Offset(ex, eyeCy), 7.3f, ry)
                        ellipse(CatLook.Pupil, Offset(ex, eyeCy), 2.0f, ry * 0.84f)
                        drawCircle(CatLook.EyeGlint, radius = 1.9f, center = Offset(ex - 2.6f, eyeCy - 3.2f))
                        drawCircle(CatLook.EyeGlint, radius = 0.9f, center = Offset(ex + 2.4f, eyeCy + 2.6f), alpha = 0.7f)
                    } else {
                        drawArc(
                            CatLook.EyeRim, 0f, 180f, false,
                            topLeft = Offset(ex - 7f, eyeCy - 3f), size = Size(14f, 6f),
                            style = Stroke(width = 2.2f),
                        )
                    }
                }
                // Nose + mouth.
                val nose = Path().apply { moveTo(0f, 2f); lineTo(-2.6f, 4.7f); lineTo(2.6f, 4.7f); close() }
                drawPath(nose, CatLook.Nose)
                drawArc(CatLook.Mouth, 0f, 180f, false, topLeft = Offset(-5f, 4f), size = Size(5f, 5f), style = Stroke(1.3f))
                drawArc(CatLook.Mouth, 0f, 180f, false, topLeft = Offset(0f, 4f), size = Size(5f, 5f), style = Stroke(1.3f))
                // Whiskers.
                for (side in listOf(-1f, 1f)) {
                    for (i in -1..1) {
                        val y0 = 3f + i * 2.6f
                        drawLine(
                            CatLook.Whisker,
                            start = Offset(side * 7f, y0),
                            end = Offset(side * 28f, y0 + i * 4.4f),
                            strokeWidth = 0.95f,
                        )
                    }
                }
                if (pressed) {
                    ellipse(CatLook.Fur, Offset(-12f, 41f), 7f, 5f)
                    ellipse(CatLook.Fur, Offset(12f, 41f), 7f, 5f)
                }
            }
        }
    }
}

private fun DrawScope.ellipse(color: Color, center: Offset, rx: Float, ry: Float, a: Float = 1f) {
    drawOval(
        color = color.copy(alpha = color.alpha * a),
        topLeft = Offset(center.x - rx, center.y - ry),
        size = Size(rx * 2f, ry * 2f),
    )
}
