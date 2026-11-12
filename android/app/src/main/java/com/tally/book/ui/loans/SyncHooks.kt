package com.tally.book.ui.loans

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * Where the sync engine plugs into the home screen. The UI does not import sync/: whoever starts sync sets these once at start-up
 * (for example from TallyApplication or MainActivity).
 */

/** What pulling down on the home screen runs (a full refresh). While it is null the pull shows "Sync is not set up yet". Exceptions it throws are caught and told. */
var syncHook: (suspend () -> Unit)? = null

/** A small line shown under the figure on the home screen ("Synced just now"). Backed by state, so setting it redraws the screen. */
var syncStatusLine: (@Composable () -> Unit)? by mutableStateOf(null)

/** Cards at the top of the home screen: people asking to join the family, for an admin ("Asha wants to join… Allow / Don't allow"); draws nothing when there are none. */
var joinRequestsCard: (@Composable () -> Unit)? by mutableStateOf(null)

/** A quiet line on the home screen when daily backup has stopped ("Backup stopped: choose the folder again"); draws nothing otherwise. */
var backupLine: (@Composable () -> Unit)? by mutableStateOf(null)

/** Opens the file picker for "I have a backup" on the welcome screen and restores the picked file. */
var restorePicker: (@Composable () -> (() -> Unit))? = null
