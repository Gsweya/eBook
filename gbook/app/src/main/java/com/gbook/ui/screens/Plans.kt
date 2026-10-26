package com.gbook.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.gbook.AppViewModel
import com.gbook.data.Book
import com.gbook.data.ReadingPlan
import com.gbook.data.Reminder
import com.gbook.data.dueLabel
import com.gbook.data.estMinutesPerDay
import com.gbook.data.paceText
import com.gbook.data.planStatus
import com.gbook.ui.components.BookCover
import com.gbook.ui.components.GSheet
import com.gbook.ui.components.GSwitch
import com.gbook.ui.components.glass
import com.gbook.ui.components.PrimaryButton
import com.gbook.ui.components.panel
import com.gbook.ui.theme.DisplaySerif
import com.gbook.ui.theme.LocalG
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val OnTrack = Color(0xFF34C759)
private val Behind = Color(0xFFFF9F0A)

/** Returns a function that asks for notification permission (Android 13+) if it isn't granted yet. */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(ctx) {
        {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun timeLabel(hour: Int, minute: Int): String {
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault()))
}

fun daysLabel(days: Set<Int>): String = when {
    days.size == 7 -> "Every day"
    days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
    days == setOf(6, 7) -> "Weekends"
    days.isEmpty() -> "Never"
    else -> days.sorted().joinToString(", ") { java.time.DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

/** Monday-to-Sunday toggle chips. */
@Composable
fun DayChips(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (d in 1..7) {
            val on = d in days
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(if (on) g.accent else g.fill)
                    .clickable { onChange(if (on) days - d else days + d) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    java.time.DayOfWeek.of(d).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) g.onAccent else g.text,
                )
            }
        }
    }
}

/** Pick a reminder time and days. [onDelete] adds a delete action for an existing reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(initial: Reminder?, onDismiss: () -> Unit, onSave: (Reminder) -> Unit, onDelete: (() -> Unit)? = null) {
    val g = LocalG.current
    val ctx = LocalContext.current
    val time = rememberTimePickerState(initial?.hour ?: 20, initial?.minute ?: 0, DateFormat.is24HourFormat(ctx))
    var days by remember { mutableStateOf(initial?.days ?: (1..7).toSet()) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.panel(RoundedCornerShape(32.dp), tint = g.sheet).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (initial == null) "New reminder" else "Edit reminder", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = g.text,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            TimePicker(
                time,
                colors = TimePickerDefaults.colors(
                    clockDialColor = g.fill, clockDialSelectedContentColor = g.onAccent, clockDialUnselectedContentColor = g.text,
                    selectorColor = g.accent, containerColor = g.sheet,
                    periodSelectorBorderColor = g.separator, periodSelectorSelectedContainerColor = g.accent.copy(alpha = 0.25f),
                    periodSelectorUnselectedContainerColor = Color.Transparent, periodSelectorSelectedContentColor = g.text,
                    periodSelectorUnselectedContentColor = g.text2, timeSelectorSelectedContainerColor = g.accent.copy(alpha = 0.25f),
                    timeSelectorUnselectedContainerColor = g.fill, timeSelectorSelectedContentColor = g.text,
                    timeSelectorUnselectedContentColor = g.text,
                ),
            )
            Text("Repeat · ${daysLabel(days)}", fontSize = 15.sp, color = g.text2, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp))
            DayChips(days) { days = it }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) TextButton(onDelete) { Text("Delete", color = g.destructive) }
                Spacer(Modifier.weight(1f))
                TextButton(onDismiss) { Text("Cancel", color = g.text) }
                TextButton(
                    { onSave(Reminder(initial?.id ?: UUID.randomUUID().toString().take(12), time.hour, time.minute, days, true, initial?.bookId)) },
                    enabled = days.isNotEmpty(),
                ) { Text("Save", color = g.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Finish-by plan for a book: target date, the daily pace it needs, and an optional reminder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSheet(vm: AppViewModel, book: Book, onDismiss: () -> Unit) {
    val g = LocalG.current
    val data = vm.data.value
    val existing = data.plans.firstOrNull { it.bookId == book.id }
    val existingReminder = data.reminders.firstOrNull { it.id == vm.planReminderId(book.id) }
    val today = LocalDate.now()
    var target by remember { mutableStateOf(existing?.let { LocalDate.parse(it.targetDate) } ?: today.plusWeeks(2)) }
    var reminder by remember { mutableStateOf(existingReminder ?: Reminder("", 20, 0)) }
    var remind by remember { mutableStateOf(existing == null || existingReminder != null) }
    var editingTime by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    val askPermission = rememberNotificationPermission()

    val preview = ReadingPlan(book.id, target.toString(), existing?.startDate ?: today.toString(), existing?.startProgress ?: book.progress)
    val status = planStatus(preview, book, today)
    val minutes = estMinutesPerDay(book, status.perDay)

    GSheet(onDismiss, "Reading plan") {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(72.dp), elevation = 4.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(book.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${(book.progress * 100).toInt()}% read", fontSize = 15.sp, color = g.text2)
                }
            }
            Text("FINISH BY", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = g.text2, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("1 week" to today.plusWeeks(1), "2 weeks" to today.plusWeeks(2), "1 month" to today.plusMonths(1), "3 months" to today.plusMonths(3))
                    .forEach { (label, date) -> SelectChip(label, target == date) { target = date } }
                val custom = target !in listOf(today.plusWeeks(1), today.plusWeeks(2), today.plusMonths(1), today.plusMonths(3))
                SelectChip(if (custom) target.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) else "Pick a date", custom) { pickingDate = true }
            }
            // The pace this plan asks for.
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(g.accent.copy(alpha = 0.12f)).padding(18.dp)) {
                Text(if (status.done) "You've finished this book" else "Read ${paceText(book, status.perDay)}", fontFamily = DisplaySerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = g.text)
                Text(
                    buildString {
                        append(target.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())))
                        append(" · ${status.dueLabel()}")
                        minutes?.let { append(" · about $it min a day") }
                    },
                    fontSize = 15.sp, color = g.text2, modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text("REMINDER", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = g.text2, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(g.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind me to read", fontSize = 17.sp, color = g.text, modifier = Modifier.weight(1f))
                    GSwitch(remind) { on -> remind = on; if (on) askPermission() }
                }
                if (remind) {
                    HorizontalDivider(Modifier.padding(start = 18.dp), color = g.separator)
                    Row(Modifier.fillMaxWidth().clickable { editingTime = true }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(timeLabel(reminder.hour, reminder.minute), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = g.text)
                            Text(daysLabel(reminder.days), fontSize = 15.sp, color = g.text2)
                        }
                        Text("Change", fontSize = 16.sp, color = g.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            PrimaryButton(
                if (existing == null) "Start plan" else "Update plan",
                onClick = {
                    vm.savePlan(preview, reminder.takeIf { remind })
                    vm.toast(if (existing == null) "Plan started — ${paceText(book, status.perDay)}" else "Plan updated")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            )
            if (existing != null) TextButton({ vm.deletePlan(book.id); onDismiss() }, Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)) {
                Text("Remove plan", color = g.destructive)
            }
        }
    }
    if (editingTime) ReminderDialog(reminder, onDismiss = { editingTime = false }, onSave = { reminder = it; editingTime = false })
    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = target.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { target = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("Done", color = g.accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton({ pickingDate = false }) { Text("Cancel", color = g.text) } },
            colors = DatePickerDefaults.colors(containerColor = g.sheet),
        ) {
            DatePicker(
                state, showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = g.sheet, selectedDayContainerColor = g.accent, selectedDayContentColor = g.onAccent,
                    todayDateBorderColor = g.accent, todayContentColor = g.accent, dayContentColor = g.text,
                    weekdayContentColor = g.text2, navigationContentColor = g.text, yearContentColor = g.text,
                    titleContentColor = g.text2, headlineContentColor = g.text, subheadContentColor = g.text2,
                ),
            )
        }
    }
}

@Composable
fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val g = LocalG.current
    Text(
        label, fontSize = 16.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = if (selected) g.accent else g.text,
        modifier = Modifier.clip(CircleShape).background(if (selected) g.accent.copy(alpha = 0.18f) else g.fill)
            .then(if (selected) Modifier.border(1.dp, g.accent.copy(alpha = 0.6f), CircleShape) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** Plan summary: target date, days left, daily pace, a progress bar with the on-schedule marker, and status. */
