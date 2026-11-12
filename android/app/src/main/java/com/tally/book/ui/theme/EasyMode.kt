package com.tally.book.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tally.book.core.DevicePrefs

/*
 * Easy mode: the app made bigger and simpler for people who want fewer choices (the owner's parents). It is a DEVICE setting
 * (DevicePrefs "tally-easy"), like Appearance: not in the book, not synced, not in a backup, the same before and after unlock.
 * [EasyMode] holds the switch as Compose state so flipping it redraws everything at once, lock screen included.
 * [EasyRules] is the pure part (the scale, the contrast, the sizes), covered by EasyModeTest.
 */
object EasyMode {
    const val KEY = "tally-easy"

    private var flag by mutableStateOf(EasyRules.parse(DevicePrefs.get(KEY)))

    /** Whether Easy mode is on. Reading it in a composable redraws that composable when it changes. */
    val on: Boolean get() = flag

    fun set(value: Boolean) {
        DevicePrefs.set(KEY, if (value) "1" else "0")
        flag = value
    }
}

/** True inside the app's theme while Easy mode is on. */
val LocalEasy = staticCompositionLocalOf { false }

object EasyRules {
    /** Easy mode asks for text 30 % bigger than the phone's own font size. */
    const val EXTRA_SCALE = 1.3f

    /** The total text scale never goes above this, however large the phone's own font size already is. */
    const val MAX_SCALE = 2.0f

    /** From this font scale, side-by-side pairs (a row's title and amount, Cancel and Save) stack. */
    const val STACK_AT = 1.29f

    /** The smallest touch target in Easy mode, dp. */
    const val MIN_TOUCH = 56

    /** The large title and the keypad digits stop growing here; they wrap instead. */
    const val TITLE_MAX_SCALE = 1.5f

    fun parse(raw: String?): Boolean = raw == "1"

    /**
     * The font scale the app draws with: the phone's own scale ([system]) times [extra] (1 when Easy mode is off), but never more
     * than [MAX_SCALE], and never less than the phone's own scale (a phone already at or above the cap is left as it is).
     * 1.0 -> 1.3, 1.15 -> 1.5, 1.3 -> 1.69, 1.5 -> 1.95, 1.54 and up -> 2.0.
     */
    fun scale(system: Float, extra: Float): Float {
        val s = if (system > 0f) system else 1f
        return if (extra <= 1f) s else maxOf(s, minOf(s * extra, MAX_SCALE))
    }

    fun stacks(fontScale: Float): Boolean = fontScale >= STACK_AT

    /** The factor that holds a style at no more than [maxScale] of its base size when the font scale is [fontScale]; 1 below that. */
    fun capFactor(fontScale: Float, maxScale: Float): Float = if (fontScale > maxScale && fontScale > 0f) maxScale / fontScale else 1f

    /**
     * Stronger contrast for Easy mode: labels darker in light and lighter in dark, secondary text only slightly dimmed from the label,
     * hairlines, outlines and fills clearly visible, a pressed row clearly highlighted.
     */
    fun contrast(c: AppleColors): AppleColors {
        val toward = if (c.dark) Color.White else Color.Black
        val label = if (c.dark) Color.White else Color.Black
        // the secondary label is the label itself, a little dimmed (about 80 %), as an opaque colour
        val secondary = lerp(label, c.cell, 0.2f)
        return c.copy(
            label = label,
            secondaryLabel = secondary,
            tertiaryLabel = if (c.dark) Color(0x99EBEBF5) else Color(0x993C3C43),
            separator = lerp(c.separator, toward, 0.35f),
            cardBorder = lerp(c.cardBorder, toward, 0.45f),
            sectionTitle = if (c.sectionTitle == c.label) label else c.sectionTitle,
            fill = if (c.dark) Color(0x5C767680) else Color(0x33767680),
            highlight = if (c.dark) Color(0x33FFFFFF) else Color(0x26000000),
            switchOff = if (c.dark) Color(0x80787880) else Color(0x55787880),
        )
    }
}

/** A touch target: at least [standard] dp, and at least 56 dp in Easy mode. */
@Composable
fun touchMin(standard: Dp): Dp = if (LocalEasy.current) maxOf(standard, EasyRules.MIN_TOUCH.dp) else standard

/** The side gutter of a screen: 16 dp, 20 dp in Easy mode. */
@Composable
fun gutter(): Dp = if (LocalEasy.current) 20.dp else Space.gutter

/** The space between the cards of a screen: 12 dp, 18 dp in Easy mode. */
@Composable
fun stackGap(): Dp = if (LocalEasy.current) 18.dp else 12.dp

/** True when the text is large enough (the phone's own large font, or Easy mode) that pairs of things should stack instead of sitting side by side. */
@Composable
fun bigText(): Boolean = EasyRules.stacks(LocalDensity.current.fontScale)

/** This style, held to at most [maxScale] of its size however large the font scale is (sizes and line heights shrink by the excess). */
@Composable
fun TextStyle.capped(maxScale: Float = EasyRules.TITLE_MAX_SCALE): TextStyle {
    val k = EasyRules.capFactor(LocalDensity.current.fontScale, maxScale)
    if (k == 1f) return this
    return copy(
        fontSize = if (fontSize.isSp) fontSize * k else fontSize,
        lineHeight = if (lineHeight.isSp) lineHeight * k else lineHeight,
    )
}
