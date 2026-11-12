package com.tally.book.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.DB
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.AppleShapes
import com.tally.book.ui.theme.ElevatedLevel
import com.tally.book.ui.theme.LocalSemantic
import com.tally.book.ui.theme.Motion
import java.time.Instant
import java.time.ZoneOffset

/*
 * The shared building blocks every screen uses, so the whole app reads as one thing: Apple's design language
 * (inset grouped cells, iOS type, springs, press-on-touch-down, no ripples). The look is described in android/UI.md.
 *
 * Reading the ledger in a screen: DB is plain mutable data, so a screen reads [rememberLedger] (the
 * revision Ledger bumps after every change) and keys its computations on it:
 *     val rev = rememberLedger()
 *     val c = remember(rev) { calc() }
 * Changing the ledger: Ledger.edit { ...mutate DB... } (redraws and saves).
 */

@Composable
fun rememberLedger(): Long = Ledger.rev.collectAsStateWithLifecycle().value

/** The eye in the top bar: every amount is masked while it is on (DB.hideAmounts). */
@Composable
fun amountsHidden(): Boolean { rememberLedger(); return DB.b("hideAmounts") }

const val MASK = "₹ ••••"

/** An amount inside a sentence: MASK while amounts are hidden (DB.hideAmounts), otherwise inr(v). */
@Composable
fun amtText(v: Double): String { rememberLedger(); return amtMask(v) }

/** The same for helpers that are not composable (a label builder, a snackbar text): MASK while amounts are hidden. Screens redraw on the eye, so it reads DB directly. */
fun amtMask(v: Double): String = if (DB.b("hideAmounts")) MASK else inr(v)

/** An amount as `inr()` writes it, masked while amounts are hidden. Always tabular figures. */
@Composable
fun Money(
    value: Double, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified, signed: Boolean = false, fontWeight: FontWeight? = null,
) {
    val hidden = amountsHidden()
    val text = when {
        hidden -> MASK
        signed && value > 0.5 -> "+" + inr(value)
        else -> inr(value)
    }
    Text(text, modifier, style = style.copy(fontFeatureSettings = "tnum"), color = color, fontWeight = fontWeight, maxLines = 1)
}

/** Positive figures in the positive colour, negative in the negative one. */
@Composable
fun SignedMoney(value: Double, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val s = LocalSemantic.current
    Money(value, modifier, style, color = if (value < -0.5) s.negative else if (value > 0.5) s.positive else Color.Unspecified, signed = true)
}

/**
 * An inset grouped section: an optional header ([title] in Headline, [subtitle] in grey, [trailing] actions) sitting
 * above a cell-coloured card with continuous corners, and the [content] inside it.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier, title: String? = null, subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        InsetSurface(outlined = true) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = Apple.type.headline, color = Apple.colors.sectionTitle)
                        if (subtitle != null) Text(subtitle, style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
                    }
                    trailing?.let { tr -> Tinted { Row(verticalAlignment = Alignment.CenterVertically) { tr() } } }
                }
                Hairline(start = 16.dp)
            }
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** A grouped card whose body folds away under its header row; the open/shut state is kept in DB.shut[key] like the web app's folds. */
@Composable
fun FoldCard(
    key: String, title: String, modifier: Modifier = Modifier, summary: (@Composable () -> Unit)? = null,
    defaultShut: Boolean = false, heldOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit,
) {
    val rev = rememberLedger()
    val stored = remember(rev, key) { DB.o("shut")?.let { if (it.containsKey(key)) it.b(key) else defaultShut } ?: defaultShut }
    val shut = stored && !heldOpen   // heldOpen shows the body without touching the stored state, e.g. while a search has matches inside
    val calm = reduceMotion()
    val angle by animateFloatAsState(if (shut) 0f else 90f, if (calm) Motion.fade() else Motion.control(), label = "fold")
    InsetSurface(modifier, outlined = true) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(enabled = !heldOpen) { Ledger.edit { DB.oo("shut")[key] = if (shut) 0 else 1 } }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Apple.type.headline, color = Apple.colors.sectionTitle, modifier = Modifier.weight(1f))
            summary?.let { Spacer(Modifier.width(8.dp)); it() }
            Spacer(Modifier.width(10.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForwardIos, null, Modifier.size(14.dp).rotate(angle), tint = Apple.colors.tertiaryLabel)
        }
        AnimatedVisibility(
            !shut,
            enter = if (calm) fadeIn(Motion.fade()) else expandVertically(Motion.standard()) + fadeIn(Motion.standard()),
            exit = if (calm) fadeOut(Motion.fade()) else shrinkVertically(Motion.standard()) + fadeOut(Motion.standard()),
        ) {
            Column {
                Hairline(start = 16.dp)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp), content = content)
            }
        }
    }
}

