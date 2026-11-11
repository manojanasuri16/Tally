package com.tally.book.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.tally.book.core.t
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Pull to refresh, built as a gesture rather than taken from Material (docs: android/UI.md "Pull to refresh").
 * - The content follows the finger through a rubber band: the further you pull, the more it resists (PullMath).
 * - A small arrow materialises with the pull and turns over once the release would refresh; releasing past the
 *   threshold holds a spinner until onRefresh returns, then everything springs back, critically damped.
 * - It is interruptible: touching during the spring-back picks the offset up where it is on screen, and the pull
 *   continues from there. It only claims a downward drag the list could not use itself (nested scroll), so it never
 *   fights the list's own scrolling or a collapsing large title.
 * - With reduced motion the spring-backs are short cross-fades (the finger is still tracked 1:1).
 */

/** The arithmetic of the pull, apart from the gesture so it can be tested. All lengths are in the same unit (px). */
object PullMath {
    /** How much of the finger's travel shows: 0 at rest, approaching [range] but never reaching it, slope [RESIST] at the start. */
    const val RESIST = 0.9f

    fun rubber(raw: Float, range: Float): Float {
        if (raw <= 0f || range <= 0f) return 0f
        return (1f - 1f / (raw * RESIST / range + 1f)) * range
    }

    /** The finger travel that shows [shown]; the inverse of [rubber], so a drag can resume from what is on screen. */
    fun raw(shown: Float, range: Float): Float {
        if (shown <= 0f || range <= 0f) return 0f
        val s = shown.coerceAtMost(range * 0.999f)
        return range / RESIST * (1f / (1f - s / range) - 1f)
    }

    /** d(shown)/d(raw) at [raw]: how fast the content moves relative to the finger there. */
    fun slope(raw: Float, range: Float): Float {
        if (range <= 0f) return 1f
        val k = raw.coerceAtLeast(0f) * RESIST / range + 1f
        return RESIST / (k * k)
    }

    /** 0..1 how far the pull is towards the threshold (drives the arrow's opacity and turn). */
    fun progress(shown: Float, threshold: Float): Float = if (threshold <= 0f) 0f else (shown / threshold).coerceIn(0f, 1f)

    /** Letting go at [shown] refreshes only from the threshold on. */
    fun releases(shown: Float, threshold: Float): Boolean = shown >= threshold

    /** The velocity to hand the spring-back: the finger's velocity, scaled to the content, and only in the direction of travel. */
    fun handoff(fingerVelocity: Float, raw: Float, range: Float, target: Float): Float {
        val v = fingerVelocity * slope(raw, range)
        return if (target <= 0f) minOf(v, 0f) else maxOf(v, 0f)
    }
}

/** The state of one pull-to-refresh: how far the content is pulled down and whether a refresh is running. */
@Stable
class PullRefreshState internal constructor(
    private val scope: CoroutineScope,
    private val range: Float,
    internal val threshold: Float,
    private val onRefresh: State<suspend () -> Unit>,
    private val calm: State<Boolean>,
    private val onArmed: () -> Unit,
) {
    /** How far the content is pulled down, in px. */
    var offset by mutableFloatStateOf(0f)
        private set
    var refreshing by mutableStateOf(false)
        private set

    private var armed = false
    private var anim: Job? = null

    private fun show(v: Float) {
        offset = v.coerceAtLeast(0f)
        val now = !refreshing && PullMath.releases(offset, threshold)
        if (now && !armed) onArmed()
        armed = now
    }

    private fun settle(target: Float, velocity: Float = 0f) {
        anim?.cancel()
        anim = scope.launch {
            if (calm.value) animate(offset, target, 0f, Motion.fade()) { v, _ -> show(v) }
            else animate(offset, target, velocity, Motion.push()) { v, _ -> show(v) }
        }
    }

    private fun release(velocity: Float) {
        if (refreshing || offset <= 0f) return
        val raw = PullMath.raw(offset, range)
        if (PullMath.releases(offset, threshold)) {
            refreshing = true
            settle(threshold, PullMath.handoff(velocity, raw, range, threshold))
            scope.launch {
                try { onRefresh.value() } finally { refreshing = false; armed = false; settle(0f) }
            }
        } else settle(0f, PullMath.handoff(velocity, raw, range, 0f))
    }

    /** Attach this to a parent of the scrolling content: `Modifier.nestedScroll(state.connection)`. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // pushing the pulled content back up: the pull gives way first, then the list scrolls
            if (source != NestedScrollSource.UserInput || refreshing || available.y >= 0f || offset <= 0f) return Offset.Zero
            anim?.cancel()
            val raw = PullMath.raw(offset, range)
            val used = maxOf(available.y, -raw)
            show(PullMath.rubber(raw + used, range))
            return Offset(0f, used)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // a downward drag the list had no room for: pull
            if (source != NestedScrollSource.UserInput || refreshing || available.y <= 0f) return Offset.Zero
            anim?.cancel()
            show(PullMath.rubber(PullMath.raw(offset, range) + available.y, range))
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f || refreshing) return Velocity.Zero
            release(available.y)
            return available
        }
    }
}

@Composable
fun rememberPullRefresh(onRefresh: suspend () -> Unit): PullRefreshState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val latest = rememberUpdatedState(onRefresh)
    val haptics = rememberHaptics()
    val calm = remember { derivedStateOf { reduceMotion() } }
    val threshold = with(density) { 64.dp.toPx() }
    val range = with(density) { 160.dp.toPx() }
    return remember(scope, threshold, range) { PullRefreshState(scope, range, threshold, latest, calm) { haptics.tick() } }
}

/** Moves the scrolling content down with the pull. */
fun Modifier.pullRefreshContent(state: PullRefreshState): Modifier = graphicsLayer { translationY = state.offset }

/**
 * The arrow and spinner, drawn in the band the pull uncovers. Put it in a Box under the bar, before the content, and give it the
 * Box's full size. The arrow fades in with the pull and turns over when releasing would refresh; the spinner replaces it while refreshing.
 */
@Composable
fun PullRefreshIndicator(state: PullRefreshState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val size = 28.dp
    val sizePx = with(density) { size.toPx() }
    val progress = PullMath.progress(state.offset, state.threshold)
    val flipped = state.refreshing || PullMath.releases(state.offset, state.threshold)
    val turn by androidx.compose.animation.core.animateFloatAsState(if (flipped) 180f else 0f, if (reduceMotion()) Motion.fade() else Motion.control(), label = "arrow")
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.TopCenter).size(size).graphicsLayer {
                translationY = state.offset / 2f - sizePx / 2f
                alpha = if (state.refreshing) minOf(1f, state.offset / state.threshold) else progress
            },
            contentAlignment = Alignment.Center,
        ) {
            if (state.refreshing) Spinner()
            else Icon(Icons.Rounded.ArrowDownward, t("home.pullToRefresh", "Pull down to refresh"), Modifier.size(24.dp).graphicsLayer { rotationZ = turn }, tint = Apple.colors.secondaryLabel)
        }
    }
}

@Composable
private fun Spinner() {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val color = Apple.colors.secondaryLabel
    Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = angle }) {
        val w = 2.5.dp.toPx()
        drawArc(color, 0f, 270f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** For a screen without a collapsing bar: pull-to-refresh around [content]. Screens with a [NavScreen] use the three pieces above instead (see LoansScreen). */
@Composable
fun PullRefresh(onRefresh: suspend () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = rememberPullRefresh(onRefresh)
    Box(modifier.nestedScroll(state.connection)) {
        PullRefreshIndicator(state)
        Box(Modifier.pullRefreshContent(state)) { content() }
    }
}
