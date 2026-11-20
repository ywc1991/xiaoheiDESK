package app.orchard.tv.ui.home

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import app.orchard.tv.MainActivity
import app.orchard.tv.system.SystemScreensaver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.components.Dissolve
import app.orchard.tv.ui.components.diagLayer
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.Aerial
import app.orchard.tv.wallpaper.AerialSet
import androidx.compose.runtime.mutableStateListOf
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.ArtGallery
import app.orchard.tv.wallpaper.Images
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Aerial's screensaver, shown on Home after a few idle minutes, before the firmware's
 * (ad-carrying) one would start. Four kinds, chosen in Personalize:
 *
 * - Aerial: Apple TV's aerial films, streamed from Apple, with the place named as the camera moves.
 * - Photos: the owner's own photos (the daily ones when there are none), slowly drifting, each
 *   with where it was taken.
 * - Clock: a large clock over the wallpaper, moving once a minute so nothing burns in.
 * - Videos: the owner's local films, uploaded from the phone page, playing silently offline.
 *
 * If the films cannot be reached, Aerial falls back to Photos. Left and right step to the previous
 * or next film or photo, as on Apple TV; see [ScreensaverSkips].
 *
 * Until one is chosen the kind follows the wallpaper (Wallpapers.screensaver): aerial films with the
 * aerial wallpaper, photos with a picture, starting from that picture.
 *
 * With [carry], Home's own wallpaper carries on as the screensaver: the aerial wallpaper's film
 * already moving behind Home, or its picture beginning to draw in there, before the photos move on
 * from it. Home fades away around it (HomeScreen, whose clock becomes the screensaver's).
 */
@Composable
fun Screensaver(visible: Boolean, apps: List<AppEntry>, now: LocalDateTime, skips: ScreensaverSkips, carry: SaverCarry? = null) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(1200, easing = Motion.EaseOut)),
        // Leaves quickly: the viewer pressed a key and wants Home back.
        exit = fadeOut(tween(220, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Follows Home while up (the film may fail and hand over to the screensaver's own), and
        // stays as it was through the fade out, so leaving does not start a player.
        val held = remember { mutableStateOf(carry) }
        if (visible) held.value = carry
        when (val carried = held.value) {
            is SaverCarry.Film -> { FilmCarry(carried.film, now, skips.steps); return@AnimatedVisibility }
            is SaverCarry.Still -> { PhotoSaver(now, skips.steps, carried.wallpaper, carried); return@AnimatedVisibility }
            null -> Unit
        }
        val context = LocalContext.current
        var aerialFailed by remember { mutableStateOf(false) }
        var videosFailed by remember { mutableStateOf(false) }
        var artFailed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (Wallpapers.screensaver) {
                ScreensaverMode.Aerial -> if (aerialFailed) PhotoSaver(now, skips.steps) else AerialSaver(
                    now, skips.steps, onUnavailable = { aerialFailed = true }, startAt = remember {
                        // A paused wallpaper film still holds the decoder (one 4K decoder, MiTV-MFTR0).
                        LiveFilm.onHome?.letDecoderGo()
                        AerialStart.start(context)
                    },
                )
                ScreensaverMode.Photos -> PhotoSaver(now, skips.steps, remember { Wallpapers.screensaverPicture() })
                ScreensaverMode.Gallery -> if (artFailed) PhotoSaver(now, skips.steps) else ArtSaver(
                    ArtGallery.Source.Online, now, skips.steps, onUnavailable = { artFailed = true },
                )
                ScreensaverMode.Usb -> if (artFailed) PhotoSaver(now, skips.steps) else ArtSaver(
                    ArtGallery.Source.Usb, now, skips.steps, onUnavailable = { artFailed = true },
                )
                ScreensaverMode.Clock, ScreensaverMode.System -> ClockSaver(apps, now)
                ScreensaverMode.Videos -> if (videosFailed) PhotoSaver(now, skips.steps) else VideoSaver(skips.steps, onUnavailable = { videosFailed = true })
            }
            // A thin white mat over the picture's edge, so the screen reads as a framed print (owner).
            // Drawn above every saver mode and clear of the player, so it cannot disturb decoding.
            // The art saver draws its own, hugging the painting, so it is left out here.
            if (Wallpapers.screensaver != ScreensaverMode.Gallery && Wallpapers.screensaver != ScreensaverMode.Usb) PictureFrame()
        }
    }
}

/**
 * The white border around the screensaver picture, like a picture frame's mat. Drawn on top of
 * every saver; the labels sit well inside it (their margins are larger than [frameWidth]), so no
 * text is covered. Off via AerialSettings.screensaverFrame.
 */
@Composable
private fun PictureFrame() {
    if (!AerialSettings.screensaverFrame) return
    Canvas(Modifier.fillMaxSize().diagLayer("picture-frame")) {
        val t = frameWidth.toPx()
        val w = size.width
        val h = size.height
        val white = frameColor
        drawRect(white, topLeft = Offset(0f, 0f), size = Size(w, t))
        drawRect(white, topLeft = Offset(0f, h - t), size = Size(w, t))
        drawRect(white, topLeft = Offset(0f, 0f), size = Size(t, h))
        drawRect(white, topLeft = Offset(w - t, 0f), size = Size(t, h))
    }
}

private val frameWidth = 22.dp

/** The mat's colour: warm off-white, as a paper mount is, not the screen's pure white. */
private val frameColor = Color(0xFFFCFBF8)

/** Between a painting's bottom edge and its name, which now sits on the painting itself. */
private val CAPTION_INSET = 34.dp

/**
 * Left / right on the remote while the screensaver is up. The host (MainActivity on Home,
 * AerialDream as the system screensaver) offers every key here first; keys not taken here wake.
 */
class ScreensaverSkips {
    private val flow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val steps: SharedFlow<Int> get() = flow

    /** True if the screensaver took [event], both its down and its up. The clock has nothing to step through. */
    fun onKey(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        if (Wallpapers.screensaver == ScreensaverMode.Clock) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) flow.tryEmit(step)
        return true
    }
}

/** What of Home's wallpaper carries on as the screensaver. */
sealed interface SaverCarry {
    /** The aerial wallpaper's film, moving on behind Home. */
    class Film(val film: LiveFilm) : SaverCarry

