package app.orchard.tv.ui.control

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.DayPoint
import app.orchard.tv.weather.HourPoint
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The forecast behind the weather on Home: today hour by hour from now on, and the coming week.
 * A glass panel that grows out of the top-right corner, the way Control Center does; a tap outside
 * or Back closes it. Back is caught here directly (not only through BackHandler) so a press of it
 * while the panel is open always dismisses it, and never falls through to the home grid behind.
 */
@Composable
fun WeatherPanel(
    visible: Boolean,
    onClose: () -> Unit,
) {
    BackHandler(enabled = visible) { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (visible) {
                    Modifier.onPreviewKeyEvent { e ->
                        if (e.nativeKeyEvent.action == NativeKeyEvent.ACTION_DOWN && e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                            onClose()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.28f)))
        }

        val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).dp
        LaunchedEffect(visible) { if (visible) Weather.loadForecast() }
        Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + scaleIn(tween(340, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
                exit = fadeOut(tween(160)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
            ) {
                LazyColumn(
                    Modifier
                        .width(430.dp)
                        .heightIn(max = maxHeight)
                        .consumeTaps()
                        .liquidGlass(30.dp)
                        .padding(22.dp),
                ) {
                    item { Header() }
                    item { Spacer(Modifier.height(16.dp)) }
                    item { SectionLabel(stringResource(R.string.wx_today)) }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { Hours() }
                    item { Spacer(Modifier.height(16.dp)) }
                    item { SectionLabel(stringResource(R.string.wx_week)) }
                    item { Spacer(Modifier.height(8.dp)) }
                    val days = Weather.forecast?.days.orEmpty()
                    if (days.isEmpty()) {
                        item { Loading() }
                    } else {
                        itemsIndexed(days, key = { _, d -> d.date }) { index, day ->
                            DayRow(day, today = index == 0)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(
                Weather.place ?: stringResource(R.string.wx_unknown_place),
                style = Type.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val now = Weather.now
            if (now != null) {
                val nowText = stringResource(R.string.wx_now, now.celsius, wxNameOf(now.code, now.kind))
                val rangeText = if (now.high != null && now.low != null)
                    stringResource(R.string.weather_high_low, now.high, now.low) else null
                BasicText(
                    if (rangeText != null) "$nowText  ·  $rangeText" else nowText,
                    style = Type.Callout.copy(color = Palette.SecondaryLabel),
                )
            }
        }
        val now = Weather.now
        if (now != null) {
            GlyphIcon(glyphFor(now.kind, now.day), Palette.Label, 34.dp, level = rainLevel(now.code))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    BasicText(text, style = Type.Caption.copy(color = Palette.SecondaryLabel))
}

@Composable
private fun Loading() {
    BasicText(stringResource(R.string.wx_loading), style = Type.Callout.copy(color = Palette.SecondaryLabel))
}

/**
 * The rest of today, hour by hour; late in the day the small hours of tomorrow fill it out. Every
 * hour takes an equal slice of the same width the week's columns share below, inset the same way,
 * so the first hour starts where the first day column does and the last hour ends where the last
 * one does.
 */
@Composable
private fun Hours() {
    val hours = Weather.forecast?.hours.orEmpty()
    if (hours.isEmpty()) {
        Loading()
        return
    }
    val first = remember { FocusRequester() }
    // The forecast arrives after the panel opens; take focus then, so the weather on Home (which
    // keeps focus behind the scrim) is not what a press of OK lands on.
    LaunchedEffect(hours.firstOrNull()?.hour) {
        if (hours.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = RowInset)) {
        hours.forEachIndexed { index, hour ->
            HourCell(
                hour,
                focusRequester = if (index == 0) first else null,
                // The strip's ends sit where the week's first and last columns put their text:
                // left flush at the row's inset, right flush at its end.
                align = when (index) {
                    0 -> TextAlign.Start
                    hours.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HourCell(
    hour: HourPoint,
    focusRequester: FocusRequester?,
    align: TextAlign = TextAlign.Center,
    modifier: Modifier = Modifier,
) {
    Pressable(onClick = {}, focusRequester = focusRequester, focusedScale = 1.05f, modifier = modifier) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "hour-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Column(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(vertical = 8.dp, horizontal = 1.dp),
            horizontalAlignment = when (align) {
                TextAlign.Start -> Alignment.Start
                TextAlign.End -> Alignment.End
                else -> Alignment.CenterHorizontally
            },
        ) {
            BasicText(
                stringResource(R.string.wx_hour, hour.hour),
                style = Type.Caption.copy(color = fg, textAlign = align),
                softWrap = false,
            )
            Spacer(Modifier.height(5.dp))
            GlyphIcon(glyphFor(hour.kind, day = hour.hour in 6..17), fg, 18.dp, level = rainLevel(hour.code))
            Spacer(Modifier.height(5.dp))
            BasicText("${hour.celsius}°", style = Type.Callout.copy(color = fg, textAlign = align), softWrap = false)
        }
    }
}

/** How far a row's content sits in from the panel's edge; the hours and the days share it. */
private val RowInset = 10.dp

@Composable
private fun DayRow(day: DayPoint, today: Boolean) {
    Pressable(onClick = {}, focusedScale = 1.02f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "day-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val dim = if (state.focused) Palette.OnFocus else Palette.SecondaryLabel
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .padding(horizontal = RowInset, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Five equal-width columns, so every day of the week lines up under the same columns.
            // The first sits left flush and the last right flush, the edges the hourly strip above
            // starts and ends on; the rest are centred in between.
            BasicText(
                if (today) stringResource(R.string.wx_today_short) else weekdayLabel(day.date),
                style = Type.Callout.copy(color = fg, textAlign = TextAlign.Start),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            BasicText(
                day.date.substring(5).replace('-', '/'),
                style = Type.Caption.copy(color = dim, textAlign = TextAlign.Center),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                GlyphIcon(glyphFor(day.kind, day = true), fg, 18.dp, level = rainLevel(day.code))
            }
            BasicText(
                wxNameOf(day.code, day.kind),
                style = Type.Caption.copy(color = dim, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            BasicText(
                stringResource(R.string.wx_range, day.min, day.max),
                style = Type.Callout.copy(color = fg, textAlign = TextAlign.End),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun weekdayLabel(iso: String): String {
    val day = runCatching { LocalDate.parse(iso).dayOfWeek }.getOrNull() ?: return iso
    return when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }
}

@Composable
private fun wxName(kind: WeatherKind): String = when (kind) {
    WeatherKind.Clear -> stringResource(R.string.wx_k_clear)
    WeatherKind.PartlyCloudy -> stringResource(R.string.wx_k_partly)
    WeatherKind.Cloudy -> stringResource(R.string.wx_k_cloudy)
    WeatherKind.Fog -> stringResource(R.string.wx_k_fog)
    WeatherKind.Rain -> stringResource(R.string.wx_k_rain)
    WeatherKind.Snow -> stringResource(R.string.wx_k_snow)
    WeatherKind.Thunder -> stringResource(R.string.wx_k_thunder)
}

/**
 * What Open-Meteo's code actually says: not just "rain", but light, moderate, heavy, or violent.
 * Falls back to the plain word for the few codes with no string of their own.
 */
@Composable
private fun wxNameOf(code: Int, kind: WeatherKind): String {
    val context = LocalContext.current
    val id = context.resources.getIdentifier("wx_c_$code", "string", context.packageName)
    return if (id != 0) stringResource(id) else wxName(kind)
}

/**
 * How hard it is raining, as the level the rain glyph is drawn at: one drop, two, or three.
 */
private fun rainLevel(code: Int): Float = when (code) {
    51, 56, 61, 66, 71, 80, 85 -> 0.2f   // light
    53, 57, 63, 67, 73, 81 -> 0.5f       // moderate
    else -> 1f                            // heavy: 55, 65, 75, 82, 86
}

private fun glyphFor(kind: WeatherKind, day: Boolean): Glyph = when (kind) {
    WeatherKind.Clear -> if (day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain -> Glyph.CloudRain
    WeatherKind.Snow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
}
