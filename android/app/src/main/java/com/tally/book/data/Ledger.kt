package com.tally.book.data

import android.content.Context
import android.util.AtomicFile
import com.tally.book.core.*
import com.tally.book.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/*
 * Where the book lives and how it is loaded, unlocked and saved.
 *
 * The file holds the DB as JSON, or with a lock the sealed envelope { enc, s, i, d, t, l }. AtomicFile keeps the previous
 * copy until a write completes, so a crash mid-write never leaves a half file.
 *
 * Threading: DB is only touched on the main thread. A save takes its JSON text on the main thread,
 * then seals and writes on IO.
 *
 * Every write of the file, a backup's read of it and a restore's replacement of it hold [disk], so a backup never copies a
 * half-written book (AtomicFile on Android 10 rewrites the file in place behind a .bak). A restore also [freeze]s saving: a
 * save already under way when the book is replaced is dropped (its generation is stale), not written over the restored book.
 */
object Ledger {
    enum class Phase { LOADING, LOCKED, READY }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase: StateFlow<Phase> = _phase
    /** Bumped after every change: screens read it so they recompose with the new figures. */
    private val _rev = MutableStateFlow(0L)
    val rev: StateFlow<Long> = _rev

    // Every read of DB also reads this, so a composable that shows ledger data redraws when the ledger changes, even when
    // its arguments have not and it never called rememberLedger() itself (a fold's open/shut state, say).
    private val observedRev = androidx.compose.runtime.mutableLongStateOf(0L)
    init { onDbRead = { observedRev.longValue } }

    /** The sealed envelope waiting for the lock (Phase.LOCKED). */
    var sealed: Obj? = null
        private set

    private lateinit var file: AtomicFile
    private val scope = MainScope()
    private var saveJob: Job? = null
    /** Called on the main thread after each successful write. */
    var onSaved: (() -> Unit)? = null
    /** Called once the ledger is in memory and started. */
    var onStarted: (() -> Unit)? = null

    fun init(ctx: Context) {
        file = AtomicFile(File(ctx.filesDir, "ledger.json"))
        UiHooks.render = { bump() }
        UiHooks.touch = { changed() }
        UiHooks.save = { scheduleSave() }
    }

    fun exists(): Boolean = file.baseFile.exists()

    /** The book file itself (a backup copies it as stored, a restore replaces it). */
    val bookFile: File get() = file.baseFile

    private val disk = Any()
    @Volatile private var generation = 0
    @Volatile private var frozen = false

    /** The stored book's bytes, read while no save is writing (a backup). Null when there is none. */
    fun readStoredBytes(): ByteArray? = synchronized(disk) { BackupCore.storedBytes(file.baseFile) }

    /** Stops saving until the next [start] (or [thaw]); a save already taken but not yet written is dropped. */
    fun freeze() {
        frozen = true
        saveJob?.cancel()
        synchronized(disk) { generation++ }
    }

    /** Saving again after a [freeze] that ended without a new book (a restore that failed). */
    fun thaw() { frozen = false; scheduleSave() }

    /** Runs [block] (a restore replacing the file) while no save can write. */
    fun <T> withDisk(block: () -> T): T = synchronized(disk) { generation++; block() }

    fun readRaw(): Obj? = runCatching { JsonCodec.parseObj(String(file.readFully(), Charsets.UTF_8)) }.getOrNull()

    /** Reads the stored ledger: starts it, or waits for the lock. */
    suspend fun load() {
        val raw = withContext(Dispatchers.IO) { if (exists()) readRaw() else null }
        if (raw != null && raw.b("enc")) { sealed = raw; _phase.value = Phase.LOCKED }
        else start(raw)
    }

    /** Opens a sealed ledger with the lock. False on a wrong PIN/pattern/password. */
    suspend fun unlock(secret: String): Boolean {
        val env = sealed ?: return false
        val data = withContext(Dispatchers.Default) { runCatching { Crypto.unseal(env, secret) }.getOrNull() } ?: return false
        start(data.asObj()); return true
    }

