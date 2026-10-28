package com.gbook.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the cases in `shared/sync/cases`, which the desktop's `sync.rs` runs too, so both apps merge alike. */
class SyncMergeTest {
    private val cases = File("../../shared/sync/cases")

    @Test
    fun sharedCases() {
        val files = cases.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no cases in ${cases.absolutePath}", files.isNotEmpty())
        for (f in files) {
            val c = Json.parseToJsonElement(f.readText()) as JsonObject
            val doc = SyncMerge.Doc.fromJson(c["doc"] as? JsonObject)
            val remote = (c["remote"] as? JsonObject)?.let { SyncMerge.Doc.fromJson(it) }
            val base = c["base"] as? JsonObject
            val library = SyncMerge.merge(doc, remote, base, c["local"] as JsonObject, c["device"]!!.jsonPrimitive.content, c["now"]!!.jsonPrimitive.long)
            val expect = c["expect"] as JsonObject
            assertEquals("${f.name}: doc", expect["doc"], doc.toJson())
            assertEquals("${f.name}: library", expect["library"], library)
            c["expect_pending"]?.let { want ->
                val got = JsonArray(SyncMerge.pendingBooks(doc, library).map { (id, n) -> JsonArray(listOf(JsonPrimitive(id), JsonPrimitive(n))) })
                assertEquals("${f.name}: pending", want, got)
            }
            // Syncing again with nothing new changes nothing.
            val again = doc.copy()
            val lib2 = SyncMerge.merge(again, doc.copy(), library, library, "aaaa1111", 5000)
            assertEquals("${f.name}: second sync changed the doc", doc.toJson(), again.toJson())
            assertEquals("${f.name}: second sync changed the library", library, lib2)
        }
    }

    @Test
    fun encodeRoundTrips() {
        val d = SyncMerge.Doc.fromJson(Json.parseToJsonElement("""{"format":1,"books":{"a":{"f":{"title":[1,"d","T"]}}},"counters":{}}""") as JsonObject)
        assertEquals(d.toJson(), SyncMerge.decode(SyncMerge.encode(d)).toJson())
        assertEquals(SyncMerge.hash(d), SyncMerge.hash(SyncMerge.decode(SyncMerge.encode(d))))
        assertTrue(JsonNull != d.toJson()["books"])
    }
}
