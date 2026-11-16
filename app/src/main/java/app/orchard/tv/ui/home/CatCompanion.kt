package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.Brush
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
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The desk cat's palette: the owner's black cat, rounded off for a TV screen. */
private object CatLook {
    val Fur = Color(0xFF1B1C1F)
    val FurLit = Color(0xFF444852)   // upper-left gradient highlight
    val FurSoft = Color(0xFF35373E)
    val FurEdge = Color(0xFF0A0B0D)
    val Sheen = Color(0xFF9AA3B4)    // faint breathing highlight
    val Shadow = Color(0x2E000000)   // contact shadow on the wallpaper
    val EarInner = Color(0xFF5A4049)
    val Eye = Color(0xFFE8C64E)
    val EyeRim = Color(0xFF96751C)
    val EyeHalo = Color(0x4DE8C64E)  // soft amber bloom behind the eye
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
 * A desk cat that dozes behind the right edge of the wallpaper and sneaks out when the remote is
 * used: [activityTick] is bumped on every key press in Home; the cat slides in, watches for a few
 * quiet seconds, then retreats. While it is out the remote can land on it and OK gives a hop and a
 * short line in a bubble. No network, no state outside this composable.
 *
 * [enabled] mirrors Home's gridEnabled, so the cat steps aside under overlays and while an app is
 * being moved. [onFocused] lets Home clear the corner-panel peek when the remote lands here.
 */
@Composable
fun CatCompanion(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activityTick: Long = 0L,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit = {},
    size: Dp = 138.dp,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current

    // Wake on any remote key or when the remote lands on us; doze after a quiet spell.
    var awake by remember { mutableStateOf(false) }
    var heldFocus by remember { mutableStateOf(false) }

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

    // Any remote activity wakes the cat for a few quiet seconds; staying focused keeps it out.
    LaunchedEffect(activityTick) {
        if (activityTick <= 0L) return@LaunchedEffect
        awake = true
        delay(3400)
        while (heldFocus) delay(240)
        awake = false
    }

    // Poking the cat (OK) makes it hop, meow, and say a line. A repeated poke replays from the top.
    val hop = remember { Animatable(0f) }
    var pressTick by remember { mutableIntStateOf(0) }
    var line by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pressTick) {
        if (pressTick == 0) return@LaunchedEffect
        awake = true
        line = CatLines[(pressTick - 1) % CatLines.size]
        Sounds.meow()
        if (!reduced) {
            hop.snapTo(1f)
            hop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 300f))
        }
        delay(1900)
        line = null
        delay(1600)
        if (!heldFocus) awake = false
    }

    val presence by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(180, easing = Motion.EaseOut), label = "cat-presence",
    )
    // Asleep: tucked behind the right edge with one ear showing. Awake or focused: fully in view.
    val out = awake || heldFocus
    val peekX by animateDpAsState(
        targetValue = if (out && enabled) (-10).dp else size * 0.72f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 170f),
        label = "cat-peek",
    )
    // Always focusable while Home is usable: a stable spot in the focus graph, so the remote can
    // always reach it (and pass through it to the corners) regardless of the doze animation.
    val usable = enabled

    Pressable(
        onClick = { pressTick++ },
        enabled = usable,
        focusedScale = 1.08f,
        focusRequester = focusRequester,
        modifier = modifier
            .alpha(presence)
            .offset(x = peekX)
            .onFocusChanged {
                heldFocus = it.isFocused
                onFocused(it.isFocused)
            },
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
    // Every transform must use Offset.Zero as pivot: after translate() the local origin is the
    // canvas centre, but DrawScope.scale()/rotate() default to the *canvas* centre, which would
    // fling the body away from the head.
    translate(size.width / 2f, size.height / 2f) {
        scale(k, k, pivot = Offset.Zero) {
            if (focused) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0x3AFFFFFF), Color(0x00FFFFFF)),
                        center = Offset.Zero, radius = 52f,
                    ),
                    radius = 52f,
                    center = Offset.Zero,
                )
                drawCircle(color = Color(0x88FFFFFF), radius = 45f, center = Offset.Zero, style = Stroke(width = 1.4f))
            }
            // A soft contact shadow keeps the cat sitting on the picture instead of floating.
            drawOval(color = CatLook.Shadow, topLeft = Offset(-28f, 42f), size = Size(56f, 10f))
            val squash = 1f + (breathe - 0.5f) * 0.04f
            scale(1f, squash, pivot = Offset.Zero) {
                // Tail behind the body, swishing.
                rotate((tail - 0.5f) * 24f, pivot = Offset.Zero) {
                    val tailPath = Path().apply {
                        moveTo(20f, 20f)
                        cubicTo(42f, 32f, 48f, 4f, 34f, -4f)
                        cubicTo(26f, -9f, 19f, 1f, 26f, 6f)
                    }
                    drawPath(tailPath, CatLook.Fur, style = Stroke(width = 9f))
                    drawPath(tailPath, CatLook.FurEdge, style = Stroke(width = 1f))
                }
                // Body with a soft radial fur gradient (lit from upper left).
                val bodyBrush = Brush.radialGradient(
                    listOf(CatLook.FurLit, CatLook.Fur, CatLook.FurEdge),
                    center = Offset(-12f, 12f), radius = 46f,
                )
                drawOval(bodyBrush, topLeft = Offset(-26f, -4f), size = Size(52f, 50f))
                ellipse(CatLook.FurSoft, Offset(-8f, 15f), 8f, 13f, 0.26f)
                // Collar: a thin band under the chin with a small bell, not a wide arc (which read
                // as a smile on the dark head).
                drawArc(
                    CatLook.Collar, 20f, 140f, false,
                    topLeft = Offset(-22f, 10f), size = Size(44f, 32f), style = Stroke(width = 2f),
                )
                drawCircle(CatLook.Collar, radius = 3.2f, center = Offset(0f, 24f))
                drawCircle(CatLook.FurEdge, radius = 1f, center = Offset(0f, 24f))
                // Ears.
                val earL = Path().apply { moveTo(-25f, -17f); lineTo(-27f, -41f); lineTo(-6f, -26f); close() }
                val earR = Path().apply { moveTo(25f, -17f); lineTo(27f, -41f); lineTo(6f, -26f); close() }
                drawPath(earL, CatLook.Fur); drawPath(earR, CatLook.Fur)
                val earLi = Path().apply { moveTo(-22f, -20f); lineTo(-23f, -35f); lineTo(-11f, -26f); close() }
                val earRi = Path().apply { moveTo(22f, -20f); lineTo(23f, -35f); lineTo(11f, -26f); close() }
                drawPath(earLi, CatLook.EarInner); drawPath(earRi, CatLook.EarInner)
                // Head with the same fur gradient, plus a faint sheen that breathes.
                val headBrush = Brush.radialGradient(
                    listOf(CatLook.FurLit, CatLook.Fur, CatLook.FurEdge),
                    center = Offset(-13f, -20f), radius = 50f,
                )
                drawCircle(CatLook.FurEdge, radius = 29.5f)
                drawCircle(headBrush, radius = 28.5f)
                ellipse(CatLook.Sheen, Offset(-11f + breathe * 3f, -13f), 6f, 9f, 0.12f)
                // Eyes: an amber bloom behind, then rim, eye, vertical pupil and glints.
                val eyeCy = -6f
                for (ex in listOf(-11.5f, 11.5f)) {
                    if (eyeOpen > 0.12f) {
                        val ry = 8.2f * eyeOpen
                        drawCircle(CatLook.EyeHalo, radius = 13f, center = Offset(ex, eyeCy))
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