    /** The wallpaper's picture, drawing in behind Home (HomeBackground) as the photos' first. */
    class Still(val wallpaper: app.orchard.tv.wallpaper.Wallpaper) : SaverCarry {
        /** The photos have moved on over it: Home's picture can stop. */
        var covered by mutableStateOf(false)
    }
}

/**
 * What a wallpaper's picture is, for the screensaver: where it was taken, else its name. The owner's
 * own photos have no name but "My Photo", which says nothing over the photo itself.
 */
fun stillCaption(wp: app.orchard.tv.wallpaper.Wallpaper): String? =
    if (wp.kind == WallpaperKind.Photo) null else wp.caption?.takeIf { it.isNotBlank() } ?: wp.title

/** How long one slow push in (or back out) of a still wallpaper takes. */
private const val DRAW_IN_MS = 60_000

/** Pushes in and back out, slowly, for as long as the screensaver is up; from 0, the picture as Home has it. */
suspend fun drawIn(push: Animatable<Float, *>) {
    while (true) {
        push.animateTo(1f, tween(DRAW_IN_MS, easing = LinearEasing))
        push.animateTo(0f, tween(DRAW_IN_MS, easing = LinearEasing))
    }
}

/** [image] over the whole screen, [push] (0 to 1) of the way into its slow draw-in: larger by 8%, drifting right. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDrawingIn(image: ImageBitmap, push: Float, alpha: Float = 1f) {
    val s = 1f + 0.08f * push
    // Scaled and moved by the canvas, in fractions of a pixel: placed on whole pixels, the picture
    // stood still and jumped a pixel every few frames, its edges at different times (it draws in
    // only 2-3 px a second), and read as a shake (Xiaomi MiTV-MFTR0).
    val drift = 24.dp.toPx() * push
    withTransform({
        translate(left = drift)
        scale(s, s, center)
    }) {
        drawImage(
            image,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}

/** Where a screensaver of aerials starts when it cannot carry Home's player on (AerialDream). */
object AerialStart {
    /**
     * Where a screensaver of aerials starts: the film and moment the aerial wallpaper or the last
     * screensaver left (one spot for both, whatever the wallpaper, so the two are always the same
     * film), or the day's new film from its start (AerialSet.takeDaily).
     */
    fun start(context: android.content.Context): Pair<String, Long>? {
        AerialSet.takeDaily(context)?.let { film ->
            DiagLog.i("today's new film ${film.id}")
            Wallpapers.leaveLiveFilm(film.id, 0L)
            return film.id to 0L
        }
        return LiveFilm.onHome?.spot() ?: Wallpapers.liveSpot()
    }

    /**
     * A few seconds before Home's screensaver: where it is to start in another format than the
     * wallpaper's (Dolby Vision chosen), that start is fetched to disk, at the moment the wallpaper's
     * film will be at [leadMs] on (AerialStream.prepareStart). The screensaver then starts there.
     */
    suspend fun prepareAhead(context: android.content.Context, leadMs: Long) {
        if (Wallpapers.screensaver != ScreensaverMode.Aerial || AerialFormats.screensaverCarriesWallpaper(context)) return
        val quality = AerialFormats.forScreensaver(context, overWallpaper = true).let { q ->
            aerialQualityCap?.takeIf { it.ordinal < q.ordinal } ?: q
        }
        val live = LiveFilm.onHome
        // A new day starts the screensaver on its new film, from the start (start()).
        val (id, at) = if (AerialSet.due(context)) AerialSet.nextNew(context).id to 0L
            else live?.spot()?.let { (id, at) -> id to at + if (live.moving) leadMs else 0L } ?: Wallpapers.liveSpot() ?: return
        val film = Aerials.all(context).firstOrNull { it.id == id } ?: return
        AerialStream.prepareStart(context, film, quality, aerialsOverHttp, at)
    }

    /** A screensaver of aerials of its own is up (Home's or the dream): it moves the wallpaper's spot on. */
    @Volatile
    var running = false
}

/**
 * The aerial wallpaper's film carried on as the screensaver, one shot from Home: the same player,
 * in the wallpaper's own format, through to the films after it. Not with Dolby Vision chosen by hand
 * (AerialFormats.screensaverCarriesWallpaper): these TVs have one 4K decoder, and the switch showed
 * black for 1–3.5 s (Xiaomi MiTV-MFTR0), which only an owner who asked for Dolby Vision should get.
 */
@Composable
private fun FilmCarry(film: LiveFilm, now: LocalDateTime, steps: SharedFlow<Int>) {
    var caption by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(film.film) {
        while (true) {
            caption = film.film?.captionAt((film.positionMs / 1000).toInt())
            delay(1000)
        }
    }
    val context = LocalContext.current
    LaunchedEffect(steps) { steps.collect { film.skip(context, it) } }
    // A new day's film comes in as the screensaver starts, over the wallpaper's film (AerialSet).
    LaunchedEffect(Unit) { if (AerialSet.due(context)) film.skip(context, 1) }
    Labels(caption, now, showTime = false, shade = false)
}

/** Where the labels' shade ends at the top and starts at the bottom, of the screen's height. */
private const val SHADE_TOP = 0.18f
private const val SHADE_BOTTOM = 0.7f

/**
 * The labels' shade, baked small and stretched: as two full-screen gradients drawn each frame it
 * was the costliest thing on the screen over a playing film (see Images.homeShade).
 */
private val labelShadeImage by lazy { labelShade() }

private fun labelShade(): ImageBitmap {
    val h = 128f
    val out = android.graphics.Bitmap.createBitmap(4, h.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    fun black(a: Float) = android.graphics.Color.argb((a * 255).roundToInt(), 0, 0, 0)
    c.drawRect(0f, 0f, 4f, h, android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, h * SHADE_BOTTOM, 0f, h, 0, black(0.45f), android.graphics.Shader.TileMode.CLAMP)
    })
    c.drawRect(0f, 0f, 4f, h, android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, h * SHADE_TOP, black(0.3f), 0, android.graphics.Shader.TileMode.CLAMP)
    })
    return out.asImageBitmap()
}

