package com.tally.book.ui.theme

import android.os.Build
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

/*
 * Balance's look, the one version 1.0.0 had: Apple-style grouped lists, set in Inter, with cards outlined by a hairline
 * and, in Black, surfaces and hairlines tinted with the accent. Built on Compose Material 3 as the base library.
 *
 * Mode (DB.themeMode): system / light / dark / black ("normal" from the web app reads as light).
 * Colour (DB.colorTheme): the eight web themes, "custom" (DB.customAccent), or "dynamic" (the
 * phone's wallpaper colours, Android 12+). Light and Dark keep neutral surfaces; Black mixes the accent into them.
 *
 * The iOS tokens live in [AppleColors] (read with `Apple.colors`), and are also mapped onto the M3
 * slots so screens that read MaterialTheme.colorScheme get them for free:
 *   background / surface        = grouped background   (#F2F2F7 light, #111113 dark, #000000 black)
 *   surfaceContainer(Low)       = secondary grouped cell (white, #1E1E21, accent-tinted in black)
 *   surfaceContainerHigh        = tertiary grouped       (#EBEBF0 on a white cell, #2C2C2F)
 *   surfaceContainerHighest     = system gray 5 / 4      (tracks, wells)
 *   onSurface / onBackground    = label
 *   onSurfaceVariant            = secondary label (60 %)
 *   outline                     = tertiary label / chevrons
 *   outlineVariant              = separator (hairlines)
 *   primary                     = the accent
 */

data class AccentPair(val name: String, val light: Color, val dark: Color)

val COLOR_THEMES: LinkedHashMap<String, AccentPair> = linkedMapOf(
    "blue" to AccentPair("Blue", Color(0xFF0071E3), Color(0xFF0A84FF)),
    "purple" to AccentPair("Purple", Color(0xFF8944AB), Color(0xFFBF5AF2)),
    "red" to AccentPair("Red", Color(0xFFD70015), Color(0xFFFF453A)),
    "orange" to AccentPair("Orange", Color(0xFFC93400), Color(0xFFFF9F0A)),
    "yellow" to AccentPair("Yellow", Color(0xFFA05A00), Color(0xFFFFD60A)),
    "green" to AccentPair("Green", Color(0xFF248A3D), Color(0xFF30D158)),
    "cyan" to AccentPair("Cyan", Color(0xFF0071A4), Color(0xFF64D2FF)),
    "mono" to AccentPair("Monochrome", Color(0xFF1D1D1F), Color(0xFFF5F5F7)),
)
private val THEME_ALIAS = mapOf("teal" to "cyan", "rose" to "red", "amber" to "orange", "pink" to "red", "graphite" to "mono")
fun colorThemeId(id: String?): String = when {
    id == "custom" || id == "dynamic" -> id
    else -> (THEME_ALIAS[id] ?: id).takeIf { it in COLOR_THEMES } ?: "blue"
}

/** Colours with a meaning, the same in every theme (web: --positive, --negative, --warning). iOS system colours. */
@Immutable
data class Semantic(
    val positive: Color, val negative: Color, val warning: Color, val muted: Color,
    val clean: Color, val categories: List<Color>,
)

val LocalSemantic = staticCompositionLocalOf {
    Semantic(Color(0xFF248A3D), Color(0xFFD70015), Color(0xFFC93400), Color(0x993C3C43), Color(0xFF34C759), emptyList())
}

/** The iOS system colour tokens. Screens read them as `Apple.colors.*` (or through the mapped M3 slots). */
@Immutable
data class AppleColors(
    /** systemGroupedBackground: the page behind inset groups. */
    val groupedBackground: Color,
    /** secondarySystemGroupedBackground: a cell / group surface. */
    val cell: Color,
    /** tertiarySystemGroupedBackground: something set inside a cell (a tile, a well). */
    val tertiary: Color,
    val label: Color,
    /** Secondary label, 60 %. */
    val secondaryLabel: Color,
    /** Tertiary label, 30 %: placeholders, chevrons, quiet icons. */
    val tertiaryLabel: Color,
    /** Hairline separator (drawn 0.5 dp). */
    val separator: Color,
    /** tertiarySystemFill: a text field, a segmented control's track, a capsule button. */
    val fill: Color,
    /** The segmented control's sliding thumb. */
    val thumb: Color,
    /** The switch track when off. */
    val switchOff: Color,
    /** The switch track when on: system green. */
    val switchOn: Color,
    /** Bars (tab bar, scrolled navigation bar): a high-opacity material that content scrolls under. */
    val bar: Color,
    /** Sheets, alerts and toasts: the elevated surface over a dimmed page. */
    val elevated: Color,
    /** A cell on an elevated surface (dark mode lifts it one step). */
    val elevatedCell: Color,
    /** The pressed highlight laid over a row. */
    val highlight: Color,
    /** The 1dp outline of a titled card. */
    val cardBorder: Color,
    /** A card's title: the label, tinted with the accent in Black. */
    val sectionTitle: Color,
    val accent: Color,
    val dark: Boolean,
)

