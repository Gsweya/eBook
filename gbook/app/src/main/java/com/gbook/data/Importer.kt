package com.gbook.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

private const val STARTER_BOOK = "starter/Fractured Minds.pdf"

private val SMALL_WORDS = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs")

class Importer(private val ctx: Context, private val store: Store, private val scope: CoroutineScope) {

    /** Extraction progress for PDFs being converted to reflowable text, keyed by book id. */
    val pdfProgress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())

    suspend fun importUri(uri: Uri): Book {
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Book"
        return importStream(name) { ctx.contentResolver.openInputStream(uri) ?: error("Cannot open $uri") }
    }

    suspend fun seedSamples() {
        val names = ctx.assets.list("").orEmpty().filter { it.endsWith(".epub") || it.endsWith(".pdf") }
        for (n in names) runCatching { importStream(n) { ctx.assets.open(n) } }
        store.update { it.copy(seeded = true) }
    }

    /** Adds the starter book that ships with every build: the author's own "Fractured Minds", free to read. */
    suspend fun seedStarter() {
        runCatching {
            val b = importStream(STARTER_BOOK.substringAfterLast('/')) { ctx.assets.open(STARTER_BOOK) }
            // The PDF has no title and a short author name in its metadata.
            store.updateBook(b.id) { it.copy(title = "Fractured Minds", author = "Galanda J. Sweya") }
        }
        store.update { it.copy(starterAdded = true) }
    }

    suspend fun importStream(displayName: String, open: () -> InputStream): Book = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("import", null, ctx.cacheDir)
        val md = MessageDigest.getInstance("SHA-1")
        open().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    md.update(buf, 0, n); out.write(buf, 0, n)
                }
            }
        }
        val id = md.digest().joinToString("") { "%02x".format(it) }.take(12)
        store.book(id)?.let { tmp.delete(); return@withContext it }

        val magic = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        val isPdf = magic[0] == '%'.code.toByte() && magic[1] == 'P'.code.toByte()
        val fallbackTitle = displayName.substringBeforeLast('.')
            .replace(Regex("\\s*\\((z-library|Z-Library|z-lib)[^)]*\\)"), "")
            .replace('_', ' ').replace('-', ' ').trim()
            .split(' ').filter { it.isNotEmpty() }
            .mapIndexed { i, w -> if (i == 0 || w.lowercase() !in SMALL_WORDS) w.replaceFirstChar { it.uppercase() } else w }
            .joinToString(" ")

        val book = if (isPdf) {
            val f = File(store.bookDir(id), "book.pdf"); tmp.copyTo(f, true); tmp.delete()
            PdfSupport.init(ctx)
            val pages = PdfSupport.renderCover(f, store.coverFile(id))
            val (t, a) = PdfSupport.metadata(f)
            Book(
                id = id, title = t.ifBlank { fallbackTitle }, author = a.ifBlank { "Unknown author" },
                format = BookFormat.PDF, fileName = f.name, hasCover = true,
                coverColor = coverColor(store.coverFile(id)), pageCount = pages, textReady = false,
            )
        } else {
            val f = File(store.bookDir(id), "book.epub"); tmp.copyTo(f, true); tmp.delete()
            val r = EpubParser.parse(f, store.imagesDir(id))
            r.cover?.let { store.coverFile(id).writeBytes(it) }
            store.saveContent(id, r.content)
            Book(
                id = id, title = r.title.ifBlank { fallbackTitle }, author = r.author.ifBlank { "Unknown author" },
                format = BookFormat.EPUB, fileName = f.name, hasCover = r.cover != null,
                coverColor = if (r.cover != null) coverColor(store.coverFile(id)) else 0xFF8F3D26,
                chapterCount = r.content.chapters.size,
            )
        }
        store.update { it.copy(books = it.books + book) }
        if (isPdf) extractPdf(book)
        book
    }

    fun extractPdf(book: Book) = scope.launch(Dispatchers.Default) {
        if (pdfProgress.value.containsKey(book.id)) return@launch
        pdfProgress.value += book.id to 0f
        runCatching {
            PdfSupport.init(ctx)
            // Text from an older extractor, re-extracted now: keep the reader's place by page.
            val old = if (book.textReady) store.content(book.id) else null
            val c = PdfSupport.extract(store.bookFile(book), store.imagesDir(book.id)) { p -> pdfProgress.value += book.id to p }
            store.saveContent(book.id, c)
            store.updateBook(book.id) { b ->
                b.copy(
                    textReady = true, chapterCount = c.chapters.size, textVersion = PdfSupport.TEXT_VERSION,
                    position = old?.let { remap(b.position, it, c) } ?: b.position,
                )
            }
        }
        pdfProgress.value -= book.id
    }

    /** Moves a reflow position from [old] text to the same PDF page in [new] text. */
    private fun remap(pos: ReadingPosition, old: BookContent, new: BookContent): ReadingPosition {
        val page = old.chapters.getOrNull(pos.chapter)?.blocks?.getOrNull(pos.item)?.page?.takeIf { it >= 0 } ?: return pos.copy(chapter = 0, item = 0, offset = 0)
        new.chapters.forEachIndexed { ci, ch ->
            val bi = ch.blocks.indexOfFirst { it.page >= page }
            if (bi >= 0) return pos.copy(chapter = ci, item = bi, offset = 0)
        }
        return pos.copy(chapter = 0, item = 0, offset = 0)
    }

    private fun coverColor(f: File): Long = runCatching {
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 16, 16, true)
        var r = 0L; var g = 0L; var b = 0L
        for (x in 0 until 16) for (y in 0 until 16) {
            val c = small.getPixel(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
        }
        // Darken so white text stays legible on the detail screen.
        val k = 0.62
        val rr = (r / 256 * k).toLong(); val gg = (g / 256 * k).toLong(); val bb = (b / 256 * k).toLong()
        (0xFF000000 or (rr shl 16) or (gg shl 8) or bb)
    }.getOrDefault(0xFF8F3D26)
}
