package com.tally.book.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/* Design tokens beside the colours and type: motion, spacing, radii, the continuous corner and the press highlight. */

/**
 * Springs, in Apple's terms (damping ratio + response). Stiffness = (2π / response)², mass 1.
 * Everything is critically damped (no bounce) unless a gesture with momentum came first.
 */
object Motion {
    /** Push / pop and moving things to a new place: response ≈ 0.32 s. */
    const val PUSH_STIFFNESS = 380f
    /** Controls that follow a tap (switch thumb, segmented thumb, chevrons): response ≈ 0.24 s. */
    const val CONTROL_STIFFNESS = 700f
    /** Touch-down feedback: response ≈ 0.16 s. */
    const val PRESS_STIFFNESS = 1500f

    fun <T> push(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = PUSH_STIFFNESS)
    fun <T> standard(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 300f)
    fun <T> control(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = CONTROL_STIFFNESS)
    fun <T> press(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = PRESS_STIFFNESS)
    /** Only after a flick or drag release: a little overshoot because the gesture carried momentum. */
    fun <T> bouncy(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = PUSH_STIFFNESS)
    /** Reduced motion: a short cross-fade replaces slides and scales. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(200)

    val pushOffset: SpringSpec<IntOffset> get() = push()
}

/** Spacing (dp): 16 side gutter, 12 between cards, 24 between sections. */
object Space {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val section: Dp = 28.dp
    /** The side gutter of every screen. */
    val gutter: Dp = 16.dp
    /** Where a row's text starts when it has a 30dp icon tile (hairlines start here too). */
    val iconInset: Dp = 60.dp
}

/** Corner radii (dp), drawn as continuous corners. */
object Radius {
    val tile: Dp = 8.dp       // a 30dp icon tile
    val field: Dp = 10.dp     // text fields, small wells
    val cell: Dp = 14.dp      // tiles, stat tiles
    val group: Dp = 18.dp     // inset groups, cards
    val sheet: Dp = 18.dp     // the top of a sheet, alerts
    val segment: Dp = 9.dp    // segmented control track
}

/**
 * An iOS-style continuous ("squircle") rounded rectangle: the curve eases into the straight edge instead of
 * meeting it at a circle's tangent. [topOnly] rounds just the top corners (sheets). Uses the iOS 7 corner
 * curve (PaintCode's reverse-engineered constants); the radius is clamped so the curve always fits.
 */
class ContinuousShape(private val radius: Dp, private val topOnly: Boolean = false) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width; val h = size.height
        val limit = minOf(w, h) / 2f / 1.52866483f
        val r = with(density) { radius.toPx() }.coerceAtMost(limit).coerceAtLeast(0f)
        if (r <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        val p = Path().apply {
            moveTo(1.52866483f * r, 0f)
            // top edge, top-right corner
            lineTo(w - 1.52866483f * r, 0f)
            cubicTo(w - 1.08849323f * r, 0f, w - 0.86840689f * r, 0f, w - 0.66993427f * r, 0.065496f * r)
            lineTo(w - 0.63149399f * r, 0.074911f * r)
            cubicTo(w - 0.37282392f * r, 0.16905899f * r, w - 0.16905899f * r, 0.37282392f * r, w - 0.074911f * r, 0.63149399f * r)
            cubicTo(w, 0.86840689f * r, w, 1.08849323f * r, w, 1.52866483f * r)
            if (topOnly) {
                lineTo(w, h); lineTo(0f, h)
            } else {
                // right edge, bottom-right corner
                lineTo(w, h - 1.52866483f * r)
                cubicTo(w, h - 1.08849323f * r, w, h - 0.86840689f * r, w - 0.065496f * r, h - 0.66993427f * r)
                lineTo(w - 0.074911f * r, h - 0.63149399f * r)
                cubicTo(w - 0.16905899f * r, h - 0.37282392f * r, w - 0.37282392f * r, h - 0.16905899f * r, w - 0.63149399f * r, h - 0.074911f * r)
                cubicTo(w - 0.86840689f * r, h, w - 1.08849323f * r, h, w - 1.52866483f * r, h)
                // bottom edge, bottom-left corner
                lineTo(1.52866483f * r, h)
                cubicTo(1.08849323f * r, h, 0.86840689f * r, h, 0.66993427f * r, h - 0.065496f * r)
                lineTo(0.63149399f * r, h - 0.074911f * r)
                cubicTo(0.37282392f * r, h - 0.16905899f * r, 0.16905899f * r, h - 0.37282392f * r, 0.074911f * r, h - 0.63149399f * r)
                cubicTo(0f, h - 0.86840689f * r, 0f, h - 1.08849323f * r, 0f, h - 1.52866483f * r)
            }
            // left edge, top-left corner
            lineTo(0f, 1.52866483f * r)
            cubicTo(0f, 1.08849323f * r, 0f, 0.86840689f * r, 0.065496f * r, 0.66993427f * r)
            lineTo(0.074911f * r, 0.63149399f * r)
            cubicTo(0.16905899f * r, 0.37282392f * r, 0.37282392f * r, 0.16905899f * r, 0.63149399f * r, 0.074911f * r)
            cubicTo(0.86840689f * r, 0f, 1.08849323f * r, 0f, 1.52866483f * r, 0f)
            close()
        }
        return Outline.Generic(p)
    }

    override fun equals(other: Any?) = other is ContinuousShape && other.radius == radius && other.topOnly == topOnly
    override fun hashCode() = radius.hashCode() * 31 + topOnly.hashCode()
}

/** The shapes the kit uses. */
object AppleShapes {
    val tile = ContinuousShape(Radius.tile)
    val field = ContinuousShape(Radius.field)
    val cell = ContinuousShape(Radius.cell)
    val group = ContinuousShape(Radius.group)
    val sheet = ContinuousShape(Radius.sheet, topOnly = true)
    val alert = ContinuousShape(Radius.sheet)
    val capsule = androidx.compose.foundation.shape.RoundedCornerShape(50)
}

/**
 * The app-wide pressed state for plain clickables (the theme provides it as LocalIndication, replacing the
 * ripple): a highlight laid over the element the instant the finger lands, fading out when it lifts.
 */
object PressHighlight : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HighlightNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

private class HighlightNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val alpha = Animatable(0f)
    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> launch { alpha.snapTo(1f); invalidateDraw() }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                        alpha.animateTo(0f, tween(220)) { invalidateDraw() }
                    }
                }
            }
        }
    }
    override fun ContentDrawScope.draw() {
        drawContent()
        val a = alpha.value
        if (a > 0f) {
            val c: Color = currentValueOf(LocalAppleColors).highlight
            drawRect(c.copy(alpha = c.alpha * a))
        }
    }
}
