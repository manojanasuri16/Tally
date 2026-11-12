package com.tally.book.ui.family

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tally.book.core.Clock
import com.tally.book.core.t
import com.tally.book.sync.Sync
import com.tally.book.ui.components.Dialogs
import com.tally.book.ui.components.pressDim
import com.tally.book.ui.components.rememberLedger
import com.tally.book.ui.loans.backupLine
import com.tally.book.ui.loans.restorePicker
import com.tally.book.ui.loans.syncHook
import com.tally.book.ui.loans.syncStatusLine
import com.tally.book.ui.theme.Apple
import kotlinx.coroutines.delay

/*
 * Plugs the sync engine into the home screen (ui/loans/SyncHooks.kt): pull to refresh runs one sync, and the quiet line
 * under the figure says how it is going. Called once from TallyApplication.
 */
fun installSyncHooks() {
    syncHook = { pullToRefresh() }
    syncStatusLine = { SyncStatusLine() }
    backupLine = { BackupHomeLine() }
    restorePicker = { rememberRestorePicker() }
}

/** Pull down on the home screen. Silent when it worked; one plain sentence when it did not, or an offer to set sharing up. */
suspend fun pullToRefresh() {
    if (!Sync.hasFamily()) {
        Dialogs.snack(t("sync.noFamily", "You are not sharing with your family yet."), t("sync.setUp", "Set up")) { FamilyFlow.openFamily() }
        return
    }
    SyncText.message(Sync.detached { Sync.engine.sync() })?.let { Dialogs.snack(it) }
}

@Composable
fun SyncStatusLine() {
    val state by Sync.engine.state.collectAsStateWithLifecycle()
    val rev = rememberLedger()
    val has = remember(rev) { Sync.hasFamily() }
    val lastAt = remember(rev, state) { Sync.lastSyncedAt() }
    var now by remember { mutableLongStateOf(Clock.millis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Clock.millis() } }
    val line = SyncText.status(state, has, lastAt, maxOf(now, lastAt))
    val tap: (() -> Unit)? = when (line.action) {
        SyncText.Action.SET_UP -> ({ FamilyFlow.openFamily() })
        SyncText.Action.RETRY -> ({ Sync.syncInBackground() })
        SyncText.Action.NONE -> null
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (tap != null) Modifier.pressDim(onClick = tap) else Modifier).padding(end = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(line.text, style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
    }
}