/** Place name bottom-left, time top-right: how tvOS labels its screensaver. */
@Composable
private fun Labels(
    caption: String?,
    now: LocalDateTime,
    showTime: Boolean = true,
    switchKey: Any? = null,
    /** A shade under the labels: over photos. The aerial films are left as they are, as on Home. */
    shade: Boolean = true,
    /**
     * When set, the caption is laid centred this far above the bottom of the screen's picture area:
     * on a framed painting it prints on the painting itself, near its lower edge, rather than on a
     * strip of card left empty under it (owner).
     */
    captionBottom: Dp? = null,
    /** Longest the centred caption may run, a share of the picture it sits on. */
    captionMaxWidth: Dp = 620.dp,
    /** The caption's ink: white over a picture, dark when it is printed on a white mount. */
    captionColor: Color = Color.White.copy(alpha = 0.92f),
) {
    // Fades in where Home's own clock has moved to, as the photos cover it.
    val time by animateFloatAsState(if (showTime) 1f else 0f, tween(2000), label = "time")
    // Custom: both stay up for [LABELS_SHOW_MS] after each switch — the next film or photo, or a
    // new caption within a film — then fade out and leave the picture alone. Skipping with
    // left / right restarts the wait.
    var shown by remember(switchKey, caption) { mutableStateOf(true) }
    LaunchedEffect(switchKey, caption) {
        shown = true
        delay(LABELS_SHOW_MS)
        shown = false
    }
    val labelsAlpha by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(if (shown) 250 else 800, easing = Motion.EaseOut),
        label = "labels",
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = labelsAlpha }) {
        if (shade) Canvas(Modifier.fillMaxSize().diagLayer("shade")) {
            val shade = labelShadeImage
            // Just enough shade under the text to read it over bright sky or snow. Only its two
            // bands: the clear middle would cost the GPU as much to fill (Lite's SaverView.Shade).
            val w = size.width.roundToInt()
            val h = size.height.roundToInt()
            val top = (h * SHADE_TOP).roundToInt()
            val bottom = (h * SHADE_BOTTOM).roundToInt()
            val sh = shade.height
            drawImage(shade, srcSize = IntSize(shade.width, (sh * SHADE_TOP).roundToInt()), dstSize = IntSize(w, top), filterQuality = FilterQuality.Low)
            drawImage(
                shade, srcOffset = IntOffset(0, (sh * SHADE_BOTTOM).roundToInt()), srcSize = IntSize(shade.width, sh - (sh * SHADE_BOTTOM).roundToInt()),
                dstOffset = IntOffset(0, bottom), dstSize = IntSize(w, h - bottom), filterQuality = FilterQuality.Low,
            )
        }
        val drift = saverDrift(now)
        if (time > 0f && AerialSettings.screensaverClock) BasicText(
            timeText(now),
            style = Type.Title.copy(color = Color.White.copy(alpha = 0.92f)),
            modifier = Modifier
                .align(if (AerialSettings.clockRight) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = SaverClockTop, start = SaverClockSide, end = SaverClockSide)
                .graphicsLayer {
                    alpha = time
                    translationX = drift.x.toPx()
                    translationY = drift.y.toPx()
                },
        )
        AnimatedContent(
            targetState = caption.orEmpty(),
            // One after the other: two names fading across each other in the same place smeared.
            transitionSpec = { fadeIn(tween(600, delayMillis = 400, easing = Motion.EaseOut)) togetherWith fadeOut(tween(400, easing = Motion.EaseOut)) },
            modifier = (if (captionBottom != null) Modifier.align(Alignment.BottomCenter).padding(bottom = captionBottom) else
                Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp))
                .graphicsLayer { translationX = drift.x.toPx(); translationY = -drift.y.toPx() },
            label = "caption",
        ) { text ->
            BasicText(
                text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = Type.Title.copy(
                    color = captionColor,
                    textAlign = if (captionBottom != null) TextAlign.Center else TextAlign.Start,
                    // On the painting the name may cross a bright sky or snow: a soft shadow keeps it
                    // readable without putting a plate behind it.
                    shadow = if (captionBottom != null) Shadow(Color(0xB3000000), blurRadius = 16f) else null,
                ),
                modifier = Modifier.widthIn(max = if (captionBottom != null) captionMaxWidth else 620.dp),
            )
        }
    }
}

/**
 * The screensaver's time, top left or right as Home's clock is (Personalize): Home's clock moves
 * here when the screensaver carries on its film, and from the left it crossed the whole screen to
 * the right, a long slide for a moment that should be quiet (issue #357).
 */
/**
 * How far the screensaver's words have wandered from their place: a little, somewhere new every
 * two minutes and eased over seconds, so nothing sits on the same pixels all night (OLED panels,
 * issue #416). Too little to read as movement; the clock screensaver moves its clock by itself.
 */
@Composable
internal fun saverDrift(now: LocalDateTime): DpOffset {
    val slot = now.toLocalDate().toEpochDay() * 720 + (now.hour * 60 + now.minute) / 2
    val rnd = remember(slot) { Random(slot) }
    val target = remember(slot) { DpOffset(SaverDriftX * (rnd.nextFloat() * 2 - 1), SaverDriftY * (rnd.nextFloat() * 2 - 1)) }
    val x by animateDpAsState(target.x, tween(6000, easing = Motion.EaseInOut), label = "drift-x")
    val y by animateDpAsState(target.y, tween(6000, easing = Motion.EaseInOut), label = "drift-y")
    return DpOffset(x, y)
}

private val SaverDriftX = 24.dp
private val SaverDriftY = 14.dp

// The same style as the small clock, which grows into it, so the hand-off does not jump.
internal val SaverClockStyle = Type.SmallClock.copy(fontSize = Type.LargeTitle.fontSize * 1.3f, color = Color.White.copy(alpha = 0.92f))
internal val SaverClockTop = 40.dp
internal val SaverClockSide = 56.dp

/**
 * Before Android 7.0 network_security_config is ignored, so Apple Root CA is never trusted and
 * HTTPS to Apple's video host always fails, but only after the player has retried for about half a
 * minute (34 s on TCL 75S365C). Those versions start on HTTP. From 7.0 on this is set for the rest of
 * the process once a film fails over HTTPS.
 */
