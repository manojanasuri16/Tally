# Tally design system

Tally uses **the Balance look, built in Jetpack Compose**: Apple-style grouped lists, set in Inter, with titled cards outlined by a hairline. The theme and the kit are copied from Balance (`ui/theme/`, `ui/components/`); where a table below and the code differ, `ui/theme/Theme.kt` is right. Material 3 is only the base library underneath (Scaffold, ModalBottomSheet, the date picker). Its look is replaced everywhere: no ripples, no tonal elevation, no Material tab rows, chips or FABs.

**Tally has a single root screen** (`LoansScreen`, route `loans`) and pushed sub-screens. There is no tab bar, no month bar, no widgets and no charts to design for. Forms are sheets. See `docs/DESIGN.md` for what the screens are.


## Principles

These are Apple's principles, applied to a money app:

1. **Response.** Feedback lands on touch-*down*, never on release. Every pressable thing in the kit scales and highlights the instant the finger lands.
2. **Interruptibility.** Every animation is a spring that starts from the current on-screen value, so it can be reversed mid-flight. Never lock input during a transition.
3. **Behaviour over animation.** Springs are critically damped by default (`dampingRatio = 1`, no bounce). A little overshoot (`Motion.bouncy`) is allowed only after a gesture that carried momentum, such as a flick or a drag release.
4. **Spatial consistency.** Things leave the way they came. A pushed screen enters from the right and pops back to the right. A modal task rises from the bottom and sinks back down.
5. **Hierarchy through grouping.** Use inset grouped lists, as in iOS Settings. Hierarchy comes from weight, size and position, not from boxes, shadows or colour.
6. **Restraint.** Each screen has one prominent action. The accent marks what you can tap; it is not decoration. Semantic colours (green, red, orange) carry meaning only.
7. **Forgiveness.** Destructive actions ask (`Dialogs.ask(…, danger = true)`) or offer Undo (`Dialogs.snack(text, t("dlg.undo","Undo")) { … }`). Nothing is saved until Save.
8. **Craft.** Spacing, timing, radii and type are all tokens, so nothing on screen is a random number.


## Tokens (`ui/theme/`)

### Colour (`Theme.kt`)

Read the iOS tokens as `Apple.colors.*`. They are also mapped onto the M3 slots, so `MaterialTheme.colorScheme.*` is correct too. **Screens use only these tokens and `LocalSemantic.current.*`, never literal colours.**

