package com.tally.book.ui.family

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.tally.book.BuildConfig
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.data.Crypto
import com.tally.book.data.Ledger
import com.tally.book.data.lockChanged
import com.tally.book.model.DB
import com.tally.book.model.memberName
import com.tally.book.model.myId
import com.tally.book.sync.Invite
import com.tally.book.sync.InviteResult
import com.tally.book.sync.Sync
import com.tally.book.ui.AppNav
import com.tally.book.ui.ScreenPadding
import com.tally.book.ui.SubScreen
import com.tally.book.ui.components.*
import com.tally.book.ui.loans.ROUTE_FAMILY
import com.tally.book.ui.lock.Bio
import com.tally.book.ui.lock.LockFlow
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import kotlinx.coroutines.launch

/*
 * The Family page (docs/DESIGN.md, Screens > Family): start sharing or join, the members, invite someone, sync now, leave;
 * then, for everyone, my name, language, app lock, hiding amounts and the version.
 * For the parents: plain words, big targets, one decision per step. "Family", "share" and "join" only.
 */

const val ROUTE_JOIN = "family/join"
const val ROUTE_SCAN = "family/scan"

/** Registers the Family page, Join and the scanner. Called from MainScaffold. */
fun NavGraphBuilder.familyGraph() {
    composable(ROUTE_FAMILY) { FamilyScreen() }
    composable(ROUTE_JOIN) { JoinScreen() }
    composable(ROUTE_SCAN) { ScanScreen() }
}

@Composable
fun FamilyScreen() {
    val rev = rememberLedger()
    val view = remember(rev) { Sync.membership() }
    var starting by remember { mutableStateOf(false) }
    var inviting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // an admin opening this page sees the people waiting right away
    LaunchedEffect(view.inFamily && view.iAmAdmin) { if (view.inFamily && view.iAmAdmin) Sync.syncInBackground() }

    SubScreen(t("family.title", "Family")) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (view.inFamily) JoinRequestCards()
            when {
                view.gone != null -> GoneCard(view.gone)
                view.oldFamily -> OldFamilyCard(onSetUp = { starting = true })
                view.joining != null -> WaitingCard(view.joining)
                view.inFamily -> InFamily(view, onInvite = { inviting = true })
                else -> NoFamily(onStart = { starting = true })
            }
            MyNameGroup()
            LanguageGroup()
            LockGroup()
            BackupGroup()
            PrivacyGroup()
            AGroup { item { ARow(t("app.version", "Version"), value = BuildConfig.VERSION_NAME) } }
        }
    }
    if (starting) StartSheet(
        initial = view.familyName.takeIf { view.oldFamily }.orEmpty(), onDismiss = { starting = false },
        onStart = { name -> scope.launch { if (view.oldFamily) FamilyFlow.setUp(name) else FamilyFlow.create(name) } },
    )
    if (inviting) InviteSheet(onDismiss = { inviting = false })
}

// ---------- no family yet ----------

@Composable
private fun NoFamily(onStart: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(t("family.none.title", "Share your loans with your family"), style = Apple.type.title2, color = Apple.colors.label)
        Text(
            t("family.none.body", "Everyone in your family sees the same loans and payments on their own phone. A loan you keep private stays on this phone."),
            style = Apple.type.body, color = Apple.colors.secondaryLabel,
        )
        Text(t("family.safe", "Only your family can read what you share."), style = Apple.type.callout, color = Apple.colors.secondaryLabel)
    }
    PrimaryButton(t("family.start", "Start sharing"), onStart, Modifier.fillMaxWidth().heightIn(min = 56.dp))
    SecondaryButton(t("family.join", "Join my family"), { AppNav.go(ROUTE_JOIN) }, Modifier.fillMaxWidth().heightIn(min = 56.dp))
}