/** A band title between groups of cards ("Broken down", "Due soon"...): Title 3, bold. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Apple.type.title3.copy(fontWeight = FontWeight.Bold), color = Apple.colors.label, modifier = Modifier.weight(1f))
        action?.let { a -> Tinted { Row(verticalAlignment = Alignment.CenterVertically) { a() } } }
    }
}

/**
 * One iOS cell: headline (Body), optional supporting line (Footnote, grey), optional leading icon tile / colour dot /
 * custom [leading], then an [amount], a grey [value], anything [trailing], and a [chevron] when it opens something.
 * Presses on touch-down (scale + highlight, no ripple); [onLongClick] adds a long press with a haptic.
 */
@Composable
fun ListRow(
    headline: String, modifier: Modifier = Modifier, supporting: String? = null,
    leadingIcon: ImageVector? = null, leadingColor: Color? = null, leading: (@Composable () -> Unit)? = null,
    amount: Double? = null, amountColor: Color = Color.Unspecified, trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    value: String? = null, chevron: Boolean = false, headlineColor: Color = Color.Unspecified,
) {
    val interactive = onClick != null || onLongClick != null
    val m = modifier.fillMaxWidth().heightIn(min = 44.dp).clip(AppleShapes.field)
        .then(if (interactive) Modifier.pressable(onLongClick = onLongClick) { onClick?.invoke() } else Modifier)
    CellRow(m, headline, supporting, leadingIcon, leadingColor, leading, amount, amountColor, value, trailing, chevron, headlineColor)
}

@Composable
private fun CellRow(
    modifier: Modifier, headline: String, supporting: String?, leadingIcon: ImageVector?, leadingColor: Color?, leading: (@Composable () -> Unit)?,
    amount: Double?, amountColor: Color, value: String?, trailing: (@Composable () -> Unit)?, chevron: Boolean, headlineColor: Color,
) {
    val c = Apple.colors
    Row(modifier.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            leading != null -> { leading(); Spacer(Modifier.width(14.dp)) }
            leadingIcon != null -> { IconTile(leadingIcon, leadingColor ?: c.accent); Spacer(Modifier.width(14.dp)) }
            leadingColor != null -> { Box(Modifier.size(10.dp).clip(AppleShapes.capsule).background(leadingColor)); Spacer(Modifier.width(14.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(headline, style = Apple.type.body, color = if (headlineColor == Color.Unspecified) c.label else headlineColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!supporting.isNullOrEmpty()) Text(supporting, style = Apple.type.footnote, color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (amount != null) { Spacer(Modifier.width(12.dp)); Money(amount, style = Apple.type.body, color = if (amountColor == Color.Unspecified) c.label else amountColor, fontWeight = FontWeight.Medium) }
        if (value != null) { Spacer(Modifier.width(10.dp)); Text(value, style = Apple.type.body, color = c.secondaryLabel, maxLines = 1) }
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
        if (chevron) { Spacer(Modifier.width(8.dp)); Chevron() }
    }
}

/** A tinted icon tile (the glyph in [color] on a wash of it). For a solid Settings-style tile use [IconTile]. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(com.tally.book.ui.theme.ContinuousShape(size * 0.27f)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

/** A labelled figure in a tile (two side by side under a hero): Footnote label, Title 2 figure. */
@Composable
fun StatTile(label: String, value: Double, modifier: Modifier = Modifier, color: Color = Color.Unspecified, onClick: (() -> Unit)? = null) {
    val c = Apple.colors
    val bg = if (LocalOnCell.current) c.tertiary else c.cell
    Column(
        modifier.clip(AppleShapes.cell).background(bg)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = Apple.type.footnote.copy(fontWeight = FontWeight.Medium), color = c.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Money(value, style = Apple.type.title2, color = color)
    }
}

/** The big figure at the top of a tab: a cell with a grey label, the amount in large bold tabular figures, and a line under it. */
@Composable
fun Hero(label: String, value: Double, modifier: Modifier = Modifier, sub: String? = null, color: Color = Color.Unspecified, flat: Boolean = false, content: (@Composable ColumnScope.() -> Unit)? = null) {
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = if (flat) 4.dp else 18.dp, vertical = if (flat) 4.dp else 16.dp)) {
            Text(label, style = Apple.type.subheadline.copy(fontWeight = FontWeight.Medium), color = Apple.colors.secondaryLabel)
            Money(value, style = Apple.type.figure, color = color)
            if (sub != null) Text(sub, style = Apple.type.subheadline, color = Apple.colors.secondaryLabel)
            content?.invoke(this)
        }
    }
    if (flat) Box(modifier.fillMaxWidth()) { body() } else InsetSurface(modifier, outlined = true) { body() }
}

/** A quiet, centred message for an empty list, with an optional plain action. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.Inbox, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Apple.colors.tertiaryLabel, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, style = Apple.type.subheadline, color = Apple.colors.secondaryLabel, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(6.dp)); PlainButton(action, onAction, bold = true) }
    }
}

// ---------- inputs: iOS inset fields (a filled cell, no outline, the label above) ----------

/** Formats a stored number for editing ("" for 0). */
fun editText(v: Double): String = if (v == 0.0) "" else jsNumber(v)

/** The frame every field shares: the label above, the filled cell, a grey line under it. */
@Composable
fun FieldFrame(label: String?, supporting: String? = null, modifier: Modifier = Modifier, cell: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Apple.colors
    val fill = if (LocalOnCell.current) c.fill else c.cell
    Column(modifier.fillMaxWidth()) {
        if (!label.isNullOrEmpty()) Text(label, style = Apple.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(AppleShapes.field).background(fill).then(cell).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, content = content,
        )
        if (!supporting.isNullOrEmpty()) Text(supporting, style = Apple.type.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp))
    }
}