    suspend fun unlockWithKey(keyBytes: ByteArray): Boolean {
        val env = sealed ?: return false
        val data = withContext(Dispatchers.Default) { runCatching { Crypto.unsealWithKey(env, keyBytes) }.getOrNull() } ?: return false
        start(data.asObj()); return true
    }

    /** Checks a secret against the stored envelope without changing anything (Settings: confirm it's you). */
    suspend fun verify(secret: String): Boolean {
        val raw = withContext(Dispatchers.IO) { readRaw() } ?: return false
        if (!raw.b("enc")) return false
        val keep = arrayOf<Any?>(Crypto.key, Crypto.salt, Crypto.type, Crypto.pinLen)
        return withContext(Dispatchers.Default) { runCatching { Crypto.unseal(raw, secret); true }.getOrDefault(false) }.also {
            Crypto.key = keep[0] as ByteArray?; Crypto.salt = keep[1] as ByteArray?; Crypto.type = keep[2] as String; Crypto.pinLen = keep[3] as Int
        }
    }

    /** Puts a book in memory: merges what was saved over a fresh one, fills any missing shape, and goes READY. */
    fun start(saved: Obj?) {
        frozen = false
        DB = freshDb()
        resetModelState()
        if (saved != null) DB.putAll(saved)
        ensureBookShape(DB)
        sealed = null
        _phase.value = Phase.READY
        bump()
        scheduleSave()
        onStarted?.invoke()
    }

    /** Puts a whole book in place (a restore), as start() would load it. */
    fun replace(data: Obj) { start(data); persistBlocking() }

    // ---------- changes and saving ----------

    fun bump() { _rev.value = _rev.value + 1; observedRev.longValue = _rev.value }

    /** After any change to DB: redraw and save soon. */
    fun changed() { bump(); scheduleSave() }

    /** Runs a change to DB and then [changed]. */
    inline fun edit(block: () -> Unit) { block(); changed() }

    fun scheduleSave() {
        if (_phase.value != Phase.READY || frozen) return
        saveJob?.cancel()
        saveJob = scope.launch { delay(400); persist() }
    }

    private fun snapshotText(): String {
        return JsonCodec.stringify(DB)
    }

    private fun writeText(text: String, gen: Int): Boolean = runCatching {
        val out = if (Crypto.locked) JsonCodec.stringify(Crypto.sealText(text)) else text
        synchronized(disk) {
            if (gen != generation) error("stale")      // the book was replaced (a restore) after this text was taken
            val s = file.startWrite()
            try { s.write(out.toByteArray(Charsets.UTF_8)); file.finishWrite(s) } catch (e: Throwable) { file.failWrite(s); throw e }
        }
    }.isSuccess

    suspend fun persist(): Boolean {
        if (_phase.value != Phase.READY || frozen) return false
        val gen = generation
        val text = snapshotText()
        val ok = withContext(Dispatchers.IO) { writeText(text, gen) }
        if (ok) onSaved?.invoke()
        return ok
    }

    /** Writes now, on this thread (leaving the app, a restore). */
    fun persistBlocking(): Boolean {
        if (_phase.value != Phase.READY || frozen) return false
        saveJob?.cancel()
        val ok = writeText(snapshotText(), generation)
        if (ok) onSaved?.invoke()
        return ok
    }

    /** Leaves the ledger in memory but asks for the lock again (re-lock after time away). */
    fun relock() {
        if (!Crypto.locked || _phase.value != Phase.READY || frozen) return
        persistBlocking()
        sealed = readRaw()
        if (sealed?.b("enc") == true) {
            DB = freshDb()
            _phase.value = Phase.LOCKED
            bump()
        }
    }

    /** Erase everything. */
    fun erase() {
        saveJob?.cancel()
        file.delete()
        Crypto.clearLock()
        start(null)
    }
}

/** lockChanged(): a lock was set, changed or removed: the stored documents follow it now. */
fun lockChanged() {
    FileStore.filesAfterPersist()
}

/** Erase everything: the documents go with the book. */
suspend fun eraseStores() {
    FileStore.clearAllFileRecords()
}
