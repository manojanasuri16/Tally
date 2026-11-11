package com.tally.book.ui.lock

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.Crypto
import com.tally.book.data.Ledger
import com.tally.book.data.lockChanged
import com.tally.book.model.DB
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import com.tally.book.ui.theme.Motion
import com.tally.book.ui.theme.Space
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * Port of app/js/ui/lock/lock-screen.js: the PIN pad, the pattern grid and the password box (by the
 * envelope's `t`), wrong-entry feedback, fingerprint unlock, and the other stages of the same state
 * machine (setting, changing and confirming the lock). The quick-add page is not built (UI.md).
 *
 * Stages, as in submitSecret(): 'open' (the sealed ledger, Ledger.Phase.LOCKED), 'new1'/'new2' (pick a
 * new lock, twice), 'confirmchange' (the current lock before a change), 'bioconfirm' (the lock before
 * fingerprint setup), 'privconfirm' (confirmItsYou), 'sealedfile' (a file sealed under some lock).
 */

fun typeNoun(type: String): String = when (type) {
    "pattern" -> t("lock.noun.pattern", "pattern"); "password" -> t("lock.noun.password", "password"); else -> t("lock.noun.pin", "PIN")
}
fun wrongMsg(type: String) = t("lock.wrong", "Wrong {what}.", "what" to typeNoun(type))
private fun lockCopy(type: String): Pair<String, String> = when (type) {
    "pattern" -> t("lock.title.pattern", "Draw your pattern") to t("lock.connectDots", "Connect the dots in order.")
    "password" -> t("lock.title.password", "Enter your password") to ""
    else -> t("lock.title.pin", "Enter your PIN") to t("lock.fourToEight", "Four to eight digits.")
}
private fun setupCopy(type: String, isChange: Boolean): Pair<String, String> {
    val noun = typeNoun(type)
    val title = if (isChange) t("lock.pickNew", "Pick a new {what}", "what" to noun) else t("lock.pick", "Pick a {what}", "what" to noun)
    val msg = when (type) { "pattern" -> t("lock.atLeastDots", "At least four dots, no repeats."); "password" -> t("lock.atLeastSix", "At least six characters."); else -> t("lock.fourToEight", "Four to eight digits.") }
    return title to msg
}
private fun confirmCopy(type: String): Pair<String, String> {
    val noun = typeNoun(type)
    return t("lock.confirm", "Confirm your {what}", "what" to noun) to t("lock.confirmMsg", "Enter your current {what} to continue.", "what" to noun)
}

/** verifyPin(): the secret against the current lock, without touching it. */
suspend fun verifySecret(secret: String): Boolean {
    val k = Crypto.key ?: return false
    val s = Crypto.salt ?: return false
    return withContext(Dispatchers.Default) { runCatching { Crypto.deriveKeyBits(secret, s).contentEquals(k) }.getOrDefault(false) }
}

/** One run of the lock screen: what it asks for and what happens with the answer. */
class LockSession(
    stage: String, type: String, title: String, msg: String,
    val skippable: Boolean,
    /** The sealed envelope ('open', 'sealedfile'): its `l` sizes and auto-submits a PIN. */
    val env: Obj? = null,
    /** 'sealedfile': opens the file with the secret; true when it opened. */
    val check: (suspend (String) -> Boolean)? = null,
    val pendingType: String? = null,
) {
    var stage by mutableStateOf(stage)
    var type by mutableStateOf(type)
    var title by mutableStateOf(title)
    var msg by mutableStateOf(msg)
    var failed by mutableStateOf(false)
    var busy by mutableStateOf(false)
    /** Bumped to clear what was typed; [shake] to shake the box. */
    var clearTick by mutableIntStateOf(0)
    var shake by mutableIntStateOf(0)
    var firstSecret = ""
    val result = CompletableDeferred<Boolean>()

    val autoLen: Int get() = when (stage) {
        "privconfirm" -> Crypto.pinLen
        "open", "sealedfile" -> n(env?.get("l")).toInt()
        else -> 0
    }

    fun fail(m: String) { msg = m; failed = true; clearTick++; shake++ }
    fun say(m: String) { msg = m; failed = false }
    fun finish(ok: Boolean) { if (!result.isCompleted) result.complete(ok) }
}

/** The lock screen, the setup screens and confirmItsYou() over the app (one at a time). */
object LockFlow {
    val session = mutableStateOf<LockSession?>(null)

    private suspend fun run(s: LockSession): Boolean {
        session.value?.finish(false)
        session.value = s
        return try { s.result.await() } finally { if (session.value === s) session.value = null }
    }

    /** #pinSet: set a lock of [target] kind, or change the current one (confirming it first). */
    suspend fun setOrChange(target: String): Boolean {
        if (Crypto.locked) {
            val (ti, m) = confirmCopy(Crypto.type)
            return run(LockSession("confirmchange", Crypto.type, ti, m, true, pendingType = target))
        }
        val (ti, m) = setupCopy(target, false)
        return run(LockSession("new1", target, ti, m, true))
    }

    /** enableBioUnlock(): the lock first, then the fingerprint. */
    suspend fun enableBio(): Boolean {
        val act = MainActivity.current ?: return false
        if (!Crypto.locked) { Dialogs.tell(t("lock.setLockFirst", "Set a lock first.")); return false }
        if (!Bio.available(act)) { Dialogs.tell(t("lock.err.noScreenLock", "this device has no fingerprint/screen lock set up, or the browser does not support it.")); return false }
        val (ti, _) = confirmCopy(Crypto.type)
        if (!run(LockSession("bioconfirm", Crypto.type, ti, t("lock.forBio", "To set up fingerprint unlock."), true))) return false
        val r = Bio.enable(act)
        if (r.isSuccess) { Dialogs.tell(t("lock.bioOnAlert", "Fingerprint unlock is on — try it next time you open the app.")); Ledger.bump(); return true }
        val e = r.exceptionOrNull()
        val why = when ((e as? Bio.Failure)?.name) {
            "NotAllowedError" -> t("lock.err.cancelled", "the fingerprint prompt was cancelled or timed out.")
            "NotSupportedError" -> t("lock.err.noScreenLock", "this device has no fingerprint/screen lock set up, or the browser does not support it.")
            else -> e?.message?.takeIf { it.isNotBlank() } ?: t("lock.err.notSupported", "not supported")
        }
        Dialogs.tell(t("lock.bioSetupFailed", "Could not set up fingerprint unlock on this device: {why}", "why" to why))
        return false
    }

    /** confirmItsYou(onOk, why, key) as a suspend call: true when it's confirmed (or nothing to check against). */
    suspend fun confirm(why: String?, key: String?): Boolean {
        if (key != null && DB.o("priv")?.get(key) == false) return true
        val act = MainActivity.current
        if (act != null && Bio.on() && DB.o("priv")?.get("confirmBio") != false) {
            if (Bio.open(act, t("lock.confirm", "Confirm your {what}", "what" to typeNoun(Crypto.type))).isSuccess) return true
        }
        if (!Crypto.locked) return true
        val (ti, _) = confirmCopy(Crypto.type)
        return run(LockSession("privconfirm", Crypto.type, ti, why ?: t("lock.forPrivate", "To show private items."), true))
    }

    /** The 'sealedfile' stage: asks for the lock a file was sealed with; [open] tries it. */
    suspend fun askSealed(env: Obj, open: suspend (String) -> Boolean): Boolean {
        val type = env.s("t").ifEmpty { "pin" }
        val (ti, m) = lockCopy(type)
        return run(LockSession("sealedfile", type, ti, m, true, env = env, check = open))
    }

    /** submitSecret(): where the PIN pad, the pattern and the password all end up. */
    suspend fun submit(s: LockSession, secret: String) {
        if (s.busy) return
        val min = if (s.type == "password") 6 else 4
        if (secret.length < min) return s.fail(when (s.type) {
            "password" -> t("lock.atLeastSix", "At least six characters.")
            "pattern" -> t("lock.connectFour", "Connect at least four dots.")
            else -> t("lock.fourDigits", "At least four digits.")
        })
        when (s.stage) {
            "new1" -> {
                s.firstSecret = secret; s.clearTick++
                s.title = t("lock.onceMore", "Once more"); s.say(t("lock.sure", "Just to be sure."))
                s.stage = "new2"
            }
            "new2" -> {
                if (secret != s.firstSecret) { s.stage = "new1"; s.firstSecret = ""; return s.fail(t("lock.mismatch", "They didn't match. Start again.")) }
                s.busy = true; s.say(t("lock.checking", "Checking…"))
                withContext(Dispatchers.Default) { Crypto.setLock(secret, s.type) }
                DB["lockType"] = s.type
                Bio.disable()   // the old copy held the previous lock
                Ledger.persist()
                lockChanged()
                Ledger.bump()
                s.busy = false
                s.finish(true)
            }
            "confirmchange", "bioconfirm", "privconfirm" -> {
                s.busy = true; s.say(t("lock.checking", "Checking…"))
                val ok = verifySecret(secret)
                s.busy = false
                if (!ok) return s.fail(wrongMsg(s.type))
                if (s.stage == "confirmchange") {
                    val next = s.pendingType ?: Crypto.type
                    val (ti, m) = setupCopy(next, true)
                    s.type = next; s.stage = "new1"; s.title = ti; s.say(m); s.clearTick++
                } else s.finish(true)
            }
            "sealedfile" -> {
                s.busy = true; s.say(t("lock.checking", "Checking…"))
                val ok = runCatching { s.check?.invoke(secret) == true }.getOrDefault(false)
                s.busy = false
                if (ok) s.finish(true) else s.fail(wrongMsg(s.type))
            }
            else -> {   // 'open'
                s.busy = true; s.say(t("lock.checking", "Checking…"))
                val ok = Ledger.unlock(secret)
                s.busy = false
                if (ok) s.finish(true) else s.fail(wrongMsg(s.type))
            }
        }
    }

    /** tryBioUnlock(): the fingerprint opens the sealed ledger with the stored key; anything else falls back to the lock. */
    suspend fun tryBio(s: LockSession, auto: Boolean) {
        val act = MainActivity.current ?: return
        val env = s.env ?: Ledger.sealed ?: return
        if (!Bio.on() || s.busy) return
        val msg0 = s.msg
        s.say(t("lock.checking", "Checking…"))
        val r = Bio.open(act, t("lock.unlockBalance", "Unlock Tally"))
        val c = r.getOrNull()
        if (c != null) {
            if (c.s("s") == env.s("s") && !(env.s("t").ifEmpty { "pin" } == "pin" && n(env["l"]) == 0.0)) {
                s.busy = true
                val ok = runCatching { Ledger.unlockWithKey(Crypto.unb64(c.s("k"))) }.getOrDefault(false)
                s.busy = false
                if (ok) { s.finish(true); return }
            }
            // the lock changed since (or a restore): the fingerprint copy no longer fits
            s.fail(t("lock.bioFail", "Fingerprint did not work — enter your {what} instead.", "what" to typeNoun(s.type)))
            return
        }
        val name = (r.exceptionOrNull() as? Bio.Failure)?.name
        if (name == "NotAllowedError") {
            if (auto) s.say(msg0)
            else s.say(t("lock.bioRetry", "Enter your {what}, or tap Use fingerprint to try again.", "what" to typeNoun(s.type)))
        } else s.fail(t("lock.bioFail", "Fingerprint did not work — enter your {what} instead.", "what" to typeNoun(s.type)))
    }
}

/** The vault gate and private items: fingerprint first, then the lock. With no lock it just says yes. */
suspend fun confirmItsYou(why: String? = null, key: String? = null): Boolean = LockFlow.confirm(why, key)

/** The sealed ledger at start or after the re-lock delay (Ledger.Phase.LOCKED). */
@Composable
fun LockScreen() {
    val env = Ledger.sealed
    val session = remember(env) {
        val type = env?.s("t")?.ifEmpty { "pin" } ?: "pin"
        val (ti, m) = lockCopy(type)
        LockSession("open", type, ti, m, false, env = env)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session) {
        if (Bio.on() && Bio.autoOn()) { delay(250); LockFlow.tryBio(session, auto = true) }
    }
    LockBody(session, onSubmit = { scope.launch { LockFlow.submit(session, it) } }, onBio = { scope.launch { LockFlow.tryBio(session, auto = false) } })
}

/** Hosts setup, change and confirm screens over the app (put once at the root, after the app). */
@Composable
fun LockOverlayHost() {
    val s = LockFlow.session.value ?: return
    val scope = rememberCoroutineScope()
    BackHandler { s.finish(false) }
    Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        LockBody(s, onSubmit = { scope.launch { LockFlow.submit(s, it) } }, onBio = null, onSkip = { s.finish(false) })
    }
}