@Composable
private fun FieldText(
    value: String, onValue: (String) -> Unit, placeholder: String?, modifier: Modifier, singleLine: Boolean = true, minLines: Int = 1,
    keyboard: KeyboardOptions = KeyboardOptions.Default, style: TextStyle = Apple.type.body,
) {
    val c = Apple.colors
    BasicTextField(
        value, onValue, modifier, singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        textStyle = style.copy(color = c.label), cursorBrush = SolidColor(c.accent), keyboardOptions = keyboard,
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && !placeholder.isNullOrEmpty()) Text(placeholder, style = style, color = c.tertiaryLabel, maxLines = if (singleLine) 1 else 3)
                inner()
            }
        },
    )
}

/** A money field: ₹ prefix, number keyboard, tabular figures. [onValue] gets n(text). */
@Composable
fun MoneyField(
    value: Double, onValue: (Double) -> Unit, label: String, modifier: Modifier = Modifier,
    supporting: String? = null, imeDone: Boolean = false, autoFocus: Boolean = false,
) {
    var text by remember { mutableStateOf(editText(value)) }
    LaunchedEffect(value) { if (n(text) != value) text = editText(value) }
    val fr = remember { FocusRequester() }
    FieldFrame(label, supporting, modifier) {
        Text("₹", style = Apple.type.body, color = Apple.colors.secondaryLabel)
        Spacer(Modifier.width(6.dp))
        FieldText(
            text, { t -> val clean = t.filter { it.isDigit() || it == '.' }; text = clean; onValue(n(clean)) }, "0",
            Modifier.weight(1f).focusRequesterIf(autoFocus, fr),
            keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = if (imeDone) ImeAction.Done else ImeAction.Next),
            style = Apple.type.body.copy(fontFeatureSettings = "tnum"),
        )
    }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

private fun Modifier.focusRequesterIf(on: Boolean, fr: FocusRequester) = if (on) this.focusRequester(fr) else this

