package com.tally.book.ui.family

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.DB
import com.tally.book.model.memberName
import com.tally.book.model.setMyName
import com.tally.book.sync.FamilyMember
import com.tally.book.sync.JoinRequest
import com.tally.book.sync.SyncError
import com.tally.book.sync.Invite
import com.tally.book.sync.Sync
import com.tally.book.sync.SyncResult
import com.tally.book.ui.AppNav
import com.tally.book.ui.components.Dialogs
import com.tally.book.ui.components.reduceMotion
import com.tally.book.ui.loans.ROUTE_FAMILY
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.AppleShapes

/*
 * What happens when the person starts sharing, joins, leaves or syncs by hand: one step each, always ending with a plain
 * sentence if it did not work. The engine does the work in the app's own scope (Sync.detached), so closing the screen
 * does not stop it halfway. Never put the invite text in a message: only its family name.
 */
object FamilyFlow {
    /** What the full-screen "please wait" says, or null. */
    val busy = mutableStateOf<String?>(null)

    /**
     * Asks "<Inviter> is inviting you to <Family>. Join?" (or "Join <Family>?" for an invite that does not say who sent it; both names
     * come from the invite, before anything is saved), then joins. True when this phone is now in the family.
     */
    suspend fun join(invite: Invite): Boolean {
        val view = Sync.membership()
        if (view.inFamily || view.joining != null) {
            Dialogs.tell(t("join.already", "This phone is already in a family. Leave it first if you want to join another."))
            return false
        }
        val ok = Dialogs.ask(joinQuestion(invite), t("join.ok", "Join"))
        if (!ok) return false
        busy.value = t("join.busy", "Joining…")
        val result = try {
            Sync.detached {
                // a family from 0.1.0/0.2.0 is dead; the person chose another family, so it is forgotten first (the loans stay)
                if (view.oldFamily) Sync.engine.leaveFamily()
                Sync.engine.requestJoin(invite)
            }
        } finally { busy.value = null }
        // the request is with the family now; an admin lets this phone in (the next sync enters the family).
        // The Family page then says "Waiting for an admin to let you in", with Check now and Cancel request.
        if (result == SyncResult.JoinPending) {
            AppNav.home()
            openFamily()
            return true
        }
        val text = SyncText.message(result, joining = true)
        if (text != null) Dialogs.tell(text)
        return false
    }

    /** The join question: who invites (when the invite says) and the family's name, then what joining means. */
    fun joinQuestion(invite: Invite): String {
        val body = t("join.body", "You will see this family's loans and payments, and they will see the ones you share.")
        val head = invite.inviter?.let { t("join.askFrom", "{inviter} is inviting you to {name}. Join?", "inviter" to it, "name" to invite.name) }
            ?: t("join.askName", "Join {name}?", "name" to invite.name)
        return head + "\n\n" + body
    }

    /** Makes the family and shares this phone's loans. True when this phone is now in a family. */
    suspend fun create(name: String): Boolean {
        busy.value = t("create.busy", "Starting…")
        val result = try { Sync.detached { Sync.engine.createFamily(name) } } finally { busy.value = null }
        return finish(result)
    }

    /** Sharing from an old family (0.1.0/0.2.0) again: the dead family is forgotten (the loans stay), then a new one is made. */
    suspend fun setUp(name: String): Boolean {
        busy.value = t("create.busy", "Starting…")
        val result = try {
            Sync.detached {
                if (Sync.membership().oldFamily) Sync.engine.leaveFamily()
                Sync.engine.createFamily(name)
            }
        } finally { busy.value = null }
        return finish(result)
    }

    /** Forgets an old family (0.1.0/0.2.0), then opens Join. */
    suspend fun joinFromOld() {
        if (Sync.membership().oldFamily) Sync.detached { Sync.engine.leaveFamily() }
        AppNav.go(ROUTE_JOIN)
    }

    /**
     * The first sync is part of joining and creating, and can fail after the phone has already entered the family (no internet):
     * then the person is in, goes home, and the status line says it could not sync. If the phone did not get in, say why.
     */
    private suspend fun finish(result: SyncResult): Boolean {
        val inFamily = Sync.hasFamily()
        val text = SyncText.message(result)
        if (inFamily) {
            AppNav.home()
            if (text != null) Dialogs.snack(text)
        } else if (text != null) Dialogs.tell(text)
        return inFamily
    }

    suspend fun leave() {
        val ok = Dialogs.ask(MemberText.leaveQuestion(Sync.membership()), t("leave.ok", "Leave"), danger = true)
        if (!ok) return
        if (!Sync.detached { Sync.engine.leaveFamily() }) Dialogs.tell(SyncText.errorMessage(SyncError.Storage))
    }

    // ---------- the joiner waits ----------

    /** "Check now": asks whether an admin has decided. The Family page changes by itself when they have. */
    suspend fun checkJoin() {
        val name = Sync.membership().joining?.familyName.orEmpty()
        when (val r = Sync.detached { Sync.engine.checkJoin() }) {
            SyncResult.JoinPending -> Dialogs.snack(t("join.notYet", "Not yet. An admin still has to allow it."))
            SyncResult.JoinDenied, SyncResult.Removed -> {}      // the Family page explains it (Sync.membership().gone)
            else -> {
                if (Sync.membership().inFamily) Dialogs.snack(t("join.in", "You are in {name}.", "name" to name))
                else SyncText.message(r, joining = true)?.let { Dialogs.snack(it) }
            }
        }
    }