@Composable
private fun StartSheet(initial: String, onDismiss: () -> Unit, onStart: (String) -> Unit) {
    // no default for a new family: whoever starts it types its one name, and everyone who joins sees that same name
    var name by remember { mutableStateOf(initial) }
    val clean = Invite.cleanName(name)
    EditSheet(
        t("family.name.ask", "Give your family a name"), onDismiss,
        onSave = { clean?.let { onDismiss(); onStart(it) } },
        saveLabel = t("family.name.ok", "Start"), saveEnabled = clean != null,
    ) {
        TextInput(
            name, { name = it }, t("family.name.label", "Family name"),
            placeholder = t("family.name.placeholder", "e.g. Our family"),
            supporting = t("family.name.help", "Your family will see this name when they join."),
        )
    }
}

// ---------- join ----------

@Composable
fun JoinScreen() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = rememberHaptics()
    var code by remember { mutableStateOf("") }      // not rememberSaveable: the invite is never written to saved state
    var problem by remember { mutableStateOf<String?>(null) }
    val invite = remember(code) { Invite.find(code) }

    SubScreen(t("join.title", "Join my family")) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                t("join.help", "Ask someone in your family to open Tally, then Family, then Invite someone. Scan the code they show you, or paste the link they sent."),
                style = Apple.type.body, color = Apple.colors.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (invite == null) {
                PrimaryButton(t("join.scan", "Scan the code"), { AppNav.go(ROUTE_SCAN) }, Modifier.fillMaxWidth().heightIn(min = 56.dp))
            } else {
                AGroup {
                    item {
                        ARow(
                            invite.name, icon = Icons.Rounded.Group,
                            sub = invite.inviter?.let { t("join.invitedBy", "{inviter} is inviting you", "inviter" to it) } ?: t("join.found", "A family is waiting for you"),
                        )
                    }
                }
                PrimaryButton(t("join.ok", "Join"), { scope.launch { FamilyFlow.join(invite) } }, Modifier.fillMaxWidth().heightIn(min = 56.dp))
            }
            TextInput(
                code, { code = it; problem = null }, t("join.paste.label", "Or paste the code"),
                supporting = problem ?: (if (code.isNotBlank() && invite == null) (if (Invite.isOld(code)) SyncText.oldCode() else t("scan.notTally", "This isn't a Tally family code")) else null),
            )
            SecondaryButton(t("join.paste", "Paste"), {
                val text = clipboard.getText()?.text.orEmpty()
                when {
                    text.isBlank() -> { haptics.reject(); problem = t("join.paste.empty", "Nothing to paste yet. Copy the code first.") }
                    Invite.find(text) == null -> { haptics.reject(); code = ""; problem = if (Invite.isOld(text)) SyncText.oldCode() else t("scan.notTally", "This isn't a Tally family code") }
                    else -> { code = text; problem = null; haptics.tick() }
                }
            }, Modifier.fillMaxWidth().heightIn(min = 52.dp))
            if (invite != null) SecondaryButton(t("join.scan", "Scan the code"), { AppNav.go(ROUTE_SCAN) }, Modifier.fillMaxWidth().heightIn(min = 52.dp))
        }
    }
}

// ---------- for everyone ----------

@Composable
private fun MyNameGroup() {
    val rev = rememberLedger()
    val name = remember(rev) { FamilyFlow.myName() }
    var editing by remember { mutableStateOf(false) }
    AGroup(title = t("me.title", "My name"), footer = t("me.help", "This is the name your family sees next to what you add.")) {
        item { ARow(name.ifBlank { t("me.add", "Add your name") }, icon = Icons.Rounded.Edit, chevron = true, onClick = { editing = true }) }
    }
    if (editing) {
        var draft by remember { mutableStateOf(name) }
        EditSheet(
            t("me.title", "My name"), { editing = false },
            onSave = { FamilyFlow.rename(draft); editing = false },
            saveEnabled = draft.isNotBlank() && draft.trim() != name,
        ) { TextInput(draft, { draft = it }, t("me.label", "Your name")) }
    }
}

