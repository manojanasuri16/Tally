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

### Sheets, alerts, toasts

- **`EditSheet(title, onDismiss, onSave, saveLabel, saveEnabled, onDelete) { … }`** is the iOS sheet. It has a grabber, a header with **Cancel** (left), the **title** (centre) and **Save** (right, semibold, disabled by `saveEnabled`), and a grouped-background body. `onSave = null` shows just **Done**. `onDelete` adds a red Delete row at the end. Save fires a confirm haptic. A form edits a copy and writes on Save, so pass `saveEnabled = changed` where the form can tell.
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

- **Font scale.** Every size is in `sp` and rows only set a minimum height (`heightIn(min = …)`), so text can grow. Long titles step down a size (`largeTitleStyle`). Never fix a height around text.
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


## Screens and data

- **Read the book** with `val rev = rememberLedger()` and compute in `remember(rev) { … }`. Change it only inside `Ledger.edit { … }`.
- **Nothing is saved until Save.** A form edits a copy (`obj.copyObj()` or a new `obj(...)`) held in `remember`, and only writes into `DB` inside `Ledger.edit {}` on Save. Cancel or dismiss drops it. New ids come from `uid(prefix)`.
- **Words.** Every string goes through `t(key, en)`. A new key goes into both `assets/i18n/te.json` and `hi.json`. Users are parents first: large type, few choices per screen, Telugu and Hindi from day one.
- **Amounts** go through `Money` / `SignedMoney` / `amtText`, never `Text(inr(x))`.

## Building

From `android/`, run `export ANDROID_HOME=~/android-sdk; flock /tmp/balance-gradle.lock ./gradlew --console=plain assembleDebug testDebugUnitTest`. Don't run the emulator while others are building (memory).
