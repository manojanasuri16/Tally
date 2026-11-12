package com.tally.book.data

import com.tally.book.core.*
import com.tally.book.model.DB
import com.tally.book.model.UiHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * L3 (docs/DESIGN.md, "Backups", "After a lock change"): when a lock is set, changed, or a book is moved to the new seal, the
 * copies of the book that are plain, or sealed by an earlier lock of this book, are sealed again under the current lock:
 * - the restore safety copy (filesDir/restore), silently;
 * - Tally's backups in the daily folder, after one question per lock ("Older backups in your folder are not locked. Lock
 *   them now?"); a "no" is remembered for that lock (they age out of the folder after 14 days);
 * - the share-sheet copy in the cache is deleted.
 * A backup sealed by a lock this phone does not know (another phone, a lock long gone) is left alone. Each rewritten file keeps
 * its date and its name, and replaces the old one only after it checks out.
 */
object BackupReseal {
    private const val ASKED = "tally-reseal-asked"

    suspend fun afterLockChange() {
        if (!Crypto.locked || Ledger.phase.value != Ledger.Phase.READY) return
        val id = Crypto.salt?.let { Crypto.b64(it) } ?: return
        // what every resealed book carries: this lock's kind and recovery code (so restoring it later asks for the right code)
        val code = DB.s("recoveryCode")
        val kind = Crypto.type
        val pick: (BackupCore.Checked) -> String? = { c -> BackupCore.resealText(c, id, Crypto::openKnown) }
        val seal: (String) -> ByteArray = { text ->
            val b = JsonCodec.parseObj(text)
            b["lockType"] = kind
            if (RecoveryCode.valid(code)) b["recoveryCode"] = code
            JsonCodec.stringify(Crypto.sealText(JsonCodec.stringify(b))).toByteArray(Charsets.UTF_8)
        }
        Backups.clearShareCache()
        runCatching { Backups.resealSafety(pick, seal) }
        Backups.refresh()
        if (DevicePrefs.get(ASKED) == id) return
        val list = runCatching { Backups.folderToReseal(pick) }.getOrDefault(emptyList())
        if (list.isEmpty()) return
        DevicePrefs.set(ASKED, id)
        val q = if (list.any { it.third }) t("backup.reseal.ask", "Older backups in your folder are not locked. Lock them now?")
        else t("backup.reseal.askOld", "Older backups in your folder still use your old lock. Lock them with the new one now?")
        if (!UiHooks.ask(q, t("backup.reseal.ok", "Lock them"), false)) return
        UiHooks.hint(t("backup.reseal.busy", "Locking older backups…"))
        val done = withContext(Dispatchers.IO) { runCatching { Backups.resealFolder(list, pick, seal) }.getOrDefault(0) }
        UiHooks.hint(
            if (done == list.size) t("backup.reseal.done", "Older backups are locked")
            else t("backup.reseal.some", "Some older backups could not be locked. They are removed from the folder after 14 days.")
        )
    }
}
