package com.gbook.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Sync through the user's Google Drive: the merge at its heart, on plain JSON so fields this version doesn't
 * know survive in the document. The format and every rule are in `shared/sync/README.md`; the desktop app
 * implements the same rules in `sync.rs`, and both run the cases in `shared/sync/cases/`.
 */
object SyncMerge {
    const val FORMAT = 1L
    /** Name of the sync document in the Drive app data folder. */
    const val DOC_NAME = "gbook-sync.json.gz"

    /** Library lists synced as entity maps, with the field that keys them. */
    private val Lists = listOf(
        "books" to "id", "highlights" to "id", "posts" to "id",
        "collections" to "id", "reminders" to "id", "plans" to "bookId",
    )
    private val BookLocal = setOf("hasCover", "coverColor", "chapterCount", "pageCount", "textReady", "textVersion", "backedUpAt")
    /** Book fields that come from the counters, named as the counters that hold them. */
    private val BookCounters = listOf("readingMs", "pagesRead")
    private val SettingsLocal = setOf("fontSize", "margin", "avatarVersion")
    private val SelfStamped = listOf("position", "progress", "positionAt", "lastOpenedAt")

    /** `[ms, device]`, compared by ms and then device. */
    data class Stamp(val ms: Long, val dev: String) : Comparable<Stamp> {
        override fun compareTo(other: Stamp) = compareValuesBy(this, other, { it.ms }, { it.dev })
        fun json() = buildJsonArray { add(JsonPrimitive(ms)); add(JsonPrimitive(dev)) }
    }

    class Field(val stamp: Stamp, val value: JsonElement)

    class Entity(val f: MutableMap<String, Field> = linkedMapOf(), var del: Stamp? = null) {
        val alive: Boolean get() = del.let { d -> if (d == null) f.isNotEmpty() else f.values.any { it.stamp > d } }
        val missing: Boolean get() = f.isEmpty() && del == null

        /** Sets [name] unless it already holds a value with a stamp at least as new. */
        fun set(name: String, st: Stamp, v: JsonElement) {
            val cur = f[name]
            if (cur == null || cur.stamp < st) f[name] = Field(st, v)
        }

        fun values(): MutableMap<String, JsonElement> = f.mapValuesTo(linkedMapOf()) { it.value.value }
    }

    /** Nested maps of numbers: values are [Long] or another [Counts]. */
    class Counts : LinkedHashMap<String, Any>() {
        fun sub(k: String): Counts = (get(k) as? Counts) ?: Counts().also { put(k, it) }
        fun num(k: String): Long = get(k) as? Long ?: 0L
    }

    class Doc(
        var format: Long = FORMAT,
        val lists: MutableMap<String, MutableMap<String, Entity>> = linkedMapOf(),
        var settings: Entity = Entity(),
        val counters: MutableMap<String, Counts> = linkedMapOf(),
    ) {
        fun list(kind: String) = lists.getOrPut(kind) { linkedMapOf() }

        fun toJson(): JsonObject = JsonObject(buildMap {
            put("format", JsonPrimitive(format))
            for ((kind, _) in Lists) lists[kind]?.let { m -> put(kind, JsonObject(m.mapValues { entityJson(it.value) })) }
            if (!settings.missing) put("settings", entityJson(settings))
            put("counters", JsonObject(counters.mapValues { countsJson(it.value) }))
        })

        fun copy(): Doc = fromJson(toJson())

        companion object {
            fun fromJson(o: JsonObject?): Doc {
                val d = Doc()
                if (o == null) return d
                d.format = num(o["format"])
                for ((kind, _) in Lists) {
                    val m = o[kind] as? JsonObject ?: continue
                    d.lists[kind] = m.mapValuesTo(linkedMapOf()) { entityOf(it.value) }
                }
                o["settings"]?.let { d.settings = entityOf(it) }
                (o["counters"] as? JsonObject)?.forEach { (dev, c) -> d.counters[dev] = countsOf(c) }
                return d
            }
        }
    }

