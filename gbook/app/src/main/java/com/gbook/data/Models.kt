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
)

@Serializable
data class LibraryData(
    val books: List<Book> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val posts: List<Post> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
    val seeded: Boolean = false,
    val lastBackupAt: Long = 0,
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
