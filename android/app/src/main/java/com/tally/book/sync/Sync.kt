package com.tally.book.sync

import com.tally.book.BuildConfig
import com.tally.book.core.*
import com.tally.book.data.Crypto
import com.tally.book.data.FileStore
import com.tally.book.data.Ledger
import com.tally.book.model.DB
import com.tally.book.model.fileById
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The app's sync: one SyncEngine wired to the Supabase relay from BuildConfig, the Ledger, the FileStore and the background
 * check (Notices). Screens call Sync.engine.* (createFamily, requestJoin, newInvite, approve, deny, setAdmin, removeMember,
 * leaveFamily, sync...) through Sync.detached, observe Sync.engine.state and Sync.engine.requests, and read Sync.membership().
 */
object Sync {
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val relay: Relay by lazy { HttpRelay(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }

    val engine: SyncEngine by lazy { SyncEngine(relay, LedgerBook, StoreFiles, notices = Notices.store) }

    private val scope = MainScope()

    /** Starts a sync that outlives the screen that asked for it (pull to refresh, app open). No-op without a family or a waiting join. */
    fun syncInBackground() {
        if (!hasFamily() && !isJoining()) return
        scope.launch { engine.sync() }
    }

    /**
     * Runs [block] (a create, join, leave or sync on [engine]) in the app's own scope and waits for it, so a screen that is closed
     * meanwhile (a pull let go, the back button) does not cancel it halfway.
     */
    suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    /** A family is in the book (a version-2 one, or an old one that needs setting up again: see [membership]). */
    fun hasFamily(db: Obj = DB): Boolean = Membership.hasFamily(db)

    /** This phone asked to join and is waiting for an admin. */
    fun isJoining(db: Obj = DB): Boolean = db.o("joining") != null

    /** Members, roles, invites, a waiting join, why the family went. Join requests: [SyncEngine.requests]. */
    fun membership(db: Obj = DB): MembershipView = engine.membership(db)

    /** When this book last synced (epoch ms), 0 = never. */
    fun lastSyncedAt(db: Obj = DB): Long = db.o("sync").num("lastAt").toLong()
}

/** The Ledger as a SyncBook: DB is read and written on the main thread, every change inside Ledger.edit and saved at once. */
object LedgerBook : SyncBook {
    override suspend fun <T> read(block: (Obj) -> T): T = withContext(Dispatchers.Main.immediate) {
        if (Ledger.phase.value != Ledger.Phase.READY) throw SyncBook.SaveFailed()
        block(DB)
    }

    override suspend fun <T> edit(block: (Obj) -> T): T = withContext(Dispatchers.Main.immediate) {
        if (Ledger.phase.value != Ledger.Phase.READY) throw SyncBook.SaveFailed()
        var out: T? = null
        Ledger.edit { out = block(DB) }
        if (!Ledger.persist()) throw SyncBook.SaveFailed()
        @Suppress("UNCHECKED_CAST")
        out as T
    }
}

/** FileStore as LocalFiles: received bytes are stored like any document (sealed while a lock is set). */
object StoreFiles : LocalFiles {
    override suspend fun has(id: String): Boolean = FileStore.fileRecGet(id) != null

    override suspend fun read(id: String): ByteArray? = withContext(Dispatchers.Main.immediate) {
        runCatching { FileStore.filesExclusive { FileStore.readFileBytes(id) } }.getOrNull()
    }

    override suspend fun write(id: String, bytes: ByteArray, mime: String) = withContext(Dispatchers.Main.immediate) {
        FileStore.filesExclusive {
            FileStore.fileRecPut(if (Crypto.locked) FileStore.fileSealRec(id, bytes) else FileStore.filePlainRec(id, bytes, mime))
        }
        FileStore.fileStates[id] = "ok"
        fileById(id)?.let { meta -> Ledger.edit { meta["enc"] = Crypto.locked } }
        Unit
    }

    override suspend fun delete(id: String) {
        withContext(Dispatchers.Main.immediate) { FileStore.fileStates.remove(id) }
        FileStore.fileRecDel(id)
    }
}
