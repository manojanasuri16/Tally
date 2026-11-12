package com.tally.book.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tally.book.core.Clock
import com.tally.book.core.t
import com.tally.book.sync.FamilyGone
import com.tally.book.sync.PendingJoin
import com.tally.book.sync.Sync
import com.tally.book.ui.AppNav
import com.tally.book.ui.components.PlainButton
import com.tally.book.ui.components.PrimaryButton
import com.tally.book.ui.components.SecondaryButton
import com.tally.book.ui.theme.Apple
import kotlinx.coroutines.launch

/* The Family page when this phone is not simply in a family: waiting to be let in, told once it is out, or on an old family. */

/** The joiner waits for an admin: "Check now" and "Cancel request". The page changes by itself when they decide. */
@Composable
fun WaitingCard(j: PendingJoin) {
    val scope = rememberCoroutineScope()
    val state by Sync.engine.state.collectAsStateWithLifecycle()
    val fam = j.familyName.ifBlank { t("family.the", "the family") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (j.back) t("wait.back.title", "Waiting for an admin to let this phone in as you") else t("wait.title", "Waiting for an admin to let you in"), style = Apple.type.title2, color = Apple.colors.label)
        val body = j.inviter?.takeIf { it.isNotBlank() }
            ?.let { t("wait.body.from", "{inviter} invited you to {name}. This phone joins by itself once an admin allows it.", "inviter" to it, "name" to fam) }
            ?: t("wait.body", "You asked to join {name}. This phone joins by itself once an admin allows it.", "name" to fam)
        Text(body, style = Apple.type.body, color = Apple.colors.secondaryLabel)
        if (j.since > 0) Text(t("wait.asked", "Asked {when}", "when" to SyncText.ago(j.since, maxOf(Clock.millis(), j.since))), style = Apple.type.callout, color = Apple.colors.secondaryLabel)
        if (j.back && j.code.isNotEmpty()) {
            // the admin sees the same code and is asked to compare it with you before letting this phone in as you
            Text(t("wait.back.code", "This phone's code: {code}. An admin may ask you to read it out.", "code" to j.code), style = Apple.type.headline, color = Apple.colors.label)
        }
    }
    PrimaryButton(
        t("wait.check", "Check now"), { if (!state.running) scope.launch { FamilyFlow.checkJoin() } },
        Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.running,
    )
    SecondaryButton(t("wait.cancel", "Cancel request"), { scope.launch { FamilyFlow.cancelJoin() } }, Modifier.fillMaxWidth().heightIn(min = 56.dp))
}

/** Said once: removed, not let in, or the request ran out. OK clears it and the page goes back to the start. */
@Composable
fun GoneCard(g: FamilyGone) {
    val scope = rememberCoroutineScope()
    val fam = g.familyName.ifBlank { t("family.the", "the family") }
    val text = when (g.why) {
        "removed" -> t("gone.removed", "You were removed from {name}. Your entries are still on this phone.", "name" to fam)
        "replaced" -> t("gone.replaced", "This phone was replaced by a new phone. It is no longer in {name}. Your entries are still on this phone.", "name" to fam)
        "denied" -> t("gone.denied", "An admin did not let you into {name}.", "name" to fam)
        "ended" -> t("gone.ended", "Sharing with {name} stopped: nobody else joined it for 60 days. Your entries are still on this phone. You can start sharing again at any time.", "name" to fam)
        else -> t("gone.expired", "Your request to join {name} ran out. Ask for a new code to try again.", "name" to fam)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = Apple.type.title3, color = Apple.colors.label)
    }
    PrimaryButton(t("dlg.ok", "OK"), { scope.launch { FamilyFlow.clearGone() } }, Modifier.fillMaxWidth().heightIn(min = 56.dp))
}

/** A family from 0.1.0/0.2.0: sharing starts again, the loans stay. */
@Composable
fun OldFamilyCard(onSetUp: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SyncText.oldFamilyText(), style = Apple.type.title3, color = Apple.colors.label)
    }
    PrimaryButton(t("old.setUp", "Set up sharing"), onSetUp, Modifier.fillMaxWidth().heightIn(min = 56.dp))
    SecondaryButton(t("family.join", "Join my family"), { scope.launch { FamilyFlow.joinFromOld() } }, Modifier.fillMaxWidth().heightIn(min = 56.dp))
}
