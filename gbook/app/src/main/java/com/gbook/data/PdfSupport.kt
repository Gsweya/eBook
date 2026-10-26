package com.gbook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfSupport {
    private const val PARA = " "

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

    /** Extracts reflowable text, split into chapters by the PDF outline (or 10-page groups). */
    fun extract(file: File, onProgress: (Float) -> Unit = {}): BookContent = PDDocument.load(file).use { doc ->
        val pageCount = doc.numberOfPages
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            paragraphStart = PARA
            paragraphEnd = ""
        }
        val pages = (0 until pageCount).map { i ->
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            val t = runCatching { stripper.getText(doc) }.getOrDefault("")
            onProgress((i + 1f) / pageCount)
            paragraphs(t)
        }

        val marks = runCatching {
            doc.documentCatalog.documentOutline?.children()?.mapNotNull { item ->
                val page = item.findDestinationPage(doc) ?: return@mapNotNull null
                val idx = doc.pages.indexOf(page)
                if (idx < 0) null else (item.title?.trim().orEmpty() to idx)
            }.orEmpty()
        }.getOrDefault(emptyList()).filter { it.first.isNotEmpty() }.distinctBy { it.second }.sortedBy { it.second }

        val ranges: List<Triple<String, Int, Int>> = if (marks.size >= 2) {
            buildList {
                if (marks.first().second > 0) add(Triple("Front Matter", 0, marks.first().second))
                marks.forEachIndexed { i, (t, p) ->
                    add(Triple(t, p, marks.getOrNull(i + 1)?.second ?: pageCount))
                }
            }
        } else {
            (0 until pageCount step 10).map { s ->
                val e = minOf(pageCount, s + 10)
                Triple("Pages ${s + 1}–$e", s, e)
            }
        }

        val chapters = ranges.mapNotNull { (title, s, e) ->
            val blocks = mutableListOf(Block(1, title, level = 1, page = s))
            for (p in s until e) {
                pages[p].forEachIndexed { i, para ->
                    val last = blocks.lastOrNull()
                    // Join a paragraph broken across a page boundary.
                    if (i == 0 && last != null && last.type == 0 && !last.text.endsSentence() && para.first().isLowerCase()) {
                        blocks[blocks.lastIndex] = last.copy(text = last.text + " " + para)
                    } else blocks += Block(0, para, page = p)
                }
            }
            if (blocks.size > 1) Chapter(title, blocks, startPage = s) else null
        }
        BookContent(chapters)
    }

    private fun String.endsSentence() = trimEnd().lastOrNull()?.let { it in ".!?:\"”’)" } ?: true

    private fun paragraphs(pageText: String): List<String> {
        val lines = pageText.replace("\r", "").lines()
            .map { it.trim() }
            .filterNot { it.replace(PARA, "").matches(Regex("\\d{1,4}|[ivxlcdm]{1,6}")) }
        val byMarker = lines.joinToString("\n").split(PARA).map(::joinLines).filter { it.isNotBlank() }
        // pdfbox paragraph detection is unreliable on uniformly spaced text; fall back to a line heuristic.
        if (byMarker.isNotEmpty() && byMarker.sumOf { it.length } / byMarker.size >= 90) return byMarker
        val clean = lines.map { it.replace(PARA, "") }.filter { it.isNotEmpty() }
        val maxLen = clean.maxOfOrNull { it.length } ?: return emptyList()
        val out = mutableListOf<String>()
        val buf = mutableListOf<String>()
        for (l in clean) {
            buf += l
            if (l.endsSentence() && l.length < maxLen * 0.8) { out += joinLines(buf.joinToString("\n")); buf.clear() }
        }
        if (buf.isNotEmpty()) out += joinLines(buf.joinToString("\n"))
        return out.filter { it.isNotBlank() }
    }

    private fun joinLines(s: String) = s.trim()
        .replace(Regex("(\\p{Ll})-\\n(\\p{Ll})"), "$1$2")
        .replace(Regex("\\s*\\n\\s*"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
