package com.gbook.data

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Saved in `sync/state.json`. */
@Serializable
data class SyncState(
    /** This device's name in the sync document. */
    val device: String = "",
    val enabled: Boolean = false,
    /** Upload and download book files too, not just reading state. */
    val syncBooks: Boolean = true,
    val account: String = "",
    val lastSync: Long = 0,
    /** Drive version of the sync document when this device last read or wrote it. */
    val version: Long = 0,
    /** Hash of the document as it is on Drive, so an unchanged one isn't uploaded again. */
    val driveHash: String = "",
    /** Why the last sync failed, if it did. */
    val error: String = "",
)

data class SyncStatus(val state: SyncState, val running: Boolean = false, val progress: String = "")

/**
 * Google Drive sync for the running app: when to sync, and the steps of one sync (see `shared/sync/README.md`).
 * Runs in its own scope so a sync started as the app goes to the background isn't cancelled with the screen.
 * [onApplied] runs after synced changes have been written into the library.
 */
class DriveSync(private val ctx: Context, private val store: Store, private val importer: Importer, private val onApplied: () -> Unit) {
    private val dir = File(ctx.filesDir, "sync").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")
    private val docFile = File(dir, "doc.json")
    private val baseFile = File(dir, "base.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val again = AtomicBoolean(false)
    private var soonJob: Job? = null
    private var loop: Job? = null
    @Volatile private var token: Pair<String, Long>? = null

    private val _status = MutableStateFlow(SyncStatus(loadState()))
    val status: StateFlow<SyncStatus> = _status
    private val state get() = _status.value.state

    private class NeedsSignIn : Exception()

    private fun loadState(): SyncState {
        val s = runCatching { GJson.decodeFromString<SyncState>(stateFile.readText()) }.getOrDefault(SyncState())
        return if (s.device.isNotEmpty()) s else s.copy(device = SyncMerge.newDeviceId()).also { save(it) }
    }

    private fun save(s: SyncState) {
        val tmp = File(dir, "state.json.tmp")
        tmp.writeText(GJson.encodeToString(SyncState.serializer(), s))
        tmp.renameTo(stateFile)
    }

    private fun edit(f: SyncState.() -> SyncState) {
        _status.update { it.copy(state = it.state.f()) }
        save(state)
    }

    private fun progress(p: String) = _status.update { it.copy(progress = p) }

    /** Forgets this device's sync history, so the next sync is a first sync. */
    private fun reset() {
        docFile.delete(); baseFile.delete()
        edit { copy(version = 0, driveHash = "", lastSync = 0) }
    }

    // ---- Google sign-in ----

    private fun request(account: String) = AuthorizationRequest.builder().setRequestedScopes(Scopes)
        .apply { if (account.isNotBlank()) setAccount(Account(account, "com.google")) }.build()

    /** Starts signing in. Returns Google's consent screen to launch, or null when access was granted without one. */
    suspend fun beginSignIn(): PendingIntent? {
        val r = Identity.getAuthorizationClient(ctx).authorize(request("")).await()
        if (r.hasResolution()) return r.pendingIntent
        signedIn(r)
        return null
    }

    /** Completes signing in with the consent screen's result. */
    suspend fun finishSignIn(data: Intent?) = signedIn(Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data))

    private suspend fun signedIn(r: AuthorizationResult) {
        val t = r.accessToken ?: throw IOException("Google didn't grant access")
        if (r.grantedScopes.none { "drive.appdata" in it }) throw DriveApi.NoDriveAccess()
        token = t to System.currentTimeMillis() + TOKEN_LIFE
        val email = kotlinx.coroutines.withContext(Dispatchers.IO) { DriveApi(t).email() }
        if (state.account != email) reset()
        edit { copy(enabled = true, account = email, error = "") }
        syncNow()
    }

    /** Stops syncing and forgets the account; the library stays as it is on this phone. */
    fun signOut() {
        val account = state.account
        edit { copy(enabled = false, account = "", error = "") }
        reset()
        token = null
        if (account.isNotBlank()) scope.launch {
            runCatching {
                Identity.getAuthorizationClient(ctx).revokeAccess(
                    RevokeAccessRequest.builder().setAccount(Account(account, "com.google")).setScopes(Scopes).build(),
                ).await()
            }
        }
    }