@Composable
private fun LanguageGroup() {
    val rev = rememberLedger()
    val lang = remember(rev) { I18n.lang }
    AGroup(title = t("lang.title", "Language")) {
        listOf("en" to "English", "te" to "తెలుగు", "hi" to "हिन्दी").forEach { (code, label) ->
            item {
                ARow(
                    label, icon = Icons.Rounded.Translate,
                    onClick = { DevicePrefs.set("tally-lang", code); I18n.setLanguage(code); Ledger.bump() },
                    trailing = if (lang == code) ({ Icon(Icons.Rounded.Check, t("lang.selected", "Selected"), tint = Apple.colors.accent) }) else null,
                )
            }
        }
    }
}

@Composable
private fun LockGroup() {
    val rev = rememberLedger()
    val scope = rememberCoroutineScope()
    val on = remember(rev) { Crypto.locked }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bioAvail = remember { Bio.available(ctx) }
    val bioOn = remember(rev) { Bio.on() }
    AGroup(
        title = t("applock.title", "App lock"),
        footer = if (on) t("applock.help.on", "Tally asks for your PIN when you open it.")
        else t("applock.help.off", "Locks Tally with a PIN. Do not forget it: it cannot be found again."),
    ) {
        item {
            ARow(
                if (on) t("applock.change", "Change the PIN") else t("applock.turnOn", "Turn on the lock"),
                icon = Icons.Rounded.Lock, chevron = true,
                onClick = { scope.launch { LockFlow.setOrChange(if (on) Crypto.type else "pin") } },
            )
        }
        if (on) {
            if (bioAvail) item {
                SwitchRow(
                    t("applock.fingerprint", "Unlock with fingerprint"), bioOn,
                    { want ->
                        scope.launch { if (want) LockFlow.enableBio() else { Bio.disable(); Ledger.bump() } }
                    },
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    icon = Icons.Rounded.Fingerprint,
                )
            } else item { NoteRow(t("applock.noFingerprint", "To unlock with a fingerprint, first add one in your phone's settings.")) }
        }
        if (on) item {
            ARow(
                t("applock.turnOff", "Turn off the lock"), icon = Icons.Rounded.LockOpen, tint = LocalSemantic.current.negative, titleColor = LocalSemantic.current.negative,
                onClick = {
                    scope.launch {
                        if (Dialogs.ask(t("applock.off.ask", "Turn off the lock?\n\nAnyone who opens your phone will be able to see your loans."), t("applock.off.ok", "Turn off"), danger = true)) {
                            Crypto.clearLock(); Bio.disable(); Ledger.persist(); lockChanged(); Ledger.bump()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun PrivacyGroup() {
    val rev = rememberLedger()
    val hidden = remember(rev) { DB.b("hideAmounts") }
    AGroup(footer = t("hide.help", "Shows dots instead of amounts, in case someone is looking.")) {
        item { SwitchRow(t("hide.title", "Hide amounts"), hidden, { v -> Ledger.edit { DB["hideAmounts"] = v } }, icon = Icons.Rounded.VisibilityOff) }
    }
}

// ---------- a link that opened the app ----------

/**
 * An invite link that opened the app (pendingInvite) is taken here once the app is unlocked and the phone has a name, and again
 * whenever the app comes back to the front: "Join <name>?" with the name from the invite, then join. Cleared either way.
 */
@Composable
fun InviteIntake() {
    val rev = rememberLedger()
    val named = remember(rev) { DB.o("me")?.s("name").orEmpty().isNotBlank() }
    val scope = rememberCoroutineScope()
    fun take() {
        if (!named) return
        // the join-request notification: open the Family page (the requests are shown there and on the home card)
        if (com.tally.book.pendingOpen == "family") { com.tally.book.pendingOpen = null; FamilyFlow.openFamily(); Sync.syncInBackground() }
        val raw = com.tally.book.pendingInvite ?: return
        com.tally.book.pendingInvite = null
        scope.launch {
            val invite = Invite.find(raw)
            if (invite == null) Dialogs.tell(if (Invite.isOld(raw)) SyncText.oldCode() else t("sync.notCode", "This is not a Tally family code.")) else FamilyFlow.join(invite)
        }
    }
    LaunchedEffect(named) { take() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { take() }
}
