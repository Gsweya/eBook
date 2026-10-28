package com.gbook.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * The few Drive REST calls sync needs, in the app's hidden folder. Blocking: call them off the main thread.
 * [base] stands in for Google's hosts in tests (see `scripts/fake-drive.py`).
 */
class DriveApi(private val token: String, private val base: String? = null) {
    data class DriveFile(val id: String, val name: String, val version: Long)

    /** Google refused the token: it expired or access was revoked. */
    class Unauthorized : IOException("Google Drive refused access")

    /** Signed in, but without the Drive permission: Google's consent screen lets people untick it. */
    class NoDriveAccess : IOException("GBook wasn't allowed to use Google Drive. Sign in again and tick the Google Drive box.")

    private fun open(method: String, real: String, query: Map<String, String> = emptyMap()): HttpURLConnection {
        val url = base?.let { it.trimEnd('/') + URL(real).path } ?: real
        val q = query.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL(if (q.isEmpty()) url else "$url?$q").openConnection() as HttpURLConnection
        // HttpURLConnection has no PATCH; Google's APIs accept it as an override on POST.
        if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun check(c: HttpURLConnection): HttpURLConnection {
        val code = c.responseCode
        if (code == 401) throw Unauthorized()
        if (code >= 300) {
            val body = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code == 403 && "insufficient" in body) throw NoDriveAccess()
            val msg = runCatching { GJson.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content }.getOrNull()
            throw IOException("Google Drive: ${msg ?: "HTTP $code"}")
        }
        return c
    }

    private fun json(c: HttpURLConnection): JsonObject = check(c).inputStream.use { GJson.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject }

    private fun fileOf(o: JsonObject) = DriveFile(
        id = o["id"]?.jsonPrimitive?.content.orEmpty(),
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        version = (o["version"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0,
    )

    /** Every file in the app data folder. */
    fun list(): List<DriveFile> {
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val q = mutableMapOf("spaces" to "appDataFolder", "pageSize" to "1000", "fields" to "nextPageToken,files($FIELDS)")
            page?.let { q["pageToken"] = it }
            val o = json(open("GET", FILES, q))
            o["files"]?.jsonArray?.forEach { out += fileOf(it.jsonObject) }
            page = o["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return out
    }

    fun download(id: String): ByteArray = check(open("GET", "$FILES/$id", mapOf("alt" to "media"))).inputStream.use { it.readBytes() }

    /** Streams a file to [dest]. */
    fun downloadTo(id: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".partial")
        check(open("GET", "$FILES/$id", mapOf("alt" to "media"))).inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
        if (!tmp.renameTo(dest)) throw IOException("Couldn't save the download")
    }

    /** Creates [name] in the app data folder. */
    fun create(name: String, bytes: ByteArray, mime: String): DriveFile {
        val boundary = "gbook" + UUID.randomUUID().toString().replace("-", "")
        val meta = """{"name":${JsonPrimitive(name)},"parents":["appDataFolder"]}"""
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray() +
            bytes + "\r\n--$boundary--\r\n".toByteArray()
        val c = open("POST", UPLOAD, mapOf("uploadType" to "multipart", "fields" to FIELDS))
        c.doOutput = true
        c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        c.setFixedLengthStreamingMode(body.size)
        c.outputStream.use { it.write(body) }
        return fileOf(json(c))
    }

    /** Replaces the contents of file [id]. */
    fun update(id: String, bytes: ByteArray, mime: String): DriveFile {
        val c = open("PATCH", "$UPLOAD/$id", mapOf("uploadType" to "media", "fields" to FIELDS))
        c.doOutput = true
        c.setRequestProperty("Content-Type", mime)
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        return fileOf(json(c))
    }

    /** Uploads [file] as [name] in one resumable session, so large books stream from disk. */
    fun uploadFile(name: String, file: File, mime: String): DriveFile {
        val meta = """{"name":${JsonPrimitive(name)},"parents":["appDataFolder"]}""".toByteArray()
        val start = open("POST", UPLOAD, mapOf("uploadType" to "resumable", "fields" to FIELDS))
        start.doOutput = true
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Type", mime)
        start.setRequestProperty("X-Upload-Content-Length", file.length().toString())
        start.setFixedLengthStreamingMode(meta.size)
        start.outputStream.use { it.write(meta) }
        val session = check(start).getHeaderField("Location") ?: throw IOException("Google Drive didn't start the upload")
        val c = URL(session).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.connectTimeout = 20_000
        c.readTimeout = 120_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", mime)
        c.setFixedLengthStreamingMode(file.length())
        c.outputStream.use { os -> file.inputStream().use { it.copyTo(os) } }
        return fileOf(json(c))
    }

    fun delete(id: String) {
        val c = open("DELETE", "$FILES/$id")
        if (c.responseCode != 404) check(c)
    }

    /** The signed-in account's email address (needs the `email` scope). */
    fun email(): String = json(open("GET", USERINFO))["email"]?.jsonPrimitive?.contentOrNull.orEmpty()

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val USERINFO = "https://openidconnect.googleapis.com/v1/userinfo"
        private const val FIELDS = "id,name,version"
    }
}