@Composable
private fun LockBody(s: LockSession, onSubmit: (String) -> Unit, onBio: (() -> Unit)?, onSkip: (() -> Unit)? = null) {
    val haptics = rememberHaptics()
    val calm = reduceMotion()
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(s.shake) {
        if (s.shake == 0) return@LaunchedEffect
        haptics.reject()
        if (!calm) { shakeX.snapTo(22f); shakeX.animateTo(0f, Motion.bouncy()) }    // after the gesture: a little overshoot is allowed
    }
    val c = Apple.colors
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = Space.xxl, vertical = Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Space.xxl))
            Icon(Icons.Rounded.Lock, null, tint = c.secondaryLabel, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(Space.m))
            Column(Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.title, style = Apple.type.title3, color = c.label, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    s.msg.ifEmpty { " " }, style = Apple.type.subheadline, textAlign = TextAlign.Center,
                    color = if (s.failed) LocalSemantic.current.negative else c.secondaryLabel,
                    modifier = Modifier.heightIn(min = 40.dp),
                )
                Spacer(Modifier.height(Space.m))
                key(s.type) {
                    when (s.type) {
                        "pattern" -> PatternGrid(s, onSubmit)
                        "password" -> PasswordBox(s, onSubmit)
                        else -> PinPad(s, onSubmit)
                    }
                }
            }
            Spacer(Modifier.height(Space.l))
            if (onBio != null && s.stage == "open" && Bio.on()) {
                PlainButton(t("lock.useFingerprint", "Use fingerprint"), onBio, enabled = !s.busy, icon = Icons.Rounded.Fingerprint, bold = true)
            }
            if (s.skippable && onSkip != null) {
                PlainButton(if (s.stage == "new1" && s.pendingType == null) t("lock.skip", "Skip for now") else t("lock.cancel", "Cancel"), onSkip)
            }
        }
    }
}

