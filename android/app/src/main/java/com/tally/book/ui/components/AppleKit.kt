package com.tally.book.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tally.book.core.*
import com.tally.book.ui.AppNav
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.AppleShapes
import com.tally.book.ui.theme.LocalSemantic
import com.tally.book.ui.theme.Motion
import com.tally.book.ui.theme.Space

/*
 * The Apple-style half of the kit: inset grouped lists (AGroup + ARow), the lead figure, pills and rings,
 * and the navigation shell every pushed screen uses (NavScreen / PushScreen, NavBar, the scroll-edge hairline).
 * Components.kt holds the older names (SectionCard, ListRow...), restyled on the same tokens.
 */

/** How much floating chrome covers the bottom of the screen (none today; kept so a floating button can be added); scrolling content pads past it. */
val LocalBottomChrome = compositionLocalOf { 0.dp }

/** The bottom padding a scrolling body needs: past the tab bar and add button when they show, else a calm 24dp. */
@Composable
fun bottomClearance(): Dp = LocalBottomChrome.current + Space.xxl

/** True inside a cell (a group or card): fields and tiles then use a fill instead of a second cell colour. */
val LocalOnCell = compositionLocalOf { false }

@Composable
internal fun flavorColor(idx: Int): Color {
    val cats = LocalSemantic.current.categories
    return if (cats.isEmpty()) MaterialTheme.colorScheme.primary else cats[((idx % cats.size) + cats.size) % cats.size]
}

/** Small uppercase grey heading above a group (iOS Settings). */
@Composable
fun GroupHead(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 7.dp).semantics { heading() },
        style = Apple.type.footnote.copy(letterSpacing = 0.3.sp), color = Apple.colors.secondaryLabel)
}

/** Small grey explanation under a group. */
@Composable
fun GroupFoot(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Text(text, modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
}

class GroupScope {
    val items = mutableListOf<Pair<Dp, @Composable () -> Unit>>()
    /** One row of the group; [inset] is where its hairline starts (60dp past an icon tile, 16dp otherwise). */
    fun item(inset: Dp = 16.dp, content: @Composable () -> Unit) { items.add(inset to content) }
}

/** The surface of an inset group: a cell-coloured continuous rounded rectangle. Rows inside know they are on a cell. */
@Composable
fun InsetSurface(modifier: Modifier = Modifier, outlined: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val border = if (outlined) androidx.compose.foundation.BorderStroke(1.dp, Apple.colors.cardBorder) else null
    Surface(shape = AppleShapes.group, color = Apple.colors.cell, border = border, modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalOnCell provides true) { Column(content = content) }
    }
}

/** An inset grouped list: continuous 14dp corners, hairlines between rows, optional heading and footer. */
@Composable
fun AGroup(title: String? = null, footer: String? = null, trailingHead: (@Composable () -> Unit)? = null, build: GroupScope.() -> Unit) {
    val g = GroupScope().apply(build)
    if (g.items.isEmpty()) return
    if (title != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            GroupHead(title, Modifier.weight(1f))
            trailingHead?.let { Box(Modifier.padding(end = 16.dp, bottom = 4.dp)) { Tinted { it() } } }
        }
    }
    InsetSurface {
        g.items.forEachIndexed { i, (inset, c) ->
            c()
            if (i < g.items.lastIndex) Hairline(start = inset)
        }
    }
    if (footer != null) GroupFoot(footer)
}

/** The iOS disclosure chevron. */
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, tint = Apple.colors.tertiaryLabel, modifier = modifier.size(14.dp))
}

