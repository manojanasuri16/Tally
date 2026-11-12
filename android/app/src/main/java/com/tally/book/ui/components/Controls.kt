package com.tally.book.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tally.book.model.DB
import com.tally.book.core.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.AppleShapes
import com.tally.book.ui.theme.Motion
import com.tally.book.ui.theme.LocalEasy
import com.tally.book.ui.theme.bigText
import com.tally.book.ui.theme.touchMin
import kotlin.math.roundToInt

/* The kit's controls: press feedback, haptics, the iOS switch, the segmented control, capsule buttons, icon tiles, bar buttons. */

/** True when the user asked for reduced motion (Settings > Appearance): cross-fade instead of slides, springs and scales. */
fun reduceMotion(): Boolean = DB.b("reduceMotion")

// ---------- press feedback ----------

/**
 * The kit's touch feedback, on touch-DOWN: a critically damped spring to [scaleTo] plus a highlight, no ripple.
 * [onLongClick] adds a long press (with a haptic). [enabled] = false makes it inert.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    enabled: Boolean = true, scaleTo: Float = 0.97f, highlight: Boolean = true,
    onLongClick: (() -> Unit)? = null, role: Role? = Role.Button, onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val calm = reduceMotion()
    val haptics = rememberHaptics()
    // Easy mode presses a little deeper, so the response to a touch is obvious
    val target = if (LocalEasy.current && scaleTo < 1f) scaleTo - 0.02f else scaleTo
    val scale by animateFloatAsState(if (pressed && !calm && enabled) target else 1f, Motion.press(), label = "press")
    val hl = Apple.colors.highlight
    val hlAlpha by animateFloatAsState(if (pressed && highlight && enabled) 1f else 0f, if (pressed) androidx.compose.animation.core.snap() else Motion.standard(), label = "hl")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .background(hl.copy(alpha = hl.alpha * hlAlpha))
        .combinedClickable(
            interactionSource = src, indication = null, enabled = enabled, role = role,
            onLongClick = onLongClick?.let { { haptics.longPress(); it() } }, onClick = onClick,
        )
}

/** Plain text and icon buttons press the iOS way: they dim to 30 % while held, with no scale and no highlight. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressDim(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val a by animateFloatAsState(if (pressed) 0.3f else 1f, if (pressed) androidx.compose.animation.core.snap() else Motion.standard(), label = "dim")
    return this.graphicsLayer { alpha = if (enabled) a else 0.35f }
        .then(Modifier.combinedClickable(interactionSource = src, indication = null, enabled = enabled, role = role, onClick = onClick))
}

// ---------- haptics ----------

/**
 * Haptics for commits only (a toggle, Save, a destructive confirm, a snap): never for plain navigation.
 * Uses the platform's richer constants where the phone has them, falling back to LocalHapticFeedback.
 */
class Haptics(private val view: View, private val fallback: HapticFeedback) {
    private fun fire(constant: Int, alt: HapticFeedbackType = HapticFeedbackType.TextHandleMove) {
        if (!view.performHapticFeedback(constant)) fallback.performHapticFeedback(alt)
    }
    /** A selection tick (segmented control, picker). */
    fun tick() = fire(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    /** A switch flipping. */
    fun toggle(on: Boolean) = fire(
        if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else HapticFeedbackConstants.CLOCK_TICK,
    )
    /** Something saved or done. */
    fun confirm() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    /** A destructive commit (delete, erase): a firm single knock. */
    fun destructive() = fire(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
    /** A refused action (validation failed). */
    fun reject() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackType.LongPress)
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val hf = LocalHapticFeedback.current
    return remember(view, hf) { Haptics(view, hf) }
}

// ---------- the switch ----------

/**
 * The iOS switch: a 51×31 capsule, green when on, a white thumb that slides on a critically damped spring and
 * stretches while held. Haptic on every flip. [onCheckedChange] = null draws it display-only (a row handles taps).
 */
@Composable
fun AppleSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Apple.colors
    val haptics = rememberHaptics()
    val calm = reduceMotion()
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val pos by animateFloatAsState(if (checked) 1f else 0f, if (calm) Motion.fade() else Motion.control(), label = "switch")
    val stretch by animateFloatAsState(if (pressed && enabled) 1f else 0f, Motion.press(), label = "stretch")
    val track by animateColorAsState(if (checked) c.switchOn else c.switchOff, if (calm) Motion.fade() else Motion.control(), label = "track")
    val interactive = onCheckedChange != null
    val k = if (LocalEasy.current) 1.25f else 1f
    Canvas(
        modifier
            .size(51.dp * k, 31.dp * k)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                if (interactive) Modifier.toggleable(checked, src, indication = null, enabled = enabled, role = Role.Switch) {
                    haptics.toggle(it); onCheckedChange?.invoke(it)
                } else Modifier,
            ),
    ) {
        val h = size.height
        drawRoundRect(track, cornerRadius = CornerRadius(h / 2, h / 2))
        val inset = 2.dp.toPx()
        val d = h - inset * 2
        val w = d + 6.dp.toPx() * stretch
        val x = inset + (size.width - inset * 2 - w) * pos
        // the soft shadow under the thumb
        drawRoundRect(Color.Black.copy(alpha = 0.10f), Offset(x, inset + 1.5.dp.toPx()), Size(w, d), CornerRadius(d / 2, d / 2))
        drawRoundRect(Color.White, Offset(x, inset), Size(w, d), CornerRadius(d / 2, d / 2))
    }
}

