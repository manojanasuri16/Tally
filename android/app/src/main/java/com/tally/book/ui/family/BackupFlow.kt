package com.tally.book.ui.family

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.*
import com.tally.book.model.DB
import com.tally.book.sync.Sync
import com.tally.book.sync.SyncModel
import com.tally.book.ui.AppNav
import com.tally.book.ui.components.*
import com.tally.book.ui.lock.Bio
import com.tally.book.ui.lock.LockFlow
import com.tally.book.ui.lock.LockModes
import com.tally.book.ui.lock.PhoneLock
import com.tally.book.ui.lock.typeNoun
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Backups on screen (docs/DESIGN.md, "Backups"): the Backup group on the Family page, the "I have a backup" link on the
 * welcome screen, and the quiet "Backup stopped" line on the home screen. One decision per step: pick the file, the PIN if
 * the backup is locked, one question that says what will be replaced, then done. The work is in data/Backups.kt.
 */

/** Plain words for backups. Pure, so they can be unit-tested. */
object BackupText {
    /** "today 9:05 am", "yesterday 9:05 am", "08-Nov-2026 9:05 am". */
    fun at(ms: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val time = t.format(DateTimeFormatter.ofPattern("h:mm a", I18n.locale())).lowercase(I18n.locale())
        return when (t.toLocalDate()) {
            today -> t("backup.today", "today {time}", "time" to time)
            today.minusDays(1) -> t("backup.yesterday", "yesterday {time}", "time" to time)
            else -> fmtISODate(t.toLocalDate().toString()) + " " + time
        }
    }

