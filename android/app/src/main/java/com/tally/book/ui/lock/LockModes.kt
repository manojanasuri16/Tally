package com.tally.book.ui.lock

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.*
import com.tally.book.model.DB
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Choosing the kind of lock (docs/DESIGN.md, "App lock"): Off, Tally's own lock (PIN, pattern or password, through LockFlow), or
 * the phone's lock (PhoneLock + a recovery code). Every switch, change and turning off confirms the current lock first. Also
 * here: showing the recovery code again, setting the phone's prompt up again after the recovery code was used, and the check
 * before hidden amounts are shown again.
 */
object LockModes {
    /** The recovery code sheet: [setup] shows it and then has it typed back once; otherwise it only shows it. */
    class CodeSheet(val code: String, val setup: Boolean) { val result = CompletableDeferred<Boolean>() }
    val sheet = mutableStateOf<CodeSheet?>(null)

    /** Shows [code] in its sheet (screenshots blocked while it is up); with [setup] it is typed back once. True when done. */
    suspend fun showCode(code: String, setup: Boolean): Boolean {
        sheet.value?.result?.complete(false)
        val s = CodeSheet(code, setup)
        ScreenGuard.hold()      // before the sheet's window opens, so it takes the flag too
        sheet.value = s
        return try { s.result.await() } finally { if (sheet.value === s) sheet.value = null; ScreenGuard.release() }
    }

    fun kind(): LockKind = LockRules.kindOf(Crypto.locked, Crypto.type)

    /** Off: asks, confirms the current lock, then the book is written plain and every copy of the key is forgotten. */
    suspend fun turnOff(): Boolean {
        if (!Crypto.locked) return true
        if (!Dialogs.ask(t("applock.off.ask", "Turn off the lock?\n\nAnyone who opens your phone will be able to see your entries."), t("applock.off.ok", "Turn off"), danger = true)) return false
        if (!LockFlow.confirm(t("lock.forOff", "To turn off the lock."), null)) return false
        DB.remove("recoveryCode")
        Crypto.clearLock(); Bio.disable()
        Ledger.persist()
        PhoneLock.disable()
        DevicePrefs.remove(Ledger.NEW_CODE)
        lockChanged(); Ledger.bump()
        return true
    }

    /**
     * Tally's own lock with [secret] of [kind] (pin, pattern, password): a new lock, a change, or a switch from the phone's lock.
     * A lock that already has a recovery code keeps it (and the data key); a first lock makes one, shows it and has it typed
     * back before anything changes (cancelling there changes nothing). Called from the lock screen's last step.
     */
    suspend fun applyOwn(secret: String, kind: String): Boolean {
        val had = DB.s("recoveryCode").takeIf { Crypto.locked && RecoveryCode.valid(it) }
        val code = had ?: RecoveryCode.make().also {
            if (!showCode(it, setup = true)) { Dialogs.snack(t("phonelock.notChanged", "Nothing was changed.")); return false }
        }
        val lock = withContext(Dispatchers.Default) { runCatching { Crypto.makeOwn(secret, kind, code) }.getOrNull() }
            ?: run { Dialogs.tell(t("lock.setFailed", "The lock could not be set on this phone. Nothing was changed.")); return false }
        Crypto.adopt(lock)
        DB["lockType"] = kind
        DB["recoveryCode"] = code
        Bio.disable()   // the old copy held the previous lock
        Ledger.persist()
        PhoneLock.disable()   // after the new seal is on disk: until then the recovery code still opens the old one
        DevicePrefs.remove(Ledger.NEW_CODE)
        lockChanged()
        Ledger.bump()
        afterLockChange()
        return true
    }

    /** After a lock was set or changed (also the update's re-seal): older plain or old-lock copies are sealed again (L3). */
    fun afterLockChange() { MainScope().launch { delay(300); BackupReseal.afterLockChange() } }