val LocalAppleColors = staticCompositionLocalOf { appleColors(false, false, Color(0xFF0071E3)) }

/** Short access to the tokens: `Apple.colors.secondaryLabel`, `Apple.type.footnote`. */
object Apple {
    val colors: AppleColors @Composable @ReadOnlyComposable get() = LocalAppleColors.current
    val type: AppleType get() = AppleTypeScale
}

fun appleColors(dark: Boolean, black: Boolean, accent: Color): AppleColors = if (!dark) AppleColors(
    groupedBackground = Color(0xFFF2F2F7), cell = Color.White, tertiary = Color(0xFFEBEBF0),
    label = Color(0xFF1D1D1F), secondaryLabel = Color(0xFF6E6E73), tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0xFFDCDCE0), fill = Color(0x1F767680), thumb = Color.White,
    switchOff = Color(0x29787880), switchOn = Color(0xFF34C759),
    bar = Color.White, elevated = Color.White, elevatedCell = Color(0xFFEBEBF0),
    highlight = Color(0x14000000), cardBorder = Color(0xFFDCDCE0), sectionTitle = Color(0xFF1D1D1F), accent = accent, dark = false,
) else if (!black) AppleColors(
    groupedBackground = Color(0xFF111113), cell = Color(0xFF1E1E21), tertiary = Color(0xFF2C2C2F),
    label = Color(0xFFF5F5F7), secondaryLabel = Color(0xFFA1A1A6), tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xFF38383A), fill = Color(0x3D767680),
    thumb = Color(0xFF636366), switchOff = Color(0x52787880), switchOn = Color(0xFF30D158),
    bar = Color(0xFF161617), elevated = Color(0xFF1E1E21), elevatedCell = Color(0xFF2C2C2F),
    highlight = Color(0x1FFFFFFF), cardBorder = Color(0xFF38383A), sectionTitle = Color(0xFFF5F5F7), accent = accent, dark = true,
) else mix(Color(0xFF2E2E36), accent, 0.28f).let { line ->
    // Black: a pure black page, with the accent mixed into the cards, the wells and the lines
    val cell = mix(Color(0xFF0D0D10), accent, 0.07f)
    val well = mix(Color(0xFF131317), accent, 0.13f)
    AppleColors(
        groupedBackground = Color.Black, cell = cell, tertiary = well,
        label = Color.White, secondaryLabel = Color(0xFFBCBCC4), tertiaryLabel = Color(0x4DEBEBF5),
        separator = line, fill = Color(0x3D767680),
        thumb = Color(0xFF636366), switchOff = Color(0x52787880), switchOn = Color(0xFF30D158),
        bar = Color.Black, elevated = cell, elevatedCell = well,
        highlight = Color(0x1FFFFFFF), cardBorder = line, sectionTitle = mix(Color.White, accent, 0.38f), accent = accent, dark = true,
    )
}

fun onColorFor(c: Color): Color = if (ColorUtils.calculateLuminance(c.toArgb()) > 0.45) Color(0xFF111113) else Color.White

private fun mix(a: Color, b: Color, t: Float) = Color(ColorUtils.blendARGB(a.toArgb(), b.toArgb(), t))

/** iOS categorical colours (light / dark). The order is fixed: a category's colour is its index. */
private val CATS_LIGHT = listOf(0xFF34C759, 0xFF30B0C7, 0xFF007AFF, 0xFF5856D6, 0xFFFF2D55, 0xFFFF9500, 0xFFA2845E, 0xFF8E8E93).map { Color(it) }
private val CATS_DARK = listOf(0xFF30D158, 0xFF40C8E0, 0xFF0A84FF, 0xFF5E5CE6, 0xFFFF375F, 0xFFFF9F0A, 0xFFAC8E68, 0xFF98989D).map { Color(it) }

