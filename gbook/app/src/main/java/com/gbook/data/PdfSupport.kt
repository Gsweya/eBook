package com.gbook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

object PdfSupport {
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
        PDDocument.load(file).use { d ->
            val info = d.documentInformation
            (info.title?.trim().orEmpty()) to (info.author?.trim().orEmpty())
        }
    }.getOrDefault("" to "")

    /** Bumped when extraction improves, so PDFs converted by an older version are converted again. */
    const val TEXT_VERSION = 4

    /**
     * Extracts reflowable text like an EPUB: paragraphs rebuilt from indents and spacing, headings found by font
     * size and weight, running headers, footers and page numbers dropped, figures and photos saved to [imagesDir]
     * and placed where they sit among the text, and chapters taken from the PDF outline or, failing that, from
     * its chapter headings.
     */
    fun extract(file: File, imagesDir: File, onProgress: (Float) -> Unit = {}): BookContent = PDDocument.load(file).use { doc ->
        val pageCount = doc.numberOfPages
        val collector = WordCollector(imagesDir) { p -> onProgress(p / pageCount.toFloat()) }
        runCatching { collector.getText(doc) }
        val layout = PdfLayout(collector.lines(), collector.pageHeights, collector.images())
        val blocks = layout.blocks()

        val marks = runCatching {
            doc.documentCatalog.documentOutline?.children()?.mapNotNull { item ->
                val page = item.findDestinationPage(doc) ?: return@mapNotNull null
                val idx = doc.pages.indexOf(page)
                if (idx < 0) null else (item.title?.trim().orEmpty() to idx)
            }.orEmpty()
        }.getOrDefault(emptyList()).filter { it.first.isNotEmpty() }.distinctBy { it.second }.sortedBy { it.second }

        val chapters = when {
            marks.size >= 2 -> byPages(blocks, buildList {
                if (marks.first().second > 0) add(Triple("Front Matter", 0, marks.first().second))
                marks.forEachIndexed { i, (t, p) -> add(Triple(t, p, marks.getOrNull(i + 1)?.second ?: pageCount)) }
            })
            else -> layout.byHeadings(blocks)
                ?: byPages(blocks, (0 until pageCount step 10).map { s -> Triple("Pages ${s + 1}–${minOf(pageCount, s + 10)}", s, minOf(pageCount, s + 10)) })
        }
        BookContent(chapters)
    }

    /** Splits [blocks] into chapters covering the given (title, first page, end page) ranges. */
    private fun byPages(blocks: List<Block>, ranges: List<Triple<String, Int, Int>>): List<Chapter> = ranges.mapNotNull { (title, s, e) ->
        val body = blocks.filter { it.page in s until e }
        if (body.none { it.type == 0 || it.type == 2 }) return@mapNotNull null
        // Outline titles are often already the first heading on the page; don't repeat them.
        val head = if (body.firstOrNull()?.let { it.type == 1 && it.text.equals(title, ignoreCase = true) } == true) emptyList()
        else listOf(Block(1, title, level = 1, page = s))
        Chapter(title, head + body, startPage = s)
    }
}
