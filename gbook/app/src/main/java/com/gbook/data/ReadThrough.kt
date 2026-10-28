package com.gbook.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Progress at which a book counts as finished. */
const val FINISHED = 0.995f

/** How a book has been read: from its first reading day to the day it was finished (or today, while unfinished). */
class ReadThrough(
    val start: LocalDate,
    /** The day the book was finished, or null while it is still being read. */
    val end: LocalDate?,
    /** Calendar days from [start] to [end] (or today), both counted. */
    val days: Int,
    /** Days with any reading time in this book. */
    val readingDays: Int,
    val readingMs: Long,
) {
    val finished get() = end != null

    /** "Finished in 12 days", "Finished in a day", or "Reading for 5 days". */
    fun headline(): String = when {
        finished && days <= 1 -> "Finished in a day"
        finished -> "Finished in $days days"
        days <= 1 -> "Started today"
        else -> "Reading for $days days"
    }

    /** "3 Oct – 14 Oct", or a single date when it all happened on one day; the year is added when it isn't this year. */
    fun dateRange(today: LocalDate = LocalDate.now()): String {
        val last = end ?: today
        fun fmt(d: LocalDate) = d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "d MMM" else "d MMM yyyy", Locale.getDefault()))
        return if (start == last) fmt(start) else "${fmt(start)} – ${if (end == null) "today" else fmt(last)}"
    }
}

/**
 * Works out [book]'s read-through from its own start and finish times, falling back to the per-day reading log
 * ([bookDays]) for books read before those were recorded. Null for a book that hasn't been read yet.
 */
fun readThrough(book: Book, bookDays: Map<String, Map<String, Long>>, today: LocalDate = LocalDate.now()): ReadThrough? {
    val read = bookDays.mapNotNull { (day, books) ->
        if ((books[book.id] ?: 0L) > 0) runCatching { LocalDate.parse(day) }.getOrNull() else null
    }.sorted()
    val zone = ZoneId.systemDefault()
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val start = listOfNotNull(book.startedAt.takeIf { it > 0 }?.let(::day), read.firstOrNull()).minOrNull() ?: return null
    val end = when {
        book.finishedAt > 0 -> day(book.finishedAt)
        // Finished before finish times were kept: the last day it was read.
        book.progress >= FINISHED -> read.lastOrNull() ?: book.positionAt.takeIf { it > 0 }?.let(::day) ?: start
        else -> null
    }?.let { maxOf(it, start) }
    val days = ChronoUnit.DAYS.between(start, end ?: today).toInt() + 1
    return ReadThrough(start, end, days.coerceAtLeast(1), read.size, book.readingMs)
}
