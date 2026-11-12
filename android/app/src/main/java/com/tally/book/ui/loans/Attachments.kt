package com.tally.book.ui.loans

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface  // framework class: no new dependency just to read the turn
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.FileStore
import com.tally.book.data.Ledger
import com.tally.book.model.*
import com.tally.book.sync.FileAsk
import com.tally.book.sync.Sync
import com.tally.book.sync.SyncModel
import com.tally.book.ui.components.Dialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/*
 * Photos and documents for payments and loans (docs/DESIGN.md "Files"). A photo is scaled once to at most 1600 px on the long edge
 * and stored as a JPEG; a document keeps its original bytes. Nothing larger than the store's cap (4 MB) is kept. The system picker
 * and the system camera are used, so no storage permission is needed.
 * The bytes live in FileStore; the record in DB.files is written through Records.kt (fileAdded / deleteFileRecord) so that sync sees it.
 */

/** A file chosen in a form and waiting for Save (a photo is already scaled). */
class Picked(val key: String, val name: String, val mime: String?, val bytes: ByteArray, val photo: Boolean)

/** The arithmetic of scaling a photo, apart from Bitmap so it can be tested. */
object PhotoMath {
    const val MAX_EDGE = 1600
    const val QUALITY = 85

    /** The size to store: the long edge at most [max], the shape kept, never enlarged, never below 1 px. */
    fun fit(w: Int, h: Int, max: Int = MAX_EDGE): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1 to 1
        val long = maxOf(w, h)
        if (long <= max) return w to h
        val f = max.toDouble() / long
        return maxOf(1, Math.round(w * f).toInt()) to maxOf(1, Math.round(h * f).toInt())
    }

    /** The decoder's power-of-two sample size that still leaves the long edge at [max] or more, so the last step only ever shrinks. */
    fun sampleSize(w: Int, h: Int, max: Int = MAX_EDGE): Int {
        var s = 1
        val long = maxOf(w, h)
        while (long / (s * 2) >= max) s *= 2
        return s
    }
}

object PhotoCodec {
    /** Scales [src] (any image the phone can decode) to at most 1600 px and returns JPEG bytes at quality 85, honouring the EXIF turn; null when it cannot be read. */
    fun scaleToJpeg(src: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(src, 0, src.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = PhotoMath.sampleSize(bounds.outWidth, bounds.outHeight) }
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size, opts) ?: return null
        val turn = runCatching {
            when (ExifInterface(src.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val (tw, th) = PhotoMath.fit(bmp.width, bmp.height)
        val m = Matrix().apply {
            if (tw != bmp.width) postScale(tw.toFloat() / bmp.width, th.toFloat() / bmp.height)
            if (turn != 0f) postRotate(turn)
        }
        val out = if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, PhotoMath.QUALITY, it) }.toByteArray()
        if (out !== bmp) out.recycle()
        bmp.recycle()
        return bytes
    }

    /** A small picture of a stored or waiting image for a list row, or null. */
    fun thumb(bytes: ByteArray, px: Int = 160): ImageBitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = maxOf(1, minOf(b.outWidth, b.outHeight) / px) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.asImageBitmap()
    }
}

/** Reading a picked stream safely. */
object AttachIo {
    /** All of [input] up to [max] bytes, or null when it is longer (so a huge file is never held in memory). */
    fun readLimited(input: InputStream, max: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > max) return null
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}

private const val RAW_PHOTO_LIMIT = 40L * 1024 * 1024

