package com.tally.book.sync

import com.tally.book.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Base64

/*
 * Family sync: create / join / leave, and one refresh (docs/DESIGN.md, "Sync" > "One refresh"):
 *   1. pull every row with seq > sync.cursor, page by page; each page is opened, merged and saved together with the new
 *      cursor, so a sync that stops halfway resumes from the last saved page;
 *   2. push every dirty record (private ones only as tombstones, and only if the relay holds them); a dirty mark is
 *      cleared only after the relay accepted the push, and only if the record did not change meanwhile;
 *   3. upload the sealed bytes of each file in sync.blobsUp; download the bytes of any live file that has none here.
 * Only one sync (or create/join/leave) runs at a time. Network work happens in the Relay (on IO), sealing on [cpu], and
 * every read or write of the book goes through [SyncBook] (the app's runs on the main thread inside Ledger.edit).
 */

/** Where the engine reads and writes the book. The app's is [LedgerBook]; tests use an in-memory one. */
interface SyncBook {
    suspend fun <T> read(block: (Obj) -> T): T
    /** Applies [block] and saves the book; throws [SyncBook.SaveFailed] when it cannot be saved. */
    suspend fun <T> edit(block: (Obj) -> T): T
    class SaveFailed : Exception("the book could not be saved")
}

/** This phone's copies of document bytes (data/Files.kt in the app). */
interface LocalFiles {
    suspend fun has(id: String): Boolean
    /** The original bytes, checked against the record's SHA-256, or null. */
    suspend fun read(id: String): ByteArray?
    /** Stores bytes that were already checked against the record's SHA-256. */
    suspend fun write(id: String, bytes: ByteArray, mime: String)
    suspend fun delete(id: String)
}

sealed class SyncResult {
    data class Done(val pulled: Int, val pushed: Int, val filesUp: Int, val filesDown: Int, val skipped: Int) : SyncResult()
    data class Failed(val error: SyncError) : SyncResult()
    /** No family on this phone: nothing to sync. */
    object NoFamily : SyncResult() { override fun toString() = "NoFamily" }
    /** Another sync, create, join or leave is running. */
    object Busy : SyncResult() { override fun toString() = "Busy" }
    /** Create or join while this phone is already in a (different) family: leave it first. */
    object AlreadyInFamily : SyncResult() { override fun toString() = "AlreadyInFamily" }
    /** The invite text (or family name) is malformed. */
    object BadInvite : SyncResult() { override fun toString() = "BadInvite" }
}

/** For the UI: whether a sync is running, how the last one ended, and when the book last synced (epoch ms, 0 = never). */
data class SyncState(val running: Boolean = false, val last: SyncResult? = null, val lastAt: Long = 0)