private fun scheme(a: AppleColors): ColorScheme {
    val accent = a.accent
    val base = if (a.dark) darkColorScheme() else lightColorScheme()
    val red = if (a.dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    // M3 reads opaque colours better for its own components: flatten the translucent labels onto the cell
    val secondary = Color(ColorUtils.compositeColors(a.secondaryLabel.toArgb(), a.cell.toArgb()))
    val tertiary = Color(ColorUtils.compositeColors(a.tertiaryLabel.toArgb(), a.cell.toArgb()))
    return base.copy(
        primary = accent, onPrimary = onColorFor(accent),
        primaryContainer = mix(a.cell, accent, if (a.dark) 0.26f else 0.14f), onPrimaryContainer = if (a.dark) mix(accent, Color.White, 0.5f) else mix(accent, Color.Black, 0.45f),
        inversePrimary = if (a.dark) mix(accent, Color.Black, 0.3f) else mix(accent, Color.White, 0.4f),
        secondary = secondary, onSecondary = a.cell,
        secondaryContainer = mix(a.cell, accent, if (a.dark) 0.18f else 0.10f), onSecondaryContainer = a.label,
        tertiary = if (a.dark) Color(0xFF5E5CE6) else Color(0xFF5856D6), onTertiary = Color.White,
        tertiaryContainer = mix(a.cell, if (a.dark) Color(0xFF5E5CE6) else Color(0xFF5856D6), 0.16f), onTertiaryContainer = a.label,
        background = a.groupedBackground, onBackground = a.label,
        surface = a.groupedBackground, onSurface = a.label,
        surfaceVariant = a.tertiary, onSurfaceVariant = secondary,
        surfaceTint = Color.Transparent,
        inverseSurface = if (a.dark) Color(0xFFF2F2F7) else Color(0xFF1C1C1E), inverseOnSurface = if (a.dark) Color.Black else Color.White,
        error = red, onError = Color.White, errorContainer = mix(a.cell, red, 0.16f), onErrorContainer = a.label,
        outline = tertiary, outlineVariant = a.separator,
        scrim = Color.Black,
        surfaceBright = a.cell, surfaceDim = a.groupedBackground,
        surfaceContainerLowest = if (a.dark) Color.Black else Color.White,
        surfaceContainerLow = a.cell, surfaceContainer = a.cell,
        surfaceContainerHigh = a.tertiary,
        surfaceContainerHighest = if (a.dark) mix(a.tertiary, Color.White, 0.08f) else Color(0xFFE5E5EA),
    )
}

/** The appearance settings the theme needs (read from DB / device prefs by the caller). */
data class Appearance(val mode: String = "system", val color: String = "blue", val customAccent: String = "#0071E3", val fontScale: Float = 1f)

@Composable
fun isDarkFor(mode: String): Boolean = when (mode) { "dark", "black" -> true; "light", "normal" -> false; else -> isSystemInDarkTheme() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TallyTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isDarkFor(appearance.mode)
    val black = appearance.mode == "black"
    val ctx = LocalContext.current
    val colorId = colorThemeId(appearance.color)
    val accent = when {
        colorId == "dynamic" && Build.VERSION.SDK_INT >= 31 -> (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)).primary
        colorId == "custom" -> {
            val c = runCatching { Color(android.graphics.Color.parseColor(appearance.customAccent)) }.getOrDefault(Color(0xFF0071E3))
            if (dark) mix(c, Color.White, 0.18f) else c
        }
        else -> COLOR_THEMES[if (colorId == "dynamic") "blue" else colorId]!!.let { if (dark) it.dark else it.light }
    }
    val apple = remember(dark, black, accent) { appleColors(dark, black, accent) }
    val scheme = remember(apple) { scheme(apple) }
    val semantic = Semantic(
        // iOS system green / red / orange; light mode uses their high-contrast variants so figures stay legible on white
        positive = if (dark) Color(0xFF30D158) else Color(0xFF248A3D),
        negative = if (dark) Color(0xFFFF6961) else Color(0xFFD70015),
        warning = if (dark) Color(0xFFFFD60A) else Color(0xFFA05A00),
        muted = scheme.onSurfaceVariant,
        clean = if (dark) Color(0xFF30D158) else Color(0xFF34C759),
        categories = if (dark) CATS_DARK else CATS_LIGHT,
    )
    CompositionLocalProvider(LocalSemantic provides semantic, LocalAppleColors provides apple) {
        AppleMaterial(scheme, content)
    }
}

/** MaterialTheme with the app's type and shapes, and no Material ripples: plain clickables get the iOS highlight, M3 components none (use the kit's buttons). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppleMaterial(scheme: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = BalanceTypography, shapes = BalanceShapes) {
        CompositionLocalProvider(LocalIndication provides PressHighlight, LocalRippleConfiguration provides null, content = content)
    }
}

/**
 * The elevated level of sheets and alerts. In dark mode iOS lifts a sheet one step (#1C1C1E page, #2C2C2E cells) so it
 * reads above the dimmed page; in light mode it is the same as the page. Both the tokens and the M3 slots follow.
 */