    private fun num(e: JsonElement?): Long = (e as? JsonPrimitive)?.let { p -> if (p.isString) null else p.longOrNull ?: p.doubleOrNull?.toLong() } ?: 0L

    private fun stampOf(e: JsonElement?): Stamp? {
        val a = e as? JsonArray ?: return null
        val dev = (a.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return Stamp(num(a.getOrNull(0)), dev)
    }

    private fun entityOf(e: JsonElement): Entity {
        val o = e as? JsonObject ?: return Entity()
        val fields = linkedMapOf<String, Field>()
        (o["f"] as? JsonObject)?.forEach { (k, v) ->
            val st = stampOf(v) ?: return@forEach
            fields[k] = Field(st, (v as JsonArray).getOrNull(2) ?: JsonNull)
        }
        return Entity(fields, stampOf(o["del"]))
    }

    private fun entityJson(e: Entity) = JsonObject(buildMap {
        if (e.f.isNotEmpty()) put("f", JsonObject(e.f.mapValues { (_, fl) -> buildJsonArray { add(JsonPrimitive(fl.stamp.ms)); add(JsonPrimitive(fl.stamp.dev)); add(fl.value) } }))
        e.del?.let { put("del", it.json()) }
    })

    private fun countsOf(e: JsonElement): Counts = Counts().also { c ->
        (e as? JsonObject)?.forEach { (k, v) -> c[k] = if (v is JsonObject) countsOf(v) else num(v) }
    }

    private fun countsJson(c: Counts): JsonObject = JsonObject(c.mapValues { (_, v) -> if (v is Counts) countsJson(v) else JsonPrimitive(v as Long) })

    // ---- Join ----

    /** Folds [other] into [doc]: the newer stamp wins per field, the newer deletion, and per device and key the larger counter. */
    fun join(doc: Doc, other: Doc) {
        doc.format = maxOf(doc.format, other.format, FORMAT)
        for ((kind, _) in Lists) {
            val theirs = other.lists[kind] ?: continue
            val mine = doc.list(kind)
            theirs.forEach { (id, e) -> joinEntity(mine.getOrPut(id) { Entity() }, e) }
        }
        joinEntity(doc.settings, other.settings)
        other.counters.forEach { (dev, c) -> joinMax(doc.counters.getOrPut(dev) { Counts() }, c) }
    }

    private fun joinEntity(mine: Entity, theirs: Entity) {
        theirs.f.forEach { (k, fl) -> val cur = mine.f[k]; if (cur == null || fl.stamp > cur.stamp) mine.f[k] = fl }
        theirs.del?.let { d -> if (mine.del.let { it == null || d > it }) mine.del = d }
    }

    private fun joinMax(mine: Counts, theirs: Counts) {
        theirs.forEach { (k, v) ->
            if (v is Counts) joinMax(mine.sub(k), v)
            else if (mine[k] !is Long || (v as Long) > mine.num(k)) mine[k] = v as Long
        }
    }

    // ---- Record ----

    private fun isSynced(kind: String, f: String) = when (kind) {
        "books" -> f !in BookLocal && f !in BookCounters
        "settings" -> f !in SettingsLocal
        else -> true
    }

    /** The time a self-stamped book field really changed, or null for ordinary fields. */
    private fun ownMs(kind: String, item: JsonObject, f: String): Long? {
        if (kind != "books" || f !in SelfStamped) return null
        val opened = num(item["lastOpenedAt"])
        if (f == "lastOpenedAt") return opened
        return num(item["positionAt"]).takeIf { it > 0 } ?: opened
    }

    private fun writeAll(e: Entity, kind: String, l: JsonObject, s: Stamp) {
        l.forEach { (f, v) ->
            if (!isSynced(kind, f)) return@forEach
            val ms = ownMs(kind, l, f)
            e.set(f, if (ms != null && ms > 0) Stamp(ms, s.dev) else s, v)
        }
    }

    /** Text the reader writes, which is merged rather than replaced when two devices both changed it. */
    private val TextFields = setOf("highlights" to "note")

    private fun isEmpty(v: JsonElement): Boolean = when (v) {
        is JsonNull -> true
        is JsonPrimitive -> if (v.isString) v.content.isEmpty() else v.content == "false" || v.doubleOrNull == 0.0
        is JsonArray -> v.isEmpty()
        is JsonObject -> v.isEmpty()
    }

    /** Both versions of a text, unless one already contains the other. */
    private fun combineText(theirs: JsonElement, mine: JsonElement): JsonElement {
        val a = str(theirs).orEmpty()
        val b = str(mine).orEmpty()
        return when {
            a.isEmpty() || a in b -> mine
            b.isEmpty() || b in a -> theirs
            else -> JsonPrimitive("$a\n\n$b")
        }
    }

    /** Writes the fields of [l] that changed since [b]. A note changed on another device meanwhile keeps both texts. */
    private fun recordChanges(e: Entity, kind: String, l: JsonObject, b: JsonObject, s: Stamp) {
        l.forEach { (f, v) ->
            if (!isSynced(kind, f) || ownMs(kind, l, f) != null || b[f] == v) return@forEach
            val theirs = e.f[f]?.value
            e.set(f, s, if (theirs != null && (kind to f) in TextFields && b[f] != theirs) combineText(theirs, v) else v)
        }
    }

    /**
     * An item the account already has, met for the first time here: the account's values stay, and this
     * device's only fill fields the account has left empty, so a first sync never overwrites anything.
     */
    private fun fillGaps(e: Entity, kind: String, l: JsonObject, s: Stamp) {
        l.forEach { (f, v) ->
            if (!isSynced(kind, f) || ownMs(kind, l, f) != null || isEmpty(v)) return@forEach
            if (e.f[f]?.value?.let(::isEmpty) != false) e.set(f, s, v)
        }
    }

    private fun changed(kind: String, l: JsonObject, b: JsonObject) = l.any { (f, v) -> isSynced(kind, f) && b[f] != v }

    private fun index(list: JsonElement?, key: String): Map<String, JsonObject> = buildMap {
        (list as? JsonArray)?.forEach { item ->
            val o = item as? JsonObject ?: return@forEach
            val id = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
            put(id, o)
        }
    }

    /** Writes what changed in [local] since [base] into [doc], as [device] at [now]. [base] is null before a device's first sync. */
    fun record(doc: Doc, base: JsonObject?, local: JsonObject, device: String, now: Long) {
        val s = Stamp(now, device)
        for ((kind, key) in Lists) {
            val lItems = index(local[kind], key)
            val bItems = index(base?.get(kind), key)
            val entities = doc.list(kind)
            for ((id, l) in lItems) {
                val b = bItems[id]
                val e = entities.getOrPut(id) { Entity() }
                when {
                    e.missing -> writeAll(e, kind, l, s)
                    e.alive -> if (b != null) recordChanges(e, kind, l, b, s) else fillGaps(e, kind, l, s)
                    (if (b != null) changed(kind, l, b) else base != null) -> writeAll(e, kind, l, s)
                }
                if (kind == "books" && e.alive) for (f in SelfStamped) {
                    val v = l[f] ?: continue
                    val ms = ownMs(kind, l, f) ?: continue
                    if (ms > 0) e.set(f, Stamp(ms, device), v)
                }
            }
            for (id in bItems.keys) {
                if (id in lItems) continue
                val e = entities[id] ?: continue
                if (!e.alive) continue
                val newest = e.f.values.maxOfOrNull { it.stamp.ms } ?: 0L
                e.del = Stamp(maxOf(now, newest + 1), device)
            }
        }

        (local["settings"] as? JsonObject)?.let { l ->
            val e = doc.settings
            if (e.f.isEmpty()) writeAll(e, "settings", l, s)
            else when (val b = base?.get("settings") as? JsonObject) {
                null -> fillGaps(e, "settings", l, s)
                else -> recordChanges(e, "settings", l, b, s)
            }
        }

        recordCounters(doc, base, local, device)
    }

    /** Totals over all devices. */
    private fun totals(doc: Doc): Counts = Counts().also { t -> doc.counters.values.forEach { addInto(t, it) } }

    private fun addInto(acc: Counts, c: Counts) {
        c.forEach { (k, v) -> if (v is Counts) addInto(acc.sub(k), v) else acc[k] = acc.num(k) + (v as Long) }
    }

    private fun lookup(root: Any?, path: List<String>): Long {
        var at: Any? = root
        for (p in path) at = when (at) {
            is Counts -> at[p]
            is JsonObject -> at[p]
            else -> null
        }
        return when (at) {
            is Long -> at
            is JsonElement -> num(at)
            else -> 0L
        }
    }

    private fun recordCounters(doc: Doc, base: JsonObject?, local: JsonObject, device: String) {
        val total = totals(doc)
        val deltas = mutableListOf<Pair<List<String>, Long>>()
        fun add(path: List<String>, lv: Long) {
            val before = if (base != null) lookup(base, path) else lookup(total, path)
            if (lv - before > 0) deltas += path to lv - before
        }
        for (kind in listOf("readingLog", "pagesLog")) {
            (local[kind] as? JsonObject)?.forEach { (day, v) -> add(listOf(kind, day), num(v)) }
        }
        (local["bookDays"] as? JsonObject)?.forEach { (day, books) ->
            (books as? JsonObject)?.forEach { (id, v) -> add(listOf("bookDays", day, id), num(v)) }
        }
        val baseBooks = index(base?.get("books"), "id")
        for ((id, book) in index(local["books"], "id")) for (f in BookCounters) {
            val lv = num(book[f])
            val before = if (base != null) num(baseBooks[id]?.get(f)) else lookup(total, listOf(f, id))
            if (lv - before > 0) deltas += listOf(f, id) to lv - before
        }
        if (deltas.isEmpty()) return
        val mine = doc.counters.getOrPut(device) { Counts() }
        for ((path, d) in deltas) {
            var at = mine
            for (p in path.dropLast(1)) at = at.sub(p)
            at[path.last()] = at.num(path.last()) + d
        }
    }

    // ---- Project ----

    private fun sortKey(kind: String, o: Map<String, JsonElement>): Pair<Long, String> =
        num(o[if (kind == "books") "addedAt" else "createdAt"]) to ((o[if (kind == "plans") "bookId" else "id"] as? JsonPrimitive)?.content ?: "")

    private fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * The library as [doc] describes it, keeping [local]'s per-device fields. Books whose file isn't on this
     * device yet are left out, with the highlights, posts and plans that belong to them.
     */
    fun project(doc: Doc, local: JsonObject): JsonObject {
        val out = local.toMutableMap()
        val total = totals(doc)
        var bookIds = emptySet<String>()
        for ((kind, key) in Lists) {
            val entities = doc.lists[kind].orEmpty()
            val localList = (local[kind] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            fun keep(o: Map<String, JsonElement>) = when (kind) {
                "highlights", "posts", "plans" -> str(o["bookId"])?.let { it in bookIds } == true
                "reminders" -> str(o["bookId"])?.let { it in bookIds } ?: true
                else -> true
            }
            val here = mutableSetOf<String>()
            val result = mutableListOf<JsonElement>()
            for (l in localList) {
                val id = str(l[key]) ?: continue
                here += id
                val e = entities[id]?.takeIf { it.alive } ?: continue
                val o = l.toMutableMap()
                e.values().forEach { (f, v) -> if (isSynced(kind, f)) o[f] = v }
                if (kind == "books") for (f in BookCounters) o[f] = JsonPrimitive(lookup(total, listOf(f, id)))
                if (keep(o)) result += JsonObject(o)
            }
            if (kind != "books") {
                val new = entities.filter { (id, e) -> id !in here && e.alive }
                    .map { (id, e) -> e.values().also { it[key] = JsonPrimitive(id) } }
                    .filter { keep(it) }
                if (kind == "posts") {
                    val sorted = new.sortedWith(compareByDescending<Map<String, JsonElement>> { sortKey(kind, it).first }.thenBy { sortKey(kind, it).second })
                    result.addAll(0, sorted.map { JsonObject(it) })
                } else {
                    result += new.sortedWith(compareBy<Map<String, JsonElement>> { sortKey(kind, it).first }.thenBy { sortKey(kind, it).second }).map { JsonObject(it) }
                }
            } else {
                bookIds = result.mapNotNull { str((it as JsonObject)["id"]) }.toSet()
            }
            out[kind] = JsonArray(result)
        }
        val settings = (local["settings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        doc.settings.values().forEach { (f, v) -> if (isSynced("settings", f)) settings[f] = v }
        out["settings"] = JsonObject(settings)
        for (kind in listOf("readingLog", "pagesLog", "bookDays")) out[kind] = (total[kind] as? Counts)?.let(::countsJson) ?: JsonObject(emptyMap())
        return JsonObject(out)
    }

    /**
     * One sync's merge: joins [remote] (when it was pulled) into [doc], records the library's changes since
     * [base], and returns the library to apply. The caller saves [doc], and as the next base the library as it
     * encodes once applied.
     */
    fun merge(doc: Doc, remote: Doc?, base: JsonObject?, local: JsonObject, device: String, now: Long): JsonObject {
        if (remote != null) join(doc, remote)
        record(doc, base, local, device, now)
        return project(doc, local)
    }

    /** Books the account has that this device doesn't yet: (id, file name). */
    fun pendingBooks(doc: Doc, local: JsonObject): List<Pair<String, String>> {
        val here = index(local["books"], "id").keys
        return doc.lists["books"].orEmpty().filter { (id, e) -> id !in here && e.alive }
            .map { (id, e) -> id to (str(e.f["fileName"]?.value) ?: "book.epub") }
    }

    /** Ids of books deleted on some device. */
    fun deletedBooks(doc: Doc): Set<String> = doc.lists["books"].orEmpty().filterValues { !it.alive }.keys

    /** Drive name for a book's file: `book-<id>.epub` or `.pdf`. */
    fun bookFileName(id: String, fileName: String) = "book-$id.${fileName.substringAfterLast('.', "epub")}"

    /** The book id in a Drive file name from [bookFileName]. */
    fun bookIdOfFile(name: String): String? = name.removePrefix("book-").takeIf { name.startsWith("book-") }?.substringBefore('.')

    /** 8 random hex digits naming this device in stamps and counters. */
    fun newDeviceId() = UUID.randomUUID().toString().replace("-", "").take(8)

    /** gzip-compressed JSON, as stored on Drive. */
    fun encode(doc: Doc): ByteArray = ByteArrayOutputStream().also { bos ->
        GZIPOutputStream(bos).use { it.write(doc.toJson().toString().toByteArray()) }
    }.toByteArray()

    fun decode(bytes: ByteArray): Doc {
        val text = runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8) }
            .getOrElse { error("Unreadable sync file") }
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse { error("Unreadable sync file") }
        if (num(o["format"]) > FORMAT) error("The sync file is from a newer GBook: update this app to sync")
        return Doc.fromJson(o)
    }

    /** A stable hash of the document, the same however its maps happen to be ordered. */
    fun hash(doc: Doc): String {
        fun canon(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.toSortedMap().mapValues { canon(it.value) })
            is JsonArray -> JsonArray(e.map(::canon))
            else -> e
        }
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(canon(doc.toJson()).toString().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
