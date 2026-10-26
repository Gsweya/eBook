package com.gbook.ui.screens

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.gbook.data.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Pixel metrics the reader lays text out with; [pageH] is the height of text one screen shows. */
class PageMetrics(
    val width: Int,
    val pageH: Float,
    val textPx: Float,
    val lineHeightPx: Float,
    val indentPx: Int,
    val paraGapPx: Float,
    val justify: Boolean,
    val body: Typeface,
    val bold: Typeface,
    /** dp to px. */
    val dp: Float,
)

/**
 * Estimated page layout of a book: the top of every block in each chapter, as laid out at the current text
 * settings, and how many screen-sized pages each chapter fills. Used for "page 12 of 340" in both the scrolling
 * and the page-turning reader.
 */
class PageMap(private val tops: List<FloatArray>, private val step: Float) {
    private val counts = IntArray(tops.size) { c -> ceil(tops[c].last() / step).toInt().coerceAtLeast(1) }
    private val starts = IntArray(tops.size).also { s -> for (c in 1 until tops.size) s[c] = s[c - 1] + counts[c - 1] }
    val total = starts.lastOrNull()?.plus(counts.last()) ?: 0

    fun pagesIn(chapter: Int) = counts.getOrElse(chapter) { 1 }

    /** Page (0-based, within [chapter]) whose top edge shows [item] scrolled by [offset] px. */
    fun pageIn(chapter: Int, item: Int, offset: Int): Int {
        val t = tops.getOrNull(chapter) ?: return 0
        val y = t[item.coerceIn(0, t.lastIndex)] + offset
        return floor((y + 1f) / step).toInt().coerceIn(0, pagesIn(chapter) - 1)
    }

    /** First page of [chapter] in book-wide numbering (0-based). */
    fun startOf(chapter: Int) = starts.getOrElse(chapter) { 0 }
}

/** Approximate height of the end-of-chapter card, in dp. */
private const val CHAPTER_END_DP = 260f

suspend fun measurePages(chapters: List<Chapter>, m: PageMetrics, imageSize: (String) -> Pair<Int, Int>?): PageMap = withContext(Dispatchers.Default) {
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    fun lines(text: String, size: Float, face: Typeface, indent: Int, justify: Boolean, centered: Boolean): Int {
        if (text.isEmpty()) return 1
        paint.textSize = size; paint.typeface = face
        val b = StaticLayout.Builder.obtain(text, 0, text.length, paint, m.width.coerceAtLeast(1))
            .setAlignment(if (centered) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        // The last indent repeats for every later line, so the 0 keeps it to the first line only.
        if (indent > 0) b.setIndents(intArrayOf(indent, 0), null)
        if (justify && Build.VERSION.SDK_INT >= 26) b.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
        return b.build().lineCount
    }
    val tops = chapters.map { ch ->
        coroutineContext.ensureActive()
        val arr = FloatArray(ch.blocks.size + 1)
        var y = 0f
        ch.blocks.forEachIndexed { i, b ->
            arr[i] = y
            y += when (b.type) {
                1 -> {
                    val big = b.level <= 2
                    val size = m.textPx * if (big) 1.5f else 1.2f
                    val lh = m.textPx * if (big) 1.9f else 1.55f
                    lines(b.text, size, m.bold, 0, false, true) * lh + (if (i == 0) 8f else 28f) * m.dp + 16f * m.dp
                }
                2 -> imageSize(b.src.orEmpty())?.let { (w, h) ->
                    val aspect = w.toFloat() / h.coerceAtLeast(1)
                    val shownW = min(m.width.toFloat(), 520f * m.dp * aspect)
                    shownW / aspect + 24f * m.dp
                } ?: 0f
                else -> lines(b.text, m.textPx, m.body, m.indentPx, m.justify, false) * m.lineHeightPx + m.paraGapPx
            }
        }
        arr[ch.blocks.size] = y + CHAPTER_END_DP * m.dp
        arr
    }
    // Page turns align to whole lines, so on average each page advances a little less than a full screen.
    PageMap(tops, (m.pageH - m.lineHeightPx * 0.5f).coerceAtLeast(m.lineHeightPx))
}

/** Pixel size of an image file without decoding it. */
fun imageBounds(path: String): Pair<Int, Int>? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}

/** Character ranges (end exclusive) of each word or sentence in [text], for the reading tracker. */
fun trackerUnits(text: String, sentences: Boolean): List<IntRange> {
    val re = if (sentences) SENTENCE else WORD
    return re.findAll(text).map { r ->
        var e = r.range.last + 1
        while (e > r.range.first && text[e - 1].isWhitespace()) e--
        r.range.first until e
    }.filter { !it.isEmpty() }.toList()
}

private val WORD = Regex("\\S+")
private val SENTENCE = Regex("[^.!?…]+(?:[.!?…]+[\"'”’)\\]]*|$)\\s*")
