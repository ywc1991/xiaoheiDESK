package app.orchard.tv.ui.control

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.components.focusGlassSurface
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.util.Lunar
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The calendar behind the date on Home: the month the date sits in, each day with its lunar (农历)
 * name, today picked out. A glass panel that grows out of the top-left corner. A tap outside or
 * Back closes it; Left/Right step the month, Down jumps back to the current one.
 */
@Composable
fun DatePanel(
    visible: Boolean,
    onClose: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var viewY by remember { mutableStateOf(today.year) }
    var viewM by remember { mutableStateOf(today.monthValue) }

    /** Step the viewed month by [delta] (-1 = previous, +1 = next), rolling the year over. */
    fun stepMonth(delta: Int) {
        var m = viewM + delta
        var y = viewY
        while (m < 1) { m += 12; y -= 1 }
        while (m > 12) { m -= 12; y += 1 }
        viewY = y
        viewM = m
    }

    BackHandler(enabled = visible) { onClose() }
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (visible) {
                    Modifier.onPreviewKeyEvent { e ->
                        if (e.nativeKeyEvent.action != NativeKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            NativeKeyEvent.KEYCODE_BACK -> { onClose(); true }
                            NativeKeyEvent.KEYCODE_DPAD_LEFT -> { stepMonth(-1); true }
                            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { stepMonth(1); true }
                            NativeKeyEvent.KEYCODE_DPAD_DOWN -> { viewY = today.year; viewM = today.monthValue; true }
                            else -> false
                        }
                    }
                } else Modifier,
            ),
    ) {
        // Focus anchor: the panel has no focusable content of its own, so the scrim holds focus
        // while it is open. That makes the root Box above an ancestor of the focus target, which is
        // what lets its key handler see Back and the month keys, and it hands focus back to the
        // clock on Home when the panel closes.
        val panelFocus = remember { FocusRequester() }
        LaunchedEffect(visible) { if (visible) panelFocus.requestFocus() }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(panelFocus)
                    .focusable()
                    .dismissOnTap(onClose)
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
        }

        Box(Modifier.fillMaxSize().padding(top = 24.dp, start = 32.dp), contentAlignment = Alignment.TopStart) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(200)) + scaleIn(tween(340, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)),
                exit = fadeOut(tween(160)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)),
            ) {
                Column(
                    Modifier
                        .width(430.dp)
                        .consumeTaps()
                        .liquidGlass(30.dp)
                        .focusGlassSurface(30.dp)
                        .padding(24.dp),
                ) {
                    Header(viewY, viewM, today)
                    Spacer(Modifier.height(16.dp))
                    WeekHeader()
                    Spacer(Modifier.height(8.dp))
                    MonthGrid(viewY, viewM, today)
                    Spacer(Modifier.height(14.dp))
                    BasicText(
                        stringResource(R.string.date_hint),
                        style = Type.Caption.copy(color = Palette.TertiaryLabel),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(viewY: Int, viewM: Int, today: LocalDate) {
    val weekday = when (today.dayOfWeek) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }
    // The month being looked at, with today anchored underneath it, the way the weather panel
    // leads with the place and keeps the current reading below.
    val fest = Lunar.festivalFor(today)
    BasicText(
        "%d年%d月".format(viewY, viewM),
        style = Type.Title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(4.dp))
    BasicText(
        "今天 ${today.monthValue}月${today.dayOfMonth}日 $weekday · ${Lunar.fullLabel(today)}" + (fest?.let { " $it" } ?: ""),
        style = Type.Callout.copy(color = Palette.SecondaryLabel),
    )
}

@Composable
private fun WeekHeader() {
    val names = listOf("日", "一", "二", "三", "四", "五", "六")
    // Equal columns, the same the day rows use, so each label sits over the column it names.
    Row(Modifier.fillMaxWidth()) {
        names.forEach {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BasicText(it, style = Type.Section, softWrap = false)
            }
        }
    }
}

@Composable
private fun MonthGrid(year: Int, month: Int, today: LocalDate) {
    val first = Lunar.firstDayWeekday(year, month) // 0 = Sunday
    val days = Lunar.daysInMonth(year, month)
    val cells = first + days
    val weeks = (cells + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0..6) {
                    val idx = w * 7 + d
                    val day = idx - first + 1
                    if (idx < first || day > days) {
                        Box(Modifier.weight(1f).height(50.dp))
                    } else {
                        val date = LocalDate.of(year, month, day)
                        val isToday = date == today
                        val bg = if (isToday) Palette.Focus else Color.Transparent
                        val fg = if (isToday) Palette.OnFocus else Palette.Label
                        val sub = if (isToday) Palette.OnFocus else Palette.SecondaryLabel
                        Column(
                            Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(bg),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            BasicText("$day", style = Type.Headline.copy(color = fg))
                            BasicText(Lunar.lunarSubLabel(date), style = Type.Caption.copy(color = sub))
                        }
                    }
                }
            }
        }
    }
}