/** A row: optional icon tile, title with a grey line under it, a value or amount, a chevron when it opens something. */
@Composable
fun ARow(
    title: String, sub: String? = null, icon: ImageVector? = null, tint: Color = Color.Unspecified,
    value: String? = null, valueColor: Color = Color.Unspecified, money: Double? = null, moneyColor: Color = Color.Unspecified,
    subColor: Color = Color.Unspecified, titleColor: Color = Color.Unspecified, chevron: Boolean = false,
    onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null, leading: (@Composable () -> Unit)? = null,
) {
    val c = Apple.colors
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Row((if (onClick != null) base.pressable(onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { IconTile(icon, tint); Spacer(Modifier.width(14.dp)) }
        else if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Apple.type.body, color = if (titleColor == Color.Unspecified) c.label else titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrEmpty()) Text(sub, style = Apple.type.footnote, color = if (subColor == Color.Unspecified) c.secondaryLabel else subColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (money != null) { Spacer(Modifier.width(10.dp)); Money(money, style = Apple.type.body, color = if (moneyColor == Color.Unspecified) c.secondaryLabel else moneyColor) }
        else if (value != null) { Spacer(Modifier.width(10.dp)); Text(value, style = Apple.type.body, color = if (valueColor == Color.Unspecified) c.secondaryLabel else valueColor, maxLines = 1) }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        if (chevron) { Spacer(Modifier.width(8.dp)); Chevron() }
    }
}

/** A centred action inside a group ("Mark paid", "Remove"): accent text, or [color] (the negative colour for a destructive one). */
@Composable
fun ButtonRow(text: String, color: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Apple.type.body, color = color, textAlign = TextAlign.Center)
    }
}

/** A plain paragraph inside a group (a note). */
@Composable
fun NoteRow(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
}

/** The screen's lead figure: small label, large amount, a line under it, and optional content (a ring) on the right. */
@Composable
fun BigFigure(label: String, amount: Double?, sub: String? = null, text: String? = null, color: Color = Color.Unspecified, right: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Apple.type.subheadline.copy(fontWeight = FontWeight.Medium), color = Apple.colors.secondaryLabel)
            val st = Apple.type.figure
            if (amount != null) Money(amount, style = st, color = color) else Text(text.orEmpty(), style = st, color = color, maxLines = 1)
            if (!sub.isNullOrEmpty()) Text(sub, style = Apple.type.subheadline, color = Apple.colors.secondaryLabel)
        }
        right?.invoke()
    }
}

/** A ring showing a share (0..1) with [content] in the middle. */
@Composable
fun ShareRing(fraction: Double?, color: Color, modifier: Modifier = Modifier, size: Dp = 56.dp, stroke: Dp = 6.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val track = Apple.colors.fill
    val target = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat()
    val f by animateFloatAsState(target, if (reduceMotion()) Motion.fade() else Motion.standard(), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val d = Size(this.size.width - w, this.size.height - w)
            val tl = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, tl, d, style = Stroke(w))
            if (f > 0f) drawArc(color, -90f, 360f * f, false, tl, d, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** A small pill: "Due in 3 days", "Closed". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = AppleShapes.capsule, color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 3.dp), style = Apple.type.caption1.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1)
    }
}

/** The large title's style: long titles (Telugu, Hindi, "Ask an LLM to analyse your finances") step down a size so they fit on one line. */
@Composable
fun largeTitleStyle(title: String): TextStyle =
    when {
        title.length > 28 -> Apple.type.title2
        title.length > 20 -> Apple.type.title1
        else -> Apple.type.largeTitle
    }

// ---------- the navigation shell ----------

/**
 * A pushed screen (or a top-level one with [back] = null): an iOS navigation bar whose large title collapses into
 * an inline title as the content scrolls, "‹ [back]" on the left, plain tinted [actions] on the right, and a hairline
 * that appears only once content scrolls under the bar. [content] gets the padding to apply.
 * [largeTitle] = false keeps just the inline bar (forms, tools).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavScreen(
    title: String, back: String? = null, actions: @Composable RowScope.() -> Unit = {},
    largeTitle: Boolean = false, onBack: () -> Unit = { AppNav.back() },
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = if (largeTitle) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    val chrome = LocalBottomChrome.current
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { NavBar(title, back, scroll, largeTitle, onBack, actions) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (chrome > 0.dp) WindowInsets(0) else ScaffoldDefaults.contentWindowInsets,
        content = content,
    )
}

/** A pushed screen whose body is one scrolling column of groups: ‹ [back], large title, then [content]. */
@Composable
fun PushScreen(title: String, back: String, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    NavScreen(title, back, actions) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.gutter).padding(top = 4.dp, bottom = bottomClearance()), content = content)
    }
}

