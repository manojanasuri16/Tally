package com.tally.book.ui.family

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tally.book.sync.JoinRequest
import com.tally.book.sync.Sync
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
 */
@Composable
fun JoinRequestCards() {
    val requests by Sync.engine.requests.collectAsStateWithLifecycle()
    val rev = rememberLedger()
    val familyName = remember(rev) { Sync.membership().familyName }
    val myId = remember(rev) { Sync.membership().myId }
    if (requests.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        requests.forEach { r -> key(r.id) { JoinRequestCard(r, MemberText.requestLine(r, familyName, myId)) } }
    }
}

@Composable
private fun JoinRequestCard(r: JoinRequest, line: String) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch { try { block() } finally { working = false } }
    }
    InsetSurface(outlined = true) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(line, style = Apple.type.headline, color = Apple.colors.label)
            PrimaryButton(t("req.allow", "Allow"), { run { FamilyFlow.allow(r) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !working)
            SecondaryButton(t("req.deny", "Don't allow"), { run { FamilyFlow.deny(r) } }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !working)
        }
    }
}
