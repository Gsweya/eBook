package com.gbook.data

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.roundToInt

/** One word as laid out on a PDF page. [y] is the baseline, growing downwards. */
internal class PdfWord(val page: Int, val text: String, val x0: Float, val x1: Float, val y: Float, val size: Float, val bold: Boolean)

/** One visual line of a page. */
internal class PdfLine(val page: Int, val words: List<PdfWord>) {
    val text = words.joinToString(" ") { it.text }
    val x0 = words.minOf { it.x0 }
    val x1 = words.maxOf { it.x1 }
    // The median baseline, so superscripts and drop caps don't move the line.
    val y = words.map { it.y }.sorted()[words.size / 2]
    private val chars = words.sumOf { it.text.length }.coerceAtLeast(1)
    val size = words.sumOf { (it.size * it.text.length).toDouble() }.toFloat() / chars
    val bold = words.filter { it.bold }.sumOf { it.text.length } * 2 > chars
    val caps = text.filter { it.isLetter() }.let { it.length >= 3 && it == it.uppercase() }
}

/** Collects every word of a document with its position, size and weight. */
internal class WordCollector(private val onPageDone: (Int) -> Unit) : PDFTextStripper() {
    private val words = ArrayList<PdfWord>()
    val pageHeights = ArrayList<Float>()

    init { sortByPosition = true }

    override fun startPage(page: PDPage) {
        pageHeights += page.mediaBox.height
        super.startPage(page)
    }

    override fun endPage(page: PDPage) {
        super.endPage(page)
        onPageDone(currentPageNo)
    }

    override fun writeString(text: String, positions: MutableList<TextPosition>) {
        val page = currentPageNo - 1
        var run = ArrayList<TextPosition>()
        fun flush() { if (run.isNotEmpty()) { words += word(page, run); run = ArrayList() } }
        for (p in positions) if (p.unicode.isNullOrBlank()) flush() else run += p
        flush()
    }

    private fun word(page: Int, ps: List<TextPosition>): PdfWord {
        val first = ps.first()
        val name = first.font?.name.orEmpty().lowercase()
        val desc = runCatching { first.font?.fontDescriptor }.getOrNull()
        val bold = listOf("bold", "black", "heavy", "semibold", "demi").any { it in name } ||
            (desc?.fontWeight ?: 0f) >= 600f || desc?.isForceBold == true
        // Some PDFs scale text with the matrix and report a 1pt font; the glyph height is the better guide then.
        val size = ps.map { p -> p.fontSizeInPt.takeIf { it > 1.5f } ?: (p.heightDir / 0.7f) }.sorted()[ps.size / 2]
        val last = ps.last()
        return PdfWord(page, ligatures(ps.joinToString("") { it.unicode }), first.xDirAdj, last.xDirAdj + last.widthDirAdj, first.yDirAdj, size, bold)
    }

    fun lines(): List<PdfLine> {
        val out = ArrayList<PdfLine>()
        var cur = ArrayList<PdfWord>()
        for (w in words) {
            val prev = cur.lastOrNull()
            val sameLine = prev != null && prev.page == w.page && abs(prev.y - w.y) < 0.5f * maxOf(prev.size, w.size) && w.x0 >= prev.x0
            if (!sameLine && cur.isNotEmpty()) { out += PdfLine(cur[0].page, cur); cur = ArrayList() }
            cur += w
        }
        if (cur.isNotEmpty()) out += PdfLine(cur[0].page, cur)
        return attachSuperscripts(out)
    }

    /**
     * Folds superscripts (ordinals like the "rd" of 33rd, footnote marks) into the line they're raised above.
     * Set smaller and higher, they otherwise come out as a line of their own, which then breaks the paragraph.
     */
    private fun attachSuperscripts(lines: List<PdfLine>): List<PdfLine> {
        val out = lines.toMutableList()
        val gone = HashSet<Int>()
        for ((i, sup) in lines.withIndex()) {
            if (sup.words.size > 4 || sup.words.any { it.text.length > 4 }) continue
            val host = (maxOf(0, i - 2)..minOf(lines.lastIndex, i + 2)).firstOrNull { j ->
                val h = lines[j]
                j != i && j !in gone && h.page == sup.page && h.words.size > sup.words.size && sup.size < h.size * 0.85f &&
                    h.y - sup.y > 0 && h.y - sup.y < h.size * 0.75f && sup.x0 >= h.x0 - 1f && sup.x1 <= h.x1 + h.size
            } ?: continue
            out[host] = PdfLine(sup.page, out[host].words.withSuperscripts(sup.words))
            gone += i
        }
        return out.filterIndexed { i, _ -> i !in gone }
    }

    /** These words with [sups] glued onto the words they follow (or placed after them if there's a gap). */
    private fun List<PdfWord>.withSuperscripts(sups: List<PdfWord>): List<PdfWord> {
        val ws = toMutableList()
        for (s in sups) {
            val k = ws.indexOfLast { it.x1 <= s.x0 + 0.5f }
            if (k < 0) { ws.add(0, s); continue }
            val w = ws[k]
            if (s.x0 - w.x1 < w.size * 0.3f) ws[k] = PdfWord(w.page, w.text + s.text, w.x0, s.x1, w.y, w.size, w.bold)
            else ws.add(k + 1, s)
        }
        return ws
    }

