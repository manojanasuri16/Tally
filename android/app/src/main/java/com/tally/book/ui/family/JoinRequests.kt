package com.tally.book.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tally.book.sync.FamilyMember
import com.tally.book.sync.JoinRequest
import com.tally.book.sync.Sync
import com.tally.book.ui.components.AGroup
import com.tally.book.ui.components.ARow
import com.tally.book.ui.components.EditSheet
import com.tally.book.ui.components.InsetSurface
import com.tally.book.ui.components.PrimaryButton
import com.tally.book.ui.components.SecondaryButton
import com.tally.book.ui.components.rememberLedger
import com.tally.book.ui.theme.Apple
import com.tally.book.core.t
import com.tally.book.core.tp
import kotlinx.coroutines.launch

/*
 * People asking to join, for an admin: one card each, at the top of the home screen and of the Family page. Allow or Don't allow
 * (which asks once). Nothing is drawn for a member, or when nobody is waiting. The list comes from Sync.engine.requests, which
 * every sync refreshes; the background check's notification opens the Family page.
 *
 * Someone who said they were already in the family (back on a new phone) gets a card with a member picker, nobody picked until
 * the admin chooses (the name in a request proves nothing), the phone's code to compare over a call, and Allow as <member> (asks
 * once, naming the member), Allow as someone new, Don't allow. A member the relay would refuse (the creator, or an admin when this
 * admin made the invite) shows why instead of the Allow as button.
 */
@Composable
fun JoinRequestCards() {
    val requests by Sync.engine.requests.collectAsStateWithLifecycle()
    val rev = rememberLedger()
    val view = remember(rev) { Sync.membership() }
    if (requests.isEmpty() && view.shareAsk == 0) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (view.shareAsk > 0 && view.inFamily) ShareAskCard(view.shareAsk, view.familyName, view.members.filter { !it.me }.map { FamilyFlow.shownName(it) })
        requests.forEach { r ->
            key(r.id) {
                val line = MemberText.requestLine(r, view.familyName, view.myId)
                if (r.back) BackRequestCard(r, line, view.members, view.myId) else JoinRequestCard(r, line)
            }
        }
    }
}

/**
 * Just let into a family, with entries already on this phone: nothing of them is shared until the person answers (the review's
 * M2). Says who is in the family, so they can tell it is the family they meant.
 */
@Composable
private fun ShareAskCard(count: Int, family: String, others: List<String>) {
    val runner = rememberRunner()
    val fam = family.ifBlank { t("family.the", "the family") }
    InsetSurface(outlined = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(t("share.ask", "Share your existing entries with {family}?", "family" to fam), style = Apple.type.headline, color = Apple.colors.label)
            Text(tp("share.count", count, "{n} entry was on this phone before you joined. Nothing of it is shared until you choose.",
                "{n} entries were on this phone before you joined. Nothing of them is shared until you choose."),
                style = Apple.type.callout, color = Apple.colors.secondaryLabel)
            if (others.isNotEmpty()) Text(t("share.who", "In {family}: {names}", "family" to fam, "names" to others.joinToString(", ")),
                style = Apple.type.callout, color = Apple.colors.secondaryLabel)
            PrimaryButton(t("share.all", "Share all"), { runner.run { FamilyFlow.answerShare(true) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working)
            SecondaryButton(t("share.keep", "Keep them on this phone only"), { runner.run { FamilyFlow.answerShare(false) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working)
        }
    }
}

/** One decision at a time per card: [run] ignores taps while one is being made. */
private class Runner(val working: Boolean, val run: (suspend () -> Unit) -> Unit)

@Composable
private fun rememberRunner(): Runner {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    return Runner(working) { block ->
        if (!working) {
            working = true
            scope.launch { try { block() } finally { working = false } }
        }
    }
}

@Composable
private fun JoinRequestCard(r: JoinRequest, line: String) {
    val runner = rememberRunner()
    InsetSurface(outlined = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(line, style = Apple.type.headline, color = Apple.colors.label)
            PrimaryButton(t("req.allow", "Allow"), { runner.run { FamilyFlow.allow(r) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working)
            SecondaryButton(t("req.deny", "Don't allow"), { runner.run { FamilyFlow.deny(r) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working)
        }
    }
}

@Composable
private fun BackRequestCard(r: JoinRequest, line: String, members: List<FamilyMember>, myId: String) {
    val runner = rememberRunner()
    var pickedId by remember(r.id) { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val picked = members.firstOrNull { it.id == pickedId }
    InsetSurface(outlined = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(line, style = Apple.type.headline, color = Apple.colors.label)
            Text(t("req.back.help", "Check with them first. Then choose who they are."), style = Apple.type.callout, color = Apple.colors.secondaryLabel)
            if (r.code.isNotEmpty()) Text(
                t("req.back.code", "Their new phone shows a code. Call them and ask them to read it: it must be {code}.", "code" to r.code),
                style = Apple.type.callout, color = Apple.colors.label,
            )
            AGroup {
                item {
                    ARow(
                        t("req.back.who", "This is"), value = picked?.let { FamilyFlow.shownName(it) } ?: t("req.back.choose", "Choose"),
                        icon = Icons.Rounded.Person, chevron = true, onClick = { picking = true },
                    )
                }
            }
            val blocked = picked?.let { MemberText.allowAsBlocked(r, it, myId) }
            if (blocked != null) Text(blocked, style = Apple.type.callout, color = Apple.colors.secondaryLabel)
            else if (picked != null) PrimaryButton(
                t("req.allowAs", "Allow as {name}", "name" to FamilyFlow.shownName(picked)),
                { runner.run { FamilyFlow.allowAs(r, picked) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working,
            )
            SecondaryButton(t("req.allowNew", "Allow as someone new"), { runner.run { FamilyFlow.allow(r) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working)
            SecondaryButton(t("req.deny", "Don't allow"), { runner.run { FamilyFlow.deny(r) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !runner.working)
        }
    }
    if (picking) {
        EditSheet(t("req.back.pick", "Who is this?"), { picking = false }, onSave = null) {
            AGroup(footer = t("req.back.pickHelp", "Their old phone will stop working, and this new phone takes its place.")) {
                members.forEach { m ->
                    item {
                        ARow(
                            FamilyFlow.shownName(m), sub = if (m.me) t("family.you", "You") else null, icon = Icons.Rounded.Person,
                            onClick = { pickedId = m.id; picking = false },
                            trailing = if (m.id == pickedId) ({ Icon(Icons.Rounded.Check, t("lang.selected", "Selected"), tint = Apple.colors.accent) }) else null,
                        )
                    }
                }
            }
        }
    }
}
