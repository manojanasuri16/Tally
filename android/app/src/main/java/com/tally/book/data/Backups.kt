package com.tally.book.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.mutableStateOf
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tally.book.BuildConfig
import com.tally.book.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/*
 * Backups, the Android part (docs/DESIGN.md, "Backups"): the folder picked once (a persisted document-tree permission; no
 * storage permission), the daily WorkManager job and the catch-up on app open, the file handed to the share sheet, and
 * putting a backup in place under the app's locks. The format and every check are in BackupCore (plain JVM).
 *
 * A backup copies the stored book and files as they are on disk, so it needs no secret and runs while the app is locked or
 * not running at all. It is taken under FileStore.filesExclusive (no bulk rewrite of the files meanwhile), reading each record
 * under FileStore.io and the book under the Ledger's disk lock (never a half-written file).
 *
 * Device-only (DevicePrefs, never in a backup): whether daily backup is on, the folder, when the last one worked, and
 * whether it stopped. Nothing here is logged.
 */
object Backups {
    private const val ON = "tally-backup-on"
    private const val TREE = "tally-backup-tree"
    private const val LAST = "tally-backup-last"
    private const val DAY = "tally-backup-day"
    private const val STOPPED = "tally-backup-stopped"
    private const val FAILED = "tally-backup-failed"
    private const val DAILY_WORK = "tally-daily-backup"
    private const val CATCH_UP_WORK = "tally-backup-catch-up"
    private const val MIME = "application/octet-stream"

    /** What the Backup group and the home screen show. */
    data class Status(val on: Boolean = false, val lastAt: Long = 0, val stopped: Boolean = false, val failed: Boolean = false, val canUndo: Boolean = false)

    val status = mutableStateOf(Status())

    private lateinit var app: Context
    private val scope = MainScope()
    /** One backup at a time (the daily job, the catch-up and Back up now). */
    private val running = Mutex()

    /** Called first thing at start, before the book or the files are read: finishes or tidies a restore that was cut short. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
        BackupReminder.noteFirstUse()
        runCatching { BackupCore.recover(place()) }
        runCatching { File(app.cacheDir, "backup").deleteRecursively(); File(app.cacheDir, "restore-in").deleteRecursively() }
        refresh()
    }

    fun place(): BackupPlace = BackupPlace(File(app.filesDir, "ledger.json"), File(app.filesDir, "files"), File(app.filesDir, "restore"))

    fun isOn(): Boolean = DevicePrefs.get(ON) == "1"
    private fun tree(): Uri? = DevicePrefs.get(TREE)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun refresh() {
        val s = Status(
            on = isOn(), lastAt = DevicePrefs.get(LAST)?.toLongOrNull() ?: 0L,
            stopped = isOn() && DevicePrefs.get(STOPPED) == "1", failed = isOn() && DevicePrefs.get(FAILED) == "1",
            canUndo = runCatching { place().safety.isFile }.getOrDefault(false),
        )
        scope.launch { status.value = s }
    }

    // ---------- making one ----------

    /** Writes a backup of what is stored now to [out] (not closed). The book is saved first if it is open. Returns the manifest. */
    suspend fun writeTo(out: OutputStream): Obj {
        withContext(Dispatchers.Main) { if (Ledger.phase.value == Ledger.Phase.READY) Ledger.persist() }
        return FileStore.filesExclusive {
            withContext(Dispatchers.IO) {
                val book = Ledger.readStoredBytes() ?: throw BackupError("failed")
                val dir = FileStore.directory ?: throw BackupError("failed")
                BackupCore.write(out, book, dir, FileStore.io, BuildConfig.VERSION_NAME, Clock.millis(), Clock.today())
            }
        }
    }

    /** A backup file in the app's cache, for the share sheet (the cache folder is emptied at the next start). */
    suspend fun shareFile(): File {
        val dir = withContext(Dispatchers.IO) { File(app.cacheDir, "backup").apply { deleteRecursively(); mkdirs() } }
        val f = File(dir, BackupFormat.fileName(Clock.today()))
        withContext(Dispatchers.IO) { f.outputStream().buffered(64 * 1024).use { o -> writeTo(o); o.flush() } }
        return f
    }

    // ---------- the folder ----------

