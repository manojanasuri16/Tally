package com.tally.book.ui.lock

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import com.tally.book.MainActivity
import com.tally.book.data.LockRules

/*
 * Screenshots and the recents thumbnail (docs/DESIGN.md, "App lock", "Screenshots"). The window carries FLAG_SECURE while an app
 * lock is set or amounts are hidden (the person asked for privacy, so the task switcher shows a blank card and screenshots and
 * screen recordings come out black), and always while a screen shows a secret: the invite QR or the recovery code
 * ([SecureWhileShown], [hold]). Dialogs and sheets inherit the flag from the activity's window. The rule is LockRules.secureWindow.
 */
object ScreenGuard {
    /** Screens showing a secret right now. */
    val holds = mutableIntStateOf(0)
    @Volatile private var base = false

    /** The lock or hidden amounts changed: [locked] or [hidden] puts the flag on. */
    fun update(locked: Boolean, hidden: Boolean) { base = locked || hidden; apply() }

    /** A secret goes on screen (before its sheet opens, so the sheet's window takes the flag too); [release] when it closes. */
    fun hold() { holds.intValue++; apply() }
    fun release() { holds.intValue = maxOf(0, holds.intValue - 1); apply() }

    fun apply() {
        val act = MainActivity.current ?: return
        val on = LockRules.secureWindow(base, false, holds.intValue > 0)
        act.runOnUiThread {
            if (on) act.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else act.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Keeps screenshots and the recents thumbnail off while the calling screen is shown (the invite QR, the recovery code). */
@Composable
fun SecureWhileShown() {
    DisposableEffect(Unit) {
        ScreenGuard.hold()
        onDispose { ScreenGuard.release() }
    }
}
