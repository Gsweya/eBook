package com.gbook.data

import kotlinx.serialization.Serializable

@Serializable
enum class BookFormat { EPUB, PDF }

@Serializable
data class ReadingPosition(
    val chapter: Int = 0,
    val item: Int = 0,
    val offset: Int = 0,
    val pdfPage: Int = 0,
)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val fileName: String,
    val hasCover: Boolean = false,
    val coverColor: Long = 0xFF8F3D26,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    val chapterCount: Int = 0,
    val pageCount: Int = 0,
    val progress: Float = 0f,
    val position: ReadingPosition = ReadingPosition(),
    /** Emoji -> count. The user's own pick is [myReaction]. */
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
    /** For PDFs: whether reflowable text has been extracted into content.json. */
    val textReady: Boolean = true,
    val backedUpAt: Long = 0,
    /** Total active reading time in this book. */
    val readingMs: Long = 0,
    /** Pages the reader has dwelt on long enough to count as read, in either reading mode. */
    val pagesRead: Int = 0,
)

@Serializable
data class Highlight(
    val id: String,
    val bookId: String,
    val chapter: Int,
    val block: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int = 0,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Last paragraph covered, with [end] an offset into it; -1 means the highlight stays within [block]. */
    val endBlock: Int = -1,
    /** 0 filled marker, 1 underline. */
    val style: Int = 0,
    /** Emoji reaction from [ReactionEmojis], or null. */
    val reaction: String? = null,
) {
    val lastBlock get() = if (endBlock < 0) block else endBlock

    fun covers(b: Int) = b in block..lastBlock

    /** Character range of this highlight inside paragraph [b] of length [len]. */
    fun rangeIn(b: Int, len: Int): IntRange {
        val s = if (b == block) start.coerceIn(0, len) else 0
        val e = if (b == lastBlock) end.coerceIn(0, len) else len
        return s until e
    }
}

@Serializable
enum class PostKind { QUOTE, IDEA, RECAP }

@Serializable
data class Post(
    val id: String,
    val bookId: String,
    val kind: PostKind,
    /** One entry per carousel slide. */
    val slides: List<String>,
    val chapterTitle: String = "",
    val chapter: Int = 0,
    val highlightId: String? = null,
    val style: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val liked: Boolean = false,
    val saved: Boolean = false,
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
)

@Serializable
data class ReaderSettings(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.5f,
    val theme: Int = 0,
    val font: String = "literata",
    val bold: Boolean = false,
    val justify: Boolean = false,
    val margin: Int = 24,
    val autoPostHighlights: Boolean = false,
    /** App accent palette id (see AccentPalettes). */
    val accent: String = "amber",
    /** 0 follow system, 1 light, 2 dark. */
    val appearance: Int = 0,
    /** Pure black backgrounds in dark mode. */
    val amoled: Boolean = false,
    /** Style for new highlights: 0 filled marker, 1 underline. */
    val highlightStyle: Int = 0,
    /** Bottom bar: 0 floating pill, 1 classic capsule with a separate settings button. */
    val navStyle: Int = 0,
    /** Bottom bar icons: 0 standard symbols, 1 Noto 3D emoji. */
    val navIcons: Int = 0,
    val profileName: String = "",
    /** Changes whenever the profile picture changes; 0 means none. */
    val avatarVersion: Long = 0,
    /** Daily reading goal in minutes; 0 means no goal. */
    val dailyGoalMin: Int = 0,
    /** 0 scroll through the whole chapter, 1 one page at a time, turned by tapping or swiping. */
    val pageMode: Int = 0,
    /** Side strip that walks a marker through the text as the finger slides along it. */
    val tracker: Boolean = false,
    /** Tracker step: 0 word, 1 sentence. */
    val trackerUnit: Int = 0,
)

@Serializable
data class LibraryData(
    val books: List<Book> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val posts: List<Post> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
    val seeded: Boolean = false,
    val lastBackupAt: Long = 0,
    /** Active reading time per local day ("yyyy-MM-dd"), across all books. */
    val readingLog: Map<String, Long> = emptyMap(),
    /** Active reading time per local day, per book id. */
    val bookDays: Map<String, Map<String, Long>> = emptyMap(),
    val reminders: List<Reminder> = emptyList(),
    val plans: List<ReadingPlan> = emptyList(),
    /** Last day ("yyyy-MM-dd") the daily goal was celebrated, so it is announced once a day. */
    val goalReachedDay: String = "",
    val collections: List<BookCollection> = emptyList(),
    /** Pages read per local day, across all books. */
    val pagesLog: Map<String, Int> = emptyMap(),
    /** Whether the first-run welcome (profile and first books) has been completed or skipped. */
    val onboarded: Boolean = false,
)

/** A named, user-made group of books. */
@Serializable
data class BookCollection(
    val id: String,
    val name: String,
    val bookIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

/** A reading reminder at [hour]:[minute] on [days] (ISO day numbers, Monday = 1). [bookId] ties it to a book. */
@Serializable
data class Reminder(
    val id: String,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = (1..7).toSet(),
    val enabled: Boolean = true,
    val bookId: String? = null,
)

/** Goal to finish [bookId] by [targetDate]; [startProgress] on [startDate] anchors the on-track check. Dates are "yyyy-MM-dd". */
@Serializable
data class ReadingPlan(
    val bookId: String,
    val targetDate: String,
    val startDate: String,
    val startProgress: Float,
)

@Serializable
data class Block(
    /** 0 = paragraph, 1 = heading, 2 = image */
    val type: Int,
    val text: String = "",
    val level: Int = 0,
    val src: String? = null,
    /** Source page for PDF-derived text, -1 otherwise. */
    val page: Int = -1,
)

@Serializable
data class Chapter(val title: String, val blocks: List<Block>, val startPage: Int = -1)

@Serializable
data class BookContent(val chapters: List<Chapter>)

val ReactionEmojis = listOf("❤️", "🔥", "💡", "🤯", "😂", "😢")