private var aerialsOverHttp by mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)

/**
 * The quality a decoder gave up on, for the rest of the process: the screensaver then starts one
 * step lower. A TV can list a decoder it cannot drive at that size or profile.
 */
private var aerialQualityCap: AerialQuality? = null

/**
 * [startAt]: the aerial wallpaper's film and moment to begin with, played in the wallpaper's format
 * so it goes on as the wallpaper looked, or in Dolby Vision where chosen (AerialFormats.forScreensaver).
 * Over the aerial wallpaper, the film and moment it is at are the wallpaper's to go on from
 * (Wallpapers.leaveLiveFilm): wallpaper and screensaver are one film in either format (owner,
 * 2026-10-29; until then the Dolby Vision screensaver began on any film whose start was on disk,
 * and Home came back on yet another).
 */
@Composable
private fun AerialSaver(
    now: LocalDateTime,
    steps: SharedFlow<Int>,
    onUnavailable: () -> Unit,
    startAt: Pair<String, Long>? = null,
) {
    val context = LocalContext.current
    var quality by remember {
        val wanted = AerialFormats.forScreensaver(context, overWallpaper = startAt != null)
        mutableStateOf(aerialQualityCap?.takeIf { it.ordinal < wanted.ordinal } ?: wanted)
    }
    // Where it is goes to the one spot the aerial wallpaper and the next screensaver go on from
    // (Wallpapers.leaveLiveFilm), whatever the wallpaper is now: switched to the aerial one later,
    // it begins on this film.
    val overLive = remember { Wallpapers.selectedId == Wallpapers.LIVE }
    fun leave(film: String, atMs: Long, persist: Boolean) = Wallpapers.leaveLiveFilm(film, atMs, persist)
    // The moment fetched ahead (AerialStart.prepareAhead), where it is the wallpaper's within a few
    // seconds: it plays from disk.
    @Suppress("NAME_SHADOWING")
    val startAt = remember {
        startAt?.let { (id, at) ->
            AerialStream.prepared?.takeIf { it.film == id && it.quality == quality && kotlin.math.abs(it.atMs - at) < PREPARED_SLACK_MS }
                ?.let { DiagLog.i("screensaver starts at the moment fetched ahead (${it.atMs - at} ms off)"); id to it.atMs }
                ?: (id to at)
        }
    }
    // The films kept (AerialSet), from the wallpaper's: the wallpaper plays them in the same order.
    val films = remember { mutableStateListOf<Aerial>().apply { addAll(AerialSet.playlist(context, startAt?.first)) } }
    val firstAtMs = remember { startAt?.takeIf { (id, _) -> films.first().id == id }?.second ?: 0L }
    // Its decoder starts on the film's own surface, not ExoPlayer's placeholder (AerialStream.player).
    val player = remember { AerialStream.player(context, quality, decodeOnScreen = true) }
    var index by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf<String?>(null) }
    // Bumped whenever a film is (re)started, so the stall check below starts over.
    var attempt by remember { mutableIntStateOf(0) }
    var shownAny by remember { mutableStateOf(false) }
    var retried by remember { mutableStateOf(false) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val film = films[index.mod(films.size)]

    // Black veil that lifts once the film is actually moving, and drops when the viewer skips.
    // Quickly in Dolby Vision: the TV has gone black already as it changed mode, and 1.5 s more of
    // black on top read as the screensaver being slow to start (owner, 2026-10-29).
    val liftMs = if (quality == AerialQuality.DolbyVision) DV_LIFT_MS else 1500
    val veil by animateFloatAsState(if (playing) 0f else 1f, tween(if (playing) liftMs else 400), label = "veil")

    fun load(at: Int, fromMs: Long = 0L) {
        player.setMediaItems(films.map { AerialStream.mediaItem(it, quality, aerialsOverHttp) }, at, fromMs)
        player.prepare()
        player.playWhenReady = true
    }

    /** The next film kept, after one that would not play. */
    fun next() {
        playing = false
        player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    /** The viewer's left and right: back to a film kept, or a new one (AerialSet.addNew). */
    fun skip(step: Int) {
        if (step < 0) {
            playing = false
            player.seekToPreviousMediaItem()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            return
        }
        val new = AerialSet.addNew(context)
        val at = player.currentMediaItemIndex + 1
        films.add(at, new)
        player.addMediaItem(at, AerialStream.mediaItem(new, quality, aerialsOverHttp))
        playing = false
        player.seekTo(at, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    DisposableEffect(player) {
        val opened = SystemClock.elapsedRealtime()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!playing) {
                    DiagLog.i("first frame of ${player.currentMediaItem?.mediaId} after ${SystemClock.elapsedRealtime() - opened} ms")
                    SystemScreensaver.dreamFirstFrame()?.let { MainActivity.dreamShowedAfter(context, it) }
                }
                playing = true
                shownAny = true
                failures = 0
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) DiagLog.i("${mediaItem?.mediaId} starts over (kept film)")
                index = player.currentMediaItemIndex
                attempt++
                // A film that simply ended hands over to the next one, already loaded, without a gap.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) playing = false
            }

            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("aerial film failed (${error.errorCodeName}): ${player.currentMediaItem?.localConfiguration?.uri}")
                playing = false
                // Before anything has shown, the decoder may still be held by Home's wallpaper film,
                // which is let go as Home goes behind the dream (TCL MT5879): once more at this
                // quality, a moment later, before going lower for good.
                if (!shownAny && !retried && error.errorCode in DECODER_ERRORS) {
                    retried = true
                    DiagLog.i("trying ${quality.key} again in $RETRY_MS ms")
                    handler.postDelayed({ load(player.currentMediaItemIndex, if (player.currentMediaItemIndex == 0) firstAtMs else 0L) }, RETRY_MS)
                    attempt++
                    return
                }
                // The decoder could not play this quality: the same film one step lower.
                if (quality != AerialQuality.Hd && error.errorCode in DECODER_ERRORS) {
                    aerialQualityCap = AerialFormats.lower(quality)
                    quality = AerialFormats.lower(quality)
                    load(player.currentMediaItemIndex)
                    attempt++
                    return
                }
                // Apple's video host chains to Apple Root CA, which Android does not ship. From 7.0 on
                // network_security_config trusts it for this host; 5.0 and 6.0 ignore that file and
                // start on HTTP. The same files are served over plain HTTP; try that before giving up.
                if (!aerialsOverHttp) {
                    aerialsOverHttp = true
                    load(player.currentMediaItemIndex)
                } else {
                    failures++
                    next()
                }
                attempt++
            }
        }
        player.addListener(listener)
        load(0, firstAtMs)
        AerialStart.running = true
        onDispose {
            AerialStart.running = false
            if (shownAny) player.currentMediaItem?.mediaId?.let { leave(it, player.currentPosition, persist = true) }
            handler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(index) {
        caption = null
        var ticks = 0
        while (true) {
            delay(1000)
            if (!playing) continue
            caption = film.captionAt((player.currentPosition / 1000).toInt())
            // Every second in memory, so Home comes back on this moment; to prefs now and then, for
            // a process that ends under the screensaver.
            leave(film.id, player.currentPosition, persist = ticks++ % 30 == 0)
        }
    }
    LaunchedEffect(steps) {
        steps.collect { step ->
            // Right always brings a new film; left needs another to go back to.
            if (step < 0 && films.size < 2) return@collect
            skip(step)
        }
    }
    // A film the CDN serves slowly is passed over rather than waited for.
    LaunchedEffect(attempt, playing) {
        if (playing) return@LaunchedEffect
        delay(STALL_MS)
        DiagLog.i("no picture from ${player.currentMediaItem?.mediaId} after $STALL_MS ms; next film")
        failures++
        // Nothing has played at all this time: Apple's CDN is out of reach. Hand over to the photo
        // screensaver after two films rather than three, so the screen is not black for long
        // (Sony A8G and X90J, TCL 65S515D, issue #20).
        if (!shownAny && failures >= 2) onUnavailable() else next()
    }
    LaunchedEffect(failures) {
        if (failures >= 3) onUnavailable()
    }
    // While a film plays, get the next screensaver's first film ready on disk.
    // And the index of this film in the wallpaper's format, where that is another: Home goes on
    // from this moment with one request to Apple.
    LaunchedEffect(playing, index) {
        val home = AerialFormats.forWallpaper(context)
        if (playing && overLive && home != quality) AerialStream.prepareSeek(context, film, home, aerialsOverHttp)
    }

    // A picture wallpaper is not carried into the aerial screensaver: the two cannot blend (another
    // plane, Dolby Vision or HDR on the film), and holding the picture until the film moved kept
    // Home's wallpaper on screen for seconds (Xiaomi MiTV-MFTR0, 1.61 to 1.63).
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FilmSlot(ctx).also {
                it.surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = DiagLog.i("film surface created")
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = DiagLog.i("film surface destroyed")
                })
                player.setVideoSurfaceView(it.surface)
            }
        },
        onRelease = { player.clearVideoSurfaceView(it.surface) },
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color.Black))
    Labels(if (playing) caption ?: film.captionAt(((if (index == 0) firstAtMs else 0L) / 1000).toInt()) else null, now, switchKey = index, shade = false)
}

