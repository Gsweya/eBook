package com.gbook.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.gbook.AppViewModel
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** No input for this long means the reader has put the phone down; that time is not counted. */
private const val IDLE_MS = 3 * 60_000L

/**
 * Counts active reading time for [bookId] while the screen is resumed. [activity] should change whenever the
 * reader interacts (scrolls, turns a page); time after [IDLE_MS] without a change is ignored.
 */
@Composable
fun TrackReadingTime(vm: AppViewModel, bookId: String, activity: Any?) {
    val lastActive = remember { longArrayOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(activity) { lastActive[0] = SystemClock.elapsedRealtime() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(bookId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            lastActive[0] = SystemClock.elapsedRealtime()
            var last = SystemClock.elapsedRealtime()
            var pending = 0L
            fun accrue() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastActive[0] < IDLE_MS) pending += now - last
                last = now
            }
            try {
                while (true) {
                    delay(5_000)
                    accrue()
                    if (pending >= 30_000) { vm.addReadingTime(bookId, pending); pending = 0 }
                }
            } finally {
                accrue()
                vm.addReadingTime(bookId, pending)
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> if (ms > 0) "<1 min" else "0 min"
        min < 60 -> "$min min"
        else -> "${min / 60}h ${min % 60}m"
    }
}

/** Days in a row, ending today or yesterday, with any reading. */
fun readingStreak(log: Map<String, Long>): Int {
    var day = LocalDate.now()
    if ((log[day.toString()] ?: 0) <= 0) day = day.minusDays(1)
    var n = 0
    while ((log[day.toString()] ?: 0) > 0) { n++; day = day.minusDays(1) }
    return n
}

/** Today's reading time, a 7-day bar chart and week / streak / total figures. */
@Composable
fun ReadingStatsCard(log: Map<String, Long>, totalMs: Long, goalMin: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { log[it.toString()] ?: 0L }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(15 * 60_000L)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(g.surface)
            .border(0.75.dp, g.separator.copy(alpha = if (g.dark) 0.55f else 1f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("READ TODAY", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = g.text2)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Reading activity", tint = g.text2, modifier = Modifier.size(18.dp))
                }
                Text(formatDuration(values.last()), fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, color = g.text)
                if (goalMin > 0) GoalProgress(values.last(), goalMin)
            }
            // Last 7 days; today's bar uses the accent gradient.
            Row(Modifier.height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, d ->
                    val isToday = i == days.lastIndex
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.width(14.dp).height(46.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.08f).clip(RoundedCornerShape(4.dp)).background(g.fill))
                            Box(
                                Modifier.fillMaxWidth().fillMaxHeight((values[i].toFloat() / max).coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(4.dp))
                                    .then(if (isToday) Modifier.background(g.accentBrush) else Modifier.background(g.accent.copy(alpha = 0.45f))),
                            )
                        }
                        Text(
                            d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), fontSize = 12.sp,
                            color = if (isToday) g.text else g.text2, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Stat("This week", formatDuration(values.sum()), Modifier.weight(1f))
            Stat("Streak", readingStreak(log).let { if (it == 1) "1 day" else "$it days" }, Modifier.weight(1f))
            Stat("All time", formatDuration(totalMs), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val g = LocalG.current
    Column(modifier) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.text)
        Text(label, fontSize = 13.sp, color = g.text2)
    }
}

/** Progress towards the daily goal; once reached, shows how far past it today's reading went. */
@Composable
fun GoalProgress(todayMs: Long, goalMin: Int) {
    val g = LocalG.current
    val goalMs = goalMin * 60_000L
    val reached = todayMs >= goalMs
    Column(Modifier.padding(top = 6.dp, end = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(g.fill)) {
            Box(Modifier.fillMaxWidth((todayMs.toFloat() / goalMs).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(g.accentBrush))
        }
        Text(
            if (reached) {
                val extra = (todayMs - goalMs) / 60_000
                "Goal reached 🎉" + if (extra > 0) " · +$extra min" else ""
            } else "of $goalMin min goal",
            fontSize = 13.sp, color = if (reached) g.accent else g.text2, fontWeight = if (reached) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Celebrates reaching the daily goal while reading, then gets out of the way so reading can continue. */
@Composable
fun GoalBanner(vm: AppViewModel, modifier: Modifier = Modifier) {
    val g = LocalG.current
    val haptics = rememberHaptics()
    val hit by vm.goalReached.collectAsState()
    var shown by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(hit) {
        val m = hit ?: return@LaunchedEffect
        shown = m
        haptics.select()
        delay(5_000)
        shown = null
        vm.goalReached.value = null
    }
    AnimatedVisibility(shown != null, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
                .panel(RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎉", fontSize = 30.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Daily goal reached!", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = g.text)
                Text(
                    "${shown ?: 0} min today — keep going, the extra time counts too.",
                    fontSize = 15.sp, color = g.text2,
                )
            }
        }
    }
}
