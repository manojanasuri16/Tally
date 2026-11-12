package com.tally.book.ui.family

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tally.book.core.t
import com.tally.book.data.DueNotices
import com.tally.book.sync.Notices
import com.tally.book.ui.components.*
import kotlinx.coroutines.launch

/*
 * "Payment reminders" (data/DueNotices.kt): one quiet notification in the morning when a payment is due or money is expected.
 * The switch is on when the setting is on AND Android lets Tally show notifications (13+ asks first, in plain words, then the
 * phone's own question). Switching on without permission asks; a "no" at either question leaves it off.
 */

private fun question() = t(
    "notice.due.ask",
    "Do you want to be reminded when a payment is due?\n\nTally will show a notification in the morning. It never shows a name or an amount.",
)

@Composable
fun RemindersGroup() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberNoticePermission { granted ->
        if (!granted) DueNotices.turn(false)
        tick++
    }
    val on = remember(tick) { DueNotices.enabled && !Notices.needsPermission(ctx) }
    AGroup(footer = t("notice.due.help", "A quiet notification in the morning when a payment is due. It never shows a name or an amount.")) {
        item {
            SwitchRow(
                t("notice.due.title", "Payment reminders"), on,
                { want ->
                    if (!want) { DueNotices.turn(false); tick++ }
                    else {
                        DueNotices.turn(true); tick++
                        if (Notices.needsPermission(ctx)) scope.launch {
                            if (Dialogs.ask(question(), t("notice.ok", "Yes, tell me"))) ask() else { DueNotices.turn(false); tick++ }
                        }
                    }
                },
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                icon = Icons.Rounded.Notifications,
            )
        }
    }
}

/**
 * Returns a function to call right after a loan is saved: the first time, and only when reminders are on but Android has not been
 * allowed yet, it asks the same plain question (then the phone's own). Never asks twice.
 */
@Composable
fun rememberReminderOffer(): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ask = rememberNoticePermission { granted -> if (!granted) DueNotices.turn(false) }
    return remember {
        {
            if (DueNotices.shouldOffer(ctx)) scope.launch {
                if (Dialogs.ask(question(), t("notice.ok", "Yes, tell me"))) ask() else DueNotices.turn(false)
            }
        }
    }
}