    /**
     * After the book opened. The update made a recovery code (a book locked before 0.6): it is shown once and typed back (asked
     * again at the next unlock until it is). Opened with the recovery code: the phone's lock is set up again, or for Tally's own
     * lock a new PIN is offered (required when the PIN cannot work on this phone).
     */
    suspend fun afterUnlock(viaCode: Boolean) {
        if (!Crypto.locked) return
        if (DevicePrefs.get(Ledger.NEW_CODE) != null) {
            val code = DB.s("recoveryCode")
            if (RecoveryCode.valid(code)) {
                Dialogs.tell(t("recovery.new.explain", "Tally now has a recovery code.\n\nIt opens Tally and your backups if you forget your {what}, and on a new phone. Write it on paper on the next screen.", "what" to typeNoun(Crypto.type)))
                if (showCode(code, setup = true)) { DevicePrefs.remove(Ledger.NEW_CODE); Dialogs.snack(t("recovery.saved", "Keep the paper safe")) }
            } else DevicePrefs.remove(Ledger.NEW_CODE)
            afterLockChange()
            return
        }
        if (!viaCode) return
        if (kind() == LockKind.PHONE) { afterRecoveryUnlock(); return }
        if (Crypto.currentPinFits()) {
            if (Dialogs.ask(t("lock.newAfterCode", "Opened with the recovery code.\n\nChoose a new {what}?", "what" to typeNoun(Crypto.type)), t("lock.newAfterCode.ok", "Choose"))) LockFlow.pickNew(Crypto.type)
        } else {
            Dialogs.tell(t("lock.pickHere", "The {what} from your other phone does not work here. Choose the {what} Tally will ask for on this phone.", "what" to typeNoun(Crypto.type)))
            LockFlow.pickNew(Crypto.type)
        }
    }

    /**
     * Before a backup leaves the phone for the first time under this lock: it opens on a new phone only with the recovery code,
     * so the code is offered once more (behind the lock). Asked once per lock (a device-only note).
     */
    suspend fun beforeFirstSend() {
        if (!Crypto.locked) return
        val id = Crypto.salt?.let { Crypto.b64(it) } ?: return
        if (DevicePrefs.get(SENT_UNDER) == id) return
        val code = DB.s("recoveryCode")
        if (RecoveryCode.valid(code)) {
            val see = Dialogs.ask(t("recovery.beforeSend", "A backup opens on a new phone only with your recovery code.\n\nSee the code now, to check it is on your paper?"), t("recovery.see", "See the code"))
            if (see && LockFlow.confirm(t("recovery.forShow", "To show the recovery code."), null)) showCode(code, setup = false)
        }
        DevicePrefs.set(SENT_UNDER, id)
    }
    private const val SENT_UNDER = "tally-recovery-sent-under"

    /** Tally's own lock of [type] (pin, pattern, password): set it, change it, or switch to it, confirming the current lock first. */
    suspend fun useOwn(type: String): Boolean = LockFlow.setOrChange(type)

    /** Why the phone's lock can't be chosen here, in plain words; null when it can. */
    fun phoneUnavailable(offer: PhoneLockOffer): String? = when (offer) {
        PhoneLockOffer.OK -> null
        PhoneLockOffer.OLD_ANDROID -> t("phonelock.oldAndroid", "Needs Android 11 or newer.")
        PhoneLockOffer.NO_SCREEN_LOCK -> t("phonelock.noScreenLock", "First set a screen lock in your phone's settings.")
    }