    fun setSyncBooks(on: Boolean) {
        edit { copy(syncBooks = on) }
        if (on) syncNow()
    }

    private suspend fun accessToken(): String {
        token?.let { (t, until) -> if (System.currentTimeMillis() < until) return t }
        val r = Identity.getAuthorizationClient(ctx).authorize(request(state.account)).await()
        if (r.hasResolution()) throw NeedsSignIn()
        val t = r.accessToken ?: throw NeedsSignIn()
        token = t to System.currentTimeMillis() + TOKEN_LIFE
        return t
    }

    private suspend fun dropToken() {
        val t = token?.first ?: return
        token = null
        runCatching { Identity.getAuthorizationClient(ctx).clearToken(ClearTokenRequest.builder().setToken(t).build()).await() }
    }

    // ---- When to sync ----

    /** The app came to the front: sync if it has been a while, then every few minutes until it leaves. */
    fun onForeground() {
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                if (System.currentTimeMillis() - state.lastSync > 60_000) syncNow()
                delay(EVERY)
            }
        }
    }

    /** The app went to the background: one last sync. */
    fun onBackground() {
        loop?.cancel()
        syncNow()
    }

    /** Syncs a moment from now, once however many times it's asked: after closing a book, say. */
    fun soon() {
        if (!state.enabled) return
        soonJob?.cancel()
        soonJob = scope.launch { delay(3_000); syncNow() }
    }

    fun syncNow() {
        if (!state.enabled) return
        scope.launch {
            if (!lock.tryLock()) { again.set(true); return@launch }
            try {
                do {
                    again.set(false)
                    runOnce()
                } while (again.get() && state.enabled)
            } finally {
                lock.unlock()
            }
        }
    }

    private suspend fun runOnce() {
        _status.update { it.copy(running = true, progress = "") }
        val r = runCatching {
            try { run() } catch (e: DriveApi.Unauthorized) { dropToken(); run() }
        }
        when (val e = r.exceptionOrNull()) {
            null -> edit { copy(lastSync = System.currentTimeMillis(), error = "") }
            is NeedsSignIn, is DriveApi.Unauthorized -> edit { copy(enabled = false, error = "Google Drive signed GBook out. Sign in again to keep syncing.") }
            is DriveApi.NoDriveAccess -> edit { copy(enabled = false, error = e.message.orEmpty()) }
            else -> {
                Log.w("GBook", "sync failed", e)
                val msg = when (e) {
                    is UnknownHostException, is ConnectException -> "No connection"
                    is SocketTimeoutException -> "Google Drive didn't answer in time"
                    else -> e.message ?: "Something went wrong"
                }
                edit { copy(error = msg) }
            }
        }
        _status.update { it.copy(running = false, progress = "") }
    }

    // ---- One sync ----

    private fun loadDoc() = SyncMerge.Doc.fromJson(runCatching { GJson.parseToJsonElement(docFile.readText()).jsonObject }.getOrNull())
    private fun loadBase(): JsonObject? = runCatching { GJson.parseToJsonElement(baseFile.readText()).jsonObject }.getOrNull()

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    /** Merges into the library as it is right now and applies the result; returns the new document and base. */
    private fun mergeAndApply(doc: SyncMerge.Doc, remote: SyncMerge.Doc?): Pair<SyncMerge.Doc, JsonObject> {
        val base = loadBase()
        while (true) {
            val cur = store.data.value
            val local = GJson.encodeToJsonElement(LibraryData.serializer(), cur).jsonObject
            val d = doc.copy()
            val projected = SyncMerge.merge(d, remote, base, local, state.device, System.currentTimeMillis())
            val next = if (projected == local) cur else GJson.decodeFromJsonElement(LibraryData.serializer(), projected)
            // Only apply onto the library the merge saw; if it changed meanwhile, merge again.
            if (next !== cur && !store.replaceIf(cur, next)) continue
            if (next !== cur) {
                saveSnapshot(local)
                val keep = next.books.map { it.id }.toSet()
                cur.books.filter { it.id !in keep }.forEach { store.deleteFiles(it.id) }
                onApplied()
            }
            val newBase = GJson.encodeToJsonElement(LibraryData.serializer(), next).jsonObject
            writeAtomic(docFile, d.toJson().toString())
            writeAtomic(baseFile, newBase.toString())
            return d to newBase
        }
    }

    /**
     * Keeps the library as it was before a sync changed it, the last [SNAPSHOTS] times, so nothing a sync
     * replaced is gone for good.
     */
    private fun saveSnapshot(library: JsonObject) {
        val history = File(dir, "history").apply { mkdirs() }
        writeAtomic(File(history, "library-${System.currentTimeMillis()}.json"), library.toString())
        history.listFiles { f -> f.extension == "json" }.orEmpty().sortedByDescending { it.name }.drop(SNAPSHOTS).forEach { it.delete() }
    }

    private suspend fun run() {
        val api = DriveApi(accessToken())

        // 1. Pull, when the document on Drive changed since this device last saw it.
        val files = api.list()
        val docs = files.filter { it.name == SyncMerge.DOC_NAME }.sortedBy { it.id }
        val primary = docs.firstOrNull()
        val extras = docs.drop(1)
        // Two devices that both started syncing at once each made a document: join them all.
        val remote = if (docs.size > 1 || (primary != null && primary.version != state.version))
            SyncMerge.Doc().also { j -> docs.forEach { SyncMerge.join(j, SyncMerge.decode(api.download(it.id))) } } else null

        // 2–3. Record and project.
        var (doc, base) = mergeAndApply(loadDoc(), remote)

        // 4. Books that are new to this phone.
        val byName = files.associateBy { it.name }
        if (state.syncBooks) {
            val todo = SyncMerge.pendingBooks(doc, base).mapNotNull { (id, name) -> byName[SyncMerge.bookFileName(id, name)]?.let { id to it } }
            var imported = 0
            todo.forEachIndexed { i, (id, f) ->
                progress("Downloading books (${i + 1} of ${todo.size})…")
                val tmp = File(ctx.cacheDir, "sync-" + f.name)
                runCatching {
                    api.downloadTo(f.id, tmp)
                    val b = importer.importStream(f.name) { tmp.inputStream() }
                    if (b.id == id) imported++ else store.deleteBook(b.id)
                }.onFailure { Log.w("GBook", "couldn't download book $id", it) }
                tmp.delete()
            }
            if (imported > 0) doc = mergeAndApply(doc, null).first
        }

        // 5. Push the document if Drive's copy is behind.
        val nowHash = SyncMerge.hash(doc)
        val driveHash = remote?.let(SyncMerge::hash) ?: state.driveHash
        if (nowHash != driveHash || primary == null || extras.isNotEmpty()) {
            progress("Syncing…")
            val bytes = SyncMerge.encode(doc)
            val saved = primary?.let { api.update(it.id, bytes, DOC_MIME) } ?: api.create(SyncMerge.DOC_NAME, bytes, DOC_MIME)
            extras.forEach { api.delete(it.id) }
            edit { copy(version = saved.version, driveHash = nowHash) }
        } else if (primary != null) {
            edit { copy(version = primary.version, driveHash = nowHash) }
        }

        // 6. Upload books Drive doesn't have; delete the files of deleted books and duplicate uploads.
        if (state.syncBooks && state.enabled) {
            val uploads = store.data.value.books
                .map { SyncMerge.bookFileName(it.id, it.fileName) to store.bookFile(it) }
                .filter { (name, f) -> name !in byName && f.isFile }
            uploads.forEachIndexed { i, (name, f) ->
                progress("Uploading books (${i + 1} of ${uploads.size})…")
                api.uploadFile(name, f, if (name.endsWith(".pdf")) "application/pdf" else "application/epub+zip")
            }
            val dead = SyncMerge.deletedBooks(doc)
            val seen = mutableSetOf<String>()
            files.sortedBy { it.id }
                .filter { f -> SyncMerge.bookIdOfFile(f.name)?.let { it in dead || !seen.add(f.name) } ?: false }
                .forEach { api.delete(it.id) }
        }
    }

    companion object {
        private val Scopes = listOf(Scope("https://www.googleapis.com/auth/drive.appdata"), Scope("email"))
        private const val DOC_MIME = "application/gzip"
        private const val SNAPSHOTS = 10
        /** How often to sync while the app is open. */
        private const val EVERY = 5 * 60_000L
        /** Access tokens last an hour; take a new one a little before. */
        private const val TOKEN_LIFE = 50 * 60_000L
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
