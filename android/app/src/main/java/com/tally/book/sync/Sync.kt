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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The app's sync: one SyncEngine wired to the Supabase relay from BuildConfig, the Ledger and the FileStore.
 * Screens call Sync.engine.createFamily / joinFamily / leaveFamily / sync (or Sync.syncInBackground()) and observe
 * Sync.engine.state.
 */
object Sync {
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val engine: SyncEngine by lazy {
        SyncEngine(HttpRelay(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), LedgerBook, StoreFiles)
    }

    private val scope = MainScope()

    /** Starts a sync that outlives the screen that asked for it (pull to refresh, app open). No-op without a family. */
    fun syncInBackground() {
        if (!hasFamily()) return
        scope.launch { engine.sync() }
    }

    fun hasFamily(db: Obj = DB): Boolean = db.o("family")?.s("id")?.isNotEmpty() == true

    /** The invite text for this phone's family, or null. Show it only on demand. */
    fun inviteText(db: Obj = DB): String? = engine.inviteText(db)

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