    /**
     * The phone's lock: explain, confirm the current lock, show a new recovery code and have it typed back, then one phone prompt
     * to seal this phone's copy of the key. Only then is the book sealed with the new lock: any step that stops changes nothing.
     */
    suspend fun usePhone(): Boolean {
        val act = MainActivity.current ?: return false
        phoneUnavailable(PhoneLock.offer(act))?.let { Dialogs.tell(t("phonelock.cant", "The phone's lock can't be used here.") + "\n\n" + it); return false }
        if (kind() == LockKind.PHONE) return true
        // Tally's own lock already has a recovery code: it stays the same (the paper the person has still works)
        val had = DB.s("recoveryCode").takeIf { Crypto.locked && RecoveryCode.valid(it) }
        if (!Dialogs.ask(
                if (had != null) t("phonelock.explainKeep", "Use the phone's lock?\n\nTally will open with your phone's screen lock or fingerprint. Your recovery code stays the same.")
                else t("phonelock.explain", "Use the phone's lock?\n\nTally will open with your phone's screen lock or fingerprint. You will also get a recovery code to write on paper. You need it to open a backup on a new phone, or if this phone's screen lock is ever removed."),
                t("phonelock.continue", "Continue"),
            )) return false
        if (Crypto.locked && !LockFlow.confirm(t("lock.forChange", "To change the lock."), null)) return false
        val code = had ?: RecoveryCode.make()
        if (had == null && !showCode(code, setup = true)) return false
        val made = withContext(Dispatchers.Default) { Crypto.makePhone(code) }
        val salt = made.lock.salt
        when (val r = PhoneLock.enable(act, salt, made.copyKey, t("phonelock.prompt.setup", "Use the phone's lock for Tally"))) {
            is PhoneSeal.Outcome.Ok -> Unit
            PhoneSeal.Outcome.Cancelled -> { Dialogs.snack(t("phonelock.notChanged", "Nothing was changed.")); return false }
            else -> {
                val why = (r as? PhoneSeal.Outcome.Failed)?.why?.takeIf { it.isNotBlank() }
                Dialogs.tell(t("phonelock.setupFailed", "Tally could not use the phone's lock. Nothing was changed.") + (why?.let { "\n\n$it" } ?: ""))
                return false
            }
        }
        // from here the new lock is the lock: the book (and its files key) are sealed with it on the next write, right now
        Crypto.adopt(made.lock)
        DB["lockType"] = LockRules.PHONE
        DB["recoveryCode"] = code
        Bio.disable()   // the fingerprint copy held Tally's own lock
        Ledger.persist()
        DevicePrefs.remove(Ledger.NEW_CODE)
        lockChanged(); Ledger.bump()
        Dialogs.snack(t("phonelock.on", "Tally now opens with your phone's lock"))
        afterLockChange()
        return true
    }

    /** "Show recovery code": behind the lock (the fingerprint, PIN or phone's prompt, or the code itself). */
    suspend fun showRecovery() {
        if (kind() == LockKind.OFF) return
        if (!LockFlow.confirm(t("recovery.forShow", "To show the recovery code."), null)) return
        val code = DB.s("recoveryCode")
        if (!RecoveryCode.valid(code)) { Dialogs.tell(t("recovery.missing", "The recovery code is not kept in this book.\n\nTo get a new one, turn the lock off and set it again.")); return }
        if (showCode(code, setup = false)) DevicePrefs.remove(Ledger.NEW_CODE)
    }

    /** After the book was opened with the recovery code (this phone's copy of the key is gone): the phone's prompt again, if it can. */
    suspend fun afterRecoveryUnlock() {
        if (kind() != LockKind.PHONE || PhoneLock.fits(Crypto.salt)) return
        val act = MainActivity.current ?: return
        val salt = Crypto.salt ?: return
        val key = withContext(Dispatchers.Default) { Crypto.phoneCopyKey(DB.s("recoveryCode").ifEmpty { return@withContext null }) } ?: return
        if (PhoneLock.offer(act) != PhoneLockOffer.OK) {
            Dialogs.tell(t("phonelock.noLockNow", "Tally can't use this phone's lock.\n\nTally will ask for your recovery code each time. To choose another lock, open Family, then App lock."))
            return
        }
        if (!Dialogs.ask(t("phonelock.again.ask", "Use the phone's lock again?\n\nTally will open with your phone's screen lock or fingerprint again. Your recovery code stays the same."), t("phonelock.again.ok", "Yes"))) return
        when (PhoneLock.enable(act, salt, key, t("phonelock.prompt.setup", "Use the phone's lock for Tally"))) {
            is PhoneSeal.Outcome.Ok -> Dialogs.snack(t("phonelock.on", "Tally now opens with your phone's lock"))
            PhoneSeal.Outcome.Cancelled -> Unit
            else -> Dialogs.tell(t("phonelock.againFailed", "Tally could not use the phone's lock. It will ask for your recovery code for now."))
        }
    }

    /** A sealed backup made with the phone's lock on this phone (an undo): this phone's prompt opens it without the code. */
    suspend fun openWithPhone(env: Obj): Crypto.Opened? {
        if (env.s("t") != LockRules.PHONE) return null
        val act = MainActivity.current ?: return null
        val salt = runCatching { Crypto.unb64(env.s("s")) }.getOrNull() ?: return null
        if (!PhoneLock.fits(salt)) return null
        val r = PhoneLock.unlockKey(act, env, t("phonelock.forBackup", "Open the backup")) as? PhoneSeal.Outcome.Ok ?: return null
        return Crypto.openEnvelopeWithKey(env, r.bytes)
    }