/** How quickly the black lifts off a Dolby Vision film once it moves. */
private const val DV_LIFT_MS = 400

/** How far from the wallpaper's moment a start fetched ahead may be and still be used. */
private const val PREPARED_SLACK_MS = 6_000L

/** How long before a film whose decoder would not open is tried once more at the same quality. */
private const val RETRY_MS = 1_500L

/** How long a film may take to show its first frame before the next one is tried. */
private const val STALL_MS = 8_000L

private val DECODER_ERRORS = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

/** How long the time and the place stay up after the screensaver switches. */
private const val LABELS_SHOW_MS = 5_000L

/**
 * A slideshow of framed paintings: the museum collection downloaded from the web ([Source.Online])
 * or the owner's own images on a USB drive ([Source.Usb]). Pictures are decoded off the main thread
 * and cached to disk, so stepping back and forth is instant and a repeat visit works offline.
 */
@Composable
private fun ArtSaver(
    source: ArtGallery.Source,
    now: LocalDateTime,
    steps: SharedFlow<Int>,
    onUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    // Show paintings whole (letterboxed) or cropped to fill, per the owner's Pictures setting.
    val fitWhole = AerialSettings.artFitWhole
    val slideMs = AerialSettings.artSlideSeconds * 1000L
    // The mat's width, taken out of the picture so the frame shows on all four sides: a picture
    // fitted to the full height would otherwise put the frame's top and bottom bands off-screen.
    val framed = AerialSettings.screensaverFrame
    val density = LocalDensity.current
    val insetPx = if (framed && fitWhole) with(density) { frameWidth.toPx() } else 0f
    // No strip is reserved under the picture: its name prints on the picture itself (owner).
    val artW = (Wallpapers.screenW - insetPx * 2).roundToInt().coerceAtLeast(1)
    val artH = (Wallpapers.screenH - insetPx * 2).roundToInt().coerceAtLeast(1)
    suspend fun decodeArt(file: java.io.File): ArtImage? = withContext(Dispatchers.Default) {
        if (fitWhole) {
            Images.decodeFitMeasured(file, artW, artH)?.let { (bmp, content) ->
                ArtImage(bmp.asImageBitmap(), content)
            }
        } else {
            Images.decodeCover(file, Wallpapers.screenW, Wallpapers.screenH)?.let { ArtImage(it.asImageBitmap(), null) }
        }
    }
    val art = remember { mutableStateListOf<ArtGallery.Artwork>() }
    var ready by remember { mutableStateOf(false) }
    var slide by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Pair<Int, ArtImage>?>(null) }

    // Load the source once. A failure or an empty library hands over to the photo screensaver.
    // The museum's paintings are shuffled, so each visit starts somewhere else and the screensaver
    // never walks the same order twice (owner); the owner's own USB folders keep their order, which
    // is how they arranged them.
    LaunchedEffect(source) {
        val list = if (source == ArtGallery.Source.Online) ArtGallery.online(context).shuffled() else ArtGallery.usb()
        if (list.isEmpty()) {
            onUnavailable()
        } else {
            art.addAll(list)
            ready = true
        }
    }

    // Decode the slide being shown, keeping only it and the next: a full screen of paintings held
    // as bitmaps would be hundreds of megabytes.
    val decoded = remember(fitWhole, framed) { mutableMapOf<Int, ArtImage>() }
    var decodeMisses by remember { mutableIntStateOf(0) }
    LaunchedEffect(art.size, slide, fitWhole, framed) {
        if (!ready || art.isEmpty()) return@LaunchedEffect
        val idx = slide.mod(art.size)
        val pic = decoded[idx] ?: decodeArt(art[idx].file)
        if (pic != null) {
            decoded[idx] = pic
            image = idx to pic
            decodeMisses = 0
            // Warm the next one so a manual step or the timer does not show a blank frame.
            val next = (idx + 1).mod(art.size)
            if (!decoded.containsKey(next)) decodeArt(art[next].file)?.let { decoded[next] = it }
            decoded.keys.retainAll(setOf(idx, next))
            delay(slideMs)
            slide++
        } else {
            // A picture that will not decode: never hold the screen black for the whole interval,
            // and never touch the owner's file (a USB picture is theirs). Step on at once, and
            // hand over only after a run of misses (a whole broken library).
            decoded.remove(idx)
            val misses = ++decodeMisses
            if (misses >= art.size.coerceAtMost(4)) {
                onUnavailable()
            } else {
                delay(400)
                slide++
            }
        }
    }
    LaunchedEffect(steps) {
        steps.collect { slide += it }
    }

    val shown = image
    Box(Modifier.fillMaxSize()) {
        // A picture shown whole hangs on a wall rather than floating in the dark (owner, 1.1.15).
        if (fitWhole) WallBackdrop()
        if (shown != null) {
            Dissolve(targetState = shown, durationMs = 2000, easing = LinearEasing, label = "art") { current ->
                if (current != null) {
                    val push = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { if (!reduced && !fitWhole) push.animateTo(1f, tween(20_000, easing = LinearEasing)) }
                    val dir = if (current.first.mod(2) == 0) 1f else -1f
                    Canvas(
                        Modifier.fillMaxSize().graphicsLayer {
                            if (fitWhole) {
                                // A fitted picture is already whole; any zoom would clip its edges again.
                                scaleX = 1f; scaleY = 1f; translationX = 0f
                            } else {
                                val s = 1.04f + 0.08f * push.value
                                scaleX = s; scaleY = s
                                translationX = dir * (push.value - 0.5f) * 40.dp.toPx()
                            }
                        },
                    ) {
                        // The wall shows through the bars either side of a picture unlike the screen,
                        // so nothing is painted behind it.
                        val src = current.second.bmp
                        drawImage(
                            image = src,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(src.width, src.height),
                            dstOffset = IntOffset(insetPx.roundToInt(), insetPx.roundToInt()),
                            dstSize = IntSize(
                                (size.width - insetPx * 2).roundToInt(),
                                (size.height - insetPx * 2).roundToInt(),
                            ),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
        } else {
            // Loading the first painting. Keep it calm and dark rather than flashing a spinner.
            Box(Modifier.fillMaxSize())
        }
        // The mat hugs the painting itself rather than the screen's edge (owner, 1.1.12).
        if (framed && shown != null) ArtMat(shown.second, insetPx)
    }
    val cur = art.getOrNull((shown?.first ?: 0).mod(art.size.coerceAtLeast(1)))
    // The name prints on the painting itself, centred near its lower edge: no strip of card is left
    // under the picture for it (owner, 1.1.16).
    val captionBottom = shown?.second?.let { artCaptionBottom(it, insetPx) }
    // On the painting the name may run no wider than the picture, or a narrow painting's name would
    // spill onto the wall beside it.
    val captionMax = shown?.second?.let { pic ->
        val c = pic.content
        if (c == null || pic.bmp.width <= 0) null else with(density) {
            (c.width * (Wallpapers.screenW - insetPx * 2) / pic.bmp.width - 120.dp.toPx()).toDp().coerceAtLeast(240.dp)
        }
    }
    Labels(
        cur?.let { "${it.title}" + if (it.subtitle.isNotEmpty()) " · ${it.subtitle}" else "" },
        now, switchKey = slide, captionBottom = captionBottom,
        captionColor = Color.White.copy(alpha = 0.94f),
        shade = captionBottom == null,
        captionMaxWidth = captionMax ?: 620.dp,
    )
}

/**
 * Where the name of a painting shown whole goes: centred, sitting on the painting near its bottom
 * edge. [ArtImage.content] is the size the picture fills in its canvas, which is centred in the
 * area inside the mat. Null for a picture that fills the screen, whose name keeps its old place.
 */
@Composable
private fun artCaptionBottom(pic: ArtImage, insetPx: Float): Dp? {
    val content = pic.content ?: return null
    val density = LocalDensity.current
    // The picture is drawn inside the mat and centred in it; its content is centred in that too.
    val screenH = Wallpapers.screenH.toFloat()
    val areaH = screenH - insetPx * 2
    val pictureBottom = insetPx + (areaH - content.height) / 2f + content.height
    // Measured up from the screen's bottom, so the name sits a little inside the painting's edge.
    return with(density) { (screenH - pictureBottom + CAPTION_INSET.toPx()).toDp() }
}

/** A decoded painting, plus the size its content fills inside the screen, for a mat that hugs it. */
private class ArtImage(val bmp: ImageBitmap, val content: android.util.Size?)

/**
 * What a framed picture hangs against: a sheet of clear glass over a pale wall, in the iOS 26
 * "liquid glass" manner (owner, 1.1.18) — the colour behind shows through almost untouched, the
 * surface carries only the thinnest white veil, and what makes it read as glass is a crisp
 * highlight running just inside the screen's edges, with the corners settling into a neutral
 * shadow. Drawn for pictures shown whole, which are the ones that leave the screen's edges free;
 * nothing is added behind a picture that fills it.
 */
@Composable
private fun WallBackdrop() {
    Box(Modifier.fillMaxSize()) {
        // What lies behind the glass: colour, out of focus. Radial gradients fade to nothing at
        // their edges, so none of it reads as a shape — no blur pass, which would cost the GPU a
        // full-screen render effect and leave the screen's own edges fading to black.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // A cool grey-white wall, not a warm one: the glass should have no colour of its own.
            drawRect(Color(0xFFE7E6EA))
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0xF2FAF3F6), Color(0x00FAF3F6)),
                    center = Offset(w * 0.28f, h * 0.10f),
                    radius = size.maxDimension * 0.74f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0xA6DCE8E7), Color(0x00DCE8E7)),
                    center = Offset(w * 0.84f, h * 0.22f),
                    radius = size.maxDimension * 0.58f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0xBFF8EEE7), Color(0x00F8EEE7)),
                    center = Offset(w * 0.70f, h * 0.88f),
                    radius = size.maxDimension * 0.66f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x99D5D2DD), Color(0x00D5D2DD)),
                    center = Offset(w * 0.06f, h * 0.92f),
                    radius = size.maxDimension * 0.52f,
                ),
            )
        }
        // The glass itself: its veil, the light it catches, its frost, and its edges.
        Canvas(Modifier.fillMaxSize().diagLayer("frost")) {
            val w = size.width
            val h = size.height
            // The merest white veil over the whole pane, a touch denser at the top.
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(Color(0x24FFFFFF), Color(0x0AFFFFFF), Color(0x12FFFFFF)),
                ),
            )
            // Light raking across the pane from the top left.
            drawRect(
                Brush.linearGradient(
                    colors = listOf(Color(0x2AFFFFFF), Color(0x0AFFFFFF), Color(0x00FFFFFF)),
                    start = Offset(0f, 0f),
                    end = Offset(w * 0.76f, h * 0.94f),
                ),
            )
            // A bright glint along the top edge, and the soft halo it throws inwards.
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(Color(0x66FFFFFF), Color(0x00FFFFFF)),
                    endY = h * 0.11f,
                ),
            )
            // The room reflected in the pane: two soft vertical bands of light, wide and faint, the
            // way a window shows in a sheet of glass. Nothing sharp — a reflection, not a stripe.
            drawRect(
                Brush.horizontalGradient(
                    colors = listOf(Color(0x00FFFFFF), Color(0x12FFFFFF), Color(0x00FFFFFF)),
                    startX = w * 0.04f,
                    endX = w * 0.46f,
                ),
            )
            drawRect(
                Brush.horizontalGradient(
                    colors = listOf(Color(0x00FFFFFF), Color(0x0BFFFFFF), Color(0x00FFFFFF)),
                    startX = w * 0.62f,
                    endX = w * 0.90f,
                ),
            )
            // The frost: fine grain, tiled so it stays grain rather than smearing when scaled.
            val grain = frostGrain
            var y = 0
            while (y < size.height) {
                var x = 0
                while (x < size.width) {
                    drawImage(grain, dstOffset = IntOffset(x, y))
                    x += grain.width
                }
                y += grain.height
            }
            // The pane is thick at its edges: each side goes a shade deeper over its last band. Only
            // a shade now — clear glass has very little body of its own.
            val edge = 90.dp.toPx()
            val deep = Color(0x1A3C3A46)
            val clear = Color(0x003C3A46)
            drawRect(Brush.verticalGradient(listOf(deep, clear), endY = edge), size = Size(w, edge))
            drawRect(
                Brush.verticalGradient(listOf(clear, deep), startY = h - edge, endY = h),
                topLeft = Offset(0f, h - edge), size = Size(w, edge),
            )
            drawRect(Brush.horizontalGradient(listOf(deep, clear), endX = edge), size = Size(edge, h))
            drawRect(
                Brush.horizontalGradient(listOf(clear, deep), startX = w - edge, endX = w),
                topLeft = Offset(w - edge, 0f), size = Size(edge, h),
            )
            // And the corners settle a little further, so the pane has an inside and an outside.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x00000000), Color(0x11202028), Color(0x2A15151C)),
                    center = Offset(w * 0.5f, h * 0.44f),
                    radius = size.maxDimension * 0.86f,
                ),
            )
            // The rim: a hairline of light just inside each edge, brightest along the top and
            // fading round the corners — the single detail that makes a sheet of glass look like a
            // sheet of glass rather than a tinted rectangle.
            val hair = 1.5.dp.toPx()
            drawRect(Color(0xA8FFFFFF), size = Size(w, hair))
            drawRect(Color(0x40FFFFFF), topLeft = Offset(0f, h - hair), size = Size(w, hair))
            drawRect(Color(0x5CFFFFFF), size = Size(hair, h))
            drawRect(Color(0x5CFFFFFF), topLeft = Offset(w - hair, 0f), size = Size(hair, h))
        }
    }
}