/** The words for a file that was turned away. */
fun attachError(code: String, name: String, size: Double): String {
    val v = mapOf("name" to name, "size" to fileSizeLabel(size), "cap" to jsNumber(fileCapMB()))
    return when (code) {
        "type" -> t("doc.err.type", "\"{name}\" cannot be kept here. Please use a PDF, a Word file or a picture.", v)
        "mismatch" -> t("doc.err.mismatch", "\"{name}\" does not look like the kind of file its name says, so it was not added.", v)
        "empty" -> t("doc.err.empty", "\"{name}\" is empty.", v)
        "tooBig" -> t("doc.err.tooBig", "\"{name}\" is too big. The most that can be kept is {cap} MB for one file.", v)
        "noRoom" -> t("doc.err.noRoom", "There is not enough room on this phone for \"{name}\". Free some space and try again.", v)
        "missing" -> t("doc.err.missing", "This file is not on this phone yet. Pull down on the home screen to refresh, then try again.", v)
        "unreadable" -> t("doc.err.unreadable", "That file could not be read.", v)
        else -> t("doc.err.failed", "\"{name}\" could not be saved. Nothing was changed.", v)
    }
}

/** Work that must finish even when the sheet that started it has gone. */
private val appScope: CoroutineScope = MainScope()

// ---------- storing and removing (main thread) ----------

/** Stores [p]'s bytes and records them under the owner. Returns the reason in words when it fails, else null. */
suspend fun storePicked(ownerType: String, ownerId: String, p: Picked): String? {
    // FileStore.addFile puts the metadata into DB.files and stores the bytes; fileAdded then stamps that same record
    // (by/at/updatedAt, dirty) by id, so it is written once, and the id goes to blobsUp for the bytes to be uploaded.
    val r = FileStore.addFile(ownerType, ownerId, p.name, p.mime, p.bytes)
    if (!r.b("ok")) return attachError(r.s("error"), p.name, p.bytes.size.toDouble())
    val meta = r.o("meta") ?: return attachError("failed", p.name, 0.0)
    Ledger.edit {
        fileAdded(meta)
        DB.oo("sync").oo("blobsUp")[meta.s("id")] = true
    }
    return null
}

/** Stores each of [list]; failures are told once each. The record was saved already, so the words say so. */
fun storeAll(ownerType: String, ownerId: String, list: List<Picked>) {
    if (list.isEmpty()) return
    appScope.launch {
        for (p in list) {
            val err = storePicked(ownerType, ownerId, p)
            if (err != null) Dialogs.tell(t("doc.err.afterSave", "{why}\n\nThe rest was saved.", "why" to err))
        }
    }
}

/** Marks files as deleted (the record stays as a tombstone so the delete can travel) and removes their bytes from this phone. */
fun removeStored(all: List<String>) {
    // in a family only the one who added a file (or its loan), or an admin, can delete it (the others' phones would keep it)
    val ids = all.filter { id -> DB.objs("files").firstOrNull { it.s("id") == id }?.let { com.tally.book.sync.SyncModel.mayDeleteHere(DB, "file", it) } ?: true }
    if (ids.size < all.size) DB.objs("files").firstOrNull { it.s("id") == all.first { a -> a !in ids } }?.let { f -> appScope.launch { Dialogs.tell(notYoursToDelete(f)) } }
    if (ids.isEmpty()) return
    Ledger.edit {
        ids.forEach { deleteFileRecord(it); DB.oo("sync").oo("blobsUp").remove(it) }
    }
    appScope.launch { ids.forEach { dropBytes(it) } }
}

private fun isBusyFetch(id: String) = FileFetch.isBusy(id)

private suspend fun dropBytes(id: String) {
    FileStore.fileStates.remove(id)
    runCatching { FileStore.fileRecDel(id) }
}

/** After a payment or loan is deleted with an Undo offered: its bytes go after the Undo has had its time, unless the record came back. */
fun dropBytesLater(ids: List<String>) {
    if (ids.isEmpty()) return
    appScope.launch {
        delay(15_000)
        ids.forEach { if (fileById(it) == null) dropBytes(it) }
    }
}

/** Removes the bytes of files that were just tombstoned together with their owner (a loan deleted after an ask). */
fun dropBytesNow(ids: List<String>) {
    if (ids.isEmpty()) return
    appScope.launch { ids.forEach { dropBytes(it) } }
}

