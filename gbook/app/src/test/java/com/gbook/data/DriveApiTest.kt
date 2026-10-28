package com.gbook.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Talks to `scripts/fake-drive.py`; runs only when FAKE_DRIVE is its address, e.g. http://127.0.0.1:8765. */
class DriveApiTest {
    private val base = System.getenv("FAKE_DRIVE")

    @Test
    fun roundTrips() {
        assumeTrue(base != null)
        val api = DriveApi("token", base)
        assertEquals("reader@example.com", api.email())
        val doc = SyncMerge.encode(SyncMerge.Doc())
        val created = api.create(SyncMerge.DOC_NAME, doc, "application/gzip")
        assertArrayEquals(doc, api.download(created.id))
        val updated = api.update(created.id, byteArrayOf(1, 2, 3), "application/gzip")
        assertEquals(created.version + 1, updated.version)
        assertArrayEquals(byteArrayOf(1, 2, 3), api.download(created.id))
        val book = File.createTempFile("book", ".epub").apply { writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        val up = api.uploadFile("book-test.epub", book, "application/epub+zip")
        val back = File.createTempFile("back", ".epub")
        api.downloadTo(up.id, back)
        assertArrayEquals(book.readBytes(), back.readBytes())
        assert(api.list().any { it.name == "book-test.epub" })
        api.delete(up.id); api.delete(created.id)
        assert(api.list().none { it.id == up.id || it.id == created.id })
    }
}