/**
 * The frost over the glass: a tile of single-pixel grains, half of them lifting the tone and half
 * deepening it, all but invisible one by one and reading as a matte surface together. Baked once:
 * a few hundred thousand pixels drawn per frame would cost the GPU far more than the look is worth.
 */
private val frostGrain by lazy { frostGrainImage() }

private fun frostGrainImage(): ImageBitmap {
    val n = 512
    val px = IntArray(n * n)
    val rnd = Random(20261120)
    for (i in px.indices) {
        val a = rnd.nextInt(0, 17)
        px[i] = if (rnd.nextBoolean()) (a shl 24) or 0xFFFFFF else (a shl 24)
    }
    return android.graphics.Bitmap
        .createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/**
 * The white frame round the painting: the same width on all four sides — 22dp, never stretched to
 * fill the screen. The picture is fitted so the frame reaches one edge of the screen (its height
 * for most pictures, its width for a very wide one) and is centred the other way, hanging on the
 * wall (owner, 1.1.16).
 */
@Composable
private fun ArtMat(pic: ArtImage, inset: Float) {
    Canvas(Modifier.fillMaxSize().diagLayer("art-mat")) {
        val m = frameWidth.toPx()
        val content = pic.content
        if (content != null && pic.bmp.width > 0) {
            // Where the picture itself lands: centred on the screen.
            val f = if (inset > 0f) (size.width - inset * 2) / pic.bmp.width else size.width / pic.bmp.width
            val cw = content.width * f
            val ch = content.height * f
            val l = (size.width - cw) / 2f
            val t = (size.height - ch) / 2f
            // The frame: a band of the same width on every side of the picture, centred with it.
            val frameW = cw + m * 2
            val frameH = ch + m * 2
            val fx = (size.width - frameW) / 2f
            val fy = (size.height - frameH) / 2f
            // The frame hangs on the wall and throws a little shadow down it, which is what makes it
            // read as an object rather than a rectangle of paint.
            val sh = 30.dp.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color(0x59000000), Color(0x00000000)), startY = fy + frameH, endY = fy + frameH + sh),
                topLeft = Offset(fx - sh, fy + frameH),
                size = Size(frameW + sh * 2, sh),
            )
            drawRect(
                Brush.verticalGradient(listOf(Color(0x1E000000), Color(0x00000000)), startY = fy - sh * 0.5f, endY = fy),
                topLeft = Offset(fx - sh * 0.5f, fy - sh * 0.5f),
                size = Size(frameW + sh, sh * 0.5f),
            )
            drawRect(frameColor, Offset(fx, fy), Size(frameW, t - fy))
            drawRect(frameColor, Offset(fx, t + ch), Size(frameW, fy + frameH - (t + ch)))
            drawRect(frameColor, Offset(fx, t), Size(l - fx, ch))
            drawRect(frameColor, Offset(l + cw, t), Size(fx + frameW - (l + cw), ch))
        } else {
            drawRect(frameColor, Offset(0f, 0f), Size(size.width, m))
            drawRect(frameColor, Offset(0f, size.height - m), Size(size.width, m))
            drawRect(frameColor, Offset(0f, 0f), Size(m, size.height))
            drawRect(frameColor, Offset(size.width - m, 0f), Size(m, size.height))
        }
    }
}

