package com.tally.book.ui.family

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tally.book.MainActivity
import com.tally.book.core.*
import com.tally.book.sync.FamilyMember
import com.tally.book.sync.Invite
import com.tally.book.sync.InviteResult
import com.tally.book.sync.MembershipView
import com.tally.book.sync.Notices
import com.tally.book.sync.Sync
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import kotlinx.coroutines.launch

/*
 * The Family page when this phone is in a family: the members (an admin taps one for Make admin / Remove as admin / Remove from
 * family), Invite someone, Sync now, Leave. The invite sheet makes a fresh one-time invite each time it opens and lists the
 * invites still open.
 */

@Composable
fun InFamily(view: MembershipView, onInvite: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by Sync.engine.state.collectAsStateWithLifecycle()
    val rev = rememberLedger()
    var picked by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val lastAt = remember(rev, state) { Sync.lastSyncedAt() }
    val now = Clock.millis()
    val members = remember(view) { view.members.sortedByDescending { it.me } }

    AGroup(title = view.familyName, footer = t("family.safe", "Only your family can read what you share.")) {
        members.forEach { m ->
            val acts = MemberText.actions(view.iAmAdmin, m)
            val joined = m.since.takeIf { it > 0 }?.let { t("family.joined", "joined {date}", "date" to fmtISODate(SyncText.isoDate(it))) }
            val sub = listOfNotNull(if (m.creator) t("family.created", "Created the family") else null, joined).joinToString("\n").ifEmpty { null }
            item {
                ARow(
                    FamilyFlow.shownName(m), sub = sub, value = MemberText.tag(m),
                    icon = if (m.admin) Icons.Rounded.AdminPanelSettings else Icons.Rounded.Person,
                    tint = if (m.me) Apple.colors.accent else Apple.colors.secondaryLabel,
                    chevron = acts.isNotEmpty(), onClick = if (acts.isNotEmpty()) ({ picked = m.id }) else null,
                )
            }
        }
    }
    AGroup {
        item { ARow(t("family.invite", "Invite someone"), icon = Icons.Rounded.PersonAdd, chevron = true, onClick = onInvite) }
        if (view.iAmAdmin) item { ARow(t("family.rename", "Rename family"), icon = Icons.Rounded.Edit, chevron = true, onClick = { renaming = true }) }
        item {
            ARow(
                if (com.tally.book.ui.theme.LocalEasy.current) t("family.refresh", "Refresh") else t("family.syncNow", "Sync now"),
                sub = if (lastAt > 0) t("family.lastSync", "Last synced {when}", "when" to SyncText.ago(lastAt, maxOf(now, lastAt))) else t("family.neverSynced", "Not synced yet"),
                icon = Icons.Rounded.Sync, onClick = { if (!state.running) scope.launch { FamilyFlow.syncNow() } },
                trailing = if (state.running) ({ CircularProgressIndicator(Modifier.size(20.dp), color = Apple.colors.accent, strokeWidth = 2.dp) }) else null,
            )
        }
    }
    AGroup {
        item {
            ARow(
                t("family.leave", "Leave family"), icon = Icons.AutoMirrored.Rounded.ExitToApp, tint = LocalSemantic.current.negative,
                titleColor = LocalSemantic.current.negative, onClick = { scope.launch { FamilyFlow.leave() } },
            )
        }
    }
    if (renaming && view.iAmAdmin) RenameSheet(view.familyName, onDismiss = { renaming = false }, onSave = { name -> renaming = false; scope.launch { FamilyFlow.renameFamily(name) } })
    // the sheet follows the list: a member removed meanwhile closes it
    val target = members.firstOrNull { it.id == picked }
    if (target != null) {
        MemberSheet(
            target, MemberText.actions(view.iAmAdmin, target), onDismiss = { picked = null },
            onAct = { a -> scope.launch { when (a) {
                MemberAction.MAKE_ADMIN -> FamilyFlow.makeAdmin(target, true)
                MemberAction.REMOVE_ADMIN -> FamilyFlow.makeAdmin(target, false)
                MemberAction.REMOVE -> FamilyFlow.remove(target)
            } } },
        )
    }
}

/** The family's name in a sheet; Save sends the new one (admins only: the row is not shown to anyone else). */
@Composable
private fun RenameSheet(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    val clean = Invite.cleanName(name)
    EditSheet(
        t("family.rename", "Rename family"), onDismiss,
        onSave = { clean?.let { onSave(it) } },
        saveEnabled = clean != null && clean != current,
    ) {
        TextInput(
            name, { name = it }, t("family.name.label", "Family name"),
            supporting = t("family.rename.help", "Everyone in your family will see the new name."),
        )
    }
}