/** Brings back a deleted payment (and its files) from copies taken before the delete, keeping who entered them and when. */
fun restorePayment(payment: Obj, files: List<Obj>) {
    Ledger.edit {
        val p = savePayment(payment)
        p["by"] = payment["by"]; p["at"] = payment["at"]; touch(p)
        files.forEach { f ->
            fileAdded(f)
            DB.objs("files").firstOrNull { it.s("id") == f.s("id") }?.let { r -> r["by"] = f["by"]; r["at"] = f["at"]; touch(r) }
            DB.oo("sync").oo("blobsUp")[f.s("id")] = true
        }
    }
}

// ---------- opening a stored document ----------

/** The ids of files whose bytes are being fetched from the family right now (the row shows "Getting the file…"). */
object FileFetch {
    val busy = mutableStateOf<Set<String>>(emptySet())
    fun isBusy(id: String): Boolean = id in busy.value
}

/**
 * The grey line under a stored file's name: its size when the bytes are on this phone, "Getting the file…" while they are being
 * fetched, and otherwise where it is kept ("On <name>'s phone · <date added>", or "On your other phone"), never an error: the
 * family's files pass through Tally's server only until everyone has them (docs/DESIGN.md "Files").
 */
@Composable
fun storedFileLine(f: Obj): String {
    val id = f.s("id")
    if (FileFetch.isBusy(id)) return t("doc.getting", "Getting the file…")
    if (FileStore.fileState(id) != "missing") return fileSizeLabel(f.num("size"))
    val at = f.num("at").takeIf { it > 0 } ?: f.num("addedAt")
    return FileAway.line(FileAway.where(f.s("by"), myId(), memberName(f.s("by"))), if (at > 0) fmtISODate(isoOfMillis(at)) else "")
}

/** Whether files added to [loan] go to the family: this phone shares with one and the loan is not private. */
fun filesShared(loan: Obj?): Boolean = loan != null && !loan.b("private") && Sync.hasFamily() && !Sync.membership().oldFamily

/**
 * Opens a stored file in the phone's viewer. When its bytes are not on this phone (someone else added it, or this phone joined
 * later or was restored without them) and there is a family, they are fetched first with a visible "Getting the file…" state.
 * When the family's server no longer holds them, it says plainly where the file is kept and offers "Ask for this file".
 */
suspend fun openStoredFile(ctx: Context, meta: Obj) {
    val id = meta.s("id")
    suspend fun read(): ByteArray = FileStore.readFileBytes(id)
    val bytes = try {
        try { read() } catch (e: FileStore.Fail) {
            if (e.code != "missing") throw e
            if (isBusyFetch(id)) return
            val family = Sync.hasFamily()
            val got = family && fetching(id) { Sync.engine.fetchFile(id) }
            if (!got) {
                val shared = family && !SyncModel.isPrivate(DB, "file", meta)
                val by = meta.s("by")
                val why = FileAway.explain(by, myId(), memberName(by), canAsk = shared)
                if (!shared) { Dialogs.tell(why); return }
                if (!Dialogs.ask(why, t("doc.away.ask", "Ask for this file"))) return
                var result: FileAsk = FileAsk.Asked
                val here = fetching(id) { Sync.engine.askForFile(id).also { result = it } == FileAsk.Here }
                if (!here) { FileAway.afterAsk(result)?.let { Dialogs.snack(it) }; return }
            }
            read()
        }
    } catch (e: FileStore.Fail) {
        Dialogs.tell(attachError(if (e.code == "missing") "missing" else "unreadable", meta.s("name"), 0.0)); return
    } catch (e: Exception) { Dialogs.tell(attachError("unreadable", meta.s("name"), 0.0)); return }
    val uri = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // only the file being opened is left behind in the cache
        val f = File(dir, fileSafeName(meta.s("name")))
        f.writeBytes(bytes)
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    }
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, fileOpenMime(meta)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    MainActivity.holdLock = true
    runCatching { ctx.startActivity(Intent.createChooser(view, meta.s("name"))) }
        .onFailure { MainActivity.holdLock = false; Dialogs.tell(t("doc.err.noViewer", "This phone has no app that can open this file.")) }
}

