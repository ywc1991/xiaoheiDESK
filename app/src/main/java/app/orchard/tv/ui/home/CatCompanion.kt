package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.system.Sounds
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.InterText
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val CatLines = listOf(
    "喵～", "摸摸我呀", "今天也陪着你", "要出去玩吗?",
    "呼噜呼噜…", "看什么呢?", "喵呜!", "最喜欢这里了",
)

/** Sprite sheet made from the owner's black-cat video: [COLS] columns of [FW]x[FH] frames. */
private const val CAT_COLS = 8
private const val CAT_FRAMES = 74
private const val CAT_FPS = 8

/**
 * A desk cat that dozes behind the right edge of the wallpaper and sneaks out when the remote is
 * used: [activityTick] is bumped on every key press in Home; the cat slides in, watches for a few
 * quiet seconds, then retreats. The cat itself is a real, blinking/breathing animation cut out of
 * the owner's cat video (a sprite sheet), so it looks exactly like their cat rather than a drawing.
 *
 * [enabled] mirrors Home's gridEnabled, so the cat steps aside under overlays. [onFocused] lets
 * Home clear any focus bookkeeping when the remote lands here. The cat stays in the focus graph so
 * the remote can reach it and pass through it to the top corners.
 */
@Composable
fun CatCompanion(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activityTick: Long = 0L,
    focusRequester: FocusRequester? = null,
    onFocused: (Boolean) -> Unit = {},
    size: Dp = 148.dp,
) {
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current

    // The cat sprite sheet is decoded once and shared; drawing a frame is just a source rect.
    val sheet = ImageBitmap.imageResource(R.drawable.cat_sheet)
    // One cell of the sheet. Frames must be drawn at their own aspect ratio — squeezing the 240x213
    // cell into a square box made the cat's face 13% too wide.
    val cellW = sheet.width / CAT_COLS
    val cellH = sheet.height / ((CAT_FRAMES + CAT_COLS - 1) / CAT_COLS)
    val cellAspect = cellH.toFloat() / cellW.toFloat()

    // Which animation frame is showing, advanced on a timer while Home is usable.
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled, reduced) {
        if (reduced) {
            frame = 0
            return@LaunchedEffect
        }
        while (enabled) {
            delay(1000L / CAT_FPS)
            frame = (frame + 1) % CAT_FRAMES
        }
    }

    // Wake on any remote key or when the remote lands on us; doze after a quiet spell.
    var awake by remember { mutableStateOf(false) }
    var heldFocus by remember { mutableStateOf(false) }
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
    val out = awake || heldFocus
    val peekX by animateDpAsState(
        targetValue = if (out && enabled) (-10).dp else size * 0.72f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 170f),
        label = "cat-peek",
    )
    val usable = enabled

    // The owner asked for the focus ring on the cat to be invisible: the cat itself is the
    // highlight. It still slides out and stays out while focused (heldFocus), and still squashes
    // a little when pressed, so the remote never feels lost on it.
    Pressable(
        onClick = { pressTick++ },
        enabled = usable,
        focusedScale = 1f,
        focusRequester = focusRequester,
        modifier = modifier
            .alpha(presence)
            .offset(x = peekX)
            .onFocusChanged {
                heldFocus = it.isFocused
                onFocused(it.isFocused)
            },
    ) { _ ->
        Box(contentAlignment = Alignment.TopCenter) {
            val bubble = line
            if (bubble != null) {
                androidx.compose.foundation.text.BasicText(
                    bubble,
                    style = TextStyle(
                        fontFamily = InterText,
                        fontWeight = FontWeight.W600,
                        fontSize = Type.Caption.fontSize,
                        color = Color(0xFFF7F7FA),
                    ),
                    modifier = Modifier
                        .offset {
                            IntOffset(0, (-(30f + hop.value * 14f) * density.density).roundToInt())
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
                    .size(width = size, height = size * cellAspect)
                    .offset {
                        IntOffset(0, (-(hop.value * 24f) * density.density).roundToInt())
                    },
            ) {
                // NB: the composable's `size: Dp` parameter wins name resolution inside this
                // lambda, so always reach the canvas extent through `this.size`.
                val canvasW = this.size.width
                val canvasH = this.size.height
                val f = frame.coerceIn(0, CAT_FRAMES - 1)
                val srcX = (f % CAT_COLS) * cellW
                val srcY = (f / CAT_COLS) * cellH
                // Contact shadow under the cat. Its bottom already fades out in the sheet, so a
                // faint oval here is what plants it on the wallpaper instead of floating.
                drawOval(
                    Color(0x30000000),
                    topLeft = Offset(canvasW * 0.20f, canvasH * 0.90f),
                    size = Size(canvasW * 0.60f, canvasH * 0.07f),
                )
                drawImage(
                    image = sheet,
                    srcOffset = IntOffset(srcX, srcY),
                    srcSize = IntSize(cellW, cellH),
                    dstSize = IntSize(canvasW.roundToInt(), canvasH.roundToInt()),
                )
            }
        }
    }
}
