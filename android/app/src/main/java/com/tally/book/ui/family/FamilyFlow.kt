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
import com.tally.book.model.setMyName
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
        if (Sync.hasFamily()) {
            Dialogs.tell(t("join.already", "This phone is already in a family. Leave it first if you want to join another."))
            return false
        }
        val ok = Dialogs.ask(joinQuestion(invite), t("join.ok", "Join"))
        if (!ok) return false
        busy.value = t("join.busy", "Joining…")
        val result = try { Sync.detached { Sync.engine.joinFamily(invite) } } finally { busy.value = null }
        return finish(result, joining = true)
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
        return finish(result, joining = false)
    }

    /**
     * The first sync is part of joining and creating, and can fail after the phone has already entered the family (no internet):
     * then the person is in, goes home, and the status line says it could not sync. If the phone did not get in, say why.
     */
    private suspend fun finish(result: SyncResult, joining: Boolean): Boolean {
        val inFamily = Sync.hasFamily()
        val text = SyncText.message(result, joining)
        if (inFamily) {
            AppNav.home()
            if (text != null) Dialogs.snack(text)
        } else if (text != null) Dialogs.tell(text)
        return inFamily
    }

    suspend fun leave() {
        val ok = Dialogs.ask(
            t("leave.ask", "Leave this family?\n\nThe loans and payments stay on this phone. You will stop getting new ones from the family, and they will stop getting yours."),
            t("leave.ok", "Leave"), danger = true,
        )
        if (!ok) return
        if (!Sync.detached { Sync.engine.leaveFamily() }) Dialogs.tell(SyncText.errorMessage(com.tally.book.sync.SyncError.Storage))
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