/** What an admin can do with one member: a short list, one decision. */
@Composable
private fun MemberSheet(m: FamilyMember, actions: List<MemberAction>, onDismiss: () -> Unit, onAct: (MemberAction) -> Unit) {
    val negative = LocalSemantic.current.negative
    EditSheet(FamilyFlow.shownName(m), onDismiss, onSave = null) {
        AGroup {
            actions.forEach { a ->
                item {
                    when (a) {
                        MemberAction.MAKE_ADMIN -> ARow(t("member.makeAdmin", "Make admin"), icon = Icons.Rounded.AdminPanelSettings, onClick = { onDismiss(); onAct(a) })
                        MemberAction.REMOVE_ADMIN -> ARow(t("member.removeAdmin", "Remove as admin"), icon = Icons.Rounded.Person, onClick = { onDismiss(); onAct(a) })
                        MemberAction.REMOVE -> ARow(
                            t("member.remove", "Remove from family"), icon = Icons.Rounded.PersonRemove, tint = negative, titleColor = negative,
                            onClick = { onDismiss(); onAct(a) },
                        )
                    }
                }
            }
        }
    }
}

// ---------- invite ----------

@Composable
fun InviteSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val rev = rememberLedger()
    val view = remember(rev) { Sync.membership() }
    // a fresh one-time invite is made each time the sheet opens; its text exists only here, while the sheet is open
    var invite by remember { mutableStateOf<String?>(null) }
    var madeId by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val askNotices = rememberNoticePermission()
    LaunchedEffect(Unit) {
        when (val r = Sync.detached { Sync.engine.newInvite() }) {
            is InviteResult.Made -> { invite = r.text; madeId = r.inviteId }
            is InviteResult.Failed -> problem = SyncText.message(r.result) ?: t("invite.none", "There is nothing to share yet.")
        }
    }
    // an admin is asked once, with the first invite, whether to be told about join requests (Android 13+ then asks its own question)
    LaunchedEffect(madeId) {
        if (madeId != null && view.iAmAdmin && Notices.needsPermission(ctx) && FamilyFlow.wantsNotices()) askNotices()
    }
    EditSheet(t("invite.title", "Invite someone"), onDismiss, onSave = null) {
        val text = invite
        if (text == null) {
            if (problem == null) CircularProgressIndicator(color = Apple.colors.accent, strokeWidth = 3.dp)
            else Text(problem!!, style = Apple.type.body, color = Apple.colors.secondaryLabel)
            return@EditSheet
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QrCode(text, size = 240.dp)
            Text(
                t("invite.help", "Ask them to open Tally → Family → Join, and scan this."),
                style = Apple.type.body, color = Apple.colors.label, textAlign = TextAlign.Center,
            )
            Text(t("invite.valid", "Valid for 24 hours, for one person"), style = Apple.type.callout, color = Apple.colors.secondaryLabel, textAlign = TextAlign.Center)
            PrimaryButton(t("invite.share", "Share"), {
                val message = t("invite.message", "Join our family in Tally:") + "\n" + text
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                MainActivity.holdLock = true      // coming back from the share sheet must not lock the app
                runCatching { ctx.startActivity(Intent.createChooser(send, t("invite.share", "Share"))) }
                    .onFailure { MainActivity.holdLock = false }
            }, Modifier.fillMaxWidth().heightIn(min = 52.dp))
            Text(
                t("invite.warn3", "Anyone with this code can ask to join. An admin must allow them."),
                style = Apple.type.callout, color = LocalSemantic.current.warning, textAlign = TextAlign.Center,
            )
        }
        val now = Clock.millis()
        val open = view.invites.filter { it.id != madeId && it.expiresAt > now && (it.mine || view.iAmAdmin) }
        AGroup(title = if (open.isEmpty()) null else t("invite.open", "Open invites")) {
            open.forEach { i ->
                item {
                    // with large text the Cancel button goes under the words instead of squeezing them
                    val stacked = com.tally.book.ui.theme.bigText()
                    val cancel = t("invite.cancel", "Cancel invite")
                    Column {
                        ARow(
                            if (i.mine) t("invite.open.mine", "Your invite") else t("invite.open.by", "Invite from {name}", "name" to i.byName.ifBlank { t("member.someone", "Someone") }),
                            sub = MemberText.invitePeriod(i.expiresAt, now),
                            trailing = if (stacked) null else ({ PlainButton(cancel, { scope.launch { FamilyFlow.cancelInvite(i.id) } }, color = LocalSemantic.current.negative) }),
                        )
                        if (stacked) ButtonRow(cancel, LocalSemantic.current.negative) { scope.launch { FamilyFlow.cancelInvite(i.id) } }
                    }
                }
            }
        }
    }
}
