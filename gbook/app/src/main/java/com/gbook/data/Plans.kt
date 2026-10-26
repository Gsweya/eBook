package com.gbook.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

/** Where a reader stands against a [ReadingPlan] today. Fractions are of the whole book (0..1). */
class PlanStatus(
    val daysLeft: Int,
    /** Share of the book to read each remaining day, today included. */
    val perDay: Float,
    /** How far along the reader should be by today to stay on schedule. */
    val expected: Float,
    val done: Boolean,
    val overdue: Boolean,
) {
    fun onTrack(progress: Float) = done || progress >= expected - 0.02f
}

fun planStatus(plan: ReadingPlan, book: Book, today: LocalDate = LocalDate.now()): PlanStatus {
    val target = LocalDate.parse(plan.targetDate)
    val start = LocalDate.parse(plan.startDate)
    val done = book.progress >= 0.995f
    val daysLeft = ChronoUnit.DAYS.between(today, target).toInt() + 1
    val total = (ChronoUnit.DAYS.between(start, target).toInt() + 1).coerceAtLeast(1)
    val elapsed = ChronoUnit.DAYS.between(start, today).toInt().coerceIn(0, total)
    val expected = plan.startProgress + (1f - plan.startProgress) * elapsed / total
    val perDay = if (done) 0f else (1f - book.progress) / daysLeft.coerceAtLeast(1)
    return PlanStatus(daysLeft, perDay, expected, done, overdue = !done && daysLeft <= 0)
}

/** "2 chapters a day", "12 pages a day" or "8% a day" for a daily share of the book. */
fun paceText(book: Book, perDay: Float): String = when {
    book.format == BookFormat.PDF && book.pageCount > 0 -> ceil(book.pageCount * perDay).toInt().coerceAtLeast(1).let { "$it ${if (it == 1) "page" else "pages"} a day" }
    book.chapterCount > 1 -> ceil(book.chapterCount * perDay).toInt().coerceAtLeast(1).let { "$it ${if (it == 1) "chapter" else "chapters"} a day" }
    else -> "${ceil(perDay * 100).toInt().coerceAtLeast(1)}% a day"
}

/** Minutes a day the pace needs, from the reader's own speed in this book; null until there is enough history. */
fun estMinutesPerDay(book: Book, perDay: Float): Int? {
    if (book.progress < 0.02f || book.readingMs < 5 * 60_000L) return null
    val msPerFraction = book.readingMs / book.progress
    return ceil(msPerFraction * perDay / 60_000.0).toInt().coerceAtLeast(1)
}

/** "7 days left", "1 day left", "due today" or "overdue" (today counts as a reading day in [PlanStatus.daysLeft]). */
fun PlanStatus.dueLabel(): String = when (val n = daysLeft - 1) {
    in Int.MIN_VALUE..-1 -> "overdue"
    0 -> "due today"
    1 -> "1 day left"
    else -> "$n days left"
}
