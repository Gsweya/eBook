package com.gbook.data

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

val GJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Single JSON-backed source of truth for the library, highlights, posts and settings. */
class Store(private val ctx: Context) {
    private val file = File(ctx.filesDir, "library.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = _data
    private val contentCache = LruCache<String, BookContent>(3)

    private fun load(): LibraryData = runCatching {
        GJson.decodeFromString<LibraryData>(file.readText())
    }.getOrDefault(LibraryData())

    fun update(f: (LibraryData) -> LibraryData) {
        _data.update(f)
        scope.launch { save() }
    }

    fun replaceAll(d: LibraryData) {
        _data.value = d
        contentCache.evictAll()
        scope.launch { save() }
    }

    /** Replaces the data with [new] only if it is still [expected]; returns whether it did. */
    fun replaceIf(expected: LibraryData, new: LibraryData): Boolean {
        if (!_data.compareAndSet(expected, new)) return false
        scope.launch { save() }
        return true
    }

    /** Removes a book's files, once the book itself is gone from the data. */
    fun deleteFiles(id: String) {
        contentCache.remove(id)
        File(booksDir, id).deleteRecursively()
    }

    private suspend fun save() = saveLock.withLock {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(GJson.encodeToString(LibraryData.serializer(), _data.value))
        tmp.renameTo(file)
    }

    fun updateBook(id: String, f: (Book) -> Book) = update { d ->
        d.copy(books = d.books.map { if (it.id == id) f(it) else it })
    }

    fun book(id: String): Book? = _data.value.books.firstOrNull { it.id == id }

    val booksDir: File get() = File(ctx.filesDir, "books").apply { mkdirs() }
    fun bookDir(id: String) = File(booksDir, id).apply { mkdirs() }
    fun bookFile(b: Book) = File(bookDir(b.id), b.fileName)
    fun coverFile(id: String) = File(bookDir(id), "cover")
    fun imagesDir(id: String) = File(bookDir(id), "images").apply { mkdirs() }
    private fun contentFile(id: String) = File(bookDir(id), "content.json")

    suspend fun content(id: String): BookContent? = withContext(Dispatchers.IO) {
        contentCache.get(id) ?: runCatching {
            GJson.decodeFromString<BookContent>(contentFile(id).readText())
        }.getOrNull()?.also { contentCache.put(id, it) }
    }

    fun saveContent(id: String, c: BookContent) {
        contentFile(id).writeText(GJson.encodeToString(BookContent.serializer(), c))
        contentCache.put(id, c)
    }

    fun deleteBook(id: String) {
        update { d ->
            d.copy(
                books = d.books.filterNot { it.id == id },
                highlights = d.highlights.filterNot { it.bookId == id },
                posts = d.posts.filterNot { it.bookId == id },
                collections = d.collections.map { c -> if (id in c.bookIds) c.copy(bookIds = c.bookIds - id) else c },
            )
        }
        contentCache.remove(id)
        scope.launch { File(booksDir, id).deleteRecursively() }
    }
}
