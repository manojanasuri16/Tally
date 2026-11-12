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

    private suspend fun showCode(code: String, setup: Boolean): Boolean {
        sheet.value?.result?.complete(false)
        val s = CodeSheet(code, setup)
        sheet.value = s
        return try { s.result.await() } finally { if (sheet.value === s) sheet.value = null }
    }

    fun kind(): LockKind = LockRules.kindOf(Crypto.locked, Crypto.type)

    /** Off: asks, confirms the current lock, then the book is written plain and every copy of the key is forgotten. */
    suspend fun turnOff(): Boolean {
        if (!Crypto.locked) return true
        if (!Dialogs.ask(t("applock.off.ask", "Turn off the lock?\n\nAnyone who opens your phone will be able to see your loans."), t("applock.off.ok", "Turn off"), danger = true)) return false
        if (!LockFlow.confirm(t("lock.forOff", "To turn off the lock."), null)) return false
        DB.remove("recoveryCode")
        Crypto.clearLock(); Bio.disable()
        Ledger.persist()
        PhoneLock.disable()
        lockChanged(); Ledger.bump()
        return true
    }

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
        if (!Dialogs.ask(
                t("phonelock.explain", "Use the phone's lock?\n\nTally will open with your phone's screen lock or fingerprint. You will also get a recovery code to write on paper. You need it to open a backup on a new phone, or if this phone's screen lock is ever removed."),
                t("phonelock.continue", "Continue"),
            )) return false
        if (Crypto.locked && !LockFlow.confirm(t("lock.forChange", "To change the lock."), null)) return false
        val code = RecoveryCode.make()
        if (!showCode(code, setup = true)) return false
        val salt = Crypto.randomBytes(16)
        val key = withContext(Dispatchers.Default) { Crypto.deriveKeyBits(code, salt) }
        when (val r = PhoneLock.enable(act, salt, key, t("phonelock.prompt.setup", "Use the phone's lock for Tally"))) {
            is PhoneSeal.Outcome.Ok -> Unit
            PhoneSeal.Outcome.Cancelled -> { Dialogs.snack(t("phonelock.notChanged", "Nothing was changed.")); return false }
            else -> {
                val why = (r as? PhoneSeal.Outcome.Failed)?.why?.takeIf { it.isNotBlank() }
                Dialogs.tell(t("phonelock.setupFailed", "Tally could not use the phone's lock. Nothing was changed.") + (why?.let { "\n\n$it" } ?: ""))
                return false
            }
        }
        // from here the new lock is the lock: the book (and its files key) are sealed with it on the next write, right now
        Crypto.adopt(Crypto.Opened(key, salt, LockRules.PHONE, 0, ""))
        DB["lockType"] = LockRules.PHONE
        DB["recoveryCode"] = code
        Bio.disable()   // the fingerprint copy held Tally's own lock
        Ledger.persist()
        lockChanged(); Ledger.bump()
        Dialogs.snack(t("phonelock.on", "Tally now opens with your phone's lock"))
        return true
    }

    /** "Show recovery code": behind the phone's prompt (or the code itself, when that is gone). */
    suspend fun showRecovery() {
        if (kind() != LockKind.PHONE) return
        if (!LockFlow.confirm(t("recovery.forShow", "To show the recovery code."), null)) return
        val code = DB.s("recoveryCode")
        if (!RecoveryCode.valid(code)) { Dialogs.tell(t("recovery.missing", "The recovery code is not kept in this book.\n\nTo get a new one, choose Tally's own lock and then the phone's lock again.")); return }
        showCode(code, setup = false)
    }

    /** After the book was opened with the recovery code (this phone's copy of the key is gone): the phone's prompt again, if it can. */
    suspend fun afterRecoveryUnlock() {
        if (kind() != LockKind.PHONE || PhoneLock.fits(Crypto.salt)) return
        val act = MainActivity.current ?: return
        val salt = Crypto.salt ?: return
        val key = Crypto.key ?: return
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
            t("recovery.keep", "Write these 20 digits on paper and keep the paper safe, away from the phone. You need them to open a backup on a new phone, or if this phone's screen lock is ever removed."),
            style = Apple.type.body, color = Apple.colors.label, modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (setup) Text(
            t("recovery.noPhoto", "Do not keep it only on this phone. Anyone with the code and a backup can open your loans."),
            style = Apple.type.footnote, color = LocalSemantic.current.warning, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