    /** "Cancel request": withdraws the join request after one question. */
    suspend fun cancelJoin() {
        val name = Sync.membership().joining?.familyName.orEmpty().ifBlank { t("family.the", "the family") }
        val ok = Dialogs.ask(
            t("join.cancel.ask", "Stop asking to join {name}?\n\nYou can ask again later with a new code.", "name" to name),
            t("join.cancel.ok", "Stop asking"),
        )
        if (!ok) return
        if (!Sync.detached { Sync.engine.cancelJoin() }) Dialogs.tell(SyncText.errorMessage(SyncError.Storage))
    }

    /** "OK" on the one-time "you were removed / not let in" card. */
    suspend fun clearGone() { Sync.detached { Sync.engine.clearGone() } }

    // ---------- admin: join requests ----------

    /** Allow: the person's phone is given the family and joins on its next check. */
    suspend fun allow(r: JoinRequest) {
        val result = Sync.detached { Sync.engine.approve(r.id) }
        if (decided(r.id, result)) Dialogs.snack(t("req.allowed", "{name} is allowed in. Their phone joins in a moment.", "name" to r.name))
    }

    /** Don't allow: asks once, then tells the asking phone it was not let in. */
    suspend fun deny(r: JoinRequest) {
        val ok = Dialogs.ask(
            t("req.deny.ask", "Don't let {name} in?\n\n{name} will be told they were not let in. Nothing is shared with them.", "name" to r.name),
            t("req.deny.ok", "Don't allow"),
        )
        if (!ok) return
        val result = Sync.detached { Sync.engine.deny(r.id) }
        if (decided(r.id, result)) Dialogs.snack(t("req.denied", "{name} was not let in.", "name" to r.name))
    }

    /** True when the relay took the decision (the request left the list), even if the sync after it failed; otherwise tells why not. */
    private fun decided(id: String, result: SyncResult): Boolean {
        if (Sync.engine.requests.value.none { it.id == id }) return true
        val text = if (result == SyncResult.Busy) t("req.busy", "One moment, then try again.") else SyncText.message(result)
        if (text != null) Dialogs.snack(text)
        return false
    }

    // ---------- members and invites ----------

    suspend fun makeAdmin(m: FamilyMember, admin: Boolean) {
        val name = shownName(m)
        val result = Sync.detached { Sync.engine.setAdmin(m.id, admin) }
        val text = if (result is SyncResult.Done) {
            if (admin) t("member.nowAdmin", "{name} is now an admin.", "name" to name) else t("member.noAdmin", "{name} is no longer an admin.", "name" to name)
        } else report(result)
        if (text != null) Dialogs.snack(text)
    }

    suspend fun remove(m: FamilyMember) {
        val name = shownName(m)
        if (!Dialogs.ask(MemberText.removeQuestion(name), t("member.remove.ok", "Remove"), danger = true)) return
        busy.value = t("member.removing", "Removing…")
        val result = try { Sync.detached { Sync.engine.removeMember(m.id) } } finally { busy.value = null }
        val text = if (result is SyncResult.Done) t("member.removed", "{name} was removed from the family.", "name" to name) else report(result)
        if (text != null) Dialogs.snack(text)
    }

    suspend fun cancelInvite(id: String) {
        val ok = Dialogs.ask(t("invite.cancel.ask", "Stop this invite?\n\nThe code will no longer work."), t("invite.cancel.ok", "Stop invite"), danger = true)
        if (!ok) return
        val result = Sync.detached { Sync.engine.cancelInvite(id) }
        val text = if (result is SyncResult.Done) t("invite.cancelled", "The invite was stopped.") else report(result)
        if (text != null) Dialogs.snack(text)
    }

    fun shownName(m: FamilyMember): String = m.name.ifBlank { memberName(m.id) }

    /** A refusal in plain words (the relay's rules: SyncText has the sentences), or a nudge when another change is running. */
    private fun report(result: SyncResult): String? =
        if (result == SyncResult.Busy) t("req.busy", "One moment, then try again.") else SyncText.message(result)

    // ---------- the notification question ----------

    /**
     * Whether to ask, once ever, about the join-request notification (the Android 13+ permission follows only after a yes).
     * Asked for an admin, when the first invite is made; a "no" is final and nothing nags afterwards.
     */
    suspend fun wantsNotices(): Boolean {
        if (DevicePrefs.get("tally-notice-asked") != null) return false
        DevicePrefs.set("tally-notice-asked", "1")
        return Dialogs.ask(
            t("notice.ask", "Do you want to know when someone asks to join?\n\nTally will show a notification. It never shows a name or an amount."),
            t("notice.ok", "Yes, tell me"),
        )
    }

    /** Sync now, from the Family page: tells only if it failed. */
    suspend fun syncNow() {
        SyncText.message(Sync.detached { Sync.engine.sync() })?.let { Dialogs.snack(it) }
    }

    /** Saves the name; with a family, the others see the new name on their next sync. */
    fun rename(name: String) { Ledger.edit { setMyName(name) } }

    fun myName(): String = DB.o("me")?.get("name") as? String ?: ""

    /** Opens the Family page. */
    fun openFamily() = AppNav.go(ROUTE_FAMILY)
}

/** A calm full-screen "please wait" over everything while a family is being started or joined. Blocks taps. */
@Composable
fun FamilyBusy() {
    val text by FamilyFlow.busy
    val calm = reduceMotion()
    AnimatedVisibility(text != null, enter = fadeIn(tween(if (calm) 200 else 120)), exit = fadeOut(tween(200))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            var shown by remember { mutableStateOf("") }
            text?.let { shown = it }
            Column(
                Modifier.background(Apple.colors.elevated, AppleShapes.group).padding(horizontal = 32.dp, vertical = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(color = Apple.colors.accent, strokeWidth = 3.dp)
                Text(shown, style = Apple.type.body, color = Apple.colors.label, textAlign = TextAlign.Center)
            }
        }
    }
}
