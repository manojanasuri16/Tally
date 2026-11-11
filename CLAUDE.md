# CLAUDE.md

Guidance for Claude Code in this repo. This file has the rules, the map and the commands. What Tally is and how it syncs is in `docs/DESIGN.md`; screen design rules are in `android/UI.md`.

## What this is

Tally is a family payment book for Android, kept mostly for loans: each phone keeps the whole book locally and works offline, and phones in the same family exchange changes through a relay that only holds ciphertext. Native Kotlin and Jetpack Compose in `android/` (package and `applicationId` `com.tally.book`).

It is a sibling of Balance (`~/Balance/android`), whose foundation it copies (core helpers, ledger storage, crypto, file store, UI kit, translations). **Balance itself is never changed from this repo.** Comments that name `app/js/...` files point at Balance's web branch.

## Hard rules

- **Never change** the `applicationId` (`com.tally.book`) or the release key (`TALLY_STORE_*` in `~/.gradle/gradle.properties`, never committed).
- **`versionCode` goes up every release.**
- **Privacy:** only the sync relay is contacted, and only ciphertext leaves the phone. `allowBackup="false"` stays. A private loan, its payments and its files never leave the phone.
- **The book is a JSON tree, not data classes.** `DB` is `Obj`/`Arr` with every number a `Double`, and unknown fields round-trip untouched.
- **Every persisted change goes through `Ledger.edit { }`.** Screens read with `val rev = rememberLedger()` and `remember(rev) { ... }`. Reading `DB` is observed by Compose (`onDbRead`).
- **Nothing is saved until the user saves it.** A form edits a copy held in `remember` and writes inside `Ledger.edit {}` on Save. New ids come from `uid(prefix)`.
- **All UI text goes through `t(key, en, vars)`,** English at the call site. A new key also goes into `android/app/src/main/assets/i18n/te.json` and `hi.json` (Telugu and Hindi are required from day one).
- **Amounts are shown through `Money(...)`/`SignedMoney(...)`, and through `amtText`/`amtMask` inside sentences.** Parse with `n(v)`, format with `inr(v)`.
- **Dates are device-local, through `Clock`:** `Clock.today()`, `todayISO()`, `todayKey()`, `Clock.millis()`. Never call `LocalDate.now()` or `System.currentTimeMillis()`; tests pin the clock.
- **Ask and tell through `Dialogs.ask(message, ok, danger)` / `Dialogs.tell` / `Dialogs.snack(text, "Undo") {}`.** Model code uses `UiHooks.ask/tell`. A destructive action asks, or offers Undo.
- **Document files are kept byte-for-byte** (`data/Files.kt`, checked by SHA-256). Never re-encode or compress one (photos are scaled once, before they are stored).
- **Design follows `android/UI.md`.** Never use Material tab or chip rows for navigation. One root screen, pushed sub-screens, forms in sheets with Cancel and Save pinned.

## Map

```
android/app/src/main/java/com/tally/book/
  MainActivity.kt: pendingSharedText and pendingInvite stashes; TallyApplication.kt
  core/        Js helpers (n, inr, obj, arr...), JsonCodec, I18n, DevicePrefs, Clock + dates (Util)
  model/       Db (DB, onDbRead), Book (freshDb), State (reset), Hooks (UiHooks), FileMeta
  data/        Ledger (load/lock/save), Crypto, Files (FileStore)
  ui/          App.kt (Routes, AppNav, shell, SubScreen), theme/, components/ (the kit + Qr), lock/, loans/ (LoansScreen, loansGraph)
android/app/src/test/   unit tests
docs/DESIGN.md
```

## Commands

```bash
export ANDROID_HOME=~/android-sdk
cd android
flock /tmp/balance-gradle.lock ./gradlew --console=plain assembleDebug       # app/build/outputs/apk/debug/
flock /tmp/balance-gradle.lock ./gradlew --console=plain testDebugUnitTest
```

- **RAM is tight (7 GB).** One Gradle at a time, always through that `flock` (shared with Balance). Stop it (`./gradlew --stop`) before booting an emulator.
- Relay and signing settings are Gradle properties: `TALLY_SUPABASE_URL`, `TALLY_SUPABASE_ANON_KEY`, `TALLY_STORE_FILE`, `TALLY_STORE_PASSWORD`, `TALLY_KEY_ALIAS`, `TALLY_KEY_PASSWORD`.

## Commit conventions

- Short imperative subject line, a blank line, then prose explaining *why*.
- **Never add AI or assistant attribution** to commits or PRs: no "Co-Authored-By" line and no mention of any AI tool.
- Update the docs a change touches (this file, `docs/DESIGN.md`, `android/UI.md`) in the same change.