@Composable
fun ElevatedLevel(content: @Composable () -> Unit) {
    val c = Apple.colors
    val lifted = remember(c) {
        c.copy(groupedBackground = c.elevated, cell = c.elevatedCell)
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, lifted) {
        base.copy(
            background = lifted.groupedBackground, surface = lifted.groupedBackground,
            surfaceContainerLow = lifted.cell, surfaceContainer = lifted.cell, surfaceContainerHigh = lifted.tertiary,
        )
    }
    CompositionLocalProvider(LocalAppleColors provides lifted) { AppleMaterial(scheme, content) }
}

// ---------- typography: the iOS text styles set in Inter, as in 1.0.0 (Telugu and Hindi letters fall through to the system fonts) ----------

@OptIn(ExperimentalTextApi::class)
private fun inter(w: FontWeight) = Font(com.tally.book.R.font.inter_var, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)))
private val InterFamily = FontFamily(inter(FontWeight.Normal), inter(FontWeight.Medium), inter(FontWeight.SemiBold), inter(FontWeight.Bold))

private const val TNUM = "tnum"
private val tight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, track: Double, weight: FontWeight, tnum: Boolean = false) = TextStyle(
    fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp, letterSpacing = track.sp, fontWeight = weight,
    fontFeatureSettings = if (tnum) TNUM else null, lineHeightStyle = tight, platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** The iOS type scale. Tracking is size-specific: tight on large text, slightly open on small. Line heights leave room for Telugu and Hindi. */
class AppleType internal constructor() {
    val largeTitle = style(34, 41, -0.4, FontWeight.Bold, tnum = true)
    val title1 = style(28, 34, -0.3, FontWeight.Bold, tnum = true)
    val title2 = style(22, 28, -0.2, FontWeight.Bold, tnum = true)
    val title3 = style(20, 25, -0.15, FontWeight.SemiBold, tnum = true)
    val headline = style(17, 22, -0.37, FontWeight.SemiBold)
    val body = style(16, 23, -0.18, FontWeight.Normal)
    val callout = style(15, 21, -0.15, FontWeight.Normal)
    val subheadline = style(15, 20, -0.1, FontWeight.Normal)
    val footnote = style(13, 18, 0.0, FontWeight.Normal)
    val caption1 = style(12, 16, 0.1, FontWeight.Normal)
    val caption2 = style(11, 13, 0.2, FontWeight.Normal)
    /** Big figures (a hero amount): bold, tight, tabular. */
    val figure = style(36, 43, -1.26, FontWeight.Bold, tnum = true)
    val figureLarge = style(48, 54, -1.0, FontWeight.Bold, tnum = true)
}
internal val AppleTypeScale = AppleType()

/** The iOS scale mapped onto M3's slots, so every `MaterialTheme.typography.*` already reads as iOS. */
val BalanceTypography = AppleTypeScale.let { a ->
    Typography(
        displayLarge = a.figureLarge,
        displayMedium = style(40, 46, -0.8, FontWeight.Bold, tnum = true),
        displaySmall = a.figure,
        headlineLarge = a.largeTitle,
        headlineMedium = a.title1,
        headlineSmall = a.title2,
        titleLarge = a.title3,
        titleMedium = a.headline,
        titleSmall = a.subheadline.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = a.body,
        bodyMedium = a.callout,
        bodySmall = a.footnote,
        labelLarge = a.subheadline.copy(fontWeight = FontWeight.Medium),
        labelMedium = a.footnote,
        labelSmall = a.caption1,
    )
}

/** Figures line up in columns (tabular numerals). */
val AmountStyle: TextStyle @Composable get() = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = TNUM, fontWeight = FontWeight.Medium)

/** Tracking for a custom size, following the same table (negative as text grows). */
fun trackingFor(sizeSp: Float): TextUnit = when {
    sizeSp >= 34 -> (-0.4).sp; sizeSp >= 28 -> (-0.3).sp; sizeSp >= 22 -> (-0.2).sp; sizeSp >= 17 -> (-0.2).sp
    sizeSp >= 15 -> (-0.1).sp; sizeSp >= 13 -> 0.sp; else -> 0.2.sp
}

// ---------- shapes ----------

/** M3's shape slots (M3 needs plain rounded corners); the kit draws its own surfaces with [ContinuousShape]. */
val BalanceShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp),
)