@Composable
private fun PhotoSaver(
    now: LocalDateTime,
    steps: SharedFlow<Int>,
    /** The wallpaper's picture, shown first. */
    first: app.orchard.tv.wallpaper.Wallpaper? = null,
    /** Home already shows [first], drawing in: the photos take over from the second. */
    carried: SaverCarry.Still? = null,
) {
    val reduced = LocalReducedMotion.current
    val pool = remember(Wallpapers.items) {
        // The owner's own photos when there are any, else the daily ones (issue #27).
        val pictures = Wallpapers.items.filter { it.kind == WallpaperKind.Photo }
            .ifEmpty { Wallpapers.items.filter { it.kind == WallpaperKind.Daily } }
        val rest = (pictures.ifEmpty { Wallpapers.items.filter { it.kind == WallpaperKind.Builtin } }).shuffled()
        if (first == null) rest else listOf(first) + rest.filter { it.id != first.id }
    }
    var slide by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }

    // Restarts on every slide, so a step from the remote also restarts the 18 s wait.
    LaunchedEffect(pool, slide) {
        if (pool.isEmpty()) return@LaunchedEffect
        if (carried == null || slide != 0) {
            val wp = pool[slide.mod(pool.size)]
            val bmp = withContext(Dispatchers.Default) { Wallpapers.picture(wp, Wallpapers.screenW, Wallpapers.screenH)?.asImageBitmap() }
            if (bmp != null) image = slide to bmp
        }
        delay(AerialSettings.artSlideSeconds * 1000L)
        slide++
    }
    // Once a photo has crossfaded in over Home's picture, Home can stop drawing it.
    LaunchedEffect(image != null) {
        if (carried != null && image != null) {
            delay(2_100)
            carried.covered = true
        }
    }
    LaunchedEffect(steps) {
        steps.collect { slide += it }
    }

    // Each photo fades in over the last, which stays whole beneath it (no dip to black mid-way).
    Dissolve(targetState = image, durationMs = 2000, easing = LinearEasing, label = "photo") { current ->
        if (current != null) {
            // Ken Burns: a slow push in, alternating direction slide to slide.
            val push = remember { Animatable(0f) }
            LaunchedEffect(Unit) { if (!reduced) push.animateTo(1f, tween(20_000, easing = LinearEasing)) }
            val dir = if (current.first.mod(2) == 0) 1f else -1f
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1.04f + 0.08f * push.value
                    scaleX = s; scaleY = s
                    translationX = dir * (push.value - 0.5f) * 40.dp.toPx()
                },
            ) {
                drawImage(current.second, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            }
        }
    }
    val wp = pool.getOrNull((image?.first ?: 0).mod(pool.size.coerceAtLeast(1)))
    Labels(wp?.let(::stillCaption), now, showTime = carried == null || image != null, switchKey = slide)
}