@Composable
fun PlanCard(vm: AppViewModel, plan: ReadingPlan, book: Book, onClick: () -> Unit, modifier: Modifier = Modifier, frosted: Boolean = false) {
    val g = LocalG.current
    val st = planStatus(plan, book)
    val onTrack = st.onTrack(book.progress)
    val (label, color) = when {
        st.done -> "Done 🎉" to g.accent
        st.overdue -> "Overdue" to g.destructive
        onTrack -> "On track" to OnTrack
        else -> "Behind" to Behind
    }
    Row(
        modifier.fillMaxWidth()
            .then(
                if (frosted) Modifier.glass(RoundedCornerShape(22.dp))
                else Modifier.clip(RoundedCornerShape(22.dp)).background(g.surface)
                    .border(0.75.dp, g.separator.copy(alpha = if (g.dark) 0.55f else 1f), RoundedCornerShape(22.dp)),
            )
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(vm.coverPath(book), book.title, book.author, book.coverColor, Modifier.height(64.dp), elevation = 3.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(book.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color,
                    modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Text(
                if (st.done) "Finished" else "Finish by " + LocalDate.parse(plan.targetDate).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) +
                    " · ${st.dueLabel()} · ${paceText(book, st.perDay)}",
                fontSize = 14.sp, color = g.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
            )
            // Progress, with a tick where the reader should be today.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(top = 10.dp).fillMaxWidth().height(8.dp)) {
                Box(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center).clip(CircleShape).background(g.fill))
                Box(Modifier.fillMaxWidth(book.progress.coerceIn(0.02f, 1f)).height(6.dp).align(Alignment.CenterStart).clip(CircleShape).background(g.accentBrush))
                if (!st.done) Box(Modifier.offset(x = maxWidth * st.expected.coerceIn(0f, 1f) - 1.dp).width(2.dp).fillMaxHeight().background(g.text.copy(alpha = 0.7f)))
            }
        }
    }
}
