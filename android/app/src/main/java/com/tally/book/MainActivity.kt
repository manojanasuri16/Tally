package com.tally.book

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tally.book.core.*
import com.tally.book.data.Backups
import com.tally.book.data.Crypto
import com.tally.book.data.FileStore
import com.tally.book.data.Ledger
import com.tally.book.data.LockRules
import com.tally.book.ui.lock.ScreenGuard
import com.tally.book.model.DB
import com.tally.book.model.UiHooks
import com.tally.book.ui.TallyRoot
import com.tally.book.ui.family.FastPoll
import com.tally.book.ui.components.Dialogs
import com.tally.book.ui.theme.Appearance
import com.tally.book.ui.theme.EasyMode
import com.tally.book.ui.theme.EasyRules
import com.tally.book.ui.theme.TallyTheme
import com.tally.book.ui.theme.ThemeMode
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
        /**
         * Set just before the app opens a system picker, share sheet, viewer, camera or prompt of its own, so coming back doesn't
         * re-lock; honoured only for a short while (LockRules.relockOnReturn: [LockRules.HOLD_MS], or the re-lock delay if longer),
         * so a viewer left open on a phone that was put down does not keep Tally open.
         */
        var holdLock: Boolean
            get() = heldAt > 0
            set(v) { heldAt = if (v) SystemClock.elapsedRealtime() else 0L }
        @Volatile private var heldAt = 0L
        /** Intent extra: the screen to open (the join-request notification puts "family"). */
        const val EXTRA_OPEN = "tally-open"
        /** True while the app is on screen (between onStart and onStop); the payment reminder is not posted then. */
        @Volatile var visible = false
            private set
    }

    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        current = this
        splash.setKeepOnScreenCondition { Ledger.phase.value == Ledger.Phase.LOADING }
        applyChrome(ThemeMode.stored())
        UiHooks.ask = { m, ok, danger -> Dialogs.ask(m, ok, danger) }
        UiHooks.tell = { m -> Dialogs.tell(m) }
        UiHooks.hint = { m -> Dialogs.snack(m) }

        if (Ledger.phase.value == Ledger.Phase.LOADING) lifecycleScope.launch { Ledger.load() }
        handle(intent)

        setContent {
            val rev by Ledger.rev.collectAsStateWithLifecycle()
            val phase by Ledger.phase.collectAsStateWithLifecycle()
            val easy = EasyMode.on   // a device setting held as Compose state: flipping it redraws everything, the lock screen included
            val appearance = remember(rev, phase, easy) { appearance(phase == Ledger.Phase.READY).copy(easy = easy, fontScale = if (easy) EasyRules.EXTRA_SCALE else 1f) }
            // the window behind the app and the system bars' icons follow the setting at once, not only the phone's mode
            val sysDark = isSystemInDarkTheme()
            LaunchedEffect(appearance.mode, sysDark) { applyChrome(appearance.mode) }
            // no screenshots and a blank recents card while a lock is set or amounts are hidden (ui/lock/ScreenGuard.kt)
            val secure = remember(rev, phase) { phase == Ledger.Phase.LOCKED || Crypto.locked || (phase == Ledger.Phase.READY && DB.b("hideAmounts")) }
            LaunchedEffect(secure) { ScreenGuard.update(secure, false) }
            TallyTheme(appearance) { TallyRoot(); com.tally.book.ui.lock.LockOverlayHost() }
        }
    }

    /**
     * The mode is the device setting (the same before and after unlock); the accent colour comes from the book once it is open,
     * and from its device copy before that (the lock screen follows the app).
     */
    private fun appearance(ready: Boolean): Appearance {
        // a book from before the mode became a device setting: take its value once
        if (ready && DevicePrefs.get(ThemeMode.KEY) == null) DB.s("themeMode").takeIf { it.isNotEmpty() }?.let { ThemeMode.set(it) }
        val mode = ThemeMode.stored()
        return if (ready) Appearance(
            mode = mode,
            color = DB.s("colorTheme").ifEmpty { "blue" },
            customAccent = DB.s("customAccent").ifEmpty { "#0071E3" },
        ).also { DevicePrefs.set("tally-colortheme", it.color); DevicePrefs.set("tally-custom-accent", it.customAccent) }
        else Appearance(
            mode = mode,
            color = DevicePrefs.get("tally-colortheme") ?: "blue",
            customAccent = DevicePrefs.get("tally-custom-accent") ?: "#0071E3",
        )
    }

    /** Paints the window behind the app and sets the status and navigation bar icons for the resolved mode (so a forced mode has no flash). */
    private fun applyChrome(mode: String) {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = ThemeMode.isDark(mode, systemDark)
        window.setBackgroundDrawable(ColorDrawable(ThemeMode.windowColor(mode, systemDark)))
        val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        enableEdgeToEdge(bars, bars)
    }

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
        visible = true
        val now = SystemClock.elapsedRealtime()
        // DB.lockDelay (seconds) mirrored to the device as "tally-relock-ms"
        val limit = DevicePrefs.get("tally-relock-ms")?.toLongOrNull() ?: (n(DB["lockDelay"]) * 1000).toLong()
        // a file picker or share sheet the app itself opened is not "leaving the app", for a short while (LockRules.HOLD_MS)
        val held = heldAt; holdLock = false
        if (Crypto.locked && LockRules.relockOnReturn(stoppedAt, now, limit, held)) Ledger.relock()
        // back from a document viewer: its plain copy goes (L2)
        if (stoppedAt > 0) FileStore.clearViewCopy()
        ScreenGuard.apply()
        // daily backup: a job that a battery saver held back runs now; a folder that went away is noticed
        Backups.catchUp()
    }

    override fun onStop() {
        visible = false
        stoppedAt = SystemClock.elapsedRealtime()
        if (Ledger.phase.value == Ledger.Phase.READY) Ledger.persistBlocking()
        super.onStop()
    }

    /** Every touch counts as "the person is here": the open app checks for join requests fast again (ui/family/FastJoin.kt). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        FastPoll.touch()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}