/** A large clock that moves once a minute over the wallpaper or the apps' light. */
@Composable
private fun ClockSaver(apps: List<AppEntry>, now: LocalDateTime) {
    val minute = now.withSecond(0).withNano(0)
    val scene = remember(minute, apps.size, Wallpapers.backdrop) {
        val rnd = Random(minute.hashCode())
        Scene(
            light = Wallpapers.backdrop ?: apps.takeIf { it.isNotEmpty() }?.get(rnd.nextInt(apps.size))?.art?.backdrop,
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        // The light apart from the clock: with a wallpaper it is the same picture every minute, and
        // crossfaded against itself it dimmed for two seconds each time the clock moved. Opaque
        // (black under it), so a new one can fade in over the old.
        Dissolve(targetState = scene.light, durationMs = 2400, easing = Motion.EaseInOut, modifier = Modifier.fillMaxSize(), label = "clock-light") { light ->
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black)
                if (light != null) {
                    drawImage(light, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = 0.75f)
                }
            }
        }
        Crossfade(targetState = scene, animationSpec = tween(2400, easing = Motion.EaseInOut), label = "clock-scene") { s ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.offset(
                        x = 64.dp + (w - 64.dp * 2 - 420.dp) * s.x,
                        y = 56.dp + (h - 56.dp * 2 - 170.dp) * s.y,
                    ),
                ) { ShelfClock(now = now) }
            }
        }
    }
}

private data class Scene(val light: ImageBitmap?, val x: Float, val y: Float)