/** The bar's background: the page colour at rest, the bar material once content scrolls under it, plus the scroll-edge hairline. */
@Composable
fun BarSurface(scrolledUnder: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Apple.colors
    val edge by animateFloatAsState(if (scrolledUnder) 1f else 0f, if (reduceMotion()) Motion.fade() else Motion.standard(), label = "edge")
    Column(modifier.fillMaxWidth().background(lerp(c.groupedBackground, c.bar, edge))) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)), content = content)
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.separator.copy(alpha = c.separator.alpha * edge)))
    }
}

/** "‹ Parent": the iOS back button. */
@Composable
fun BackButton(label: String, onClick: () -> Unit = { AppNav.back() }) {
    val desc = t("dlg.back", "Back")
    Row(
        Modifier.heightIn(min = 44.dp).semantics(mergeDescendants = true) { contentDescription = desc }.pressDim(onClick = onClick).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = Apple.colors.accent, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, style = Apple.type.body, color = Apple.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
    }
}

/** Lays out leading | title | trailing with the title centred on the bar (iOS), narrowing it rather than overlapping. */
@Composable
fun NavBarRow(leading: @Composable () -> Unit, title: @Composable () -> Unit, trailing: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(contents = listOf(leading, title, trailing), modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)) { (l, ti, tr), cons ->
        val w = cons.maxWidth
        val loose = Constraints(maxWidth = w, maxHeight = cons.maxHeight)
        val lp = l.map { it.measure(loose.copy(maxWidth = (w * 0.45f).toInt())) }
        val tp = tr.map { it.measure(loose.copy(maxWidth = (w * 0.5f).toInt())) }
        val lw = lp.maxOfOrNull { it.width } ?: 0
        val tw = tp.maxOfOrNull { it.width } ?: 0
        val side = maxOf(lw, tw)
        val gap = 8.dp.roundToPx()
        val titleW = (w - 2 * side - 2 * gap).coerceAtLeast(0)
        val tip = ti.map { it.measure(Constraints(maxWidth = titleW, maxHeight = cons.maxHeight)) }
        val h = maxOf(cons.minHeight, lp.maxOfOrNull { it.height } ?: 0, tp.maxOfOrNull { it.height } ?: 0, tip.maxOfOrNull { it.height } ?: 0)
        layout(w, h) {
            lp.forEach { it.place(0, (h - it.height) / 2) }
            tp.forEach { it.place(w - it.width, (h - it.height) / 2) }
            tip.forEach { it.place((w - it.width) / 2, (h - it.height) / 2) }
        }
    }
}

/** The navigation bar of [NavScreen]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavBar(
    title: String, back: String?, scroll: TopAppBarScrollBehavior?, largeTitle: Boolean = true,
    onBack: () -> Unit = { AppNav.back() }, actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Apple.colors
    val density = LocalDensity.current
    val style = largeTitleStyle(title)
    val largePx = with(density) { style.lineHeight.toPx() + 10.dp.toPx() }
    val state = scroll?.state
    if (largeTitle && state != null) SideEffect { if (state.heightOffsetLimit != -largePx) state.heightOffsetLimit = -largePx }
    val collapsed = if (!largeTitle) 1f else state?.collapsedFraction ?: 0f
    val under = if (largeTitle) collapsed > 0.99f else (state?.contentOffset ?: 0f) < -1f
    BarSurface(under) {
        NavBarRow(
            leading = { if (back != null) BackButton(back, onBack) },
            title = {
                Text(title, style = Apple.type.headline, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer { alpha = if (largeTitle) ((collapsed - 0.7f) / 0.3f).coerceIn(0f, 1f) else 1f })
            },
            trailing = { Tinted { Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = actions) } },
        )
        if (largeTitle) {
            val h = with(density) { (largePx + (state?.heightOffset ?: 0f)).coerceAtLeast(0f).toDp() }
            Box(Modifier.fillMaxWidth().height(h).clipToBounds()) {
                Text(title, style = style, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = Space.gutter + 4.dp, end = Space.gutter, bottom = 6.dp).semantics { heading() })
            }
        }
    }
}
