package com.gbook.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Library backup as a single .zip, written through the Storage Access Framework so the user can
 * pick Google Drive (or any other provider) as the destination.
 */
object Backup {
    suspend fun export(ctx: Context, store: Store, target: Uri) = withContext(Dispatchers.IO) {
        val data = store.data.value.copy(lastBackupAt = System.currentTimeMillis())
        ctx.contentResolver.openOutputStream(target, "wt")!!.use { os ->
            ZipOutputStream(os.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("library.json"))
                zip.write(GJson.encodeToString(LibraryData.serializer(), data).toByteArray())
                zip.closeEntry()
                store.booksDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("books/" + f.relativeTo(store.booksDir).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        store.update { it.copy(lastBackupAt = data.lastBackupAt, books = it.books.map { b -> b.copy(backedUpAt = data.lastBackupAt) }) }
    }

    suspend fun restore(ctx: Context, store: Store, source: Uri): Int = withContext(Dispatchers.IO) {
        var restored: LibraryData? = null
        val root = store.booksDir.canonicalFile
        ctx.contentResolver.openInputStream(source)!!.use { ins ->
            ZipInputStream(ins.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.name == "library.json" ->
                            restored = GJson.decodeFromString(LibraryData.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                        e.name.startsWith("books/") && !e.isDirectory -> {
                            val out = File(store.booksDir, e.name.removePrefix("books/")).canonicalFile
                            if (!out.path.startsWith(root.path + File.separator)) continue // zip-slip guard
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
        }
        val incoming = restored ?: error("Not a GBook backup")
        val current = store.data.value
        // Merge: keep existing items, add anything new from the backup.
        val merged = current.copy(
            books = current.books + incoming.books.filter { b -> current.books.none { it.id == b.id } },
            highlights = current.highlights + incoming.highlights.filter { h -> current.highlights.none { it.id == h.id } },
            posts = current.posts + incoming.posts.filter { p -> current.posts.none { it.id == p.id } },
        )
        store.replaceAll(merged)
        incoming.books.size
    }
}