    fun last(lastAt: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (lastAt <= 0) t("backup.never", "Never") else at(lastAt, maxOf(nowMs, lastAt), zone)

    fun error(code: String): String = when (code) {
        "notBackup" -> t("backup.err.notBackup", "This is not a Tally backup.")
        "newer" -> t("backup.err.newer", "This backup is from a newer Tally. Update Tally first, then try again.")
        "damaged" -> t("backup.err.damaged", "This backup is damaged and cannot be used. Nothing was changed.")
        "tooBig" -> t("backup.err.tooBig", "This file is too big to be a Tally backup.")
        "space" -> t("backup.err.space", "There is not enough space on this phone. Free some space and try again. Nothing was changed.")
        else -> t("backup.err.failed", "The backup could not be restored. Nothing was changed.")
    }

    /** The restore question: what is replaced, the backup's date and loans when known, and what a restore cannot bring back. */
    fun restoreQuestion(date: String, loans: Int?, phoneHasData: Boolean): String {
        val head = if (phoneHasData) t("backup.ask.replace", "Replace everything on this phone with this backup?") else t("backup.ask.fresh", "Bring back this backup?")
        val from = if (loans != null) tp("backup.ask.from", loans, "Backup from {date}, with {n} loan.", "Backup from {date}, with {n} loans.", mapOf("date" to fmtISODate(date)))
        else t("backup.ask.fromDate", "Backup from {date}.", "date" to fmtISODate(date))
        val what = if (phoneHasData) t("backup.ask.what", "The loans, payments, private loans, files, your name and your family on this phone will be replaced.") else ""
        val limit = t("backup.ask.limit", "Shared loans and payments your family deleted since then stay deleted.")
        return head + "\n\n" + listOf(from, what, limit).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun undoQuestion(date: String): String =
        t("backup.undo.ask", "Undo the last restore?\n\nThis puts back what was on this phone before it, as it was on {date}.", "date" to fmtISODate(date))

    fun noLockWarning(): String = t("backup.noLock", "Without an app lock, anyone who gets this file can read your loans and join your family.")
}

object BackupFlow {
    /** A file picked to restore from (the welcome link, or Restore from a backup). */
    suspend fun restorePicked(uri: Uri) {
        FamilyFlow.busy.value = t("backup.reading", "Reading the backup…")
        val file = try { Backups.receive(uri) } catch (e: BackupError) { FamilyFlow.busy.value = null; Dialogs.tell(BackupText.error(e.code)); return }
        restore(file, undo = false)
    }

    suspend fun undoLast() = restore(Backups.place().safety, undo = true)

    private suspend fun restore(file: File, undo: Boolean) {
        FamilyFlow.busy.value = t("backup.reading", "Reading the backup…")
        val checked = try { Backups.check(file) } catch (e: BackupError) { FamilyFlow.busy.value = null; Dialogs.tell(BackupText.error(e.code)); return }
        finally { FamilyFlow.busy.value = null }

        // a locked backup: its PIN is checked against the backup before anything else happens
        var opened: Crypto.Opened? = null
        var typedCode: String? = null
        val data: Obj
        if (checked.sealed) {
            val env = checked.envelope!!
            var got: Obj? = null
            // made with the phone's lock on this phone (an undo, or a backup from this same lock): the phone's prompt opens it
            LockModes.openWithPhone(env)?.let { o ->
                val parsed = runCatching { JsonCodec.parseObj(o.text) }.getOrNull()
                if (BackupCore.isBook(parsed)) { opened = o; got = parsed }
            }
            if (got == null) {
                val ok = LockFlow.askSealed(env) { secret ->
                    val o = withContext(Dispatchers.Default) { Crypto.openEnvelope(env, secret) }
                    val parsed = o?.let { runCatching { JsonCodec.parseObj(it.text) }.getOrNull() }
                    if (o != null && BackupCore.isBook(parsed)) { opened = o; got = parsed; typedCode = secret; true } else false
                }
                if (!ok) return
            }
            data = got!!
        } else data = checked.plain!!

        val phoneHasData = DB.objs("loans").any { !it.b("deleted") } || Sync.hasFamily()
        val question = if (undo) BackupText.undoQuestion(checked.date)
        else BackupText.restoreQuestion(checked.date, BackupCore.counts(data).num("loans").toInt(), phoneHasData)
        if (!Dialogs.ask(question, if (undo) t("backup.undo.ok", "Undo") else t("backup.restore.ok", "Restore"), danger = true)) return

        val keyBefore = Crypto.key?.copyOf()
        val codeBefore = DB.s("recoveryCode")
        FamilyFlow.busy.value = t("backup.restoring", "Restoring…")
        val failure: String? = try {
            Sync.detached {
                Sync.engine.paused {
                    // a plain backup onto a locked phone keeps the phone's lock: the book goes onto the disk already sealed with it
                    val keepLock = !checked.sealed && Crypto.locked && !undo
                    val book = if (keepLock) JsonCodec.stringify(Crypto.sealText(String(checked.book, Charsets.UTF_8))).toByteArray(Charsets.UTF_8) else checked.book
                    Backups.install(file, checked, book, keepSafety = !undo) {
                        when {
                            checked.sealed -> Crypto.adopt(opened!!)
                            keepLock -> Unit
                            else -> Crypto.clearLock()
                        }
                        val next = data.copyObj()
                        if (Crypto.locked) next["lockType"] = Crypto.type
                        when {
                            // the phone's lock keeps its recovery code inside the book, whatever the backup held
                            Crypto.locked && Crypto.type == LockRules.PHONE && keepLock -> next["recoveryCode"] = codeBefore
                            Crypto.locked && Crypto.type == LockRules.PHONE -> if (!RecoveryCode.valid(next.s("recoveryCode")) && typedCode != null) next["recoveryCode"] = typedCode
                            else -> next.remove("recoveryCode")
                        }
                        Ledger.start(next)
                        Ledger.edit { SyncModel.markRestored(DB) }
                    }
                }
            }
            null
        } catch (e: BackupError) { BackupText.error(e.code) } catch (e: Exception) { BackupText.error("failed") }
        finally { FamilyFlow.busy.value = null }
        if (failure != null) { Dialogs.tell(failure); return }

        val lockChanged = !(keyBefore?.contentEquals(Crypto.key) ?: (Crypto.key == null))
        if (lockChanged) Bio.disable()       // the fingerprint copy holds the old lock
        if (!PhoneLock.fits(Crypto.salt)) PhoneLock.disable()   // and the phone's copy, unless it is this very lock
        Ledger.persist()
        if (undo) Backups.dropSafety()
        Sync.engine.resetState()
        AppNav.home()
        when {
            lockChanged && Crypto.locked && Crypto.type == "pin" -> Dialogs.tell(t("backup.done.locked", "Restored.\n\nTally is now locked with the PIN from this backup."))
            lockChanged && Crypto.locked -> {
                Dialogs.tell(t("backup.done.lockedWhat", "Restored.\n\nTally is now locked with the {what} from this backup.", "what" to typeNoun(Crypto.type)))
                // the phone's lock from a backup: this phone's prompt is set up again (asked), or the recovery code is used
                if (Crypto.type == LockRules.PHONE) LockModes.afterRecoveryUnlock()
            }
            lockChanged -> Dialogs.tell(t("backup.done.unlocked", "Restored.\n\nThe app lock is off, as it was then."))
            else -> Dialogs.snack(if (undo) t("backup.undo.done", "Put back as it was") else t("backup.done", "Restored"))
        }
        Sync.syncInBackground()
    }

    /** Back up now: into the folder when one is chosen, then (if wanted) the share sheet. */
    suspend fun backUpNow(ctx: Context) {
        FamilyFlow.busy.value = t("backup.making", "Making the backup…")
        val saved = try { if (Backups.isOn()) Backups.saveToFolder() else null } catch (e: Exception) { Backups.Outcome.FAILED }
        val file = try { Backups.shareFile() } catch (e: Exception) { null } finally { FamilyFlow.busy.value = null }
        if (file == null) {
            Dialogs.tell(if (saved == Backups.Outcome.OK) t("backup.savedOnly", "Backup saved in your folder.") else t("backup.err.make", "The backup could not be made. Free some space and try again."))
            return
        }
        val head = when (saved) {
            Backups.Outcome.OK -> t("backup.saved.send", "Backup saved in your folder.\n\nSend a copy too?")
            Backups.Outcome.STOPPED -> t("backup.stopped.send", "Backup stopped: choose the folder again.\n\nSend the backup instead?")
            Backups.Outcome.FAILED -> t("backup.failed.send", "Could not save in your folder.\n\nSend the backup instead?")
            else -> t("backup.send.ask", "Send the backup?\n\nKeep it somewhere safe: Drive, WhatsApp to yourself, or email.")
        }
        val text = if (Crypto.locked) head else head + " " + BackupText.noLockWarning()
        if (!Dialogs.ask(text, t("backup.send", "Send"))) return
        share(ctx, file)
    }

    private fun share(ctx: Context, file: File) {
        val uri = runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file) }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        MainActivity.holdLock = true      // coming back from the share sheet must not lock the app
        runCatching { ctx.startActivity(Intent.createChooser(send, t("backup.send", "Send"))) }.onFailure { MainActivity.holdLock = false }
    }
}

