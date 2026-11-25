package com.gbook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object PdfSupport {
    private const val TAG = "GBookPdf"

    fun init(ctx: Context) = PDFBoxResourceLoader.init(ctx.applicationContext)

    /** Renders page 0 to [out] as PNG; returns page count. */
    fun renderCover(file: File, out: File): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                r.openPage(0).use { p ->
                    val w = 600
                    val h = (w * p.height / p.width.toFloat()).toInt()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                return r.pageCount
            }
        }
    }

    fun metadata(file: File): Pair<String, String> = runCatching {
        open(file).use { d ->
            val info = d.documentInformation
            (info.title?.trim().orEmpty()) to (info.author?.trim().orEmpty())
        }
    }.getOrDefault("" to "")

    /** Bumped when extraction improves, so PDFs converted by an older version are converted again. */
    const val TEXT_VERSION = 5

    /** Pages each worker takes at a time; small enough to share the work out evenly. */
    private const val CHUNK_PAGES = 24
    /** A chapter longer than this many pages is split into sections. */
    private const val LONG_PAGES = 36
    /** About how many pages a section of a split chapter covers. */
    private const val SECTION_PAGES = 14

    /**
     * Opens [file]. PDFBox copies the file's streams into a scratch buffer as it parses; past 16 MB that goes to a
     * temporary file, as a big PDF read by several workers would otherwise fill the heap.
     */
    private fun open(file: File): PDDocument = PDDocument.load(file, MemoryUsageSetting.setupMixed(16L shl 20))

    /** How many copies of the document to read at once: one per spare core, fewer when memory is tight. */
    fun workersFor(pages: Int): Int {
        if (pages < 2 * CHUNK_PAGES) return 1
        val cores = Runtime.getRuntime().availableProcessors() - 2
        val memory = (Runtime.getRuntime().maxMemory() / (96L shl 20)).toInt()
        return minOf(4, cores, memory, pages / CHUNK_PAGES).coerceAtLeast(1)
    }

    /**
     * Extracts reflowable text like an EPUB: paragraphs rebuilt from indents and spacing, headings found by font
     * size and weight, running headers, footers and page numbers dropped, figures and photos saved to [imagesDir]
     * and placed where they sit among the text, and chapters taken from the PDF outline or, failing that, from
     * its chapter headings. Long books are read by several [workers] at once, each with its own copy of the file.
     * A PDF of scanned pages, with no text to speak of, gives no chapters.
     */
    suspend fun extract(file: File, imagesDir: File, workers: Int? = null, onProgress: (Float) -> Unit = {}): BookContent {
        val t0 = SystemClock.elapsedRealtime()
        val docs = ArrayList<PDDocument>()
        try {
            val main = open(file).also { docs += it }
            val pageCount = main.numberOfPages
            val heights = main.pages.map { p -> runCatching { p.mediaBox.height }.getOrDefault(792f) }
            val n = workers ?: workersFor(pageCount)
            repeat(n - 1) { docs += open(file) }

            // Text, in chunks of pages handed out to the workers as they finish the last.
            val chunks = (0 until pageCount step CHUNK_PAGES).map { it until minOf(pageCount, it + CHUNK_PAGES) }
            val done = AtomicInteger()
            val collectors = chunks.map { WordCollector(images = true) { onProgress(0.85f * done.incrementAndGet() / pageCount) } }
            val owner = IntArray(chunks.size)
            val next = AtomicInteger()
            parallel(n) { w ->
                while (true) {
                    val c = next.getAndIncrement()
                    if (c >= chunks.size) break
                    owner[c] = w
                    collectors[c].collect(docs[w], chunks[c].first + 1, chunks[c].last + 1)
                }
            }
            val t1 = SystemClock.elapsedRealtime()
            val words = collectors.flatMap { it.words }
            val textPages = words.mapTo(HashSet()) { it.page }
            val failed = collectors.sumOf { it.failedPages }
            if (textPages.size < maxOf(1, pageCount / 20)) {
                Log.i(TAG, "$pageCount pages, text on ${textPages.size}: scanned, nothing to reflow (${t1 - t0} ms)")
                return BookContent(emptyList())
            }

            // Images worth keeping, each decoded once, by the worker whose copy of the document drew it.
            val kept = keptImages(collectors.flatMap { it.drawn }, textPages)
            val names = kept.map { it.key }.distinct().withIndex().associate { (i, k) -> k to "pdf-$i" }
            val saved = ConcurrentHashMap<Any, String>()
            val decoded = AtomicInteger()
            parallel(n) { w ->
                collectors.forEachIndexed { c, col ->
                    if (owner[c] == w) col.save(names, saved, imagesDir) { onProgress(0.85f + 0.12f * decoded.incrementAndGet() / names.size.coerceAtLeast(1)) }
                }
            }
            val pictures = kept.filter { img -> saved[img.key].orEmpty().also { img.file = it }.isNotEmpty() }
            val t2 = SystemClock.elapsedRealtime()

            val layout = PdfLayout(pdfLines(words), heights, pictures)
            val blocks = layout.blocks()
            val marks = bookmarks(main, pageCount)
            val chapters = when {
                marks.size >= 2 -> splitLong(byPages(blocks, buildList {
                    if (marks.first().second > 0) add(Triple("Front Matter", 0, marks.first().second))
                    marks.forEachIndexed { i, (t, p) -> add(Triple(t, p, marks.getOrNull(i + 1)?.second ?: pageCount)) }
                }), untitled = false)
                else -> layout.byHeadings(blocks)?.let { splitLong(it, untitled = false) }
                    // No outline and no chapter headings: sections cut at sub-headings, or every so many pages.
                    ?: splitLong(listOf(Chapter("", blocks, startPage = 0)), untitled = true)
            }
            Log.i(
                TAG,
                "$pageCount pages, $n workers: text ${t1 - t0} ms, ${names.size} images ${t2 - t1} ms, layout " +
                    "${SystemClock.elapsedRealtime() - t2} ms; ${marks.size} bookmarks, ${chapters.size} chapters, $failed pages unreadable",
            )
            return BookContent(chapters.filter { ch -> ch.blocks.any { it.type == 0 || it.type == 2 } })
        } finally {
            docs.forEach { runCatching { it.close() } }
        }
    }

    /** Runs [work] for workers 0 until [n], at the same time when there's more than one. */
    private suspend fun parallel(n: Int, work: (Int) -> Unit) {
        if (n == 1) return work(0)
        coroutineScope { (0 until n).map { w -> async(Dispatchers.Default) { work(w) } }.awaitAll() }
    }

    private class Mark(val title: String, val page: Int, val kids: List<Mark>)

    /**
     * The outline's entries as (title, first page), at the depth that divides the book usefully: a book whose
     * outline has one entry for the whole book, or only its parts, uses the chapters inside them.
     */
    private fun bookmarks(doc: PDDocument, pageCount: Int): List<Pair<String, Int>> {
        val pageIndex = HashMap<COSBase, Int>()
        doc.pages.forEachIndexed { i, p -> pageIndex[p.cosObject] = i }
        var count = 0
        fun read(node: PDOutlineNode, depth: Int): List<Mark> {
            val out = ArrayList<Mark>()
            for (item in node.children()) {
                // A malformed outline can loop back on itself.
                if (++count > 5000) break
                val kids = if (depth < 4) runCatching { read(item, depth + 1) }.getOrDefault(emptyList()) else emptyList()
                val page = runCatching { item.findDestinationPage(doc)?.let { pageIndex[it.cosObject] ?: doc.pages.indexOf(it) } }
                    .getOrNull()?.takeIf { it >= 0 } ?: kids.firstOrNull()?.page ?: continue
                out += Mark(item.title?.trim().orEmpty(), page, kids)
            }
            return out
        }
        var level = runCatching { doc.documentCatalog.documentOutline?.let { read(it, 0) } }.getOrNull().orEmpty()
        val want = maxOf(3, pageCount / 40)
        repeat(3) {
            if (level.distinctBy { it.page }.size >= want) return@repeat
            val deeper = level.flatMap { m ->
                if (m.kids.isEmpty()) listOf(m)
                // A part title on a page of its own stays; one on the same page as its first chapter goes.
                else (if (m.page < m.kids.first().page) listOf(m) else emptyList()) + m.kids
            }
            if (deeper.size > level.size && (level.size < 2 || deeper.size <= pageCount / 2)) level = deeper
        }
        return level.filter { it.title.isNotEmpty() && it.page < pageCount }.map { it.title to it.page }
            .sortedBy { it.second }.distinctBy { it.second }
    }

    /** Splits [blocks] into chapters covering the given (title, first page, end page) ranges. */
    private fun byPages(blocks: List<Block>, ranges: List<Triple<String, Int, Int>>): List<Chapter> {
        val out = ArrayList<Chapter>()
        var i = 0
        for ((title, s, e) in ranges) {
            while (i < blocks.size && blocks[i].page < s) i++
            val from = i
            while (i < blocks.size && blocks[i].page < e) i++
            val body = blocks.subList(from, i)
            if (body.none { it.type == 0 || it.type == 2 }) continue
            // Outline titles are often already the first heading on the page; don't repeat them.
            val head = if (body.first().let { it.type == 1 && it.text.equals(title, ignoreCase = true) }) emptyList()
            else listOf(Block(1, title, level = 1, page = s))
            out += Chapter(title, head + body, startPage = s)
        }
        return out
    }

    /**
     * Splits chapters that run longer than [LONG_PAGES] into sections of about [SECTION_PAGES] pages, cut where a
     * heading starts if there's one near, otherwise where a page starts, so the contents can still take the reader
     * near any place. [untitled] chapters (a book without any structure) name sections by heading or pages.
     */
    private fun splitLong(chapters: List<Chapter>, untitled: Boolean): List<Chapter> = chapters.flatMap { ch ->
        val pages = ch.blocks.map { it.page }.filter { it >= 0 }
        val first = pages.minOrNull() ?: return@flatMap listOf(ch)
        val last = pages.max()
        fun named(part: List<Block>, k: Int): Chapter {
            val s = part.firstOrNull { it.page >= 0 }?.page ?: first
            val e = part.lastOrNull { it.page >= 0 }?.page ?: s
            val head = part.firstOrNull()?.takeIf { it.type == 1 }?.text
            val title = when {
                untitled -> head ?: if (s == e) "Page ${s + 1}" else "Pages ${s + 1}–${e + 1}"
                k == 0 -> ch.title
                head != null -> head
                else -> "${ch.title}, page ${s + 1}"
            }
            return Chapter(title, part, startPage = s)
        }
        if (last - first < LONG_PAGES) return@flatMap listOf(named(ch.blocks, 0))
        val cuts = mutableListOf(0)
        var from = first
        for (i in 1 until ch.blocks.size) {
            val b = ch.blocks[i]
            val prev = ch.blocks[i - 1]
            // Leave the last section a few pages at least.
            if (b.page < 0 || last - b.page < 4) continue
            val span = b.page - from
            val atHeading = b.type == 1 && prev.type != 1
            val atPage = b.page > prev.page && prev.page >= 0
            if ((atHeading && span >= SECTION_PAGES * 6 / 10) || (atPage && span >= SECTION_PAGES * 3 / 2)) {
                cuts += i
                from = b.page
            }
        }
        cuts.mapIndexed { k, c -> named(ch.blocks.subList(c, cuts.getOrNull(k + 1) ?: ch.blocks.size).toList(), k) }
    }
}
