package com.tally.book.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.ui.components.*
import com.tally.book.ui.loans.loansGraph
import com.tally.book.ui.lock.LockScreen
import com.tally.book.ui.theme.Motion
import com.tally.book.ui.theme.Space

/*
 * The app shell: the lock gate, one NavHost with a single root screen (Routes.LOANS) and pushed sub-screens (no tab bar,
 * no month bar), the push/pop transitions, and the one alert host and toast host.
 * Each area registers its screens in a NavGraphBuilder extension (loansGraph(), ...) called from MainScaffold.
 */

object Routes {
    /** The one root screen. */
    const val LOANS = "loans"
}

/** Navigation from anywhere (model hooks, deep links, the share target). */
object AppNav {
    var controller: NavHostController? = null
    /** A route to open once the app is unlocked and showing (a deep link, a shared text). */
    val pending = mutableStateOf<String?>(null)

    /** Pushes [route]; a route that isn't registered shows a note instead of crashing. */
    fun go(route: String) {
        val c = controller ?: return
        try { c.navigate(route) { launchSingleTop = true } }
        catch (e: IllegalArgumentException) { Dialogs.snack(t("app.comingSoon", "This part arrives in the next update")) }
    }

    fun back() { controller?.popBackStack() }
    /** Back to the root screen, dropping everything above it. */
    fun home() { controller?.popBackStack(Routes.LOANS, false) }
}

/** The floating chrome at the bottom (for the toast host, which lives outside the NavHost). Zero until a floating button exists. */
object AppChrome {
    val bottom = mutableStateOf(0.dp)
}

@Composable
fun TallyRoot() {
    val phase by Ledger.phase.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.background) {
        AnimatedContent(phase, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "phase") { p ->
            when (p) {
                Ledger.Phase.LOADING -> Box(Modifier.fillMaxSize())
                Ledger.Phase.LOCKED -> LockScreen()
                Ledger.Phase.READY -> MainScaffold()
            }
        }
        val chrome by AppChrome.bottom
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ToastHost(
                Modifier.then(if (chrome > 0.dp) Modifier.padding(bottom = chrome + 10.dp) else Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(bottom = 12.dp)),
            )
        }
        DialogHost()
    }
}

@Composable
fun MainScaffold() {
    val nav = rememberNavController()
    DisposableEffect(nav) { AppNav.controller = nav; onDispose { if (AppNav.controller === nav) AppNav.controller = null } }
    val pending by AppNav.pending
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(pending, entry) {
        val p = pending ?: return@LaunchedEffect
        if (entry == null) return@LaunchedEffect
        AppNav.pending.value = null
        AppNav.go(p)
    }

    // iOS push: in from the right on a critically damped spring, the screen behind shifts a quarter left and dims;
    // pop is the same path in reverse. Reduced motion cross-fades everything.
    val push = Motion.push<IntOffset>()
    val calm = { reduceMotion() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalBottomChrome provides 0.dp) {
            NavHost(
                nav, startDestination = Routes.LOANS, modifier = Modifier.fillMaxSize(),
                enterTransition = { if (calm()) fadeIn(tween(220)) else slideInHorizontally(push) { it } },
                exitTransition = { if (calm()) fadeOut(tween(160)) else slideOutHorizontally(push) { -it / 4 } + fadeOut(tween(260), targetAlpha = 0.6f) },
                popEnterTransition = { if (calm()) fadeIn(tween(220)) else slideInHorizontally(push) { -it / 4 } + fadeIn(tween(260), initialAlpha = 0.6f) },
                popExitTransition = { if (calm()) fadeOut(tween(160)) else slideOutHorizontally(push) { it } },
            ) {
                loansGraph()
            }
        }
    }
}

/** The label of the "‹ Parent" back button: the screen this one was pushed from. */
private fun parentLabel(e: NavBackStackEntry?): String =
    if (e?.destination?.route == Routes.LOANS) t("app.name", "Tally") else t("dlg.back", "Back")

/** The back label for the screen being composed (read once, when it is pushed). */
@Composable
fun rememberBackLabel(): String = remember { parentLabel(AppNav.controller?.previousBackStackEntry) }

/**
 * A standard screen pushed over the root (a loan, the family page): the iOS navigation bar with "‹ Parent" (the screen it
 * came from, or [back]), a large title that collapses to an inline one, and plain tinted [actions]. [content] gets the padding to apply.
 */
@Composable
fun SubScreen(title: String, actions: @Composable RowScope.() -> Unit = {}, back: String? = null, content: @Composable (PaddingValues) -> Unit) {
    val label = back ?: rememberBackLabel()
    NavScreen(title, label, actions, largeTitle = true, content = content)
}

/** Standard padding and spacing for a scrolling screen body. */
val ScreenPadding: PaddingValues
    @Composable get() = PaddingValues(start = Space.gutter, end = Space.gutter, top = 4.dp, bottom = bottomClearance())
