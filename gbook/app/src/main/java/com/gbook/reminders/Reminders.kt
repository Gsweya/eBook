package com.gbook.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gbook.MainActivity
import com.gbook.R
import com.gbook.data.GJson
import com.gbook.data.LibraryData
import com.gbook.data.Reminder
import com.gbook.data.dueLabel
import com.gbook.data.paceText
import com.gbook.data.planStatus
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

const val EXTRA_OPEN_BOOK = "com.gbook.OPEN_BOOK"
private const val ACTION_REMIND = "com.gbook.REMIND"
private const val CHANNEL = "reading_reminders"

/**
 * Schedules reading reminders with inexact alarms (no exact-alarm permission needed; they fire within a few
 * minutes of the set time). Each reminder schedules only its next occurrence; the receiver re-arms it.
 */
object ReminderScheduler {
    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Reminders from your reading timetable and book plans" },
            )
        }
    }

    /** Cancels every previously scheduled reminder and schedules the enabled ones in [reminders]. */
    fun sync(ctx: Context, reminders: List<Reminder>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val prefs = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        prefs.getStringSet("scheduled", emptySet()).orEmpty().forEach { am.cancel(intentFor(ctx, it)) }
        val active = reminders.filter { it.enabled && it.days.isNotEmpty() }
        active.forEach { schedule(ctx, it) }
        prefs.edit().putStringSet("scheduled", active.map { it.id }.toSet()).apply()
    }

    fun schedule(ctx: Context, r: Reminder) {
        val at = nextTrigger(r) ?: return
        ctx.getSystemService(AlarmManager::class.java)
            .setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, intentFor(ctx, r.id))
    }

    /** Next time after [from] that falls on one of the reminder's days at its hour and minute. */
    fun nextTrigger(r: Reminder, from: ZonedDateTime = ZonedDateTime.now()): Long? {
        if (r.days.isEmpty()) return null
        var t = from.withHour(r.hour).withMinute(r.minute).withSecond(0).withNano(0)
        repeat(8) {
            if (t.isAfter(from) && t.dayOfWeek.value in r.days) return t.toInstant().toEpochMilli()
            t = t.plusDays(1)
        }
        return null
    }

    private fun intentFor(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.hashCode(),
        Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_REMIND).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun loadLibrary(ctx: Context): LibraryData = runCatching {
        GJson.decodeFromString<LibraryData>(File(ctx.filesDir, "library.json").readText())
    }.getOrDefault(LibraryData())
}

/** Posts the reminder notification, then schedules the reminder's next occurrence. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val data = ReminderScheduler.loadLibrary(ctx)
        val r = data.reminders.firstOrNull { it.id == id && it.enabled } ?: return
        ReminderScheduler.schedule(ctx, r)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val book = data.books.firstOrNull { it.id == r.bookId }
            ?: data.books.filter { it.lastOpenedAt > 0 }.maxByOrNull { it.lastOpenedAt }
        val todayMin = (data.readingLog[LocalDate.now().toString()] ?: 0L) / 60_000
        val goal = data.settings.dailyGoalMin
        val plan = book?.let { b -> data.plans.firstOrNull { it.bookId == b.id } }
        val title = if (r.bookId != null && book != null) "Time to read ${book.title}" else "Time for your reading"
        val text = when {
            plan != null && book != null -> planStatus(plan, book).let { st ->
                if (st.done) "You finished it — pick your next book" else "${st.dueLabel().replaceFirstChar { it.uppercase() }} · ${paceText(book, st.perDay)} keeps you on plan"
            }
            goal > 0 && todayMin >= goal -> "You've hit today's $goal-minute goal. A few more pages?"
            goal > 0 -> "$todayMin of $goal minutes today" + (book?.let { " · continue ${it.title}" } ?: "")
            book != null -> "Pick up ${book.title} where you left off (${(book.progress * 100).toInt()}%)"
            else -> "Open GBook and read a few pages"
        }
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { book?.let { putExtra(EXTRA_OPEN_BOOK, it.id) } }
        val tap = PendingIntent.getActivity(ctx, id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        ReminderScheduler.ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_book_glyph)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
    }
}

/** Re-arms reminders after a reboot, an app update, or a clock or time zone change. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ReminderScheduler.sync(ctx, ReminderScheduler.loadLibrary(ctx).reminders)
    }
}
