package com.gbook

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gbook.data.Backup
import com.gbook.data.Book
import com.gbook.data.BookFormat
import com.gbook.data.Highlight
import com.gbook.data.Importer
import com.gbook.data.LibraryData
import com.gbook.data.Post
import com.gbook.data.PostGenerator
import com.gbook.data.ReaderSettings
import com.gbook.data.ReadingPosition
import com.gbook.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val importer = Importer(app, store, viewModelScope)
    val data: StateFlow<LibraryData> = store.data

    /** Transient toast message shown by the app shell. */
    val toast = MutableStateFlow<String?>(null)
    /** Non-null while a long-running task (import, backup) is in progress. */
    val busy = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            if (!store.data.value.seeded) {
                busy.value = "Adding sample books…"
                runCatching { importer.seedSamples() }
                busy.value = null
            }
            // Resume PDF text extraction that was interrupted by the process dying.
            store.data.value.books.filter { it.format == BookFormat.PDF && !it.textReady }.forEach { importer.extractPdf(it) }
        }
    }

    fun toast(msg: String) { toast.value = msg }

    fun coverPath(b: Book): String? = if (b.hasCover) store.coverFile(b.id).path else null
    fun imagePath(bookId: String, src: String) = java.io.File(store.imagesDir(bookId), src).path

    fun import(uri: Uri, then: (Book) -> Unit = {}) = viewModelScope.launch {
        busy.value = "Importing…"
        runCatching { importer.importUri(uri) }
            .onSuccess { toast("Added “${it.title}”"); then(it) }
            .onFailure { toast("Couldn't open that file: ${it.message ?: "unknown error"}") }
        busy.value = null
    }

    fun markOpened(id: String) = store.updateBook(id) { it.copy(lastOpenedAt = System.currentTimeMillis()) }

    fun savePosition(id: String, pos: ReadingPosition, progress: Float) =
        store.updateBook(id) { it.copy(position = pos, progress = progress.coerceIn(0f, 1f)) }

    fun updateSettings(f: (ReaderSettings) -> ReaderSettings) = store.update { it.copy(settings = f(it.settings)) }

    fun reactBook(id: String, emoji: String) = store.updateBook(id) { b -> b.copy(reactions = toggle(b.reactions, b.myReaction, emoji), myReaction = if (b.myReaction == emoji) null else emoji) }

    fun reactPost(id: String, emoji: String) = store.update { d ->
        d.copy(posts = d.posts.map { p -> if (p.id != id) p else p.copy(reactions = toggle(p.reactions, p.myReaction, emoji), myReaction = if (p.myReaction == emoji) null else emoji) })
    }

    private fun toggle(counts: Map<String, Int>, mine: String?, emoji: String): Map<String, Int> {
        val m = counts.toMutableMap()
        mine?.let { old -> m[old] = (m[old] ?: 1) - 1; if ((m[old] ?: 0) <= 0) m.remove(old) }
        if (mine != emoji) m[emoji] = (m[emoji] ?: 0) + 1
        return m
    }

    fun togglePostLike(id: String) = store.update { d -> d.copy(posts = d.posts.map { if (it.id == id) it.copy(liked = !it.liked) else it }) }
    fun likePost(id: String) = store.update { d -> d.copy(posts = d.posts.map { if (it.id == id) it.copy(liked = true) else it }) }
    fun togglePostSave(id: String) = store.update { d -> d.copy(posts = d.posts.map { if (it.id == id) it.copy(saved = !it.saved) else it }) }
    fun deletePost(id: String) = store.update { d -> d.copy(posts = d.posts.filterNot { it.id == id }) }

    fun addHighlight(bookId: String, chapter: Int, block: Int, start: Int, endBlock: Int, end: Int, text: String, color: Int, chapterTitle: String, style: Int = 0, makePost: Boolean = false): Highlight {
        val h = Highlight(
            UUID.randomUUID().toString().take(12), bookId, chapter, block, start, end, text, color,
            endBlock = if (endBlock == block) -1 else endBlock, style = style,
        )
        val book = store.book(bookId)
        store.update { d ->
            val post = if ((makePost || d.settings.autoPostHighlights) && book != null) PostGenerator.fromHighlight(book, h, chapterTitle) else null
            d.copy(highlights = d.highlights + h, posts = listOfNotNull(post) + d.posts)
        }
        return h
    }

    fun updateHighlight(id: String, f: (Highlight) -> Highlight) = store.update { d ->
        d.copy(highlights = d.highlights.map { if (it.id == id) f(it) else it })
    }

    fun deleteHighlight(id: String) = store.update { d ->
        d.copy(highlights = d.highlights.filterNot { it.id == id }, posts = d.posts.filterNot { it.highlightId == id })
    }

    fun postFromHighlight(h: Highlight, chapterTitle: String) {
        val book = store.book(h.bookId) ?: return
        store.update { d ->
            val p = PostGenerator.fromHighlight(book, h, chapterTitle)
            d.copy(posts = listOf(p) + d.posts.filterNot { it.id == p.id })
        }
        toast("Post added to your Feed")
    }

    /** Generates feed posts for the given books; returns the number of new posts. */
    suspend fun generatePosts(bookIds: List<String>, fromHighlights: Boolean, ideas: Boolean, recaps: Boolean): Int = withContext(Dispatchers.Default) {
        val d = store.data.value
        val fresh = mutableListOf<Post>()
        for (id in bookIds) {
            val book = d.books.firstOrNull { it.id == id } ?: continue
            val content = store.content(id) ?: continue
            if (fromHighlights) d.highlights.filter { it.bookId == id }.forEach { h ->
                fresh += PostGenerator.fromHighlight(book, h, content.chapters.getOrNull(h.chapter)?.title.orEmpty())
            }
            if (ideas || recaps) {
                fresh += PostGenerator.fromBook(book, content, ideasPerChapter = if (ideas) 1 else 0, recaps = recaps)
            }
        }
        var added = 0
        store.update { cur ->
            val existing = cur.posts.map { it.id }.toSet()
            val new = fresh.filter { it.id !in existing }.distinctBy { it.id }
            added = new.size
            // Interleave books so the feed doesn't show one book for 30 posts in a row.
            val mixed = new.groupBy { it.bookId }.values.map { it.toMutableList() }.let { groups ->
                buildList { while (groups.any { it.isNotEmpty() }) groups.forEach { g -> if (g.isNotEmpty()) add(g.removeAt(0)) } }
            }
            cur.copy(posts = mixed + cur.posts)
        }
        added
    }

    fun deleteBook(id: String) = store.deleteBook(id)

    fun backup(uri: Uri) = viewModelScope.launch {
        busy.value = "Backing up…"
        runCatching { Backup.export(getApplication(), store, uri) }
            .onSuccess { toast("Library backed up") }
            .onFailure { toast("Backup failed: ${it.message}") }
        busy.value = null
    }

    fun restore(uri: Uri) = viewModelScope.launch {
        busy.value = "Restoring…"
        runCatching { Backup.restore(getApplication(), store, uri) }
            .onSuccess { toast("Restored $it books") }
            .onFailure { toast("Restore failed: ${it.message}") }
        busy.value = null
    }

    fun exportBook(book: Book, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { os ->
                store.bookFile(book).inputStream().use { it.copyTo(os) }
            }
            store.updateBook(book.id) { it.copy(backedUpAt = System.currentTimeMillis()) }
        }.onSuccess { toast("Saved “${book.title}”") }.onFailure { toast("Save failed: ${it.message}") }
    }
}