/** A plain text field. */
@Composable
fun TextInput(
    value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    singleLine: Boolean = true, placeholder: String? = null, keyboard: KeyboardType = KeyboardType.Text, supporting: String? = null,
) {
    FieldFrame(label, supporting, modifier) {
        FieldText(
            value, onValue, placeholder, Modifier.weight(1f), singleLine = singleLine, minLines = 3,
            keyboard = KeyboardOptions(keyboardType = keyboard, capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

/** A date as a tappable field; opens the date picker. ISO in, ISO out ("" = none). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(iso: String, onIso: (String) -> Unit, label: String, modifier: Modifier = Modifier, clearable: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    val c = Apple.colors
    FieldFrame(label, null, modifier, cell = Modifier.pressable(scaleTo = 1f) { open = true }) {
        val shown = fmtISODate(iso)
        Text(shown.ifEmpty { "—" }, style = Apple.type.body, color = if (shown.isEmpty()) c.tertiaryLabel else c.label, modifier = Modifier.weight(1f), maxLines = 1)
        Icon(Icons.Rounded.CalendarMonth, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
    if (open) {
        val init = parseISO(iso)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = init ?: Clock.today().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            shape = AppleShapes.alert,
            colors = DatePickerDefaults.colors(containerColor = c.elevatedCell),
            confirmButton = {
                PlainButton(t("dlg.ok", "OK"), {
                    state.selectedDateMillis?.let { onIso(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }, bold = true)
            },
            dismissButton = {
                Row {
                    if (clearable) PlainButton(t("dlg.clear", "Clear"), { onIso(""); open = false })
                    PlainButton(t("dlg.cancel", "Cancel"), { open = false })
                }
            },
        ) { DatePicker(state, colors = DatePickerDefaults.colors(containerColor = c.elevatedCell)) }
    }
}

/**
 * An in-page single choice (value to label). Two to four short options draw as an iOS segmented control with a
 * sliding thumb; more (or long ones, as Telugu and Hindi often are) wrap as capsules. Never for navigation.
 */
@Composable
fun ChoiceChips(options: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier, colors: Map<String, Color> = emptyMap()) {
    val segmented = options.size in 2..4 && options.all { it.second.length <= 14 } && options.sumOf { it.second.length } <= 36
    if (segmented) SegmentedControl(options, selected, onSelect, modifier, colors = colors)
    else CapsuleChoice(options, selected, onSelect, modifier, colors)
}

/** A switch row (Settings): the whole row toggles, the iOS switch on the right, a haptic on every flip. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null) {
    val haptics = rememberHaptics()
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val m = modifier.fillMaxWidth().heightIn(min = 44.dp)
        .toggleable(checked, src, indication = null, role = Role.Switch) { haptics.toggle(it); onChange(it) }
    CellRow(m, title, subtitle, icon, null, null, null, Color.Unspecified, null, { AppleSwitch(checked, null) }, false, Color.Unspecified)
}

// ---------- sheets, dialogs, toasts ----------

/**
 * A form in a sheet: a grabber, the title with a close button, the [content], then Cancel and Save side by side,
 * pinned under it ([saveEnabled]). [onSave] = null has just the close button. [onDelete] adds a red Delete row at the end of the body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(
    title: String, onDismiss: () -> Unit, onSave: (() -> Unit)?, saveLabel: String = t("dlg.save", "Save"),
    saveEnabled: Boolean = true, onDelete: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    // A form is not closed by a drag or a stray tap outside: Material's sheet dismissed after a short pull (or a pull while
    // scrolling the form back to the top) and threw the typed entry away. The sheet refuses to hide unless X, Cancel or Back
    // asked for it, so a drag springs back. It has no grabber, since it no longer drags. A sheet with nothing to save keeps both.
    val form = onSave != null
    val closing = remember { mutableStateOf(false) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !form || closing.value })
    val close = { closing.value = true; onDismiss() }
    val haptics = rememberHaptics()
    val negative = LocalSemantic.current.negative
    ModalBottomSheet(
        // a tall sheet stops below the status bar: under it, the system takes the taps meant for Cancel and Save
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
        onDismissRequest = onDismiss, sheetState = state, containerColor = Apple.colors.elevated, shape = AppleShapes.sheet,
        tonalElevation = 0.dp, scrimColor = Color.Black.copy(alpha = 0.35f),
        dragHandle = if (form) ({ Spacer(Modifier.height(12.dp)) }) else ({ Grabber() }),
    ) {
        if (form) androidx.activity.compose.BackHandler { close() }
        ElevatedLevel {
            Column(Modifier.fillMaxWidth().imePadding()) {
                SheetHeader(title, close)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                    if (onDelete != null) {
                        Spacer(Modifier.height(4.dp))
                        InsetSurface { ButtonRow(t("dlg.delete", "Delete"), negative, onDelete) }
                    }
                }
                if (onSave != null) SheetActions(saveLabel, saveEnabled, onCancel = close, onSave = { haptics.confirm(); onSave() })
            }
        }
    }
}

/** The app's confirm/notice dialogs and its undo toast, reachable from anywhere (UiHooks.ask/tell use them). */
object Dialogs {
    data class Ask(val message: String, val ok: String, val cancel: String?, val danger: Boolean, val done: (Boolean) -> Unit)
    val current = mutableStateOf<Ask?>(null)
    val snackbar = SnackbarHostState()

    suspend fun ask(message: String, ok: String? = null, danger: Boolean = false): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { c ->
            current.value = Ask(message, ok ?: t("dlg.ok", "OK"), t("dlg.cancel", "Cancel"), danger) { r -> current.value = null; if (c.isActive) c.resumeWith(Result.success(r)) }
        }

    suspend fun tell(message: String) {
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { c ->
            current.value = Ask(message, t("dlg.ok", "OK"), null, false) { current.value = null; if (c.isActive) c.resumeWith(Result.success(Unit)) }
        }
    }

    private val scope = kotlinx.coroutines.MainScope()
    /** `showSnack(text, actionLabel, onAction)`; `showHint(text)` is this without an action. Shown as a floating toast. */
    fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, actionLabel = action, withDismissAction = action == null, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

/**
 * The iOS alert for [Dialogs.ask]/[Dialogs.tell]: a centred title (the message's first paragraph) and message, buttons
 * under a hairline (side by side, or stacked when the words are long), the destructive action in red. It scales in on
 * a critically damped spring (a fade with reduced motion).
 */
@Composable
fun DialogHost() {
    val a = Dialogs.current.value ?: return
    val c = Apple.colors
    val s = LocalSemantic.current
    val haptics = rememberHaptics()
    val calm = reduceMotion()
    val appear = remember(a) { Animatable(0f) }
    LaunchedEffect(a) { appear.animateTo(1f, if (calm) Motion.fade() else Motion.control()) }
    val parts = a.message.split("\n\n", limit = 2)
    val head = parts[0].trim()
    val body = parts.getOrNull(1)?.trim().orEmpty()
    val titled = body.isNotEmpty() || head.length <= 110
    Dialog(onDismissRequest = { a.done(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        ElevatedLevel {
            Column(
                Modifier.widthIn(max = 300.dp).fillMaxWidth(0.78f)
                    .graphicsLayer { val k = appear.value; alpha = k; if (!calm) { scaleX = 1.12f - 0.12f * k; scaleY = scaleX } }
                    .clip(AppleShapes.alert).background(c.cell),
            ) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (titled) Text(head, style = Apple.type.headline, color = c.label, textAlign = TextAlign.Center)
                    else Text(head, style = Apple.type.subheadline, color = c.label, textAlign = TextAlign.Center)
                    if (body.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(body, style = Apple.type.subheadline, color = c.label, textAlign = TextAlign.Center) }
                }
                Hairline()
                val okColor = if (a.danger) s.negative else c.accent
                val ok: @Composable (Modifier) -> Unit = { m ->
                    AlertButton(a.ok, okColor, bold = !a.danger, modifier = m) { if (a.danger) haptics.destructive(); a.done(true) }
                }
                val cancel = a.cancel
                when {
                    cancel == null -> ok(Modifier.fillMaxWidth())
                    a.ok.length + cancel.length > 22 -> Column {
                        ok(Modifier.fillMaxWidth()); Hairline()
                        AlertButton(cancel, c.accent, bold = a.danger, modifier = Modifier.fillMaxWidth()) { a.done(false) }
                    }
                    else -> Row(Modifier.height(IntrinsicSize.Min)) {
                        AlertButton(cancel, c.accent, bold = a.danger, modifier = Modifier.weight(1f)) { a.done(false) }
                        Box(Modifier.fillMaxHeight().width(0.5.dp).background(c.separator))
                        ok(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertButton(text: String, color: Color, bold: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 46.dp).pressable(scaleTo = 1f, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Apple.type.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal), color = color, textAlign = TextAlign.Center)
    }
}

/** The floating capsule toast for [Dialogs.snack] (one host, placed by the app shell above the tab bar). */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    SnackbarHost(Dialogs.snackbar, modifier) { data -> Toast(data) }
}

@Composable
private fun Toast(data: SnackbarData) {
    val c = Apple.colors
    val haptics = rememberHaptics()
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.shadow(18.dp, AppleShapes.capsule, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(AppleShapes.capsule).background(c.elevatedCell)
                .pressable(scaleTo = 0.98f, highlight = false) { data.dismiss() }
                .heightIn(min = 48.dp).padding(start = 20.dp, end = if (data.visuals.actionLabel != null) 10.dp else 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(data.visuals.message, style = Apple.type.subheadline.copy(fontWeight = FontWeight.Medium), color = c.label, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            data.visuals.actionLabel?.let { label ->
                Spacer(Modifier.width(8.dp))
                PlainButton(label, { haptics.confirm(); data.performAction() }, bold = true)
            }
        }
    }
}

// ---------- small charts ----------

/** Horizontal share bars: label, amount, fraction of the largest. */
@Composable
fun ShareBar(label: String, value: Double, max: Double, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val frac = if (max > 0) (value / max).toFloat().coerceIn(0f, 1f) else 0f
    val anim by animateFloatAsState(frac, if (reduceMotion()) Motion.fade() else Motion.standard(), label = "bar")
    Column(modifier.fillMaxWidth().clip(AppleShapes.field).then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier).padding(vertical = 6.dp, horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = Apple.type.subheadline, color = Apple.colors.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Money(value, style = Apple.type.subheadline, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(AppleShapes.capsule).background(Apple.colors.fill)) {
            Box(Modifier.fillMaxWidth(anim).fillMaxHeight().clip(AppleShapes.capsule).background(color))
        }
    }
}

/** A progress bar toward a target (goals, limits): a capsule on a grey track. Over 100% shows in the negative colour. */
@Composable
fun ProgressLine(fraction: Double, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val over = fraction > 1.0
    val f by animateFloatAsState(fraction.toFloat().coerceIn(0f, 1f), if (reduceMotion()) Motion.fade() else Motion.standard(), label = "progress")
    Box(modifier.fillMaxWidth().height(8.dp).clip(AppleShapes.capsule).background(Apple.colors.fill)) {
        if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(AppleShapes.capsule).background(if (over) LocalSemantic.current.negative else color))
    }
}

/** Vertical bars over time (Insights). values may be negative. */
@Composable
fun BarChart(values: List<Double>, labels: List<String>, modifier: Modifier = Modifier.height(160.dp), color: Color = MaterialTheme.colorScheme.primary, highlight: Int = -1, onBar: ((Int) -> Unit)? = null) {
    val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val track = Apple.colors.fill
    val spec = if (reduceMotion()) Motion.fade<Float>() else Motion.standard()
    val haptics = rememberHaptics()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            values.forEachIndexed { i, v ->
                val f by animateFloatAsState((v / max).toFloat().coerceIn(0f, 1f), spec, label = "bar$i")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(AppleShapes.tile)
                        .then(if (onBar != null) Modifier.pressDim { haptics.tick(); onBar(i) } else Modifier),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight().background(track.copy(alpha = track.alpha * 0.6f)))
                    Box(Modifier.fillMaxWidth().fillMaxHeight(f.coerceAtLeast(0.01f)).clip(AppleShapes.tile).background(if (highlight == -1 || highlight == i) color else color.copy(alpha = 0.4f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), style = Apple.type.caption2, color = Apple.colors.secondaryLabel, maxLines = 1, textAlign = TextAlign.Center) }
        }
    }
}

/** A ring split into coloured parts (where the money went). */
@Composable
fun Donut(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier.size(140.dp), thickness: Dp = 18.dp, center: (@Composable () -> Unit)? = null) {
    val total = parts.sumOf { it.first }.takeIf { it > 0 } ?: 1.0
    val track = Apple.colors.fill
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = thickness.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Butt)
            val inset = thickness.toPx() / 2
            val sz = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, tl, sz, style = stroke)
            var start = -90f
            parts.forEach { (v, c) ->
                val sweep = (v / total * 360).toFloat()
                if (sweep > 0.5f) drawArc(c, start, sweep - 1.2f, false, tl, sz, style = stroke)
                start += sweep
            }
        }
        center?.invoke()
    }
}