    private fun ligatures(s: String) = s
        .replace("ﬀ", "ff").replace("ﬁ", "fi").replace("ﬂ", "fl").replace("ﬃ", "ffi").replace("ﬄ", "ffl").replace("ﬅ", "st").replace("ﬆ", "st")
}

/**
 * Turns the lines of a PDF into EPUB-like blocks: paragraphs rebuilt from spacing and indents, headings found by
 * font size (and bold or all-caps lines at body size), and running headers, footers and page numbers dropped.
 */
internal class PdfLayout(all: List<PdfLine>, private val pageHeights: List<Float>) {
    /** Body text size: the size most characters are set in. */
    private val body: Float = all.groupBy { (it.size * 2).roundToInt() }
        .maxByOrNull { (_, ls) -> ls.sumOf { it.text.length } }?.key?.div(2f) ?: 11f
    private val lines = all.filterNot(::isRunningText)
    private val bodyLines = lines.filter { isBodySize(it) }
    private val leftByPage: Map<Int, Float> = bodyLines.groupBy { it.page }.mapValues { (_, ls) -> mode(ls.map { it.x0 }) }
    private val rightByPage: Map<Int, Float> = bodyLines.groupBy { it.page }.mapValues { (_, ls) -> percentile(ls.map { it.x1 }, 0.9f) }
    private val left = mode(bodyLines.map { it.x0 })
    private val right = percentile(bodyLines.map { it.x1 }, 0.9f)
    /** Typical distance between consecutive lines of a paragraph. */
    private val spacing: Float = bodyLines.zipWithNext()
        .mapNotNull { (a, b) -> (b.y - a.y).takeIf { a.page == b.page && it > 0 && it < body * 3 } }
        .let { if (it.isEmpty()) body * 1.3f else percentile(it, 0.5f) }

    private fun isBodySize(l: PdfLine) = abs(l.size - body) <= body * 0.25f
    private fun leftOf(page: Int) = leftByPage[page] ?: left
    private fun rightOf(page: Int) = rightByPage[page] ?: right
    private fun indented(l: PdfLine) = l.x0 > leftOf(l.page) + body * 0.8f

    /** Page numbers, and small lines repeated at the top or bottom of many pages. */
    private val repeated: Set<String> = all.filter(::inMargin).groupBy { key(it) }
        .filter { (_, ls) -> ls.map { it.page }.distinct().size >= maxOf(3, pageHeights.size / 4) }.keys

    private fun inMargin(l: PdfLine): Boolean {
        val h = pageHeights.getOrNull(l.page) ?: return false
        return l.size <= body * 1.1f && (l.y < h * 0.07f || l.y > h * 0.93f)
    }

    private fun key(l: PdfLine) = l.text.lowercase().replace(Regex("\\d+"), "#").trim()

    private fun isRunningText(l: PdfLine): Boolean {
        if (!inMargin(l)) return false
        val t = l.text.trim()
        return t.matches(Regex("(?i)(page\\s*)?[\\divxlcdm]{1,6}(\\s*(of|/)\\s*\\d+)?|[-–—]\\s*\\d+\\s*[-–—]")) || key(l) in repeated
    }

    private sealed interface Item { val page: Int }
    private class Heading(override val page: Int, val text: String, val size: Float, val sub: Boolean) : Item
    private class Para(override val page: Int, val text: String) : Item

