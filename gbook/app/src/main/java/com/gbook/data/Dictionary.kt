package com.gbook.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * One meaning of a word. [pos] is "noun", "verb", "adjective" or "adverb" (or whatever the online source says);
 * [lemma] is the base form it was found under, e.g. "run" for "running" as a verb.
 */
class Sense(val pos: String, val lemma: String, val meaning: String, val examples: List<String>, val synonyms: List<String>)

class Definition(val word: String, val phonetic: String?, val senses: List<Sense>, val source: String)

/**
 * Word definitions, either from WordNet downloaded once into the app's files (offline, nothing leaves
 * the phone) or from the Free Dictionary API (online). The reader picks which the first time they look a word up;
 * the choice is kept on this device only.
 */
class Dictionary(private val app: Context) {
    enum class Source { NONE, OFFLINE, ONLINE }

    private val prefs = app.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "dictionary")
    private var wordnet = WordNet(dir)

    val source = MutableStateFlow(runCatching { Source.valueOf(prefs.getString("source", null) ?: "NONE") }.getOrDefault(Source.NONE))
    /** Download progress 0..1 while the offline dictionary is being fetched, otherwise null. */
    val progress = MutableStateFlow<Float?>(null)
    /** While downloading: about how many seconds are left, once there's enough to go on; and bytes so far / total. */
    val secondsLeft = MutableStateFlow<Long?>(null)
    val bytes = MutableStateFlow(0L to DOWNLOAD_BYTES)
    val installed get() = File(dir, READY).exists()
    /** Space the offline dictionary takes, in bytes. */
    val installedBytes get() = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun choose(s: Source) {
        prefs.edit().putString("source", s.name).apply()
        source.value = s
    }

    /** Whether a download handed to the system is still under way (it carries on while GBook is closed). */
    val pending get() = prefs.getLong("download", -1L) >= 0

    /**
     * Downloads the offline dictionary through Android's download manager, so it keeps going in the background and
     * on a slow connection, then unpacks it and makes it the source. Calling it again while a download is under way
     * (e.g. after the app was closed) picks that download up instead of starting another.
     */
    suspend fun download() = withContext(Dispatchers.IO) {
        if (progress.value != null) return@withContext
        progress.value = 0f
        val dm = app.getSystemService(DownloadManager::class.java)
        // The download manager writes to the app's own external folder; no storage permission is needed.
        val zip = File(app.getExternalFilesDir(null), ZIP)
        val tmp = File(dir.parentFile, "dictionary.tmp")
        var id = prefs.getLong("download", -1L)
        // A download started for an older address is dropped and started again from the current one.
        if (id >= 0 && prefs.getString("downloadUrl", null) != DOWNLOAD_URL) { dm.remove(id); id = -1L }
        try {
            if (id < 0 || dm.statusOf(id) == null) {
                zip.delete()
                val req = DownloadManager.Request(Uri.parse(DOWNLOAD_URL))
                    .setTitle("GBook dictionary")
                    .setDescription("WordNet, for looking words up offline")
                    .setDestinationInExternalFilesDir(app, null, ZIP)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                id = dm.enqueue(req)
                prefs.edit().putLong("download", id).putString("downloadUrl", DOWNLOAD_URL).apply()
            }
            // Download speed, smoothed over the last few seconds, for the time left.
            var rate = 0.0
            var lastBytes = -1L
            var lastAt = System.nanoTime()
            while (true) {
                val st = dm.statusOf(id) ?: throw IOException("The download was cancelled")
                when (st.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> break
                    DownloadManager.STATUS_FAILED -> throw IOException("The download failed (error ${st.reason})")
                }
                val total = st.total.takeIf { it > 0 } ?: DOWNLOAD_BYTES
                progress.value = (st.done.toFloat() / total).coerceIn(0f, 0.98f)
                bytes.value = st.done to total
                val now = System.nanoTime()
                if (lastBytes in 0..st.done && st.done > 0) {
                    val r = (st.done - lastBytes) / ((now - lastAt) / 1e9)
                    rate = if (rate == 0.0) r else rate * 0.8 + r * 0.2
                    secondsLeft.value = if (rate > 1) ((total - st.done) / rate).toLong() else null
                }
                lastBytes = st.done; lastAt = now
                delay(500)
            }
            tmp.deleteRecursively(); tmp.mkdirs()
            untar(zip, tmp) { it in WordNet.FILES }
            if (WordNet.FILES.any { !File(tmp, it).exists() }) throw IOException("The dictionary download was incomplete")
            // Irregular forms ("ran", "children") come with the app; the download doesn't have them.
            for (pos in WordNet.EXCEPTIONS) app.assets.open("wordnet/$pos").use { a -> File(tmp, pos).outputStream().use { a.copyTo(it) } }
            File(tmp, READY).writeText(DOWNLOAD_URL)
            dir.deleteRecursively()
            if (!tmp.renameTo(dir)) throw IOException("Couldn't save the dictionary")
            wordnet = WordNet(dir)
            choose(Source.OFFLINE)
            forget(dm, id, zip)
        } catch (e: IOException) {
            forget(dm, id, zip)
            throw e
        } finally {
            tmp.deleteRecursively()
            progress.value = null
            secondsLeft.value = null
        }
    }

    /** Unpacks the files of a .tgz whose names (without their folders) pass [keep] into [into]. */
    private fun untar(tgz: File, into: File, keep: (String) -> Boolean) {
        GZIPInputStream(tgz.inputStream().buffered()).use { input ->
            val header = ByteArray(512)
            while (true) {
                if (readFully(input, header) < 512 || header.all { it.toInt() == 0 }) break
                fun field(from: Int, len: Int) = String(header, from, len, Charsets.US_ASCII).substringBefore('\u0000').trim()
                val name = (field(345, 155).let { if (it.isEmpty()) "" else "$it/" }) + field(0, 100)
                val size = field(124, 12).toLongOrNull(8) ?: 0L
                val file = header[156].toInt().toChar().let { it == '0' || it == '\u0000' }
                val base = name.substringAfterLast('/')
                if (file && keep(base)) File(into, base).outputStream().use { out -> copyExactly(input, out, size) }
                else skipExactly(input, size)
                // Entries are padded to whole 512-byte blocks.
                skipExactly(input, (512 - size % 512) % 512)
            }
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var got = 0
        while (got < buf.size) {
            val r = input.read(buf, got, buf.size - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    /** Copies exactly [n] bytes to [out], or skips them when [out] is null. */
    private fun copyExactly(input: java.io.InputStream, out: java.io.OutputStream?, n: Long) {
        val buf = ByteArray(64 * 1024)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("The dictionary download was incomplete")
            out?.write(buf, 0, r); left -= r
        }
    }

    private fun skipExactly(input: java.io.InputStream, n: Long) = copyExactly(input, null, n)

    private fun forget(dm: DownloadManager, id: Long, zip: File) {
        if (id >= 0) dm.remove(id)
        zip.delete()
        prefs.edit().remove("download").remove("downloadUrl").apply()
    }

    private class Status(val status: Int, val reason: Int, val done: Long, val total: Long)

    private fun DownloadManager.statusOf(id: Long): Status? = query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (!c.moveToFirst()) return@use null
        fun col(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        Status(
            col(DownloadManager.COLUMN_STATUS).toInt(), col(DownloadManager.COLUMN_REASON).toInt(),
            col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
        )
    }

    fun deleteOffline() {
        dir.deleteRecursively()
        wordnet = WordNet(dir)
        if (source.value == Source.OFFLINE) choose(Source.NONE)
    }

    /** Looks [raw] up in the chosen source; null when the word isn't there. Throws [IOException] when offline and online lookup fails. */
    suspend fun define(raw: String, from: Source = source.value): Definition? = withContext(Dispatchers.IO) {
        val word = clean(raw)
        if (word.isEmpty()) return@withContext null
        if (from == Source.OFFLINE && installed) wordnet.define(word) else online(word)
    }

    // ---- Online: the Free Dictionary API (dictionaryapi.dev, Wiktionary data) ----

    private fun online(word: String): Definition? {
        val c = URL(ONLINE_URL + URLEncoder.encode(word.lowercase(), "UTF-8").replace("+", "%20")).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        val body = try {
            if (c.responseCode == 404) return null
            if (c.responseCode >= 300) throw IOException("The dictionary didn't answer (HTTP ${c.responseCode})")
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: FileNotFoundException) { return null }
        val entries = GJson.parseToJsonElement(body) as? JsonArray ?: return null
        val senses = mutableListOf<Sense>()
        var phonetic: String? = null
        var shown = word
        for (e in entries) {
            val o = e as? JsonObject ?: continue
            if (senses.isEmpty()) shown = o.str("word") ?: word
            phonetic = phonetic ?: o.str("phonetic") ?: (o["phonetics"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonObject)?.str("text") }
            for (m in o["meanings"] as? JsonArray ?: continue) {
                val mo = m as? JsonObject ?: continue
                val pos = mo.str("partOfSpeech") ?: ""
                val shared = mo.strings("synonyms")
                (mo["definitions"] as? JsonArray)?.take(MAX_SENSES)?.forEach { d ->
                    val dobj = d as? JsonObject ?: return@forEach
                    val text = dobj.str("definition") ?: return@forEach
                    senses += Sense(pos, shown, text, listOfNotNull(dobj.str("example")), (dobj.strings("synonyms") + shared).distinct().take(6))
                }
            }
        }
        if (senses.isEmpty()) return null
        return Definition(shown, phonetic, senses, "Free Dictionary API · Wiktionary")
    }

    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

    companion object {
        /** "About 2 min left", "About 40 sec left"; null until the speed is known. */
        fun timeLeft(sec: Long?): String? = when {
            sec == null -> null
            sec < 10 -> "A few seconds left"
            sec < 60 -> "About ${(sec + 4) / 5 * 5} sec left"
            sec < 3600 -> "About ${(sec + 30) / 60} min left"
            else -> "About ${sec / 3600} h ${(sec % 3600 + 30) / 60} min left"
        }

        /** "3.1 of 10.5 MB". */
        fun sizeText(done: Long, total: Long) = "%.1f of %.1f MB".format(done / 1e6, total / 1e6)

        /**
         * Princeton WordNet 3.1 in its database format, as packaged on npm (`wordnet-db`). npm's CDN is many times
         * faster than GitHub's release downloads on some connections; the exception lists come with the app instead.
         */
        const val DOWNLOAD_URL = "https://registry.npmjs.org/wordnet-db/-/wordnet-db-3.1.14.tgz"
        /** Size of [DOWNLOAD_URL], for progress if the server doesn't say. */
        const val DOWNLOAD_BYTES = 10_548_693L
        const val ONLINE_URL = "https://api.dictionaryapi.dev/api/v2/entries/en/"
        private const val READY = "ready"
        private const val ZIP = "wordnet.tgz"
        private const val MAX_SENSES = 8

        /** The selection as a dictionary query: surrounding punctuation and a possessive "'s" removed. */
        fun clean(raw: String): String = raw.trim()
            .replace('’', '\'')
            .trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
            .trim('\'', '-')
            .removeSuffix("'s")
            .replace(Regex("\\s+"), " ")
    }
}

/** WordNet's database files in [dir], searched in place: nothing is loaded into memory up front. */
class WordNet(private val dir: File) {
    fun define(word: String): Definition? {
        val key = word.lowercase().replace(' ', '_')
        val senses = mutableListOf<Sense>()
        var shown: String? = null
        for ((pos, name) in POS) {
            val lemma = baseForms(key, pos).firstOrNull { indexLine(pos, it) != null } ?: continue
            val idx = indexLine(pos, lemma)!!.trim().split(' ')
            // lemma pos synset_cnt p_cnt [ptr…] sense_cnt tagsense_cnt offset…
            val synsets = idx[2].toInt()
            val offsets = idx.takeLast(synsets).mapNotNull { it.toLongOrNull() }
            RandomAccessFile(File(dir, "data.$pos"), "r").use { f ->
                offsets.take(MAX_SENSES).forEach { off -> dataSense(lineAt(f, off), name, lemma)?.let { senses += it } }
            }
            if (shown == null) shown = lemma.replace('_', ' ')
        }
        if (senses.isEmpty()) return null
        return Definition(shown ?: word, null, senses, "WordNet 3.1 · Princeton University")
    }

    /** The word itself, then its base forms by WordNet's exception lists and suffix rules ("running" → "run"). */
    private fun baseForms(w: String, pos: String): List<String> {
        val out = linkedSetOf(w)
        exceptions(pos)[w]?.let { out += it }
        for ((suffix, ending) in RULES[pos].orEmpty()) {
            if (w.length > suffix.length + 1 && w.endsWith(suffix)) {
                val stem = w.dropLast(suffix.length) + ending
                out += stem
                // "stopped", "bigger": a doubled final consonant.
                if (ending.isEmpty() && stem.length > 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiouls") out += stem.dropLast(1)
            }
        }
        return out.toList()
    }

    private val excCache = HashMap<String, Map<String, List<String>>>()
    private fun exceptions(pos: String) = excCache.getOrPut(pos) {
        runCatching {
            File(dir, "$pos.exc").readLines().associate { l -> l.split(' ').let { it[0] to it.drop(1).filter(String::isNotBlank) } }
        }.getOrDefault(emptyMap())
    }

    /** Binary search of the sorted index.<pos> for the line starting with [lemma]. */
    private fun indexLine(pos: String, lemma: String): String? {
        val target = lemma.toByteArray()
        RandomAccessFile(File(dir, "index.$pos"), "r").use { f ->
            var lo = 0L
            var hi = f.length()
            while (lo < hi) {
                val mid = (lo + hi) / 2
                // Start of the first whole line at or after mid.
                val start = if (mid == 0L) 0L else nextLineStart(f, mid)
                if (start >= hi) { hi = mid; continue }
                val line = lineAt(f, start)
                val key = line.substringBefore(' ').toByteArray()
                // The licence header lines start with spaces, so they sort before every word.
                val cmp = if (line.startsWith(" ")) -1 else compareBytes(key, target)
                when {
                    cmp == 0 -> return line
                    cmp < 0 -> lo = start + line.toByteArray().size + 1
                    else -> hi = mid
                }
            }
        }
        return null
    }

    private fun nextLineStart(f: RandomAccessFile, from: Long): Long {
        f.seek(from - 1)
        val buf = ByteArray(4096)
        var pos = from - 1
        while (true) {
            val n = f.read(buf)
            if (n <= 0) return f.length()
            for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) return pos + i + 1
            pos += n
        }
    }

    private fun lineAt(f: RandomAccessFile, at: Long): String {
        f.seek(at)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = f.read(buf)
            if (n <= 0) break
            val nl = (0 until n).firstOrNull { buf[it] == '\n'.code.toByte() }
            out.write(buf, 0, nl ?: n)
            if (nl != null) break
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Parses a data.<pos> line: offset lex_filenum ss_type w_cnt (word lex_id)… p_cnt (pointer)… | gloss */
    private fun dataSense(line: String, pos: String, lemma: String): Sense? {
        val head = line.substringBefore(" | ").split(' ')
        val gloss = line.substringAfter(" | ", "").trim()
        if (gloss.isEmpty() || head.size < 4) return null
        val count = head[3].toIntOrNull(16) ?: 0
        val words = (0 until count).mapNotNull { head.getOrNull(4 + it * 2) }
            // Adjective markers such as "(a)" and "(p)" follow the word in the database.
            .map { it.substringBefore('(').replace('_', ' ') }
        val examples = Regex("\"([^\"]+)\"").findAll(gloss).map { it.groupValues[1].trim() }.toList()
        val meaning = gloss.substringBefore("; \"").substringBefore(" \"").trim().trimEnd(';').trim()
        val me = lemma.replace('_', ' ')
        return Sense(pos, me, meaning.ifEmpty { gloss }, examples, words.filterNot { it.equals(me, ignoreCase = true) }.distinct())
    }

    companion object {
        private const val MAX_SENSES = 8
        private val POS = listOf("noun" to "noun", "verb" to "verb", "adj" to "adjective", "adv" to "adverb")
        /** The files the download must provide. */
        val FILES = POS.flatMap { (p, _) -> listOf("index.$p", "data.$p") }.toSet()
        /** Irregular word forms, from Open English WordNet (CC BY 4.0), bundled in assets/wordnet. */
        val EXCEPTIONS = POS.map { (p, _) -> "$p.exc" }
        private val RULES = mapOf(
            "noun" to listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
            "verb" to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to ""),
            "adj" to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
        )
    }
}