class SyncEngine(
    private val relay: Relay,
    private val book: SyncBook,
    private val files: LocalFiles,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val pageSize: Int = 500,
    private val pushChunk: Int = 200,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    private class Family(val id: String, val key: ByteArray, val name: String) {
        val encKey: ByteArray by lazy { SyncCrypto.encKey(key) }
        val token: String by lazy { SyncCrypto.authToken(key) }
    }

    private class Stop(val error: SyncError) : Exception()

    private fun <T> RelayResult<T>.orStop(): T = when (this) {
        is RelayResult.Ok -> value
        is RelayResult.Err -> throw Stop(error)
    }

    private fun familyOf(db: Obj): Family? {
        val f = db.o("family") ?: return null
        val id = f.s("id"); val k = f.s("key")
        if (!Invite.isFamilyId(id) || k.isEmpty()) return null
        val key = runCatching { Base64.getDecoder().decode(k) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        return Family(id, key, f.s("name"))
    }

    /** The invite for this phone's family (show only on demand), or null when there is no family. */
    fun inviteText(db: Obj): String? = familyOf(db)?.let { runCatching { Invite.make(it.id, it.key, it.name.ifEmpty { "Family" }) }.getOrNull() }

    // ---------- create / join / leave ----------

    /** Makes a new family with this phone as its first member, registers it with the relay, then syncs. */
    suspend fun createFamily(name: String): SyncResult = exclusive {
        val nm = Invite.cleanName(name) ?: return@exclusive SyncResult.BadInvite
        if (book.read { familyOf(it) != null }) return@exclusive SyncResult.AlreadyInFamily
        val fam = Family(Invite.newFamilyId(), Invite.newKey(), nm)
        val tokenHash = withContext(cpu) { SyncCrypto.tokenHash(fam.token) }
        relay.createFamily(fam.id, tokenHash).orStop()
        book.edit { db -> enter(db, fam) }
        runSync()
    }

    /** Joins the family in [inviteText]: checks the invite against the relay, adds this phone as a member, pulls everything. */
    suspend fun joinFamily(inviteText: String): SyncResult {
        val inv = Invite.parse(inviteText) ?: return SyncResult.BadInvite
        return joinFamily(inv)
    }

    suspend fun joinFamily(invite: Invite): SyncResult = exclusive {
        val cur = book.read { familyOf(it) }
        if (cur != null) {
            if (cur.id == invite.familyId && cur.key.contentEquals(invite.key)) return@exclusive runSync()
            return@exclusive SyncResult.AlreadyInFamily
        }
        val fam = Family(invite.familyId, invite.key, invite.name)
        relay.pull(fam.id, fam.token, 0, 1).orStop()      // a wrong or stale invite stops here, before the book changes
        book.edit { db -> enter(db, fam) }
        runSync()
    }

    private fun enter(db: Obj, fam: Family) {
        db["family"] = obj("id" to fam.id, "key" to Base64.getEncoder().encodeToString(fam.key), "name" to fam.name)
        val old = db.o("sync")
        db["sync"] = obj(
            "cursor" to 0, "lastAt" to 0,
            "dirty" to (old.o("dirty")?.copyObj() ?: obj()),
            "blobsUp" to (old.o("blobsUp")?.copyObj() ?: obj()),
            "pushed" to obj(),
        )
        SyncModel.upsertMe(db, Clock.millis())
        SyncModel.markAllDirty(db)
    }

    /** Leaves the family on this phone: forgets the key and the sync state, keeps every record. */
    suspend fun leaveFamily(): Boolean {
        lock.lock()
        try {
            book.edit { db -> db.remove("family"); db.remove("sync") }
            _state.value = SyncState()
            return true
        } catch (e: Exception) {
            return false
        } finally { lock.unlock() }
    }

    // ---------- one refresh ----------

    /** One refresh. Returns [SyncResult.Busy] at once if another is running. */
    suspend fun sync(): SyncResult = exclusive { runSync() }

    /** Downloads one file's bytes now (opening a document whose bytes have not arrived yet). True when they are here. */
    suspend fun fetchFile(id: String): Boolean {
        if (files.has(id)) return true
        val (fam, want) = book.read { db -> familyOf(db) to SyncModel.liveFiles(db).firstOrNull { it.id == id } }
        if (fam == null || want == null) return false
        return runCatching { download(fam, want) == 1 }.getOrDefault(false)
    }

    private suspend fun exclusive(block: suspend () -> SyncResult): SyncResult {
        if (!lock.tryLock()) return SyncResult.Busy
        _state.value = _state.value.copy(running = true)
        val result = try {
            block()
        } catch (e: Stop) {
            SyncResult.Failed(e.error)
        } catch (e: SyncBook.SaveFailed) {
            SyncResult.Failed(SyncError.Storage)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the caller went away mid-sync: do not leave the UI showing "Syncing…" for ever
            _state.value = _state.value.copy(running = false)
            throw e
        } finally {
            lock.unlock()
        }
        val lastAt = runCatching { book.read { it.o("sync").num("lastAt").toLong() } }.getOrDefault(0L)
        _state.value = SyncState(running = false, last = result, lastAt = lastAt)
        return result
    }

    private suspend fun runSync(): SyncResult {
        val fam = book.read { familyOf(it) } ?: return SyncResult.NoFamily
        withContext(cpu) { fam.encKey; fam.token }

        // 1. pull
        var pulled = 0; var skipped = 0
        var cursor = book.read { SyncModel.cursor(it) }
        while (true) {
            val rows = relay.pull(fam.id, fam.token, cursor, pageSize).orStop()
            if (rows.isEmpty()) break
            val opened = withContext(cpu) { rows.map { open(fam, it) } }
            val next = maxOf(cursor, rows.maxOf { it.seq })
            val outcome = book.edit { db ->
                checkFamily(db, fam)
                val o = SyncModel.mergeRemote(db, opened.filterNotNull())
                SyncModel.syncOf(db)["cursor"] = next.toDouble()
                o
            }
            outcome.filesRemoved.forEach { runCatching { files.delete(it) } }
            cursor = next; pulled += rows.size; skipped += opened.count { it == null }
            if (rows.size < pageSize) break
        }

        // 2. push
        var pushed = 0
        val items = book.read { SyncModel.collectPush(it) }
        val nothing = items.filter { !it.send }
        if (nothing.isNotEmpty()) book.edit { db -> checkFamily(db, fam); SyncModel.afterPush(db, nothing) }
        val sealed = withContext(cpu) {
            items.filter { it.send }.map { it to SyncCrypto.sealRecord(fam.encKey, fam.id, it.id, it.json!!) }
        }
        val fits = sealed.filter { it.second.length <= MAX_RECORD_BLOB }
        skipped += sealed.size - fits.size    // too large to send; stays dirty
        for (chunk in fits.chunked(pushChunk)) {
            relay.push(fam.id, fam.token, chunk.map { (item, blob) -> RelayRow(item.id, item.kind, blob) }).orStop()
            book.edit { db -> checkFamily(db, fam); SyncModel.afterPush(db, chunk.map { it.first }) }
            pushed += chunk.size
        }

        // 3. files up
        var filesUp = 0
        val plan = book.read { SyncModel.planBlobs(it) }
        if (plan.drop.isNotEmpty()) book.edit { db -> plan.drop.forEach { SyncModel.blobsUp(db).remove(it) } }
        for (id in plan.upload) {
            val bytes = files.read(id) ?: continue
            val data = withContext(cpu) { SyncCrypto.sealFile(fam.encKey, fam.id, id, bytes) }
            if (data.length > MAX_FILE_BLOB) { skipped++; continue }
            relay.blobPut(fam.id, fam.token, id, data).orStop()
            book.edit { db -> checkFamily(db, fam); SyncModel.blobsUp(db).remove(id) }
            filesUp++
        }

        // 3b. files down
        var filesDown = 0
        val wants = book.read { SyncModel.liveFiles(it) }
        for (w in wants) {
            if (files.has(w.id)) continue
            when (download(fam, w)) { 1 -> filesDown++; -1 -> skipped++ }
        }

        book.edit { db -> checkFamily(db, fam); SyncModel.syncOf(db)["lastAt"] = Clock.millis().toDouble() }
        return SyncResult.Done(pulled, pushed, filesUp, filesDown, skipped)
    }

    /** 1 = stored, 0 = not on the relay yet, -1 = there but unusable (does not open, or the hash does not match). */
    private suspend fun download(fam: Family, w: SyncModel.FileWant): Int {
        val data = relay.blobGet(fam.id, fam.token, w.id).orStop() ?: return 0
        val bytes = withContext(cpu) {
            SyncCrypto.openFile(fam.encKey, fam.id, w.id, data)?.takeIf { SyncCrypto.sha256Hex(it) == w.sha256 }
        } ?: return -1
        files.write(w.id, bytes, w.mime)
        return 1
    }

    private fun checkFamily(db: Obj, fam: Family) {
        if (familyOf(db)?.id != fam.id) throw Stop(SyncError.BadData("the family changed during sync"))
    }

    /** A pulled row opened and checked, or null: it must open under this family and id, and say the same kind and id inside. */
    private fun open(fam: Family, row: RelayRow): SyncModel.WireRecord? {
        val text = SyncCrypto.openRecord(fam.encKey, fam.id, row.id, row.blob) ?: return null
        val env = runCatching { JsonCodec.parseObj(text) }.getOrNull() ?: return null
        val k = env["k"] as? String ?: return null
        val r = env.o("r") ?: return null
        if (k != row.kind || k !in SyncModel.LISTS || r["id"] != row.id) return null
        return SyncModel.WireRecord(k, r)
    }

    companion object {
        /** The relay's limits (the tally_relay migration in supabase/migrations). */
        const val MAX_RECORD_BLOB = 65_536
        const val MAX_FILE_BLOB = 6 * 1024 * 1024
    }
}
