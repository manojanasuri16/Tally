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
import kotlinx.coroutines.launch

/*
 * People asking to join, for an admin: one card each, at the top of the home screen and of the Family page. Allow or Don't allow
 * (which asks once). Nothing is drawn for a member, or when nobody is waiting. The list comes from Sync.engine.requests, which
 * every sync refreshes; the background check's notification opens the Family page.
 *
 * Someone who said they were already in the family (back on a new phone) gets a card with a member picker (pre-selected by the
 * name they typed): Allow as <member> (asks once), Allow as someone new, Don't allow.
 */
@Composable
fun JoinRequestCards() {
    val requests by Sync.engine.requests.collectAsStateWithLifecycle()
    val rev = rememberLedger()
    val view = remember(rev) { Sync.membership() }
    if (requests.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        requests.forEach { r ->
            key(r.id) {
                val line = MemberText.requestLine(r, view.familyName, view.myId)
                if (r.back) BackRequestCard(r, line, view.members) else JoinRequestCard(r, line)
            }
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
private fun BackRequestCard(r: JoinRequest, line: String, members: List<FamilyMember>) {
    val runner = rememberRunner()
    var pickedId by remember(r.id) { mutableStateOf(MemberText.likelyMember(r, members)?.id) }
    var picking by remember { mutableStateOf(false) }
    val picked = members.firstOrNull { it.id == pickedId }
    InsetSurface(outlined = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(line, style = Apple.type.headline, color = Apple.colors.label)
            Text(t("req.back.help", "Check with them first. Then choose who they are."), style = Apple.type.callout, color = Apple.colors.secondaryLabel)
            AGroup {
                item {
                    ARow(
                        t("req.back.who", "This is"), value = picked?.let { FamilyFlow.shownName(it) } ?: t("req.back.choose", "Choose"),
                        icon = Icons.Rounded.Person, chevron = true, onClick = { picking = true },
                    )
                }
            }
            if (picked != null) PrimaryButton(
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