@Composable
private fun PinPad(s: LockSession, onSubmit: (String) -> Unit) {
    var entry by remember { mutableStateOf("") }
    LaunchedEffect(s.clearTick) { entry = "" }
    val haptics = rememberHaptics()
    val c = Apple.colors
    val shown = if (s.autoLen > 0) s.autoLen else maxOf(4, entry.length)
    Row(Modifier.height(24.dp).semantics { contentDescription = "${entry.length}" }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(shown) { i ->
            val on = i < entry.length
            val fill by animateColorAsState(if (on) c.label else Color.Transparent, Motion.control(), label = "dot")
            Box(Modifier.size(13.dp).clip(CircleShape).background(fill).border(1.25.dp, c.label, CircleShape))
        }
    }
    Spacer(Modifier.height(Space.xxl))
    val okLabel = if (s.stage == "new1" || s.stage == "new2") t("lock.set", "Set") else t("lock.ok", "OK")
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("x", "0", "ok"))
    fun press(k: String) {
        haptics.tick()
        when {
            k == "x" -> entry = entry.dropLast(1)
            k == "ok" -> onSubmit(entry)
            entry.length < 8 -> {
                entry += k
                val want = s.autoLen
                if (s.stage in setOf("open", "sealedfile", "privconfirm") && want > 0 && entry.length == want) onSubmit(entry)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { k ->
                    val alt = k == "x" || k == "ok"
                    Box(
                        Modifier.size(76.dp).clip(CircleShape)
                            .background(if (alt) Color.Transparent else c.fill)
                            .pressable(enabled = !s.busy, scaleTo = 0.9f, highlight = false, onLongClick = if (k == "x") ({ entry = "" }) else null) { press(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            "x" -> Icon(Icons.AutoMirrored.Rounded.Backspace, t("lock.backspaceHoldToClear", "Backspace — hold to clear"), tint = c.label)
                            "ok" -> Text(okLabel, style = Apple.type.headline, color = c.accent, maxLines = 1)
                            else -> Text(k, style = Apple.type.largeTitle.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"), color = c.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternGrid(s: LockSession, onSubmit: (String) -> Unit) {
    val path = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(s.clearTick) { path.clear(); finger = null }
    val dot = Apple.colors.tertiaryLabel
    val on = Apple.colors.accent
    val haptics = rememberHaptics()
    BoxWithConstraints(Modifier.size(264.dp)) {
        val sizePx = constraints.maxWidth.toFloat()
        fun center(i: Int) = Offset(sizePx / 6f * (1 + 2 * (i % 3)), sizePx / 6f * (1 + 2 * (i / 3)))
        fun hit(p: Offset): Int { for (i in 0 until 9) { val c = center(i); if (hypot(c.x - p.x, c.y - p.y) < sizePx / 9f) return i }; return -1 }
        Canvas(Modifier.fillMaxSize().pointerInput(s.clearTick, s.busy) {
            if (s.busy) return@pointerInput
            detectDragGestures(
                onDragStart = { p -> path.clear(); val i = hit(p); if (i >= 0) { path.add(i); haptics.tick() }; finger = p },
                onDrag = { ch, _ -> val p = ch.position; finger = p; val i = hit(p); if (i >= 0 && i !in path) { path.add(i); haptics.tick() } },
                onDragEnd = {
                    finger = null
                    if (path.isNotEmpty()) {
                        if (path.size < 4) s.fail(t("lock.connectFour", "Connect at least four dots.")) else onSubmit(path.joinToString("-"))
                    }
                },
                onDragCancel = { finger = null; path.clear() },
            )
        }) {
            val stroke = 5.dp.toPx()
            for (k in 1 until path.size) drawLine(on.copy(alpha = 0.7f), center(path[k - 1]), center(path[k]), stroke, StrokeCap.Round)
            val last = path.lastOrNull(); val f = finger
            if (last != null && f != null) drawLine(on.copy(alpha = 0.4f), center(last), f, stroke, StrokeCap.Round)
            for (i in 0 until 9) {
                val lit = i in path
                drawCircle(if (lit) on else dot, radius = (if (lit) 10.dp else 7.dp).toPx(), center = center(i))
                if (lit) drawCircle(on.copy(alpha = 0.18f), radius = 22.dp.toPx(), center = center(i))
            }
        }
    }
}

@Composable
private fun PasswordBox(s: LockSession, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(s.clearTick) { text = "" }
    val fr = remember { FocusRequester() }
    val c = Apple.colors
    LaunchedEffect(Unit) { if (!(s.stage == "open" && Bio.on() && Bio.autoOn())) { delay(150); runCatching { fr.requestFocus() } } }
    FieldFrame(t("lock.password", "Password"), modifier = Modifier.widthIn(max = 360.dp)) {
        BasicTextField(
            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.weight(1f).focusRequester(fr),
            textStyle = Apple.type.body.copy(color = c.label), cursorBrush = SolidColor(c.accent),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onSubmit(text) }),
        )
    }
    Spacer(Modifier.height(Space.l))
    PrimaryButton(t("lock.continue", "Continue"), { onSubmit(text) }, Modifier.widthIn(min = 160.dp), enabled = !s.busy)
}
