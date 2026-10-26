package com.gbook

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gbook.data.Backup
import com.gbook.data.Book
import com.gbook.data.BookCollection
import com.gbook.data.BookFormat
import com.gbook.data.Highlight
import com.gbook.data.Importer
import com.gbook.data.LibraryData
import com.gbook.data.Post
import com.gbook.data.PostGenerator
import com.gbook.data.ReaderSettings
import com.gbook.data.ReadingPlan
import com.gbook.data.Reminder
import com.gbook.reminders.ReminderScheduler
import com.gbook.data.ReadingPosition
import com.gbook.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
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
            if (!store.data.value.starterAdded) importer.seedStarter()
            // Resume PDF text extraction that was interrupted by the process dying.
            store.data.value.books.filter { it.format == BookFormat.PDF && !it.textReady }.forEach { importer.extractPdf(it) }
        }
        ReminderScheduler.ensureChannel(app)
        ReminderScheduler.sync(app, store.data.value.reminders)
    }

    /** Minutes read today at the moment the daily goal was reached; the reader shows a banner, then clears it. */
    val goalReached = MutableStateFlow<Long?>(null)

    fun toast(msg: String) { toast.value = msg }

    /** Adds active reading time to a book and to today's total. */
    fun addReadingTime(bookId: String, ms: Long) {
        if (ms <= 0) return
        val day = LocalDate.now().toString()
        val before = store.data.value.readingLog[day] ?: 0L
        val goalMs = store.data.value.settings.dailyGoalMin * 60_000L
        val reached = goalMs > 0 && before < goalMs && before + ms >= goalMs && store.data.value.goalReachedDay != day
        if (reached) goalReached.value = (before + ms) / 60_000
        store.update { d ->
            d.copy(
                goalReachedDay = if (reached) day else d.goalReachedDay,
                books = d.books.map { if (it.id == bookId) it.copy(readingMs = it.readingMs + ms) else it },
                readingLog = d.readingLog + (day to (d.readingLog[day] ?: 0L) + ms),
                bookDays = d.bookDays + (day to (d.bookDays[day].orEmpty().let { m -> m + (bookId to (m[bookId] ?: 0L) + ms) })),
            )
        }
    }

    fun setDailyGoal(minutes: Int) = updateSettings { it.copy(dailyGoalMin = minutes) }

    fun saveReminder(r: Reminder) {
        store.update { d -> d.copy(reminders = d.reminders.filterNot { it.id == r.id } + r) }
        syncReminders()
    }

    fun deleteReminder(id: String) {
        store.update { d -> d.copy(reminders = d.reminders.filterNot { it.id == id }) }
        syncReminders()
    }

    /** Creates or replaces the plan for its book; [reminder] (or none) replaces the plan's own reminder. */
    fun savePlan(plan: ReadingPlan, reminder: Reminder?) {
        val rid = planReminderId(plan.bookId)
        store.update { d ->
            d.copy(
                plans = d.plans.filterNot { it.bookId == plan.bookId } + plan,
                reminders = d.reminders.filterNot { it.id == rid } + listOfNotNull(reminder?.copy(id = rid, bookId = plan.bookId)),
            )
        }
        syncReminders()
    }

    fun deletePlan(bookId: String) {
        store.update { d -> d.copy(plans = d.plans.filterNot { it.bookId == bookId }, reminders = d.reminders.filterNot { it.id == planReminderId(bookId) }) }
        syncReminders()
    }

    fun planReminderId(bookId: String) = "plan-$bookId"

    private fun syncReminders() = ReminderScheduler.sync(getApplication(), store.data.value.reminders)

    val avatarFile get() = java.io.File(getApplication<Application>().filesDir, "profile.jpg")

    /** Saves an already-cropped square image as the 512px profile picture. */
    fun saveAvatar(bmp: android.graphics.Bitmap) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val out = if (bmp.width == 512 && bmp.height == 512) bmp else android.graphics.Bitmap.createScaledBitmap(bmp, 512, 512, true)
                avatarFile.outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }.isSuccess
        }
        if (ok) updateSettings { it.copy(avatarVersion = System.currentTimeMillis()) } else toast("Couldn't save that picture")
    }

    fun removeAvatar() {
        avatarFile.delete()
        updateSettings { it.copy(avatarVersion = 0) }
    }

    fun coverPath(b: Book): String? = if (b.hasCover) store.coverFile(b.id).path else null
    fun imagePath(bookId: String, src: String) = java.io.File(store.imagesDir(bookId), src).path

    fun import(uri: Uri, then: (Book) -> Unit = {}) = viewModelScope.launch {
        busy.value = "Importing…"
        runCatching { importer.importUri(uri) }
            .onSuccess { toast("Added “${it.title}”"); then(it) }
            .onFailure { toast("Couldn't open that file: ${it.message ?: "unknown error"}") }
        busy.value = null
    }

    /** Imports several files one after another, reporting progress in the busy toast. */
    fun importMany(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        if (uris.size == 1) { import(uris[0]); return@launch }
        var added = 0
        uris.forEachIndexed { i, uri ->
            busy.value = "Importing ${i + 1} of ${uris.size}…"
            if (runCatching { importer.importUri(uri) }.isSuccess) added++
        }
        busy.value = null
        val failed = uris.size - added
        toast(if (failed == 0) "Added $added books" else "Added $added books · $failed couldn't be opened")
    }

    fun finishOnboarding() = store.update { it.copy(onboarded = true) }

    /** Counts [n] more pages read in a book and in today's total. */
    fun addPagesRead(bookId: String, n: Int = 1) {
        val day = LocalDate.now().toString()
        store.update { d ->
            d.copy(
                books = d.books.map { if (it.id == bookId) it.copy(pagesRead = it.pagesRead + n) else it },
                pagesLog = d.pagesLog + (day to (d.pagesLog[day] ?: 0) + n),
            )
        }
    }

    fun createCollection(name: String, bookIds: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString().take(12)
        store.update { d -> d.copy(collections = d.collections + BookCollection(id, name.trim().ifBlank { "Untitled" }, bookIds.distinct())) }
        return id
    }

    fun renameCollection(id: String, name: String) = updateCollection(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    fun deleteCollection(id: String) = store.update { d -> d.copy(collections = d.collections.filterNot { it.id == id }) }

    fun addToCollection(id: String, bookIds: List<String>) = updateCollection(id) { it.copy(bookIds = (it.bookIds + bookIds).distinct()) }

    fun removeFromCollection(id: String, bookIds: List<String>) = updateCollection(id) { it.copy(bookIds = it.bookIds - bookIds.toSet()) }

    private fun updateCollection(id: String, f: (BookCollection) -> BookCollection) =
        store.update { d -> d.copy(collections = d.collections.map { if (it.id == id) f(it) else it }) }

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

    /** Sets the highlight's reaction; picking the current one clears it. */
    fun reactHighlight(id: String, emoji: String) = updateHighlight(id) { it.copy(reaction = if (it.reaction == emoji) null else emoji) }

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

    fun deleteBook(id: String) {
        val hadPlan = store.data.value.plans.any { it.bookId == id }
        store.deleteBook(id)
        if (hadPlan) deletePlan(id)
    }

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