    /** Headings, sub-headings and paragraphs in reading order. */
    private val items: List<Item> by lazy {
        val out = ArrayList<Item>()
        var para: StringBuilder? = null
        var paraPage = 0
        var prev: PdfLine? = null
        fun endPara() { para?.let { if (it.isNotBlank()) out += Para(paraPage, it.toString()) }; para = null }

        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val before = lines.getOrNull(i - 1)?.takeIf { it.page == l.page }
            val after = lines.getOrNull(i + 1)?.takeIf { it.page == l.page }

            if (l.size >= body * 1.25f) {
                // A heading, possibly over several lines of the same size; a run that reads like a sentence is
                // display text (an epigraph or pull quote), so it becomes a paragraph instead.
                endPara()
                val run = mutableListOf(l)
                while (true) {
                    val n = lines.getOrNull(i + run.size) ?: break
                    val last = run.last()
                    if (n.page != l.page || abs(n.size - l.size) > l.size * 0.1f || n.y - last.y > l.size * 1.8f || isLabel(last.text)) break
                    run += n
                }
                val text = run.joinToString(" ") { it.text }.trim()
                if (text.split(' ').size > 14 || (text.endsWith('.') && !isLabel(text))) out += Para(l.page, text)
                else out += Heading(l.page, text, l.size, sub = false)
                i += run.size
                prev = null
                continue
            }

            val standalone = (before == null || l.y - before.y > spacing * 1.2f) && (after == null || after.y - l.y > spacing * 1.2f)
            val words = l.text.split(' ').size
            if (standalone && words <= 12 && (l.caps || l.bold) && !l.text.trimEnd().endsWith('.') && !l.text.trimEnd().endsWith(',')) {
                endPara()
                out += Heading(l.page, l.text.trim(), l.size, sub = true)
                i++
                prev = null
                continue
            }

            val p = prev
            val newPara = when {
                para == null || p == null -> true
                p.page != l.page -> para.toString().endsSentence() || indented(l)
                l.y - p.y > spacing * 1.4f -> true
                abs(l.size - p.size) > body * 0.15f -> true
                indented(l) && !indented(p) -> true
                p.x1 < rightOf(p.page) - body * 2.5f && p.text.endsSentence() -> true
                else -> false
            }
            if (newPara) { endPara(); para = StringBuilder(l.text.trim()); paraPage = l.page }
            else para!!.let { b ->
                // Rejoin a word hyphenated across the line break.
                if (b.endsWith("-") && l.text.firstOrNull()?.isLowerCase() == true) b.setLength(b.length - 1) else b.append(' ')
                b.append(l.text.trim())
            }
            prev = l
            i++
        }
        endPara()
        out
    }

    /** Heading size from which a heading starts a chapter, or null when the headings don't divide the book. */
    private val chapterSize: Float? by lazy {
        val pages = pageHeights.size
        val sizes = items.filterIsInstance<Heading>().filterNot { it.sub }.map { (it.size * 2).roundToInt() / 2f }.distinct().sorted()
        sizes.firstOrNull { s ->
            val runs = headingRuns().count { run -> run.any { it is Heading && !it.sub && it.size >= s - 0.25f } }
            runs >= 2 && runs <= maxOf(2, pages / 2) && runs <= 150
        }
    }

    private fun headingRuns(): List<List<Item>> {
        val runs = ArrayList<List<Item>>()
        var cur = ArrayList<Item>()
        for (item in items) {
            if (item is Heading && !item.sub) cur += item
            else if (cur.isNotEmpty()) { runs += cur; cur = ArrayList() }
        }
        if (cur.isNotEmpty()) runs += cur
        return runs
    }

    private fun level(h: Heading) = when {
        h.sub -> 3
        chapterSize != null && h.size >= chapterSize!! - 0.25f -> 1
        else -> 2
    }

    fun blocks(): List<Block> = items.map {
        when (it) {
            is Heading -> Block(1, it.text, level = level(it), page = it.page)
            is Para -> Block(0, it.text, page = it.page)
        }
    }

    /** Chapters starting at each run of chapter-level headings, or null if the headings don't divide the book. */
    fun byHeadings(blocks: List<Block>): List<Chapter>? {
        if (chapterSize == null) return null
        val chapters = ArrayList<Chapter>()
        var cur = ArrayList<Block>()
        var i = 0
        while (i < blocks.size) {
            // A run of headings that includes a chapter-level one opens a new chapter. Headings with no text
            // between them (a part title, then "Chapter 1") stay together at the start of the same chapter.
            var j = i
            while (j < blocks.size && blocks[j].type == 1 && blocks[j].level <= 2) j++
            if (j > i && (i until j).any { blocks[it].level == 1 }) {
                if (cur.any { it.type == 0 }) { chapters += chapter(cur, chapters.isEmpty()); cur = ArrayList() }
                cur.addAll(blocks.subList(i, j))
                i = j
                continue
            }
            cur += blocks[i]
            i++
        }
        if (cur.isNotEmpty()) chapters += chapter(cur, chapters.isEmpty())
        return chapters.takeIf { it.size >= 2 }
    }

    private fun chapter(blocks: List<Block>, first: Boolean): Chapter {
        val heads = blocks.takeWhile { it.type == 1 }.filter { it.level == 1 }.map { it.text }
        val label = heads.indexOfLast { isLabel(it) }
        val title = when {
            label >= 0 -> titleCase(heads[label]) + (heads.getOrNull(label + 1)?.let { ": " + titleCase(it) } ?: "")
            heads.isNotEmpty() -> titleCase(heads.first())
            first -> "Front Matter"
            else -> "Untitled"
        }
        return Chapter(title, blocks.toList(), startPage = blocks.first().page)
    }

    private fun isLabel(t: String) = t.trim().matches(Regex("(?i)(chapter|part|book|section|lesson|unit)\\s+([\\divxlcdm]+|[a-z]+)\\.?"))

    private fun titleCase(t: String): String {
        val letters = t.filter { it.isLetter() }
        if (letters.isEmpty() || letters != letters.uppercase()) return t
        val small = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs")
        return t.lowercase().split(' ').mapIndexed { i, w ->
            if (i > 0 && w in small) w else w.replaceFirstChar { it.uppercase() }
        }.joinToString(" ")
    }

    private fun mode(xs: List<Float>): Float =
        xs.groupBy { it.roundToInt() }.maxByOrNull { it.value.size }?.key?.toFloat() ?: 0f

    private fun percentile(xs: List<Float>, p: Float): Float =
        if (xs.isEmpty()) 0f else xs.sorted()[((xs.size - 1) * p).roundToInt()]
}

internal fun String.endsSentence() = trimEnd().lastOrNull()?.let { it in ".!?:\"”’)" } ?: true