// ---------- segmented control ----------

/**
 * The iOS segmented control for an in-page choice of 2–4 short options (value to label): a grey track and a
 * thumb that slides to the chosen segment on a spring. A tick haptic on change. Never use it to navigate.
 */
@Composable
fun SegmentedControl(
    options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, colors: Map<String, Color> = emptyMap(),
) {
    if (options.isEmpty()) return
    // Two to four short options fit a segmented control. Longer words (Telugu, Hindi) or large text (the phone's own large font, Easy mode)
    // would cramp it, so the same choice is drawn as capsules that wrap, which grow with the text.
    val fits = options.size in 2..4 && options.all { it.second.length <= 14 } && options.sumOf { it.second.length } <= 36 && !bigText()
    if (!fits) { CapsuleChoice(options, selected, onSelect, modifier, colors); return }
    val c = Apple.colors
    val haptics = rememberHaptics()
    val calm = reduceMotion()
    val idx = options.indexOfFirst { it.first == selected }
    val pos = remember { Animatable(idx.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(idx) {
        if (idx >= 0) { if (calm || pos.value < 0) pos.snapTo(idx.toFloat()) else pos.animateTo(idx.toFloat(), Motion.control()) }
    }
    Box(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.45f)
            .clip(com.tally.book.ui.theme.ContinuousShape(com.tally.book.ui.theme.Radius.segment)).background(c.fill).padding(2.dp),
    ) {
        // the thumb, sized to one segment, sliding under the labels
        if (idx >= 0) Layout(content = {
            Box(Modifier.shadow(if (c.dark) 0.dp else 2.dp, com.tally.book.ui.theme.ContinuousShape(7.dp)).clip(com.tally.book.ui.theme.ContinuousShape(7.dp)).background(c.thumb))
        }, modifier = Modifier.matchParentSize()) { m, cons ->
            val segW = cons.maxWidth / options.size
            val p = m.first().measure(androidx.compose.ui.unit.Constraints.fixed(segW, cons.maxHeight))
            layout(cons.maxWidth, cons.maxHeight) { p.place(IntOffset((segW * pos.value).roundToInt(), 0)) }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            options.forEachIndexed { i, (v, label) ->
                val sel = i == idx
                Row(
                    Modifier.weight(1f).heightIn(min = 32.dp)
                        .semantics { role = Role.Tab; this.selected = sel }
                        .pressDim(enabled = enabled, role = null) { if (!sel) { haptics.tick(); onSelect(v) } }
                        .padding(horizontal = 6.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    colors[v]?.let { Box(Modifier.size(8.dp).clip(AppleShapes.capsule).background(it)); Spacer(Modifier.width(6.dp)) }
                    Text(
                        label, style = Apple.type.footnote.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                        color = c.label, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A wrapping set of capsules for a choice with many or long options (the selected one is filled with the accent). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapsuleChoice(
    options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier, colors: Map<String, Color> = emptyMap(),
) {
    val c = Apple.colors
    val haptics = rememberHaptics()
    val easy = LocalEasy.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(if (easy) 10.dp else 8.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            val bg by animateColorAsState(if (sel) c.accent else c.fill, Motion.control(), label = "cap")
            Row(
                Modifier.clip(AppleShapes.capsule).background(bg)
                    .semantics { this.selected = sel }
                    .pressable(scaleTo = 0.95f, highlight = false) { if (!sel) haptics.tick(); onSelect(v) }
                    .heightIn(min = touchMin(34.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // the label wraps rather than being cut off
                Text(label, style = Apple.type.footnote.copy(fontSize = androidx.compose.ui.unit.TextUnit(13.5f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = if (easy) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (sel) MaterialTheme.colorScheme.onPrimary else c.label)
            }
        }
    }
}

// ---------- buttons ----------

/** The one prominent action of a screen or sheet: a filled accent capsule, 50dp tall. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Color.Unspecified) {
    val bg = if (color == Color.Unspecified) Apple.colors.accent else color
    // the dim state is drawn in the colours, so the modifier chain stays the same when a button turns on
    val dim = if (enabled) 1f else 0.4f
    Box(
        modifier.heightIn(min = touchMin(50.dp) + (if (LocalEasy.current) 4.dp else 0.dp)).clip(AppleShapes.capsule).background(bg.copy(alpha = bg.alpha * dim))
            .pressable(enabled = enabled, highlight = false, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Apple.type.headline, color = com.tally.book.ui.theme.onColorFor(bg).copy(alpha = if (enabled) 1f else 0.7f), textAlign = TextAlign.Center)
    }
}

/** A secondary action: a tinted capsule (accent text on an accent wash). */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Color.Unspecified) {
    val fg = if (color == Color.Unspecified) Apple.colors.accent else color
    Box(
        modifier.heightIn(min = touchMin(44.dp)).clip(AppleShapes.capsule).background(fg.copy(alpha = if (enabled) (if (LocalEasy.current) 0.2f else 0.15f) else 0.06f))
            .pressable(enabled = enabled, highlight = false, onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Apple.type.headline.copy(fontWeight = FontWeight.SemiBold), color = fg.copy(alpha = if (enabled) 1f else 0.4f), textAlign = TextAlign.Center)
    }
}

/** A borderless text button in the accent (or [color]); dims while held. [bold] for the preferred action. */
@Composable
fun PlainButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Color.Unspecified, bold: Boolean = false, icon: ImageVector? = null, maxLines: Int = 3) {
    val fg = if (color == Color.Unspecified) Apple.colors.accent else color
    Row(modifier.heightIn(min = touchMin(44.dp)).pressDim(enabled, onClick = onClick).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Apple.type.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = fg, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** A sheet's header: its title on the left and a round close button on the right. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Apple.colors
    val desc = t("dlg.close", "Close")
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = Apple.type.headline, color = c.label, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(touchMin(44.dp)).semantics { contentDescription = desc }.pressDim(onClick = onClose), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp).clip(AppleShapes.capsule).background(c.fill), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The bar pinned under a sheet's form: Cancel, and the wider Save beside it. */
@Composable
fun SheetActions(saveLabel: String, saveEnabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier) {
    if (bigText()) {
        // large text: Save over Cancel, each the full width, so neither word is squeezed
        Column(modifier.fillMaxWidth().padding(horizontal = if (LocalEasy.current) 20.dp else 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(saveLabel, onSave, Modifier.fillMaxWidth(), enabled = saveEnabled)
            SecondaryButton(t("dlg.cancel", "Cancel"), onCancel, Modifier.fillMaxWidth().heightIn(min = 50.dp))
        }
    } else Row(modifier.fillMaxWidth().padding(horizontal = if (LocalEasy.current) 20.dp else 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(t("dlg.cancel", "Cancel"), onCancel, Modifier.weight(1f).heightIn(min = 50.dp))
        PrimaryButton(saveLabel, onSave, Modifier.weight(1.5f), enabled = saveEnabled)
    }
}

/** A plain tinted icon in a navigation bar or toolbar, 44dp touch target, dims while held. */
@Composable
fun BarIcon(icon: ImageVector, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Color.Unspecified) {
    Box(modifier.size(touchMin(44.dp)).pressDim(enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = if (tint == Color.Unspecified) Apple.colors.accent else tint, modifier = Modifier.size(if (LocalEasy.current) 30.dp else 24.dp))
    }
}

/** A plain tinted text action in a navigation bar ("Edit", "Add", "Save"). */
@Composable
fun BarButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, bold: Boolean = false, color: Color = Color.Unspecified) {
    val fg = if (color == Color.Unspecified) Apple.colors.accent else color
    Box(modifier.heightIn(min = touchMin(44.dp)).pressDim(enabled, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Apple.type.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = fg, maxLines = 2)
    }
}

// ---------- small pieces ----------

/** A Settings-style icon tile: a white glyph on a solid colour, continuous corners. */
@Composable
fun IconTile(icon: ImageVector, color: Color = Color.Unspecified, size: Dp = 30.dp) {
    val bg = if (color == Color.Unspecified) Apple.colors.accent else color
    Box(Modifier.size(size).clip(com.tally.book.ui.theme.ContinuousShape(size * 0.27f)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = com.tally.book.ui.theme.onColorFor(bg), modifier = Modifier.size(size * 0.62f))
    }
}

/** A 0.5dp hairline in the separator colour, optionally inset from the start. */
@Composable
fun Hairline(modifier: Modifier = Modifier, start: Dp = 0.dp) {
    Box(modifier.fillMaxWidth().padding(start = start).height(0.5.dp).background(Apple.colors.separator))
}

/** The sheet grabber: a 36×5 capsule. */
@Composable
fun Grabber(modifier: Modifier = Modifier) {
    Box(modifier.padding(top = 6.dp, bottom = 4.dp).size(36.dp, 5.dp).clip(AppleShapes.capsule).background(Apple.colors.tertiaryLabel))
}

/** Provides the accent as the content colour, so M3 icons and texts inside a bar read as tinted plain controls. */
@Composable
fun Tinted(content: @Composable () -> Unit) = CompositionLocalProvider(LocalContentColor provides Apple.colors.accent, content = content)
