# CLAUDE.md

Guidance for Claude Code in this repo. This file has the rules, the map and the commands. What Tally is and how it syncs is in `docs/DESIGN.md`; screen design rules are in `android/UI.md`.

## What this is

Tally is a family payment book for Android, kept mostly for loans: each phone keeps the whole book locally and works offline, and phones in the same family exchange changes through a relay that only holds ciphertext. Native Kotlin and Jetpack Compose in `android/` (package and `applicationId` `com.tally.book`).

It is a sibling of Balance (`~/Balance/android`), whose foundation it copies (core helpers, ledger storage, crypto, file store, UI kit, translations). **Balance itself is never changed from this repo.** Comments that name `app/js/...` files point at Balance's web branch.

## Hard rules

- **Never change** the `applicationId` (`com.tally.book`), the release key (`TALLY_STORE_*` in `~/.gradle/gradle.properties`, never committed), or the widget receiver's class name and package (`com.tally.book.widget.NextPaymentWidgetReceiver`: placed widgets point at it).
- **`versionCode` goes up every release.**
- **Privacy:** only the sync relay is contacted, and only ciphertext leaves the phone. `allowBackup="false"` stays. A private loan, its payments and its files never leave the phone, except inside a backup file the person makes.
- **The book is a JSON tree, not data classes.** `DB` is `Obj`/`Arr` with every number a `Double`, and unknown fields round-trip untouched.
- **Every persisted change goes through `Ledger.edit { }`.** Screens read with `val rev = rememberLedger()` and `remember(rev) { ... }`. Reading `DB` is observed by Compose (`onDbRead`).
- **Nothing is saved until the user saves it.** A form edits a copy held in `remember` and writes inside `Ledger.edit {}` on Save. New ids come from `uid(prefix)`.
- **All UI text goes through `t(key, en, vars)`,** English at the call site. A new key also goes into `android/app/src/main/assets/i18n/te.json` and `hi.json` (Telugu and Hindi are required from day one).
- **Amounts are shown through `Money(...)`/`SignedMoney(...)`, and through `amtText`/`amtMask` inside sentences.** Parse with `n(v)`, format with `inr(v)`.
- **Dates are device-local, through `Clock`:** `Clock.today()`, `todayISO()`, `todayKey()`, `Clock.millis()`. Never call `LocalDate.now()` or `System.currentTimeMillis()`; tests pin the clock.
- **Ask and tell through `Dialogs.ask(message, ok, danger)` / `Dialogs.tell` / `Dialogs.snack(text, "Undo") {}`.** Model code uses `UiHooks.ask/tell`. A destructive action asks, or offers Undo.
- **Document files are kept byte-for-byte** (`data/Files.kt`, checked by SHA-256). Never re-encode or compress one (photos are scaled once, before they are stored).
- **Sync** (`sync/`, `docs/DESIGN.md` "Sync"; the relay SQL is `supabase/migrations/`, the current protocol from `*_tally_membership.sql` on):
  - Nothing reaches the relay unsealed: every record, file, invite box and the family's name (admins only, `tally_family_meta_set`) goes through `SyncCrypto.sealE` under the newest epoch (AES-256-GCM, AAD bound to family and id), and family keys only ever travel inside key boxes sealed to a member's public key (`SyncCrypto.sealTo`) with a MAC (`Membership.sealBox`). Never add a relay call that sends plaintext, a key, an invite secret or a private key. Credentials go to the relay only as their SHA-256 (create, join request) and the secret only as the `p_secret` parameter; the notice token only to `tally_pending`.
  - **Membership invariants** (`docs/DESIGN.md` "Membership", "Threat model"): the invite never carries a family key (only its own one-time secret, which never reaches the relay); a join needs an admin's phone to check the request (`Membership.check`) and seal a grant; key boxes are sealed only to **pinned** public keys, never to a key the relay supplies, and a pinned key is never replaced by a pulled record; every box is proved by a MAC under a key the relay never has (the previous epoch's key, or the invite's grant key), and a box or grant that does not check out is refused, never "trusted for now"; a phone never pushes under an epoch older than the family's newest (`waitingForKey`); removing a member always rotates the key in the same relay call. **Back on a new phone** (`docs/DESIGN.md`): only an admin lets a member back as the same member id (`approveAs` → `tally_member_reclaim`, which revokes the old credential (`TA007`, "replaced") and rotates the key in the same call); the `back` flag travels only inside the sealed request; the grant is sealed and MAC'd for the member id the admin picked, and the joining phone takes only a grant that checks out for the id it is told; a pin changes only by approval or a re-pin inside a checked box; never let a non-admin approve, not even for the creator. Keep `FakeRelay` (tests) enforcing the same role rules as the SQL: a change to one is a change to both.
  - The role rules live in the relay (the phone's checks are only for wording): at least one admin; the creator cannot be removed or demoted by others; an admin can remove another admin; the last admin leaving promotes the longest-standing member; the last member leaving deletes the family on the relay.
  - `SyncModel.collectPush` is the only place that decides what leaves the phone. A private loan, its payments and its files go up only as bare tombstones, and only when `sync.pushed` says the relay holds them. Keep `isPrivate` failing closed for pushing.
  - The merge is pure (`SyncModel`, no Android, no network) and covered by `SyncModelTest`/`SyncEngineTest`; change it there first. Model write helpers must keep stamping `updatedAt`/`updatedBy` and marking `sync.dirty` (and `sync.blobsUp` for new file bytes); making a loan private or shared must mark it, its payments and its files dirty.
  - The relay is reached only through the `tally_*` RPC functions. Tables stay RLS-on with no policies and no grants to `anon`/`authenticated`; every function that changes a family takes `tally_private.tally_lock` before checking the caller; helpers stay in `tally_private`. A schema change is a new migration, never an edit of an applied one. Never commit `supabase/.temp` or any env file. The limits (members, invites, requests, records, file bytes, new families per hour) are part of the protocol: change them in SQL, `FakeRelay` and DESIGN.md together.
  - **Files pass through the relay only in transit** (`docs/DESIGN.md` "Files", the `*_tally_files_in_transit.sql` migration): a file is kept for the members active at upload (not the uploader; later joiners are not owed it), until each has acknowledged it or 30 days. Acknowledge (`tally_blob_ack`) only after the bytes are stored here and read back matching the record's SHA-256. A removed or departed member is owed nothing (the `tally_member` trigger); a member back on a new phone keeps owing under the same id. "Ask for this file" (`askForFile`, `tally_file_ask`) is answered only from bytes checked against the record, never for a private loan's file. A phone without a file's bytes shows "On <name>'s phone" / "On your other phone" (`ui/loans/FileAway.kt`), never an error. `FakeRelay` mirrors the owed lists, expiry, asks and the 50 MiB per-family cap. Keep `tally_relay_usage` totals-only (no family ids, nothing per family) and `tally_ping` returning 200.
  - The wire envelope `{ k, r }`, the blob formats (version byte 2 with the epoch; version 1 still readable), the key-box format, the MAC inputs and the AAD/HKDF info strings are a protocol between phones on different versions: change them only with a new version byte (or tag) and a reader for the old one.
  - The member's private key and credentials live in the book (`DB.family.me`), sealed with the lock when one is set, and so in backups: a restored phone is the same member. The notice token is also copied to `DevicePrefs` (`tally-notice`) for the background check; that copy is never in a backup. Background work is only the join-request check (`sync/Notices.kt`) and the daily payment reminder (`data/DueNotices.kt`); neither notification names anybody or any amount. While the app is open an admin's phone also polls `SyncEngine.refreshRequests()` (and a joining phone `checkJoin()`) every 5 s (`ui/family/FastJoin.kt`, `sync/PollPacer.kt`).
- **The invite text** (`tally://join#t2...`) is made fresh by `SyncEngine.newInvite()` each time the Invite sheet opens, shown only there and handed to the system share sheet. Never put it (or its secret) in a snack, a dialog, a log, a crash message, saved state (`rememberSaveable`) or the book; the join confirmation shows only the family name and the inviter's name parsed from it. The camera scanner and the Join field pass it straight to `Invite.find` (old 0.1.0/0.2.0 codes: `Invite.isOld`, refused with a plain message). The joining phone keeps the secret in `DB.joining` only until it is let in or refused.
- **UI words about sharing** are "family", "share", "join". Never "relay", "key" or "encrypt" on screen, apart from the one line "Only your family can read what you share." Files in transit may say "Tally's server" (the owner's wording): "Your family gets a copy. Each phone keeps its own; Tally's server holds it only until everyone has it."
- **Backups** (`docs/DESIGN.md` "Backups"): the `.tally-backup` format is version 1 (`BackupFormat.VERSION`); a change to it is a new version with a reader for the old one. The book and files go in exactly as stored on disk (sealed when locked), never decrypted, re-encoded or compressed. Device-only settings (`DevicePrefs`: language, the backup folder, fingerprint) are never in a backup. A restore checks everything before touching anything, keeps one safety copy, swaps in behind a journal, never removes a lock the person set, and marks the book `sync.restored`. A backup reads the book only through `Ledger.readStoredBytes()` and the files under `FileStore.filesExclusive` + `FileStore.io`; any new writer of the book or files holds those locks too.
- **App lock** (`docs/DESIGN.md` "App lock"): two kinds, one envelope. Tally's own lock (PIN, pattern, password; PBKDF2 of it seals the book) is never weakened, and `Bio` stays a convenience copy. The phone's lock (`t: "phone"`) seals the book under a 20-digit recovery code and keeps the derived key on the phone under a Keystore key made with `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`. Never set `setInvalidatedByBiometricEnrollment(true)` on that key, never seal the book with a Keystore key alone, and never put the Keystore copy in the book or a backup. The recovery code must always open the book (and every backup made under that lock), and `DB.recoveryCode` exists only inside a sealed book. A lock change (switch, change, off, restore) must keep the book readable at every step: build and test the new lock first, write the book under it, and only then forget the old copy. Switching, changing and turning off confirm the current lock first; showing hidden amounts asks too. Every system prompt holds `MainActivity.holdLock`, and the lock screen shows the phone's prompt once per lock, never in a loop. The pure rules are in `data/LockRules.kt` and tested by `LockModesTest` with a fake key box.
- **Widget privacy:** the "Next payment" widget (`widget/`) shows a loan name or amount only from the device snapshot (`tally-widget-next`), which exists only while a widget is placed, the book is open, no app lock is set and amounts are shown (`NextPaymentData.snapshotText`). Locked or hidden, it shows dates-only text from the due-date copy. Any new way to lock or hide must clear it.
- **Appearance** (Automatic / Light / Dark) is a device setting (`DevicePrefs` `tally-theme-mode`, `ui/theme/ThemeMode.kt`), never in the book or a backup.
- **Easy mode** (bigger text, fewer choices) is a device setting (`DevicePrefs` `tally-easy`, `ui/theme/EasyMode.kt`), never in the book, synced or backed up, and it applies at once, lock screen included. The total font scale is the phone's times 1.3, capped at 2.0 (`EasyRules.scale`). It changes only what is drawn and asked: never loan maths, sync or backups. Touch targets 56 dp (`touchMin`), no swipe gestures, sheets close only from X/Cancel/Back, and a loan that already uses an advanced option shows it in the form (`EasyForm.extrasOf`). New screens must work at font scale 2.0 in Telugu (wrap, never ellipsis; figures via `Money`; stack with `bigText()`): see `android/UI.md` "Large text".
- **Design follows `android/UI.md`.** Never use Material tab or chip rows for navigation. One root screen, pushed sub-screens, forms in sheets with Cancel and Save pinned.

## Map

```
android/app/src/main/java/com/tally/book/
  MainActivity.kt: pendingSharedText and pendingInvite stashes; TallyApplication.kt
  core/        Js helpers (n, inr, obj, arr...), JsonCodec, I18n, DevicePrefs, Clock + dates (Util)
  model/       Db (DB, onDbRead), Book (freshDb), State (reset), Hooks (UiHooks), FileMeta, Records (every write), Loans (view, due list, totals, checks),
               LoanMath (instalments, interest periods, pending), LoanLabels (labels and wording), Reminders (pure: what is due, for a notification)
  data/        Ledger (load/lock/save), Crypto, LockRules (pure: lock kinds, recovery code, PhoneSeal, unhide rule), Files (FileStore), BackupCore (the backup file: make, check, install; plain JVM),
               Backups (folder, daily WorkManager job + catch-up, share file, install under the app's locks; BackupWorker),
               DueNotices (the daily payment-reminder job + notification, from a device copy of due dates only)
  widget/      NextPaymentWidget + NextPaymentWidgetReceiver (Glance), WidgetText (pure: what it says), WidgetData (NextPaymentData: device snapshot, refresh/clear, daily redraw job)
  sync/        family sync: SyncCrypto (HKDF, epoch seal/open, P-256 identities, sealing to a public key), Invite, Relay (+ HttpRelay),
               Membership (pure: keyring, key boxes, requests, invite boxes, MembershipView), SyncModel (pure merge/push rules),
               SyncEngine (sync + create/join/approve/remove/roles/rename/leave), Sync (app wiring), Notices (join-request check, WorkManager + notification)
  ui/          App.kt (Routes, AppNav, shell, SubScreen), theme/ (Theme, Tokens, ThemeMode: the Appearance setting, EasyMode: the Easy mode switch, scale cap, contrast and size helpers), components/ (the kit + Qr + PullRefresh), lock/ (LockScreen + LockFlow, Bio: fingerprint for Tally's own lock, PhoneLock: Keystore key + system prompt, LockModes: choosing the kind, recovery code sheet, unhide check),
               family/ (FamilyScreen + Join + familyGraph, FamilyMembers (members, member sheet, invite sheet), FamilyStates (waiting, removed/denied, old family), JoinRequests (the Allow / Don't allow cards), MemberText (pure: member actions, leave/remove/request words), QrScanner + QrDecoder (CameraX + ZXing), FamilyFlow (create/join/leave, "please wait"), BackupFlow (Backup group, restore/undo flow, BackupText, home "stopped" line),
               SyncWiring (pull to refresh + status line), FastJoin (open-app join polling), ReminderSettings (Payment reminders switch + first-loan offer), SyncText (pure words for sync: ago, errors, status), NoticePermission (the Android 13+ notification permission hook)),
               loans/ (LoansScreen + Welcome + loansGraph, LoanDetail, LoanSheet, FormRules (pure: which fields show, renewal suggestion; EasyForm: the fields of Easy mode), LoanActions (Rate changed, Renewed sheets), PaymentSheet, Attachments (photos, documents, FileStore glue),
               FormBits, Words (plain words, and `loanWords(given)`: the one place wording turns around for money I gave), SyncHooks (syncHook, syncStatusLine),
               FileAway (pure words for a file whose bytes are on another phone, and the share note))
android/app/src/test/   unit tests
docs/DESIGN.md
supabase/migrations/     the relay (tables + tally_* functions); deploy with `npx supabase db push --remote` from the repo root (agent mode: add --yes, only when the owner approved it)
.github/workflows/keep-awake.yml   tally_ping four times a day so the free Supabase project never pauses, then tally_relay_usage: fails (GitHub emails the owner) above 70 % of 500 MB
```

## Commands

```bash
export ANDROID_HOME=~/android-sdk
cd android
flock /tmp/balance-gradle.lock ./gradlew --console=plain assembleDebug       # app/build/outputs/apk/debug/
scripts/release.sh X.Y.Z ["notes"]                                           # from the repo root: bump, build signed APK, tag vX.Y.Z, publish a GitHub Release (PRERELEASE=1 for a pre-release)
flock /tmp/balance-gradle.lock ./gradlew --console=plain testDebugUnitTest
flock /tmp/balance-gradle.lock ./gradlew --console=plain lintRelease assembleRelease   # release must build: R8 with CameraX + ZXing
TALLY_LIVE=1 flock /tmp/balance-gradle.lock ./gradlew --console=plain testDebugUnitTest --tests 'com.tally.book.SyncLiveTest'   # opt-in: against the deployed relay
```

- **RAM is tight (7 GB).** One Gradle at a time, always through that `flock` (shared with Balance). Stop it (`./gradlew --stop`) before booting an emulator.
- Relay and signing settings are Gradle properties: `TALLY_SUPABASE_URL`, `TALLY_SUPABASE_ANON_KEY`, `TALLY_STORE_FILE`, `TALLY_STORE_PASSWORD`, `TALLY_KEY_ALIAS`, `TALLY_KEY_PASSWORD`.

## Commit conventions

- Short imperative subject line, a blank line, then prose explaining *why*.
- **Never add AI or assistant attribution** to commits or PRs: no "Co-Authored-By" line and no mention of any AI tool.
- Update the docs a change touches (this file, `docs/DESIGN.md`, `android/UI.md`) in the same change.