| Token (`Apple.colors`) | M3 slot | Light | Dark | Black |
|---|---|---|---|---|
| `groupedBackground` (page) | `background`, `surface` | #F2F2F7 | #111113 | #000000 |
| `cell` (a group or card) | `surfaceContainer`, `…Low` | #FFFFFF | #1E1E21 | 7 % accent in #0D0D10 |
| `tertiary` (inside a cell) | `surfaceContainerHigh`, `surfaceVariant` | #EBEBF0 | #2C2C2F | 13 % accent in #131317 |
| `label` | `onSurface`, `onBackground` | #1D1D1F | #F5F5F7 | #FFF |
| `secondaryLabel` | `onSurfaceVariant` | #6E6E73 | #A1A1A6 | #BCBCC4 |
| `cardBorder` (1 dp outline of a titled card) | – | #DCDCE0 | #38383A | 28 % accent in #2E2E36 |
| `sectionTitle` (a card's title) | – | the label | the label | 38 % accent in white |
| `tertiaryLabel` (30 %): placeholders, chevrons | `outline` (made opaque) | #3C3C43 at 30 % | #EBEBF5 at 30 % | the same |
| `separator` (0.5 dp hairlines) | `outlineVariant` | #DCDCE0 | #38383A | as `cardBorder` |
| `fill`: fields on a cell, segmented track, wells | – | #767680 at 12 % | #767680 at 24 % | the same |
| `bar`: scrolled nav bar | – | #FFFFFF | #161617 | #000000 |
| `elevated` / `elevatedCell`: sheets, alerts, toasts | – | #FFF / #EBEBF0 | #1E1E21 / #2C2C2F | as `cell` / `tertiary` |
| `accent` | `primary` | the user's colour theme | | |
| `switchOn` | – | #34C759 | #30D158 | |

- **Accent.** The user's colour theme (`DB.colorTheme` (defaults to blue): eight themes, `custom`, or `dynamic`, which takes the wallpaper colour on Android 12+) chooses the accent. Light and Dark keep neutral surfaces; Black mixes the accent into them.
- **`LocalSemantic`** holds the iOS system colours: `positive` (green), `negative` (red), `warning` (orange), `muted`, `clean`, and `categories` (eight iOS colours; a category's colour is its index, so never reorder them). Light mode uses Apple's high-contrast variants for text, for example #248A3D green and #D70015 red.
- **`ElevatedLevel { }`** wraps sheet and alert content. In dark mode it lifts the level one step (#1C1C1E page, #2C2C2E cells) so the sheet reads above the dimmed page.
- **`LocalOnCell`** is true inside a group or card. Fields and tiles then use `fill` or `tertiary` instead of a second white cell.

### Type (`Theme.kt`)

Use `Apple.type.*`, or the M3 slot it is mapped to. The face is Inter (`res/font/inter_var.ttf`, Latin and ₹; Telugu and Hindi fall through to the system fonts). Body is 16/23, Callout 15/21 and Figure 36/43. Tracking depends on size: tight on large text, slightly open on small text. Line heights leave room for Telugu and Hindi. The `figure`, title and display styles use tabular numerals (`tnum`), and `Money` always does.

| iOS style | `Apple.type` | M3 slot | Size / line | Weight | Tracking |
|---|---|---|---|---|---|
| Large Title | `largeTitle` | `headlineLarge` | 34 / 41 | Bold | −0.4 |
| Title 1 | `title1` | `headlineMedium` | 28 / 34 | Bold | −0.3 |
| Title 2 | `title2` | `headlineSmall` | 22 / 28 | Bold | −0.2 |
| Title 3 | `title3` | `titleLarge` | 20 / 25 | Semibold | −0.15 |
| Headline | `headline` | `titleMedium` | 17 / 22 | Semibold | −0.2 |
| Body | `body` | `bodyLarge` (the default `Text`) | 17 / 24 | Regular | −0.2 |
| Callout | `callout` | `bodyMedium` | 16 / 22 | Regular | −0.15 |
| Subheadline | `subheadline` | `titleSmall` (semibold), `labelLarge` (medium) | 15 / 20 | | −0.1 |
| Footnote | `footnote` | `bodySmall`, `labelMedium` | 13 / 18 | Regular | 0 |
| Caption 1 | `caption1` | `labelSmall` | 12 / 16 | Regular | +0.1 |
| Caption 2 | `caption2` | – | 11 / 13 | Regular | +0.2 |
| Figure | `figure` | `displaySmall` | 34 / 41 | Bold | −0.6 |
| Large figure | `figureLarge` | `displayLarge` | 48 / 54 | Bold | −1.0 |

Build hierarchy from weight, size and colour together. Emphasise with weight (Semibold), not with colour.


### Spacing, radii, shapes (`Tokens.kt`)

- **`Space`:** `xs` 4, `s` 8, `m` 12, `l` 16, `xl` 20, `xxl` 24, `section` 28. The side gutter is 16 (`Space.gutter`) and cards sit 12 apart. Rows are at least 44 dp tall (52 dp for group rows). The hairline under a row with an icon starts at 60 dp (`Space.iconInset`).
- **`Radius`:** `tile` 8, `field` 10, `cell` 12, `group` 14, `sheet` 14, `segment` 9.
- **`AppleShapes`:** `tile`, `field`, `cell`, `group`, `sheet` (top corners only), `alert`, and `capsule`. All of them are `ContinuousShape`, an iOS "squircle" whose curve eases into the edge. Buttons, pills and toasts are capsules. M3's own `MaterialTheme.shapes` (6/10/12/14/14) are plain rounded corners, used only by leftover M3 components.

### Motion (`Tokens.kt`)

| Spring | Stiffness (response) | Use |
|---|---|---|
| `Motion.push()` | 380 (≈ 0.32 s) | push, pop, sheets, moving things |
| `Motion.standard()` | 300 (≈ 0.36 s) | content changes, bars, expand and collapse |
| `Motion.control()` | 700 (≈ 0.24 s) | switch thumb, segmented thumb, chevrons, alert scale-in |
| `Motion.press()` | 1500 (≈ 0.16 s) | touch-down scale |
| `Motion.bouncy()` | damping 0.8 | only after a gesture with momentum |
| `Motion.fade()` | tween 200 ms | the reduced-motion replacement |

Every spring has `dampingRatio = 1` (no bounce) except `bouncy`.


## The kit

The kit lives in `ui/components/`: `Components.kt` (the original names, restyled), `AppleKit.kt` (groups, rows and the navigation shell) and `Controls.kt` (controls, press and haptics). The shell is in `ui/App.kt`. Add a component to the kit only when two areas need it; otherwise keep it in your package.


### Lists and cells

- **`AGroup(title?, footer?, trailingHead?) { item(inset) { … } }`** is the inset grouped list: continuous 18 dp corners, hairlines between rows (pass `item(Space.iconInset)` when the rows have icon tiles), an uppercase grey heading above and a grey footnote below. **This is the default container for anything list-like.**
- **`ARow(title, sub, icon, tint, value, money, …, chevron, onClick, trailing)`** is a row in an `AGroup`. Use `chevron = true` when tapping it pushes a screen.
- **`ButtonRow(text, color) { }`** is a centred action inside a group ("Mark paid", or "Remove" in `LocalSemantic.current.negative`).
- **`NoteRow(text)`**, **`GroupHead(text)`**, **`GroupFoot(text)`** and **`InsetSurface { }`** are the plain cell surface; `LocalOnCell` is true inside it.
- **`SectionCard(title?, subtitle?, trailing?) { … }`** is an outlined section card. The header sits inside the card in Headline weight over a hairline, with the trailing actions tinted. Use it for free-form content such as a chart, a paragraph or a few fields. Prefer `AGroup` for rows.
- **`FoldCard(key, title, summary?) { … }`** is a group whose body folds under its header row. The state is kept in `DB.shut[key]`. The chevron rotates on a spring. `heldOpen = true` shows the body without touching the stored state, as Savings does while a search has matches inside.

- **`ListRow(headline, …, value = null, chevron = false, headlineColor = Unspecified)`** is the general iOS cell: an optional icon tile (`leadingIcon` + `leadingColor`), a colour dot (`leadingColor` alone) or a custom `leading`, then an amount, a grey value, a trailing slot and a chevron. `onLongClick` adds a long press with a haptic.
- **`SectionHeader(text, action?)`** is a Title 3 band title between groups of cards.
- **`IconTile(icon, color, size = 30.dp)`** is a Settings-style solid tile with a white glyph. **`IconBadge(icon, color)`** is the tinted variant (the glyph on a 15 % wash). **`Chevron()`** is the disclosure chevron and **`Hairline(start)`** a 0.5 dp separator.

### Figures

- **`Hero(label, value, sub?, color?) { … }`** is the lead card of a tab: a grey label, the amount in `figure` (34 bold, tabular), and a line under it. A `StatTile` inside it automatically uses the tertiary colour.
- **`BigFigure(label, amount, sub?, right?)`** is the same figure without a card, at the top of a pushed screen. It can take a ring on the right.
- **`StatTile(label, value, color?, onClick?)`** is a Footnote label over a Title 2 figure. Use two side by side.
- **`Money` / `SignedMoney`** show every amount, masked by the eye. Inside sentences use `amtText` / `amtMask`.
- **`Pill(text, color)`** is a status capsule ("Due in 3 days"). **`ShareRing`**, **`ShareBar`**, **`ProgressLine`**, **`BarChart`** and **`Donut`** are small charts on `Apple.colors.fill` tracks that animate on springs.
- **`EmptyState(text, icon, action?, onAction?)`** is a quiet, centred empty state with an optional plain action. **Every list has one.**

### Inputs

All inputs are iOS inset fields: a filled cell (`cell` on the page, `fill` inside a group), no outline, the label above in grey Footnote, an optional placeholder inside, and a supporting line below.

- **`TextInput(value, onValue, label, singleLine, placeholder, keyboard, supporting)`**
- **`MoneyField(value, onValue, label, supporting, imeDone, autoFocus)`**: a grey ₹ prefix, tabular figures, the decimal keyboard.
- **`DateField(iso, onIso, label, clearable)`**: a tappable cell that opens the date picker.
- **`FieldFrame(label, supporting, cell = Modifier) { … }`**: the frame itself, for a custom field.
- **`ChoiceChips(options, selected, onSelect, colors)`** is an in-page single choice. With two to four short options it draws as a **segmented control** (`SegmentedControl`) whose thumb slides on a spring. Otherwise it **wraps as capsules** (`CapsuleChoice`). Never use it for navigation.
- **`SegmentedControl(options, selected, onSelect, enabled, colors)`** can be used directly; `Segmented` in Settings uses it.
- **`SwitchRow(title, checked, onChange, subtitle, icon)`**: the whole row toggles. **`AppleSwitch(checked, onCheckedChange?, enabled)`** is the custom-drawn 51×31 iOS switch: a green track, a white thumb on a spring that stretches while held, and a haptic on every flip. Pass `null` for display-only use inside a row that handles the tap.

### Buttons

- **`PrimaryButton(text, onClick, enabled, color?)`** is the filled accent capsule, 50 dp tall. Use **one per screen at most.**
- **`SecondaryButton(text, onClick)`** is a tinted capsule (accent text on a 15 % wash).
- **`PlainButton(text, onClick, bold, icon?, color?)`** is borderless accent text that dims while held, as in iOS.
- **`BarIcon(icon, description, onClick)`** and **`BarButton(text, onClick, bold)`** are plain tinted controls for navigation bars and toolbars. Inside a bar's `actions`, M3 `IconButton`s also come out tinted, because the bar provides the accent as `LocalContentColor` (`Tinted { }`). New code should use `BarIcon`.

### Pull to refresh (`components/PullRefresh.kt`)

Built as a gesture, not Material's indicator. `rememberPullRefresh(onRefresh)` gives a `PullRefreshState`; put `Modifier.nestedScroll(state.connection)` on a parent of the scrolling content, `Modifier.pullRefreshContent(state)` on the content and `PullRefreshIndicator(state)` behind it (`PullRefresh(onRefresh) { }` does all three for a screen without a collapsing bar). On a `NavScreen`, put the connection **outside** the `NavScreen`: the large title then takes a downward drag first and the pull starts only once the title is fully open (`LoansScreen`).

- The content follows the finger through a rubber band (`PullMath.rubber`: slope 0.9 at the start, never reaching the 160 dp range). The arrow fades in with the pull and turns over at the 64 dp threshold, with a tick.
- Releasing past the threshold holds a spinner until `onRefresh` returns, then springs back (`Motion.push()`, critically damped); releasing early springs back at once. The finger's velocity is handed to the spring only in the direction of travel.
- Interruptible: touching during the spring-back resumes from the offset on screen (`PullMath.raw` inverts the band). It only takes a downward drag the list had no room for, so it never fights the list's scrolling. Reduced motion replaces the springs with a 200 ms fade.


### Sheets, alerts, toasts

- **`EditSheet(title, onDismiss, onSave, saveLabel, saveEnabled, onDelete) { … }`** is the iOS sheet. It has a grabber, a header with **Cancel** (left), the **title** (centre) and **Save** (right, semibold, disabled by `saveEnabled`), and a grouped-background body. `onSave = null` shows just **Done**. `onDelete` adds a red Delete row at the end. Save fires a confirm haptic. A form edits a copy and writes on Save, so pass `saveEnabled = changed` where the form can tell. A sheet with Save closes from a drag only when it is pulled down at least a third of its height (and 160 dp); a shorter pull or a quick flick springs back, and a tap outside does nothing. X, Cancel and Back always close it. A sheet without Save keeps its grabber and drag.
- **`Dialogs.ask` / `Dialogs.tell`** show an **iOS alert** (`DialogHost`). The first paragraph of the message (up to `\n\n`) is the bold title and the rest is the message. Buttons sit under a hairline, side by side or stacked when the words are long. A destructive action is red and fires a firm haptic. The alert scales in on a spring (a fade with reduced motion). Write messages so the first sentence is a question or a statement ("Remove this card?\n\nIts payments stay in Spends.").
- **`Dialogs.snack(text, action?) { }`** shows a **floating capsule toast** at the bottom, with the action (Undo) in semibold accent. There is one host, in `TallyRoot`; never add another `SnackbarHost`.


### The shell (`ui/App.kt`, `AppleKit.kt`)

- **`NavScreen(title, back?, actions, largeTitle = false, onBack) { pad -> }`** is the one screen shell. It shows "‹ Parent" on the left, a large title that collapses into the inline centred title as you scroll, plain tinted actions on the right, and the scroll-edge hairline. `back = null` gives the root screen (`LoansScreen` passes `largeTitle = true`).
  - **`SubScreen(title, actions, back = null) { pad -> }`** is a `NavScreen` (large title) whose back label is the screen it was pushed from.
  - **`PushScreen(title, back, actions) { … }`** is a `NavScreen` with one scrolling column of groups.
  - **`NavBar`**, **`NavBarRow`**, **`BarSurface`** and **`BackButton`** are the pieces, for screens with their own layout.
- **Navigation:** `AppNav.go(route)` pushes, `AppNav.back()` pops, `AppNav.home()` returns to the root, `AppNav.pending.value = route` navigates once the app is unlocked and showing. Register routes with `composable(...)` in a `NavGraphBuilder` extension (`loansGraph()`), called from `MainScaffold`.
- **Padding:** a scrolling body uses **`ScreenPadding`** (or ends with **`bottomClearance()`**). Never hard-code a bottom padding. If a floating button is ever added, set `AppChrome.bottom` and `LocalBottomChrome` so bodies and the toast clear it.
- **Transitions** are the NavHost default, so don't add per-route transitions:
  - A **push** enters from the right on a critically damped spring while the parent shifts a quarter left and dims. A **pop** is the same path reversed. Predictive Back works.
  - With **reduced motion** (`DB.b("reduceMotion")`, `reduceMotion()`), everything cross-fades.


## Interaction rules

- **Press feedback.** Use `Modifier.pressable(enabled, scaleTo = 0.97f, highlight, onLongClick) { }` for cells, tiles and capsules: a critically damped scale and a highlight on touch-down. Use `Modifier.pressDim { }` for text and icon buttons, which dim to 30 % while held. Plain `clickable` gets the iOS highlight, because the theme replaces `LocalIndication`. **Material ripples are switched off app-wide** (`LocalRippleConfiguration = null`), so leftover M3 buttons show no feedback at all; replace them with kit buttons.
- **Haptics** (`rememberHaptics()`) are for commits only: `toggle(on)` for a switch flipping, `tick()` for a segment or selection change, `confirm()` for Save, Undo or done, `destructive()` for a delete or erase confirm, `reject()` for refused input, and `longPress()`. Fire them on the same frame as the visual change. There is none for plain navigation.
- **Gestures.** Swipe actions and drags track the finger 1:1 and settle on `Motion.push()` with the release velocity. Use `Motion.bouncy()` only after a flick.

- **Wayfinding.** A screen with several parts is a list whose rows push their own screens. The back button always names where it goes.


## Redrawing a form

Forms edit a plain `Obj` draft in place and bump a counter (`FormState.tick`, `tick.intValue`) to redraw. That only works with the Compose compiler's strong skipping **off** (`composeCompiler` in `app/build.gradle.kts`): with it on, a child taking the same draft instance is skipped and a choice is stored but never shown. Keep it off.

The other half is ledger reads. Reading `DB` also reads a Compose state that `Ledger.bump()` changes (`onDbRead`, set in `data/Ledger.kt`), so a composable that shows ledger data redraws when the ledger changes, even if its arguments are unchanged. Without this, a block like a calculator's fold stored the tap but only showed it after leaving the screen. Change `DB` through `Ledger.edit { }` as always: a change that skips `Ledger.bump()` is invisible.

## Accessibility

- **Font scale.** Every size is in `sp` and rows only set a minimum height (`heightIn(min = …)`), so text can grow. Long titles step down a size (`largeTitleStyle`). Never fix a height around text. See "Large text" and "Easy mode" below.
- **Reduced motion.** Read `reduceMotion()`. Slides, scales and parallax become 200 ms cross-fades, and opacity changes that help comprehension stay. The kit already does this.
- **Contrast.** Body text is `label`. Grey is for secondary information only. Light-mode semantic text uses the high-contrast greens and reds. Never put text on the translucent bar without the bar material behind it.
- **Telugu and Hindi** run 30–60 % longer. Names may use `maxLines` with an ellipsis; figures and buttons never do. The segmented control switches to wrapping capsules when labels are long, alert buttons stack, and sheet titles may take two lines. Test with the language set to Telugu.
- **Semantics.** Switches `Role.Switch`. Large titles are headings, and icon-only controls carry a `description`.


## Do and don't

| Do | Don't |
|---|---|
| Inset grouped lists (`AGroup`, `ARow`) with hairlines | Material `Card`, `ElevatedCard` or shadows on cells |
| Rows that push their own screen | **Material tab rows or chip rows for navigation** |
| `ChoiceChips` / `SegmentedControl` for an in-page choice | `FilterChip`, `AssistChip`, `SegmentedButton` |
| `PrimaryButton`, `PlainButton`, `BarIcon`, `BarButton` | `Button`, `OutlinedButton`, `TextButton`, `IconButton` in new code |
| `TextInput`, `MoneyField`, `DateField` | `OutlinedTextField`, `TextField` |
| `AppleSwitch`, `SwitchRow` | M3 `Switch` |
| `EditSheet`, `Dialogs.ask/tell/snack` | `AlertDialog`, extra `SnackbarHost`s |
| `NavScreen`, `SubScreen`, `PushScreen` | `TopAppBar`, `LargeTopAppBar`, `CenterAlignedTopAppBar` |
| One clear primary action per screen | **A FAB** |
| Springs from `Motion` | `tween` for movement (fades only), bouncy springs on taps |
| `Apple.colors`, `MaterialTheme.colorScheme`, `LocalSemantic` | literal `Color(0x…)` in screens |
| `bottomClearance()` / `ScreenPadding` | a hard-coded bottom padding |
| Ripples off, `pressable` / `pressDim` | `indication = ripple()` |


## Large text (the phone's own font size, up to 200 %)

The app must work at font scale 1.3, 1.5 and 2.0 in English, Telugu and Hindi. The rules the kit follows, so a screen gets them by using the kit:

- **Wrap, never ellipsis,** for labels, row titles, supporting lines, buttons, capsule choices, pills, toasts and sheet titles (`ARow`, `ListRow`, `CapsuleChoice`, `SheetHeader`, `Toast` all wrap). An ellipsis is only for a file name, a back label and the last resort of a large title (two lines, then an ellipsis).
- **Figures never truncate.** `Money` is one line, and when it is wider than its room it steps down in size until it fits (`onTextLayout`), so a large total shrinks instead of being cut off.
- **Stack what does not fit.** From a font scale of 1.3 (`bigText()`, `EasyRules.STACK_AT`) a row's amount or value goes under its title (`ARow`, `ListRow`); a Due row puts its Pay button under the details; a sheet's Cancel and Save stack, Save on top, both full width and pinned (the body above scrolls); alert buttons stack and a long message scrolls inside the alert; a segmented control becomes wrapping capsules (it does that already for long Telugu words).
- **Titles.** The large title may take two lines (it is measured, so the collapsing bar is exactly as tall as the title) and never grows past 1.5x (`capped()`); the inline title may take two lines.
- **Fixed shapes cap their text.** The keypad keys hold their digits to 1.3x (`capped(1.3f)`), and the Material calendar, which does not scroll, is held to 1.3x inside its dialog.
- **Dimensions that follow the text** use `heightIn(min = …)`; the touch minimum is `touchMin(44.dp)`; spacing between cards is `stackGap()` and the side gutter `gutter()`, both larger in Easy mode.

## Easy mode

A device setting (`EasyMode`, `DevicePrefs` `tally-easy`, `ui/theme/EasyMode.kt`): not in the book, not synced, not in a backup, and applied at once (it is Compose state read in `MainActivity`, so the lock screen follows too). The switch is the first row of the Family page's settings; the welcome screen does not offer it (it asks only for the name). What the app is told: `Appearance(easy = true, fontScale = 1.3f)`, `LocalEasy`, and a stronger `AppleColors`.

- **Scale.** `EasyRules.scale(system, extra)` = the phone's font scale times 1.3, never above **2.0** and never below the phone's own scale (1.0 gives 1.3, 1.15 gives 1.5, 1.3 gives 1.69, 1.5 gives 1.95, 1.54 and up give 2.0; a phone already above 2.0 is left alone). `TallyTheme` provides the result as `LocalDensity`'s font scale, so every `sp` follows.
- **Sizes.** Touch targets are at least 56 dp (`touchMin`): rows, buttons (the primary button 60), bar icons, the sheet's X, segmented and capsule choices, alert buttons, the toast. Row padding, the gutter (20) and the gap between cards (18) grow; icon tiles are 38 dp, switches 1.25x, amounts in rows are bold.
- **Contrast** (`EasyRules.contrast`): labels pure black or white, secondary text the label dimmed to 80 %, hairlines and card outlines pulled 35 to 45 % toward black or white, fills and the press highlight stronger, presses scale a little deeper, and a field being typed in gets a 2 dp accent outline.
- **Gestures.** `SwipeRow` is off (the payment's sheet has Delete, which asks and then offers Undo); `EditSheet` never closes from a drag or a tap outside, has no grabber and closes from X, Cancel or Back. Pull to refresh stays.
- **Home.** `DueRow` is stacked with a full-width Pay or Received button; the sync line (`SyncText.needsAttention`) and the private group's header totals are hidden; "added by" is left off the rows.
- **Forms.** `EasyForm` (in `FormRules.kt`) decides which fields of the loan form are drawn; `extrasOf(existing)` makes a loan that already uses Flat, a frequency other than monthly, a pay-on day, a non-default "interest is paid" or Paid up to show those fields. Hidden fields keep their defaults, and `suggestPaidUpTo` still applies to a new loan with a past start date.

## App lock

- **The App lock group** (Family page): one row "Lock" with its value (Off / Tally's own lock / The phone's lock, and PIN, Pattern or Password under it), opening a small sheet (`EditSheet` with Done): Off (asks, then the current lock), Tally's own lock (a second list: PIN, Pattern, Password; "A PIN is the easiest."), The phone's lock (its subtitle says why it can't be chosen: "Needs Android 11 or newer." / "First set a screen lock in your phone's settings."). Under the row: Tally's own lock has "Change the PIN" (pattern, password) and the "Unlock with fingerprint" switch; the phone's lock has the note "Unlocks with your phone's screen lock or fingerprint" and "Show recovery code". The footer says what to keep safe.
- **The recovery code sheet** (`RecoveryCodeHost`, hosted with the lock overlay): the 20 digits in groups of four in Title 2 with tabular figures, centred in a group, wrapping at the spaces at large text; what it is for in Body; Save reads "I wrote it down". Then "Check the code": a number field with "{n} of 20 digits" under it, Done only when it matches, and "Show the code again".
- **The lock screen for the phone's lock** has no pad: the lock icon, "Tally is locked", one line, a primary **Try again** (56 dp) and the plain button "Use recovery code"; the recovery box is a number field with the digit count, and "Use the phone's lock" goes back when this phone can. The system prompt shows by itself once per lock.
- **Hide amounts:** the switch has a subtitle saying what showing them again asks for, or that nothing can be asked.

## Appearance and the widget

- **Appearance** (Family page, after Language): three check rows, "Automatic (follows the phone)", "Light", "Dark". A device setting (`ThemeMode`, `tally-theme-mode`), the same before and after unlock. It applies at once, lock screen included: `MainActivity.applyChrome` paints the window behind the app (`ThemeMode.windowColor`, the same as the page background and `values-night/colors.xml`, so no white flash) and sets the status and navigation bar icon colours for the resolved mode. Black is no longer offered (an old stored value still works).
- **The "Next payment" widget** (`widget/`): a rounded 22 dp card, white (dark: #1E1E21), the app's label and secondary-label colours, system red for "overdue", no Material tonal colours. Small (2x1): one bold line (name, date, amount) and a second line (overdue in red, or the title). Larger: title, name in 20 sp bold, date and amount in 16 sp, the overdue line. Light and dark follow the phone, not the Appearance setting.

## Screens and data

- **Read the book** with `val rev = rememberLedger()` and compute in `remember(rev) { … }`. Change it only inside `Ledger.edit { … }`.
- **Nothing is saved until Save.** A form edits a copy (`obj.copyObj()` or a new `obj(...)`) held in `remember`, and only writes into `DB` inside `Ledger.edit {}` on Save. Cancel or dismiss drops it. New ids come from `uid(prefix)`.
- **Words.** Every string goes through `t(key, en)`. A new key goes into both `assets/i18n/te.json` and `hi.json`. Users are parents first: large type, few choices per screen, Telugu and Hindi from day one.
- **Amounts** go through `Money` / `SignedMoney` / `amtText`, never `Text(inr(x))`.

## Building

From `android/`, run `export ANDROID_HOME=~/android-sdk; flock /tmp/balance-gradle.lock ./gradlew --console=plain assembleDebug testDebugUnitTest`. Don't run the emulator while others are building (memory).