/** Opens the system file picker for a backup to restore; the rest of the restore follows the pick. */
@Composable
fun rememberRestorePicker(): () -> Unit {
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { BackupFlow.restorePicked(uri) }
    }
    return { MainActivity.holdLock = true; runCatching { pick.launch(arrayOf("*/*")) }.onFailure { MainActivity.holdLock = false } }
}

/** The folder picker for daily backups: picking one turns daily backup on and makes the first backup there. */
@Composable
private fun rememberFolderPicker(): () -> Unit {
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            FamilyFlow.busy.value = t("backup.making", "Making the backup…")
            val ok = try { Backups.chooseFolder(uri) } catch (e: Exception) { false } finally { FamilyFlow.busy.value = null }
            if (ok) Dialogs.snack(t("backup.on.done", "Backed up. Tally will do this every day."))
            else Dialogs.tell(t("backup.err.folder", "Tally could not save in that folder. Please choose another one."))
        }
    }
    return { MainActivity.holdLock = true; runCatching { pick.launch(null) }.onFailure { MainActivity.holdLock = false } }
}

/** The Backup group on the Family page. */
@Composable
fun BackupGroup() {
    val rev = rememberLedger()
    val st = Backups.status.value
    val locked = remember(rev) { Crypto.locked }
    val type = remember(rev) { Crypto.type }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooseFolder = rememberFolderPicker()
    val restore = rememberRestorePicker()
    val warn = LocalSemantic.current.warning
    AGroup(
        title = t("backup.title", "Backup"),
        footer = when {
            !locked -> BackupText.noLockWarning()
            type == LockRules.PHONE -> t("backup.help.phone", "Backups are locked with your recovery code. To restore one on another phone you need that code.")
            type == "pin" -> t("backup.help.locked", "Backups are locked with your PIN. To restore one you need that PIN.")
            else -> t("backup.help.lockedWhat", "Backups are locked with your {what}. To restore one you need it.", "what" to typeNoun(type))
        },
    ) {
        item {
            SwitchRow(
                t("backup.daily", "Back up every day"), st.on,
                { want -> if (want) chooseFolder() else Backups.turnOff() },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp), icon = Icons.Rounded.Backup,
            )
        }
        if (st.stopped) item {
            ARow(t("backup.stopped", "Backup stopped: choose the folder again"), icon = Icons.Rounded.Warning, tint = warn, titleColor = warn, chevron = true, onClick = chooseFolder)
        } else item {
            ARow(
                t("backup.last", "Last backup"), value = BackupText.last(st.lastAt, Clock.millis()), icon = Icons.Rounded.History,
                sub = if (st.failed) t("backup.failedOnce", "It did not work last time. Tally will try again.") else null,
            )
        }
        item { ARow(t("backup.now", "Back up now"), icon = Icons.Rounded.Save, onClick = { scope.launch { BackupFlow.backUpNow(ctx) } }) }
        item { ARow(t("backup.restore", "Restore from a backup"), icon = Icons.Rounded.Restore, chevron = true, onClick = restore) }
        if (st.canUndo) item { ARow(t("backup.undo", "Undo the last restore"), icon = Icons.AutoMirrored.Rounded.Undo, onClick = { scope.launch { BackupFlow.undoLast() } }) }
    }
}

/** The quiet line on the home screen, only when daily backup has stopped. Tapping it opens the Family page. */
@Composable
fun BackupHomeLine() {
    val st = Backups.status.value
    if (!st.stopped) return
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).pressDim(onClick = { FamilyFlow.openFamily() }).padding(start = 4.dp, end = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(t("backup.stopped", "Backup stopped: choose the folder again"), style = Apple.type.footnote, color = LocalSemantic.current.warning)
    }
}