    /** What the Hide amounts row says about showing them again. */
    fun unhideNote(deviceSecure: Boolean): String = when (LockRules.unhideAsk(kind(), deviceSecure)) {
        UnhideAsk.NOTHING -> t("hide.sub.none", "Anyone can show them again: this phone has no screen lock.")
        else -> t("hide.sub.ask", "Showing them again asks for your fingerprint or lock")
    }

    /** Showing hidden amounts again: the app lock when set, else the phone's own lock when it has one, else nothing to ask. */
    suspend fun mayUnhide(): Boolean {
        val act = MainActivity.current ?: return false
        return when (LockRules.unhideAsk(kind(), PhoneLock.deviceSecure(act))) {
            UnhideAsk.APP_LOCK -> LockFlow.confirm(t("hide.forShow", "To show amounts again."), null)
            UnhideAsk.NOTHING -> true
            UnhideAsk.PHONE -> when (val r = PhoneLock.confirmPlain(act, t("hide.prompt", "Show amounts"))) {
                is PhoneSeal.Outcome.Ok -> true
                PhoneSeal.Outcome.Gone -> !PhoneLock.deviceSecure(act)   // the screen lock went away meanwhile: nothing left to ask
                PhoneSeal.Outcome.Cancelled -> false
                is PhoneSeal.Outcome.Failed -> { Dialogs.tell(r.why.ifBlank { t("phonelock.failedShort", "That didn't work. Try again.") }); false }
            }
        }
    }
}

/** The recovery code sheet, hosted with the lock overlay. */
@Composable
fun RecoveryCodeHost() {
    val s = LockModes.sheet.value ?: return
    SecureWhileShown()
    var step by remember(s) { mutableIntStateOf(0) }
    var typed by remember(s) { mutableStateOf("") }
    val close = { s.result.complete(false); Unit }
    if (!s.setup) {
        EditSheet(t("recovery.sheetTitle", "Recovery code"), close, onSave = null) { CodeShown(s.code, setup = false) }
        return
    }
    if (step == 0) {
        EditSheet(t("recovery.sheetTitle", "Recovery code"), close, onSave = { step = 1 }, saveLabel = t("recovery.wrote", "I wrote it down")) {
            CodeShown(s.code, setup = true)
        }
    } else {
        val same = RecoveryCode.normalize(typed) == s.code
        val full = RecoveryCode.normalize(typed).length >= RecoveryCode.DIGITS
        EditSheet(t("recovery.checkTitle", "Check the code"), close, onSave = { if (same) s.result.complete(true) }, saveLabel = t("recovery.done", "Done"), saveEnabled = same) {
            Text(
                t("recovery.checkMsg", "Type the code from your paper, to be sure it was written down right."),
                style = Apple.type.body, color = Apple.colors.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextInput(
                typed, { if (it.length <= 40) typed = it }, t("recovery.label", "Recovery code"), keyboard = KeyboardType.Number,
                supporting = if (full && !same) t("recovery.notSame", "That is not the same code. Check the paper, or show the code again.")
                else t("recovery.count", "{n} of 20 digits", "n" to RecoveryCode.normalize(typed).length),
            )
            PlainButton(t("recovery.showAgain", "Show the code again"), { step = 0; typed = "" })
        }
    }
}

@Composable
private fun CodeShown(code: String, setup: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AGroup {
            item {
                Text(
                    RecoveryCode.format(code), style = Apple.type.title2.copy(fontFeatureSettings = "tnum"), color = Apple.colors.label,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
        }
        Text(
            t("recovery.keepAll", "Write these 20 digits on paper and keep the paper safe, away from the phone. You need them to open a backup on a new phone, or if you forget your lock."),
            style = Apple.type.body, color = Apple.colors.label, modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (setup) Text(
            t("recovery.noPhoto", "Do not keep it only on this phone. Anyone with the code and a backup can open your entries."),
            style = Apple.type.footnote, color = LocalSemantic.current.warning, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
