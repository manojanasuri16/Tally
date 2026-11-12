package com.tally.book

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tally.book.core.*
import com.tally.book.data.Backups
import com.tally.book.data.Crypto
import com.tally.book.data.Ledger
import com.tally.book.model.DB
import com.tally.book.model.UiHooks
import com.tally.book.ui.TallyRoot
import com.tally.book.ui.components.Dialogs
import com.tally.book.ui.theme.Appearance
import com.tally.book.ui.theme.TallyTheme
import kotlinx.coroutines.launch

/** Text shared to Tally (a bank SMS, from the share sheet), waiting for the screen that will use it ("Log payment"). Null once taken. */
var pendingSharedText: String? = null

/** An invite link (tally://join#...) that opened the app, waiting for the Join screen. Null once taken. */
var pendingInvite: String? = null

/** A screen a notification asked to open ("family"), taken once the app is unlocked. Null once taken. */
var pendingOpen: String? = null

/**
 * The one activity. It loads the book (or shows the lock), applies the appearance, and stashes the ways in
 * (a shared text, an invite link) in [pendingSharedText] / [pendingInvite].
 */
class MainActivity : FragmentActivity() {

    companion object {
        /** Re-lock after this long away (the lock-delay setting overrides it: DevicePrefs "tally-relock-ms"). */
        const val RELOCK_AFTER_MS = 60_000L
        var current: MainActivity? = null
            private set
        /** Set just before the app opens a system picker or share sheet of its own, so coming back doesn't re-lock. */
        @Volatile var holdLock = false
        /** Intent extra: the screen to open (the join-request notification puts "family"). */
        const val EXTRA_OPEN = "tally-open"
    }

    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        current = this
        splash.setKeepOnScreenCondition { Ledger.phase.value == Ledger.Phase.LOADING }
        enableEdgeToEdge()
        UiHooks.ask = { m, ok, danger -> Dialogs.ask(m, ok, danger) }
        UiHooks.tell = { m -> Dialogs.tell(m) }
        UiHooks.hint = { m -> Dialogs.snack(m) }

        if (Ledger.phase.value == Ledger.Phase.LOADING) lifecycleScope.launch { Ledger.load() }
        handle(intent)

        setContent {
            val rev by Ledger.rev.collectAsStateWithLifecycle()
            val phase by Ledger.phase.collectAsStateWithLifecycle()
            val appearance = remember(rev, phase) { appearance(phase == Ledger.Phase.READY) }
            TallyTheme(appearance) { TallyRoot(); com.tally.book.ui.lock.LockOverlayHost() }
        }
    }

    /** Appearance from the book once it is open; before that from the device copy (the lock screen follows the app). */
    private fun appearance(ready: Boolean): Appearance =
        if (ready) Appearance(
            mode = DB.s("themeMode").ifEmpty { "system" },
            color = DB.s("colorTheme").ifEmpty { "blue" },
            customAccent = DB.s("customAccent").ifEmpty { "#0071E3" },
        ).also {
            DevicePrefs.set("tally-theme-mode", it.mode); DevicePrefs.set("tally-colortheme", it.color); DevicePrefs.set("tally-custom-accent", it.customAccent)
        }
        else Appearance(
            mode = DevicePrefs.get("tally-theme-mode") ?: "system",
            color = DevicePrefs.get("tally-colortheme") ?: "blue",
            customAccent = DevicePrefs.get("tally-custom-accent") ?: "#0071E3",
        )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Ways in: an invite link (tally://join#...) or a shared text. Both are only stashed for now. */
    private fun handle(intent: Intent?) {
        intent ?: return
        val data: Uri? = intent.data
        when {
            intent.getStringExtra(EXTRA_OPEN) == "family" -> { pendingOpen = "family"; intent.removeExtra(EXTRA_OPEN); return }
            intent.action == Intent.ACTION_VIEW && data?.scheme == "tally" && data.host == "join" -> pendingInvite = data.toString()
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
                if (text.isNotBlank()) pendingSharedText = text
            }
            else -> return
        }
        // consumed: a rotation or process restore must not run it again
        intent.data = null; intent.action = Intent.ACTION_MAIN
    }

    override fun onStart() {
        super.onStart()
        val away = SystemClock.elapsedRealtime() - stoppedAt
        // DB.lockDelay (seconds) mirrored to the device as "tally-relock-ms"
        val limit = DevicePrefs.get("tally-relock-ms")?.toLongOrNull() ?: (n(DB["lockDelay"]) * 1000).toLong()
        // a file picker or share sheet the app itself opened is not "leaving the app"
        val own = holdLock; holdLock = false
        if (stoppedAt > 0 && Crypto.locked && away > limit && !own) Ledger.relock()
        // daily backup: a job that a battery saver held back runs now; a folder that went away is noticed
        Backups.catchUp()
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
        if (Ledger.phase.value == Ledger.Phase.READY) Ledger.persistBlocking()
        super.onStop()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
