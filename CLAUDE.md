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
- **Sync** (`sync/`, `docs/DESIGN.md` "Sync"; the relay SQL is `supabase/migrations/*_tally_relay.sql`):
  - Nothing reaches the relay unsealed: every record and file goes through `SyncCrypto` (AES-256-GCM, AAD bound to family and id). Never add a relay call that sends plaintext, and never send `family.key` or `authToken` anywhere but the invite and the token parameter.
  - `SyncModel.collectPush` is the only place that decides what leaves the phone. A private loan, its payments and its files go up only as bare tombstones, and only when `sync.pushed` says the relay holds them. Keep `isPrivate` failing closed for pushing.
  - The merge is pure (`SyncModel`, no Android, no network) and covered by `SyncModelTest`/`SyncEngineTest`; change it there first. Model write helpers must keep stamping `updatedAt`/`updatedBy` and marking `sync.dirty` (and `sync.blobsUp` for new file bytes); making a loan private or shared must mark it, its payments and its files dirty.
  - The relay is reached only through the `tally_*` RPC functions. Tables stay RLS-on with no policies and no grants to `anon`/`authenticated`; a schema change is a new migration, never an edit of an applied one. Never commit `supabase/.temp` or any env file.
  - The wire envelope `{ k, r }`, the blob format (version byte 1) and the AAD strings are a protocol between phones on different versions: change them only with a new version byte and a reader for the old one.
- **Design follows `android/UI.md`.** Never use Material tab or chip rows for navigation. One root screen, pushed sub-screens, forms in sheets with Cancel and Save pinned.

## Map

```
android/app/src/main/java/com/tally/book/
  MainActivity.kt: pendingSharedText and pendingInvite stashes; TallyApplication.kt
  core/        Js helpers (n, inr, obj, arr...), JsonCodec, I18n, DevicePrefs, Clock + dates (Util)
  model/       Db (DB, onDbRead), Book (freshDb), State (reset), Hooks (UiHooks), FileMeta
  data/        Ledger (load/lock/save), Crypto, Files (FileStore)
  sync/        family sync: SyncCrypto (HKDF, seal/open), Invite, Relay (+ HttpRelay), SyncModel (pure merge/push rules), SyncEngine, Sync (app wiring)
  ui/          App.kt (Routes, AppNav, shell, SubScreen), theme/, components/ (the kit + Qr + PullRefresh), lock/,
               loans/ (LoansScreen + Welcome + loansGraph, LoanDetail, LoanSheet, PaymentSheet, Attachments (photos, documents, FileStore glue),
               FormBits, Words, SyncHooks (syncHook, syncStatusLine))
android/app/src/test/   unit tests
docs/DESIGN.md
supabase/migrations/     the relay (tables + tally_* functions); deploy with `npx supabase db push --remote` from the repo root
.github/workflows/keep-awake.yml   daily tally_ping so the free Supabase project never pauses
```

## Commands

```bash
export ANDROID_HOME=~/android-sdk
cd android
flock /tmp/balance-gradle.lock ./gradlew --console=plain assembleDebug       # app/build/outputs/apk/debug/
flock /tmp/balance-gradle.lock ./gradlew --console=plain testDebugUnitTest
TALLY_LIVE=1 flock /tmp/balance-gradle.lock ./gradlew --console=plain testDebugUnitTest --tests 'com.tally.book.SyncLiveTest'   # opt-in: against the deployed relay
```

- **RAM is tight (7 GB).** One Gradle at a time, always through that `flock` (shared with Balance). Stop it (`./gradlew --stop`) before booting an emulator.
- Relay and signing settings are Gradle properties: `TALLY_SUPABASE_URL`, `TALLY_SUPABASE_ANON_KEY`, `TALLY_STORE_FILE`, `TALLY_STORE_PASSWORD`, `TALLY_KEY_ALIAS`, `TALLY_KEY_PASSWORD`.

## Commit conventions

- Short imperative subject line, a blank line, then prose explaining *why*.
- **Never add AI or assistant attribution** to commits or PRs: no "Co-Authored-By" line and no mention of any AI tool.
- Update the docs a change touches (this file, `docs/DESIGN.md`, `android/UI.md`) in the same change.
