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
import app.orchard.tv.ui.components.panelPreview
import app.orchard.tv.ui.components.smokeGlassPanel
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
    /** False while the forecast is only a peek from Home's focus: no focus, keys, scrim or taps. */
    interactive: Boolean = true,
) {
    BackHandler(enabled = visible && interactive) { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (visible && interactive) {
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
            Box(
                Modifier.fillMaxSize()
                    .then(if (interactive) Modifier.dismissOnTap(onClose) else Modifier)
                    .background(if (interactive) Color.Black.copy(alpha = 0.18f) else Color.Transparent)
            )
        }

        val maxHeight = (LocalConfiguration.current.screenHeightDp - 48).dp
        LaunchedEffect(visible) { if (visible) Weather.loadForecast() }
        Box(Modifier.fillMaxSize().padding(top = if (interactive) 24.dp else 64.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + scaleIn(tween(340, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
                exit = fadeOut(tween(160)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
            ) {
                LazyColumn(
                    Modifier
                        .width(430.dp)
                        .heightIn(max = maxHeight)
                        .panelPreview(interactive, 1f, 0f)
                        .consumeTaps()
                        .smokeGlassPanel(dense = !interactive)
                        .padding(22.dp),
                ) {
                    item { Header() }
                    item { Spacer(Modifier.height(16.dp)) }
                    item { SectionLabel(stringResource(R.string.wx_today)) }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { Hours(interactive) }
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

/**
 * The city on the left, with the air quality under it; on the right the sky's glyph, and under the
 * glyph the reading: the temperature with the condition beside it, then today's high and low. The
 * eye takes the place first and the numbers beside it, as a weather app puts them, rather than one
 * long line of equal small print across the panel's head.
 */
@Composable
private fun Header() {
    val now = Weather.shown(System.currentTimeMillis())
    val air = Weather.air
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            BasicText(
                Weather.place ?: stringResource(R.string.wx_unknown_place),
                style = Type.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (air != null) {
                Spacer(Modifier.height(8.dp))
                AirQualityInline(air)
            }
        }
        if (now != null) {
            Column(
                Modifier.padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlyphIcon(glyphFor(now.kind, now.day), Palette.Label, 34.dp, level = rainLevel(now.code))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("${now.celsius}°", style = Type.Headline, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(5.dp))
                    BasicText(
                        wxNameOf(now.code, now.kind),
                        style = Type.Caption.copy(color = Palette.SecondaryLabel),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (now.high != null && now.low != null) {
                    Spacer(Modifier.height(3.dp))
                    BasicText(
                        stringResource(R.string.weather_high_low, now.high, now.low),
                        style = Type.Caption.copy(color = Palette.TertiaryLabel),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Air quality on the reading's line: a dot in its band's colour, then the AQI and PM2.5. */
@Composable
private fun AirQualityInline(air: app.orchard.tv.weather.AirQuality) {
    val label = when {
        air.aqi <= 50 -> stringResource(R.string.wx_air_good)
        air.aqi <= 100 -> stringResource(R.string.wx_air_moderate)
        air.aqi <= 150 -> stringResource(R.string.wx_air_light)
        air.aqi <= 200 -> stringResource(R.string.wx_air_medium)
        air.aqi <= 300 -> stringResource(R.string.wx_air_heavy)
        else -> stringResource(R.string.wx_air_severe)
    }
    val tint = when {
        air.aqi <= 50 -> Palette.Green
        air.aqi <= 100 -> Color(0xFFFFD60A)
        air.aqi <= 150 -> Palette.Orange
        air.aqi <= 200 -> Palette.Red
        air.aqi <= 300 -> Color(0xFFBF5AF2)
        else -> Color(0xFF8E5933)
    }
    val pm = air.pm25?.let { stringResource(R.string.wx_air_pm25, it) } ?: ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(tint))
        Spacer(Modifier.width(6.dp))
        BasicText(
            stringResource(R.string.wx_air_line, air.aqi, label, pm),
            style = Type.Caption.copy(color = Palette.SecondaryLabel),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun Hours(interactive: Boolean) {
    val hours = Weather.forecast?.hours.orEmpty()
    if (hours.isEmpty()) {
        Loading()
        return
    }
    val first = remember { FocusRequester() }
    // The forecast arrives after the panel opens; take focus then, so the weather on Home (which
    // keeps focus behind the scrim) is not what a press of OK lands on. A peek never takes it:
    // when OK turns the peek into the real panel, the changed key asks for focus again.
    LaunchedEffect(hours.firstOrNull()?.hour, interactive) {
        if (!interactive || hours.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = RowInset)) {
        hours.forEachIndexed { index, hour ->
            HourCell(
                hour,
                focusRequester = if (index == 0) first else null,
                // Every hour owns exactly the same slice of the strip, whose ends line up with
                // the week's first and last columns below.
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HourCell(
    hour: HourPoint,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    Pressable(onClick = {}, focusRequester = focusRequester, focusedScale = 1.05f, modifier = modifier) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "hour-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Column(
            Modifier
                // Fill the whole equal-weight slice: a wrap-content plate made neighbouring
                // hours look narrower or wider than one another.
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                .padding(vertical = 8.dp, horizontal = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                stringResource(R.string.wx_hour, hour.hour),
                style = Type.Caption.copy(color = fg, textAlign = TextAlign.Center),
                softWrap = false,
            )
            Spacer(Modifier.height(5.dp))
            // A fixed slot keeps every glyph on the same width, whichever shape it draws.
            GlyphSlot(glyphFor(hour.kind, day = hour.hour in 6..17), fg, rainLevel(hour.code))
            Spacer(Modifier.height(5.dp))
            BasicText("${hour.celsius}°", style = Type.Callout.copy(color = fg, textAlign = TextAlign.Center), softWrap = false)
        }
    }
}

/** The rows' inset from the panel edge: hours and days share it, so their content is the same width. */
private val RowInset = 10.dp
/** Both the hourly strip and the week use this glyph size, inside the same fixed-width slot. */
private val ForecastGlyph = 18.dp
private val GlyphSlotWidth = 24.dp

@Composable
private fun GlyphSlot(glyph: Glyph, tint: Color, level: Float) {
    Box(Modifier.width(GlyphSlotWidth), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, tint, ForecastGlyph, level = level)
    }
}

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
                // RowInset + the hour cell's 1 dp inset: the week's text and the hourly plates
                // then start and end on exactly the same x, so both sections are the same width.
                .padding(horizontal = RowInset + 1.dp, vertical = 7.dp),
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
            // Same glyph slot as the hourly strip above, centred in its equal-width column.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                GlyphSlot(glyphFor(day.kind, day = true), fg, rainLevel(day.code))
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
