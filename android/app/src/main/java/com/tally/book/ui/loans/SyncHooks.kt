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