/** Runs [block] in the app's own scope with [id] shown as "Getting the file…". */
private suspend fun fetching(id: String, block: suspend () -> Boolean): Boolean {
    FileFetch.busy.value = FileFetch.busy.value + id
    return try { runCatching { Sync.detached { block() } }.getOrDefault(false) } finally { FileFetch.busy.value = FileFetch.busy.value - id }
}

// ---------- the pickers ----------

class AttachPickers(val takePhoto: () -> Unit, val choosePhoto: () -> Unit, val chooseDocument: () -> Unit)

/**
 * The three ways in: the system camera (a FileProvider URI; the camera permission, which the manifest declares for scanning, is asked first),
 * the system photo picker, and the system document picker (any type; the store turns away what it cannot keep, with a reason).
 * [onPicked] gets each file on the main thread, a photo already scaled; a file that is turned away is explained here.
 */
@Composable
fun rememberAttachPickers(onPicked: (Picked) -> Unit): AttachPickers {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    var camFile by remember { mutableStateOf<File?>(null) }
    var counter by remember { mutableIntStateOf(0) }
    val cap = (fileCapMB() * 1048576).toLong()

    fun photoFrom(read: () -> ByteArray?) {
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { read()?.let { PhotoCodec.scaleToJpeg(it) } }.getOrNull() }
            counter++
            val name = "photo-" + todayISO() + "-" + counter + ".jpg"
            when {
                out == null -> Dialogs.tell(t("doc.err.photo", "That photo could not be read. Please try another."))
                out.size > cap -> Dialogs.tell(attachError("tooBig", name, out.size.toDouble()))
                else -> latest.value(Picked(uid("a"), name, "image/jpeg", out, true))
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile; camFile = null
        if (f == null) return@rememberLauncherForActivityResult
        if (ok) photoFrom { f.readBytes().also { f.delete() } } else f.delete()
    }
    fun startCamera() {
        val dir = File(ctx.cacheDir, "camera").also { it.mkdirs() }
        val f = File(dir, "cap-" + Clock.millis() + ".jpg"); camFile = f
        MainActivity.holdLock = true
        runCatching { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }
            .onFailure { MainActivity.holdLock = false; Dialogs.snack(t("doc.err.noCamera", "No camera could be opened.")) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else Dialogs.snack(t("doc.err.cameraOff", "The camera is not allowed. You can choose a photo instead."))
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) photoFrom { ctx.contentResolver.openInputStream(uri)?.use { AttachIo.readLimited(it, RAW_PHOTO_LIMIT) } }
    }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, mime) = withContext(Dispatchers.IO) {
                var nm = "document"
                runCatching { ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) nm = c.getString(i) ?: nm } } }
                nm to ctx.contentResolver.getType(uri)
            }
            val bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { AttachIo.readLimited(it, cap) } }.getOrNull() }
            if (bytes == null) {
                // null is "too long" or "could not read"; the size tells which
                val size = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L }
                Dialogs.tell(attachError(if (size > cap) "tooBig" else "failed", name, maxOf(size, 0L).toDouble()))
                return@launch
            }
            val check = fileTypeCheck(name, mime, bytes.size.toDouble())
            if (!check.b("ok")) { Dialogs.tell(attachError(check.s("reason"), name, bytes.size.toDouble())); return@launch }
            latest.value(Picked(uid("a"), name, mime, bytes, false))
        }
    }

    return remember {
        AttachPickers(
            takePhoto = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                else permission.launch(Manifest.permission.CAMERA)
            },
            choosePhoto = { MainActivity.holdLock = true; gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            chooseDocument = { MainActivity.holdLock = true; document.launch(arrayOf("*/*")) },
        )
    }
}
