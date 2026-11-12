package com.tally.book.ui.family

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tally.book.data.Ledger
import com.tally.book.sync.PollPacer
import com.tally.book.sync.Sync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/*
 * Fast join checks while the app is open (docs/DESIGN.md, "Notices"). Only while the screen is showing (lifecycle STARTED) and the
 * book is unlocked (this is composed only inside the unlocked shell, and the loop checks the phase): an admin's phone counts the
 * waiting requests every 5 seconds, and a phone that asked to join asks whether it was let in. After 2 minutes without anything
 * new it slows to every 30 seconds; a touch or a new request makes it fast again. One call at a time, and failures stay silent (the
 * status line already reports sync problems). The background JoinCheckWorker is a separate, slower thing and is not changed.
 */
object FastPoll {
    private val pacer = PollPacer()

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** The person touched the screen: check fast again. Called by the activity for every touch. */
    fun touch() = pacer.activity(now())

    /** Runs until cancelled (the lifecycle leaving STARTED cancels it). */
    suspend fun run() {
        pacer.reset(now())
        while (true) {
            if (Ledger.phase.value != Ledger.Phase.READY) return
            try {
                if (once()) pacer.activity(now())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // silent
            }
            delay(pacer.intervalAt(now()))
        }
    }

    /** One check; true when something new turned up. */
    private suspend fun once(): Boolean {
        val view = Sync.membership()
        return when {
            view.inFamily && view.iAmAdmin -> Sync.detached { Sync.engine.refreshRequests() }
            view.joining != null -> { Sync.detached { Sync.engine.checkJoin() }; false }
            else -> false
        }
    }
}

/** Put once in the unlocked shell. */
@Composable
fun FastJoinChecks() {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { FastPoll.run() } }
}
