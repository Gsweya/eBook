package com.gbook.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gbook.AppViewModel
import com.gbook.data.Book
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.formatDuration
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Reading time over the last 7 or 30 days (or all time) and the books read most in that period. */
@Composable
fun ReadingActivitySheet(vm: AppViewModel, onOpen: (Book) -> Unit, onDismiss: () -> Unit) {
    val g = LocalG.current
    val data by vm.data.collectAsState()
    var range by rememberSaveable { mutableIntStateOf(0) }
    val today = LocalDate.now()
    val days = when (range) { 0 -> 7; 1 -> 30; else -> 0 }
    // Oldest first, ending today.
    val dates = remember(range, today) { (days - 1 downTo 0).map { today.minusDays(it.toLong()) } }
    val ranked = remember(data, range) {
        val totals = if (range == 2) data.books.associate { it.id to it.readingMs }
        else HashMap<String, Long>().also { m -> dates.forEach { d -> data.bookDays[d.toString()]?.forEach { (id, ms) -> m.merge(id, ms, Long::plus) } } }
        data.books.mapNotNull { b -> totals[b.id]?.takeIf { it > 0 }?.let { b to it } }.sortedByDescending { it.second }
    }
    val total = if (range == 2) data.books.sumOf { it.readingMs } else dates.sumOf { data.readingLog[it.toString()] ?: 0L }

    GSheet(onDismiss, "Reading activity", full = true, heightFraction = 0.9f) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Segmented(listOf("7 days", "30 days", "All time"), range, { range = it })
            }
            Text(
                formatDuration(total), fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = g.text,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp),
            )
            Text(
                buildString {
                    if (days > 0) append("about ${formatDuration(total / days)} a day · ")
                    append(if (ranked.size == 1) "1 book" else "${ranked.size} books")
                },
                fontSize = 16.sp, color = g.text2, modifier = Modifier.padding(start = 20.dp),
            )
            if (days > 0) DayBars(dates, dates.map { data.readingLog[it.toString()] ?: 0L })

            Text(
                "MOST READ", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = g.text2,
                modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp),
            )
            if (ranked.isEmpty()) {
                Text(
                    "Open a book and start reading — the books you spend the most time with will show up here.",
                    fontSize = 16.sp, color = g.text2, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            val top = ranked.firstOrNull()?.second ?: 1L
            ranked.forEachIndexed { i, (book, ms) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(book) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}", fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        color = if (i < 3) g.accent else g.text2, modifier = Modifier.width(28.dp),
                    )
                    BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(64.dp), elevation = 3.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(book.author, fontSize = 14.sp, color = g.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(g.fill)) {
                            Box(Modifier.fillMaxWidth((ms.toFloat() / top).coerceIn(0.03f, 1f)).fillMaxHeight().clip(CircleShape).background(g.accentBrush))
                        }
                    }
                    Text(formatDuration(ms), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.text, modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}

/** Daily bars; weekday initials for a week, start and end dates for longer ranges. */
@Composable
private fun DayBars(dates: List<LocalDate>, values: List<Long>) {
    val g = LocalG.current
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(15 * 60_000L)
    val week = dates.size <= 7
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(if (week) 10.dp else 3.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                val isToday = i == values.lastIndex
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp)).background(g.fill))
                    if (v > 0) Box(
                        Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max).coerceIn(0.04f, 1f)).clip(RoundedCornerShape(if (week) 6.dp else 3.dp))
                            .then(if (isToday) Modifier.background(g.accentBrush) else Modifier.background(g.accent.copy(alpha = 0.5f))),
                    )
                }
            }
        }
        if (week) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dates.forEachIndexed { i, d ->
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), fontSize = 12.sp,
                        color = if (i == dates.lastIndex) g.text else g.text2, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(dates.first().format(fmt), fontSize = 12.sp, color = g.text2, modifier = Modifier.weight(1f))
                Text("Today", fontSize = 12.sp, color = g.text)
            }
        }
    }
}