    /** The folder the person picked (ACTION_OPEN_DOCUMENT_TREE): keep the permission, turn daily backup on, and make the first one. */
    suspend fun chooseFolder(uri: Uri): Boolean {
        val cr = app.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { cr.takePersistableUriPermission(uri, flags) }.isFailure) return false
        tree()?.takeIf { it != uri }?.let { old -> runCatching { cr.releasePersistableUriPermission(old, flags) } }
        DevicePrefs.set(TREE, uri.toString()); DevicePrefs.set(ON, "1")
        DevicePrefs.remove(STOPPED); DevicePrefs.remove(FAILED)
        schedule()
        refresh()
        return saveToFolder() == Outcome.OK
    }

    /** Daily backup off: the job stops and the folder permission is given back. Files already in the folder stay. */
    fun turnOff() {
        DevicePrefs.remove(ON); DevicePrefs.remove(STOPPED); DevicePrefs.remove(FAILED)
        tree()?.let { runCatching { app.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        DevicePrefs.remove(TREE)
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(DAILY_WORK); WorkManager.getInstance(app).cancelUniqueWork(CATCH_UP_WORK) }
        refresh()
    }

    enum class Outcome { OK, SKIPPED, STOPPED, FAILED }

    private fun permitted(tree: Uri): Boolean =
        app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }

    private fun folderDoc(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** The folder's files (name, uri). Throws when the folder is gone or no longer allowed. */
    private fun children(tree: Uri): List<Pair<String, Uri>> {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val out = ArrayList<Pair<String, Uri>>()
        app.contentResolver.query(q, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out.add((c.getString(1) ?: "") to DocumentsContract.buildDocumentUriUsingTree(tree, id))
            }
        } ?: throw FileNotFoundException("folder")
        return out
    }

    private fun delete(uri: Uri) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) } }

    /**
     * Writes today's backup into the folder (replacing one made earlier today) and keeps the newest [BackupFormat.KEEP] of
     * Tally's own files there. The file is written under a ".partial" name and renamed when complete, so the folder never
     * holds a half backup under a real name.
     */
    suspend fun saveToFolder(): Outcome = running.withLock {
        val tree = tree() ?: return@withLock stopped()
        withContext(Dispatchers.IO) {
            if (!permitted(tree)) return@withContext stopped()
            val cr = app.contentResolver
            val kids = try { children(tree) } catch (e: Exception) { return@withContext stopped() }
            val day = Clock.today()
            val name = BackupFormat.fileName(day)
            kids.filter { BackupFormat.isPartial(it.first) }.forEach { delete(it.second) }
            val made: Uri? = try {
                DocumentsContract.createDocument(cr, folderDoc(tree), MIME, BackupFormat.partialName(day))
            } catch (e: SecurityException) { return@withContext stopped() } catch (e: FileNotFoundException) { return@withContext stopped() } catch (e: Exception) { null }
            val tmp = made ?: return@withContext failed()
            try {
                (cr.openOutputStream(tmp, "w") ?: throw FileNotFoundException("out")).use { o -> val b = o.buffered(64 * 1024); writeTo(b); b.flush() }
            } catch (e: Exception) {
                delete(tmp)
                return@withContext if (e is SecurityException) stopped() else failed()
            }
            val replaced = BackupFormat.replacedBy(kids.map { it.first }, day).toSet()
            kids.filter { it.first in replaced }.forEach { delete(it.second) }
            val renamed = runCatching { DocumentsContract.renameDocument(cr, tmp, name) }.getOrNull()
            if (renamed == null) {
                // a folder that cannot rename: write the real name directly (today's earlier file is already gone)
                val direct = runCatching { DocumentsContract.createDocument(cr, folderDoc(tree), MIME, name) }.getOrNull()
                val ok = direct != null && runCatching { cr.openOutputStream(direct, "w")!!.use { o -> val b = o.buffered(64 * 1024); writeTo(b); b.flush() } }.isSuccess
                delete(tmp)
                if (!ok) { direct?.let { delete(it) }; return@withContext failed() }
            }
            runCatching {
                val now = children(tree)
                val drop = BackupFormat.toPrune(now.map { it.first }).toSet()
                now.filter { it.first in drop }.forEach { delete(it.second) }
            }
            DevicePrefs.set(LAST, Clock.millis().toString()); DevicePrefs.set(DAY, day.toString())
            DevicePrefs.remove(STOPPED); DevicePrefs.remove(FAILED)
            refresh()
            Outcome.OK
        }
    }

    private fun stopped(): Outcome { DevicePrefs.set(STOPPED, "1"); refresh(); return Outcome.STOPPED }
    private fun failed(): Outcome { DevicePrefs.set(FAILED, "1"); refresh(); return Outcome.FAILED }

    /** The daily job: today's backup into the folder, unless today's is already there. */
    suspend fun daily(): Outcome {
        if (!isOn()) return Outcome.SKIPPED
        if (!BackupFormat.dueToday(DevicePrefs.get(DAY), Clock.today())) return Outcome.SKIPPED
        return saveToFolder()
    }

    // ---------- the schedule ----------

    private fun schedule() {
        runCatching {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }

    /**
     * On app open: keeps the daily job in place and, when the last backup is more than a day old (battery savers often hold the
     * job back), runs one now. Notices a folder that went away or is no longer allowed.
     */
    fun catchUp() {
        if (!isOn()) return
        schedule()
        scope.launch(Dispatchers.IO) {
            val tree = tree()
            if (tree == null || !permitted(tree) || runCatching { children(tree) }.isFailure) { stopped(); return@launch }
            val last = DevicePrefs.get(LAST)?.toLongOrNull() ?: 0L
            if (Clock.millis() - last > TimeUnit.HOURS.toMillis(24)) runCatching {
                WorkManager.getInstance(app).enqueueUniqueWork(CATCH_UP_WORK, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build())
            }
        }
    }

    // ---------- restoring ----------

    /** Copies a picked file into the app's cache (refusing anything bigger than a backup can be) so it can be checked and read. */
    suspend fun receive(uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "restore-in").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, "incoming" + BackupFormat.EXT)
        try {
            (app.contentResolver.openInputStream(uri) ?: throw BackupError("notBackup")).use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        total += r; if (total > BackupFormat.MAX_TOTAL) throw BackupError("tooBig")
                        out.write(buf, 0, r)
                    }
                }
            }
        } catch (e: BackupError) { f.delete(); throw e } catch (e: Exception) { f.delete(); throw BackupError("notBackup") }
        f
    }

    suspend fun check(file: File): BackupCore.Checked = withContext(Dispatchers.IO) { BackupCore.check(file) }

    /**
     * Puts a checked backup in place with [book] as the book's bytes, then runs [then] (on the main thread, still inside the
     * files lane, so no lock pass sees the new files with the old book): the caller sets the lock and starts the new book there.
     * Saving is frozen from before the book is replaced until [then] starts the new one; a failure leaves everything as it was
     * and saving resumes. [keepSafety]: keep the current book and files first as the one "before restore" copy.
     */
    suspend fun install(file: File, checked: BackupCore.Checked, book: ByteArray, keepSafety: Boolean, then: () -> Unit) {
        withContext(Dispatchers.Main) { if (Ledger.phase.value == Ledger.Phase.READY) Ledger.persist() }
        FileStore.filesExclusive {
            withContext(Dispatchers.Main) {
                Ledger.freeze()
                try {
                    withContext(Dispatchers.IO) {
                        Ledger.withDisk {
                            BackupCore.install(file, checked, book, place(), keepSafety, BuildConfig.VERSION_NAME, Clock.millis(), Clock.today(), FileStore.io)
                        }
                    }
                } catch (e: Throwable) { Ledger.thaw(); throw e }
                FileStore.afterRestore()
                then()
            }
        }
        refresh()
    }

    /** Forgets the "before restore" copy (after it was put back). */
    fun dropSafety() { runCatching { place().safety.delete() }; refresh() }

    // ---------- sealing older copies again after a lock change (L3, data/BackupReseal.kt) ----------

    /** The backup made for the share sheet (cache/backup): gone after a lock change; the next Back up now makes a sealed one. */
    fun clearShareCache() { runCatching { File(app.cacheDir, "backup").deleteRecursively() } }

    private fun scratch(): File = File(app.cacheDir, "reseal").apply { deleteRecursively(); mkdirs() }

    /** Copies a folder file into [dest] (at most a backup's size); false when it cannot be read. */
    private fun copyIn(uri: Uri, dest: File): Boolean = runCatching {
        (app.contentResolver.openInputStream(uri) ?: return false).use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    total += r; if (total > BackupFormat.MAX_TOTAL) return false
                    out.write(buf, 0, r)
                }
            }
        }
        true
    }.getOrDefault(false)

    /**
     * Tally's backups in the chosen folder that [pick] would seal again (it gives the plain book text, or null to leave one
     * alone), as (name, uri, was plain). Empty when there is no folder or it cannot be read.
     */
    suspend fun folderToReseal(pick: (BackupCore.Checked) -> String?): List<Triple<String, Uri, Boolean>> = running.withLock {
        withContext(Dispatchers.IO) {
            val tree = tree() ?: return@withContext emptyList()
            if (!isOn() || !permitted(tree)) return@withContext emptyList()
            val kids = runCatching { children(tree) }.getOrNull() ?: return@withContext emptyList()
            val dir = scratch()
            val out = ArrayList<Triple<String, Uri, Boolean>>()
            for ((name, uri) in kids) {
                if (BackupFormat.dayOf(name) == null) continue
                val f = File(dir, "in" + BackupFormat.EXT)
                if (!copyIn(uri, f)) continue
                val c = runCatching { BackupCore.check(f) }.getOrNull() ?: continue
                if (pick(c) != null) out.add(Triple(name, uri, !c.sealed))
                f.delete()
            }
            dir.deleteRecursively()
            out
        }
    }

    /** Rewrites those folder backups sealed ([sealBook] seals a book's text); each is replaced only by a copy that checks out. Returns how many. */
    suspend fun resealFolder(list: List<Triple<String, Uri, Boolean>>, pick: (BackupCore.Checked) -> String?, sealBook: (String) -> ByteArray): Int = running.withLock {
        withContext(Dispatchers.IO) {
            val tree = tree() ?: return@withContext 0
            if (!permitted(tree)) return@withContext 0
            val cr = app.contentResolver
            val dir = scratch()
            var done = 0
            for ((name, uri, _) in list) {
                val src = File(dir, "in" + BackupFormat.EXT); val made = File(dir, "out" + BackupFormat.EXT)
                try {
                    if (!copyIn(uri, src)) continue
                    val c = BackupCore.check(src)
                    val text = pick(c) ?: continue
                    made.outputStream().buffered(64 * 1024).use { o -> BackupCore.reseal(src, c, text, sealBook, o); o.flush() }
                    if (!BackupCore.check(made).sealed) continue
                    // written under a ".partial" name first, then the plain one is deleted and the new one takes its name
                    val tmp = DocumentsContract.createDocument(cr, folderDoc(tree), MIME, "$name.partial") ?: continue
                    val ok = runCatching { (cr.openOutputStream(tmp, "w") ?: error("out")).use { o -> made.inputStream().use { it.copyTo(o, 64 * 1024) } } }.isSuccess
                    if (!ok) { delete(tmp); continue }
                    delete(uri)
                    if (runCatching { DocumentsContract.renameDocument(cr, tmp, name) }.getOrNull() == null) {
                        val direct = runCatching { DocumentsContract.createDocument(cr, folderDoc(tree), MIME, name) }.getOrNull()
                        val wrote = direct != null && runCatching { cr.openOutputStream(direct, "w")!!.use { o -> made.inputStream().use { it.copyTo(o, 64 * 1024) } } }.isSuccess
                        if (wrote) delete(tmp) else direct?.let { delete(it) }
                    }
                    done++
                } catch (e: Exception) { /* left as it was */ } finally { src.delete(); made.delete() }
            }
            dir.deleteRecursively()
            done
        }
    }

    /** The restore safety copy sealed again, silently (it never leaves the phone, but it holds the whole book). True when rewritten. */
    suspend fun resealSafety(pick: (BackupCore.Checked) -> String?, sealBook: (String) -> ByteArray): Boolean = running.withLock {
        withContext(Dispatchers.IO) {
            val safety = place().safety
            if (!safety.isFile) return@withContext false
            val made = File(safety.path + ".reseal")
            runCatching {
                val c = BackupCore.check(safety)
                val text = pick(c) ?: return@runCatching false
                made.outputStream().buffered(64 * 1024).use { o -> BackupCore.reseal(safety, c, text, sealBook, o); o.flush() }
                BackupCore.check(made).sealed && made.renameTo(safety)
            }.getOrDefault(false).also { made.delete() }
        }
    }
}

/** The daily backup job (and the catch-up). It never retries on its own: the next day, or the next app open, tries again. */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Backups.daily() }
        return Result.success()
    }
}
