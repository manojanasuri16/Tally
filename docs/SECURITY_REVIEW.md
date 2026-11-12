# Tally security review (2026-11-12)

Independent review of `/home/manoja/Tally` at commit `72e8858` (Tally 0.5.0, versionCode 6), the live relay (project `vnogrkfxlnkxscswiaex`, inspected read-only) and `.github/workflows/keep-awake.yml`. Nothing was changed except this file.

**Verified** means the code path was read end to end, or the behaviour was reproduced live. **Suspected** means it follows from the code but was not exercised.

## Findings, most severe first

### H1. Anyone with the APK can fill or freeze the relay for every family (verified, by reading the SQL)
- **Where:** `tally_family_create` (global 20 new families/hour), `tally_push` (20 000 records × 64 KiB per family, with no byte cap), `tally_rotate` / `tally_box_put` (no limit on epochs; each rotation stores up to 20 × 32 KiB of boxes and keeps them forever), `tally_blob_put` (50 MiB in transit per family), and the free tier's 500 MB database and 5 GB egress.
- **Attack:** the publishable key and URL are in the APK (confirmed in `classes.dex`). With them, a stranger can:
  - **Block sign-up:** call `tally_family_create` 20 times an hour with random ids. That uses up the relay-wide quota, so no real user can start a family again. This needs no invite and leaves nothing to trace.
  - **Fill the database:** create a single family and push 20 000 rows of 64 KiB, about 1.25 GiB. The 500 MB database fills in minutes and Supabase puts the project into read-only mode, so sync stops for everyone. Repeated rotations do the same more slowly, and the box rows are never deleted.
  - **Burn the egress:** pull that family's 1.25 GiB over and over until the 5 GB egress quota is gone.
  - **Why the alarm does not help:** the GitHub alert runs only 4 times a day and fires at 70 %, after the damage is done.
- **Fix:**
  - Give each family a byte budget for records (for example 20 MB, tracked like `blob_bytes`) and a cap on epochs. Delete box rows older than the newest epoch each member has.
  - Rate-limit family creation per source, for example with a proof of work or an Edge Function with an IP limit, instead of one global counter. At least raise the global count and make it a sliding window per IP.
  - Consider putting all RPCs behind one Edge Function that applies per-IP limits.
  - Lower the alert threshold and run it more often.

### H2. Locked backups (and a stolen locked book) fall to an offline PIN guess in seconds (verified)
- **Where:**
  - `data/Crypto.kt:34`: PBKDF2-HMAC-SHA256 with 150 000 rounds is the only thing protecting the envelope. The AES-GCM tag gives a perfect oracle for each guess.
  - `LockRules.minLength`: a PIN can be 4 digits.
  - `BackupCore.write`: the backup contains that envelope, and the envelope holds the whole book: the family keyring, this member's P-256 private key, relay secret and notice token.
  - `BackupFlow.kt:291`: the app tells the person "Backups are locked with your PIN".
  - The monthly card (`backup.sendReminder`) asks them to send the file to Google Drive or WhatsApp.
- **Cost of guessing every value, at about 9×10⁹ PBKDF2 iterations/s on one RTX 4090 (hashcat mode 10900):**

  | Lock | Guesses | One GPU |
  |---|---|---|
  | 4-digit PIN | 1.5×10⁹ iterations in total | about 0.2 s (minutes on a laptop CPU) |
  | 6-digit PIN | 10⁶ | about 17 s |
  | Any 4–9-dot pattern | 389 112 | about 7 s |
  | 8 lowercase letters | 2×10¹¹ | about 40 days |
  | Phone lock's 20-digit recovery code | 10²⁰ | out of reach |

- **Impact:** anyone who gets a backup sent to Drive, WhatsApp or email, from a leaked chat or a shared Drive folder, gets every family entry. With the relay secret and private key they also become that member: they can read everything new and write as that member until an admin removes them. The same applies to `ledger.json` pulled off a phone in AFU state by forensic tools.
- **Fix:**
  - Never let a low-entropy secret be the only protection of a file that leaves the phone. Seal backups (and the book) under a random 256-bit data key. Wrap that key:
    - on the phone, with a Keystore key (hardware-backed, rate-limited, so a PIN guess needs the device);
    - for backups, with a high-entropy recovery code, as the phone's lock already does. Make that code mandatory for every lock kind before backups can be sent off the phone.
  - At the very least: require 6+ digits, refuse patterns for backups, warn plainly that a PIN does not protect a backup sent off the phone, and use a memory-hard KDF (scrypt or Argon2id). A KDF alone does not save a 4-digit PIN: 10⁴ guesses at 1 s each is still under 3 hours.

### M1. Any admin can take over the creator's (or any admin's) seat; any inviter can stage the request (verified)
- **Where:** `tally_member_reclaim` (rejoin migration) only checks that the caller is an admin and the target is active. `SyncEngine.approveAs` puts no restriction on `target`. The `back` flag lives inside the seal, so the relay cannot check it.
- **Attack 1 (malicious admin):** the admin makes an invite, joins from a second app install with "I was already in this family" ticked, then on their own phone picks **Allow as <creator>**.
  - In one call the creator's credentials are replaced: the creator's phone gets `TA007` and leaves the family.
  - The attacker now holds the creator's member id, admin role and creator protection. Nobody can demote or remove them, and the real creator cannot come back without an admin.
  - This gets around the documented rule that nobody can remove the creator.
- **Attack 2 (malicious non-admin):** any member can make an invite (`tally_invite_create`) and so holds its secret. They submit a sealed request saying `{name: "Amma", back: true}`.
  - The admin sees "Amma says they are back on a new phone. <member> invited them.", with Amma pre-selected (`MemberText.likelyMember`).
  - One tap on Allow hands Amma's seat, and her admin role if she has one, to the attacker.
  - The same works for anyone holding a leaked QR code.
- **Fix:**
  - On the relay, refuse `tally_member_reclaim` when the target is the creator, unless the caller is the creator.
  - Consider requiring a second admin's approval (or a time delay, with the old phone told and able to veto) for reclaiming an admin.
  - On the phone, show a much stronger warning when the target is an admin or the creator, never pre-select the target, and show the joining phone's key fingerprint so the person can compare it out of band.

### M2. Joining a family silently uploads the joiner's whole existing book, and invite names are not authenticated (verified)
- **Where:** `Membership.enter` calls `SyncModel.markAllDirty` and queues every live file (`blobsUp`). The confirmation in `FamilyScreen` shows the family and inviter names that come from the invite itself, which are attacker-controlled. The `tally://join` link is `BROWSABLE`, so any web page or chat link can bring up that confirmation.
- **Attack:** a stranger makes their own family and sends a link whose family name is "Example family" and whose inviter is "Amma". A parent who is not yet in a family taps Join; the attacker approves. Every non-private loan, payment and document already on that phone is pushed to the attacker's family, and so is everything entered later.
- **Fix:**
  - When a phone enters a family and already holds entries, ask "Share your N existing entries with <family>?" (or make them private by default) before calling `markAllDirty`.
  - After approval, show who is in the family before anything is pushed.
  - Tell the person, at the join confirmation, that the names come from the code and are not checked.

### M3. A hostile relay can roll records back to any earlier version (verified)
- **Where:** `SyncModel.mergeRemote`, line 172-176. A record that is not dirty is replaced by the pulled row whatever its `updatedAt`. The AAD binds only family and id (`tally:<fam>:<id>`), and blobs sealed under old epochs still open.
- **Attack:** the relay operator, or anyone with database access, keeps old ciphertexts and serves an older one with a fresh `seq`. Every phone then reverts that loan or payment: a deleted payment comes back, an amount reverts, a tombstone is undone.
- **Gap:** DESIGN says only that the relay can "show stale state" and "cannot forge a record". This is stronger: it rewrites the state phones already hold.
- **Fix:** take a pulled row only when it is newer than the local copy (`remoteWins`) or equal to it, for dirty and clean records alike. Clamp a pulled `updatedAt` to now plus a small tolerance, so a member cannot future-date a record to make it permanent. Document the residual risk: a relay can still withhold updates.

### M4. A member can poison other phones' key pins (TOFU taken from member records), leaving a member unable to get new keys (suspected; the code path was read)
- **Where:**
  - `Membership.pinFromRecords` runs after every pulled page and pins the `pub` of any member record, written by anyone, whenever that id is not pinned yet.
  - Member records are ordinary records that any member can write, and `updatedAt` is not checked.
  - `rotationBoxes` and the repair in `runSync` seal new keys to `fam.pins`.
- **Attack:**
  1. When admin A lets X in, A's phone pins X's real key and writes X's record.
  2. Member M sees X in `tally_state.members` and at once pushes a record for X with M's own `pub` and an `updatedAt` far in the future.
  3. Admin B pulls both records in one page; M's wins the merge (latest `updatedAt`), so B pins M's key for X.
  4. B's next rotation (after a leave or a removal) seals X's box to M's key. X can never open it and stays at `waitingForKey`, pushing nothing.
  5. The relay has recorded `has = newest` for X, so no repair box is ever made.
  6. B's grants then pass the bad pin on to everyone B lets in.
- With the relay's help, the removed M could also fetch X's box and read the new epoch.
- **Fix:** do not pin from records. Take pins only from material an admin has MAC'd: the approving admin's grant or rotation box, and add the new member's pin to the next box an admin seals for each member. Or at least pin only member records whose `updatedBy` is an admin and whose `invite` matches an approval. Also let a member report "I can't open my box" so an admin re-seals to the correct key.

### M5. The app lock never comes back after a share sheet, document viewer, picker or camera (verified)
- **Where:** `MainActivity.onStart` skips the relock when `holdLock` is set. `holdLock` is set before every share sheet, viewer, picker and camera (`Attachments.kt:283,333,370,371`, `FamilyMembers.kt:183`, `BackupFlow.kt:232,255,270`) and is cleared only in the next `onStart`, with no time limit. Only the phone prompt has a 1.5 s timer.
- **Scenario:** someone opens a loan document in a PDF viewer (or shares an invite or a backup), presses Home and puts the phone down. Hours later, anyone holding the unlocked phone opens Tally from recents and gets in with no PIN.
- **Fix:** store the time when `holdLock` was set and honour it only for a short window (for example 2 minutes), or only while the relock delay has not passed. Always relock when the time away is over the limit.

### L1. No FLAG_SECURE: recents thumbnails and screenshots show amounts, the invite QR and the recovery code even while "locked" (verified)
- **Where:** no `FLAG_SECURE` or `setRecentsScreenshotEnabled` anywhere. The relock happens only in `onStart`, so the system snapshot taken on leave shows the last screen.
- **Impact:**
  - The task switcher of an unlocked phone shows names and amounts.
  - It can also show the live invite QR (valid 24 h, one request) or the recovery code sheet.
  - Screenshots and screen-recording apps capture them.
- **Fix:** call `setRecentsScreenshotEnabled(false)` (API 33+), or set `FLAG_SECURE` while a lock is set. Always set `FLAG_SECURE` on the invite sheet and the recovery code sheet.

### L2. A decrypted copy of the last opened document stays in the cache (verified)
- **Where:** `Attachments.kt:276-279` writes the plain bytes to `cacheDir/shared/<name>`. That folder is cleared only when the next document is opened; nothing clears it at start or when the app locks.
- **Impact:** with an app lock on, one document per phone stays unencrypted at rest, which defeats `FileStore`'s seal for it.
- **Fix:** delete `cache/shared` at start, at relock and in `onStop` after a short delay. Or serve the bytes through a `ContentProvider` that streams from memory.

### L3. Plain copies outlive turning a lock on (verified)
- **What stays plain:**
  - Daily backups made before the lock was set: up to 14 files in the user's folder, often shared storage, each holding the keyring and credentials.
  - The restore safety copy `filesDir/restore/before-restore.tally-backup`.
  - Any `cache/backup` file until the next start.
- Changing a PIN because it leaked does not re-seal old backups either.
- **Fix:**
  - When a lock is first set, offer to delete or re-make the folder's earlier backups.
  - Re-seal or delete the safety copy.
  - Consider refusing daily backups to a shared folder while no lock is set; the footer warning alone is weak for a file that is written automatically every day.

### L4. Who wrote what is not authenticated, and members can rewrite each other's records (verified; partly documented)
- **Where:** `by` and `updatedBy` are whatever the pushing phone writes. Neither the relay nor the merge checks them, and member records (names, `left`, `deleted`, `pub`) are writable by any member.
- **What a malicious member can do:**
  - Make an entry appear to be made by someone else.
  - Rename other members.
  - Tombstone any shared loan.
  - Flip `private: true` on someone's shared loan. The victim's phone takes it; its next edit then pushes a tombstone, which deletes the loan for everyone.
- DESIGN says "trust within the family is the point", but the screens present "who entered it" as fact.
- **Fix:** document this plainly in the threat model. For attribution that holds up, sign each record with the member's P-256 key and verify it against the pin on merge.

### L5. A member can burn other members' invites (verified)
- **Where:** `tally_state.invites` gives every member the ids of open invites. `tally_join_request` needs only the invite id.
- **Impact:** a member can submit a junk request on someone else's invite. The admin's phone auto-denies it and the real joiner is told the code does not work. This is availability only.
- **Fix:** return only the caller's own invite ids to non-admins.

### L6. Supabase Auth email sign-up is on although the app does not use Auth (verified live)
- **Where:** `/auth/v1/settings` reports `disable_signup: false` with email enabled.
- **Impact:** strangers can create `authenticated` users, and use the project's mailer to send confirmation emails. `authenticated` has the same RPC grants as `anon`, so there is no extra access.
- **Fix:** turn off sign-ups and every provider in the dashboard.

### L7. A missing lock key falls back to debug signing (verified)
- **Where:** `app/build.gradle.kts` uses the debug key if `TALLY_STORE_FILE` is missing.
- **Mitigation already in place:** `scripts/release.sh` checks for `CN=Tally`.
- **Fix:** fail the release build instead of falling back.

### Info
- **Unbounded responses:** `HttpRelay` reads a whole response into memory with no cap (`readBytes`), so a hostile relay can crash the app with a huge body. Cap the size.
- **Usage call reveals activity:** `tally_relay_usage` gives the total database size to anyone. It is useful to an attacker timing H1, and harmless otherwise. `tally_ping` and `tally_relay_usage` can be spammed without credentials (each costs only a `pg_database_size` call or a light sweep).
- **No guess limit on the lock screen:** wrong PINs are not limited or delayed beyond the PBKDF2 time of about 0.3 to 1 s each. A 4-digit PIN entered by hand or by automation on an unlocked phone is a matter of hours. Add exponential delays.
- **Keys stay in memory after relock:** `Crypto.key` stays set and `SyncEngine.checked` keeps invite secrets. Only process-memory forensics could use this.
- **Writes under the old key after a leave:** until an admin's phone syncs and rotates, new data is still written under the key the departed member holds. Exploiting it needs relay database access. Consider rotating from any admin's phone right after a leave, or letting a member's phone ask for it.
- **Leaked invite text:** the invite is shared as plain text through the share sheet, which is by design. A leaked code still needs an admin's approval, but see M1 and M2 for what a leaked code is worth.
- **Future tables:** the default privileges in `public` grant `anon` and `authenticated` full rights on any new table or sequence. Every migration so far revokes them, and every future one must too. Consider `alter default privileges ... revoke`.

## What was checked and found sound
- **Relay access control (live):**
  - All 10 tables have RLS on with no policies and no grants to `anon` or `authenticated`. PostgREST answers 401 to reads and inserts (tried on `tally_family`, `tally_member`, `tally_record`, `tally_blob`, `tally_replaced`).
  - The sequence has no grants. There are no views, no storage buckets and no publication tables. `pg_graphql` is off, and the OpenAPI root needs the secret key.
  - Every `public.tally_*` function and every `tally_private` helper is `SECURITY DEFINER` with `search_path=""`. The `tally_private` schema has no USAGE grant, and its functions are executable only by `postgres` (an RPC call to `tally_lock` gives 404).
  - Unknown family, member or secret all give the same `28000` (live). Bad invites give `TA005`.
- **Relay logic:**
  - Every function that changes a family takes the advisory lock before authenticating, and authentication compares SHA-256 hashes of 256-bit secrets.
  - Admin-only calls check the role on the relay.
  - The creator cannot be removed or demoted by others (`tally_member_role`, `tally_member_remove`); see M1 for the way around it.
  - Epoch checks prevent writes under stale keys. Removing a member rotates the key in the same transaction.
  - The member trigger drops what a departed member is owed and has asked for.
  - Inputs are checked: id regexes, blob, box and meta sizes, jsonb shapes, row counts, and 256-character secret caps.
  - Error messages are fixed strings. There is no way to enumerate families or members: ids are 128-bit, and invite and secret values are needed for anything else.
  - `tally_join_check` cannot hand a phone another member's seat: the grant is MAC'd per member id.
- **Cryptography:**
  - HKDF follows RFC 5869 with distinct info strings for each purpose.
  - AES-256-GCM uses random 96-bit IVs from `SecureRandom`; message counts are far below collision limits.
  - The v2 header (version and epoch) is bound into the AAD, and the AAD binds family and id (records), file id, invite, meta and join request.
  - The ECIES boxes use a fresh ephemeral key per box, a salt made of both SPKIs, and the context as both HKDF info and AAD. Public keys are length- and curve-checked, and points are validated by the provider.
  - Box MACs cover family, recipient, public key, `from`, keys, pins, creator, sender and invite, and are compared in constant time. A grant (`from = 0`) cannot be used as a rotation box. Keys must run contiguously up to the row's epoch.
  - The invite secret never reaches the relay. Invite parsing is strict and canonical.
  - The epoch only goes up (`max`), so the relay cannot downgrade it. A phone pushes nothing while `waitingForKey`.
- **App lock keys:**
  - The phone lock's Keystore key: AES-GCM, per-use authentication with `AUTH_BIOMETRIC_STRONG | AUTH_DEVICE_CREDENTIAL`, not invalidated by enrolment, used as the `CryptoObject`, and the copy is bound to the salt.
  - Fingerprint unlock (`Bio`): strong biometric, invalidated by enrolment.
  - The recovery code gives about 66 bits.
- **On the device:**
  - `allowBackup=false`, and the data-extraction rules exclude every domain.
  - FileProvider exposes only `cache/{shared,camera,backup}` and is not exported.
  - Only the launcher activity and the widget receiver are exported. Intents use `FLAG_IMMUTABLE` PendingIntents, and the deep link and shared text are only stashed and parsed strictly.
  - No `Log.*` or `println` anywhere. The app reads the clipboard only when the person taps Paste and never writes to it.
  - Notifications and the widget name nobody and no amount when locked. The widget snapshot exists only with no lock and amounts shown.
  - `DevicePrefs` holds only dates, settings and the notice token, which can only count requests.
- **Files:** stored ids are sanitised (`FileStore.safe`), and opened names go through `fileSafeName`. Opening uses an allow-listed MIME type. A download is acknowledged only after it opened, matched its SHA-256 and was read back. A private loan's files are never uploaded or offered on an ask.
- **Backups:**
  - The checks reject anything but known entry names (no paths, traversal, directories or duplicates), and enforce size caps, per-entry size and SHA-256, the manifest and version, and the book shape.
  - The restore is staged behind a journal, and a restore never removes a lock.
- **Network:** HTTPS only. There is no network-security config, and targetSdk 36 refuses cleartext and user CAs; the `http://` URL redirects. There are 15 s and 60 s timeouts. Only the relay is contacted: no Firebase, analytics or Play Services.
- **APK:** the release build has R8 and resource shrinking on, is not debuggable, has `allowBackup=false`, and contains only the URL and publishable key; no service, secret or JWT key was found in the dex.
- **Workflow:** `permissions: {}`. It uses only the publishable key from secrets and prints only the HTTP status, the ping body and relay-wide totals. The repo is private.

## Fixes (on the device)

Commits `f6f0c99` (L7) and `4295d02` (everything else below). Tests: `LockSecurityTest` (new), `LockModesTest`, `BackupCoreTest`. Design: `docs/DESIGN.md` "App lock" (the seal, the cost table, wrong tries, `holdLock`, screenshots) and "Backups" ("After a lock change").

- **H2: Fixed** (`4295d02`). The book is sealed under a random 256-bit data key (envelope `enc: 2`). It is wrapped under PBKDF2 of a 20-digit recovery code, which every lock now has (shown and typed back when a lock is first set, offered again before the first backup leaves the phone, and viewable behind the lock). For Tally's own lock it is also wrapped under HKDF(a 32-byte secret kept under an Android Keystore key, PBKDF2(PIN)). A backup or `ledger.json` copied off the phone can only be opened with the recovery code: about 10²⁰ × 150 000 PBKDF2 iterations, roughly 5×10⁷ years on one RTX 4090. On the same phone the PIN still opens a backup; on another phone the recovery code is asked. Books and backups sealed the old way still open, and the first unlock re-seals them (`Ledger.open`). Tally's own lock gets a new recovery code, shown once. The fingerprint and phone-lock copies keep working, with no extra prompt.
  - **Remains:** a backup made before the update that already left the phone (Drive, WhatsApp) stays as weak as its old PIN. It cannot be reached; only the person can delete it.
  - **Remains:** with code running as the app on an unlocked or rooted phone, the waits can be skipped and a 4-digit PIN is guessed on the phone's CPU in hours. A long password or the phone's lock is the defence there (DESIGN cost table).
- **M5: Fixed** (`4295d02`). `holdLock` now records when it was set. It is honoured only if the app comes back within 2 minutes, or within the re-lock delay if that is longer (`LockRules.relockOnReturn`, tested).
- **L1: Fixed** (`4295d02`). `FLAG_SECURE` is on while a lock is set (the lock screen included) or amounts are hidden, and always while the invite sheet or the recovery code sheet is up (`ScreenGuard`).
  - **To check on a device:** Compose sheets inherit the flag from the activity window.
- **L2: Fixed** (`4295d02`). `cache/shared` is emptied when the app comes back to the front, at re-lock and at every start. The cache is not in backups: `BackupCore` copies only the book and `files/`, `allowBackup` is false, and the extraction rules exclude every domain.
  - **Trade-off:** a viewer that reads lazily loses the file once Tally is back in front.
- **L3: Fixed** (`4295d02`). After a lock is set or changed, or a book is moved to the new seal:
  - the restore safety copy is sealed again silently;
  - the share cache is deleted;
  - Tally's backups in the daily folder that are plain, or sealed by an earlier lock this session knows, are rewritten sealed after one question per lock (`BackupReseal`, `BackupCore.reseal`). The rewrite keeps the date and name, and the new file replaces the old one only after it checks out.
  - A PIN change keeps the data key and the recovery code, so older backups of the lock open with the same paper.
  - **Not done:** daily backup is not refused into a shared folder while no lock is set. The footer warning stays.
- **L7: Fixed** (`f6f0c99`). `packageRelease` and `bundleRelease` fail without the release signing config. Verified with `-PTALLY_STORE_FILE=/nonexistent`. Debug builds and lint are unaffected, and `scripts/release.sh` is unchanged.
- **Info, no guess limit: Fixed** (`4295d02`). After 5 wrong tries in a row, the waits are 30 s, 1 min, 5 min, 15 min, then 1 hour each.
  - The count is kept in `DevicePrefs`, so a restart does not reset it, and a clock set back restarts the wait.
  - A right answer resets it. Nothing is wiped.
  - It covers unlock, confirm, the restore's sealed-file stage and the recovery code.
- **Info, keys in memory after relock: Partly fixed** (`4295d02`). Re-lock zeroes the data key and any remembered older key (`Crypto.forget`) and drops the book.
  - Strings from the dropped book (the files key, the recovery code) cannot be zeroed in the JVM.
  - `SyncEngine.checked` (invite secrets) belongs to `sync/` and was left to the relay/membership fixes.

## Fixes (relay and membership)

Commits `ea6e5cf` (L6), `fd3c481` (the relay: migration `20261112115931_tally_hardening.sql`, deployed 2026-11-12, and the storage alert) and `82ffcf4` (the app: merge rules, signatures, joining, Allow as). Tests: `SyncHardeningTest` (new), `SyncModelTest`, `SyncRejoinTest`, `SyncRestoreTest`, `SyncMembershipTest`; `FakeRelay` mirrors the migration; `SyncLiveTest` passed against the deployed relay and cleaned up after itself. Design: `docs/DESIGN.md` "Joining", "Back on a new phone", "Pins", "Threat model", "Merge rules", "Who wrote what", "The relay", "Limits".

- **H1: Fixed** (`fd3c481`, `82ffcf4`).
  - **Filling:** each family has a byte budget for records and key boxes (5 MiB while it has had one member, 20 MiB after). The relay refuses any growth once the families hold 300 MiB or the database is above 425 MiB, so it never reaches the size at which Supabase makes it read-only. Removals, rotations, approvals and deletions are never refused by it. At most 300 epochs per family; boxes of members no longer active are deleted and each member keeps its newest 20.
  - **Freezing sign-up:** a 20-bit proof of work per family (about a second on a phone); 5 an hour and 20 a day per source address (Cloudflare's `cf-connecting-ip`; IPv6 by /64; stored only as a peppered SHA-256, two days); relay-wide sliding windows of 100 an hour and 500 a day.
    - **Checked live:** the address came from `cf-connecting-ip`. A forged `X-Forwarded-For` or `X-Real-IP` did not change it, and a forged `CF-Connecting-IP` was refused by Cloudflare (403).
  - **Egress:** pull pages hold at most 4 MiB, and a family can download at most 200 MiB a day.
  - **Cleanup:** families that never had a second member and have not pulled for 60 days are deleted by `tally_ping`. The phone says so once and keeps every entry (`SyncResult.Ended`).
  - **Alert:** every three hours, at 50 % or after 25 MB of growth in 24 hours (`grew_24h_bytes`).
  - **Remains:** someone with many addresses can still start families up to the relay-wide window and fill the relay to its ceiling. New growth then stops for every family until the owner deletes the abusive ones; reading, deleting and every security action keep working. Closing this needs accounts or an attestation service, which the design rules out. Tally 0.5.0 and older can no longer start a family (the call now needs the proof of work).
- **M1: Fixed** (`fd3c481`, `82ffcf4`).
  - **On the relay:** `tally_member_reclaim` refuses the creator as target unless the caller is the creator (moving phones from the old one). It refuses another admin as target when the approving admin made the invite, so a second person must take part: another member makes the invite, or another admin approves.
    - **Why this rule:** a delay with a veto would protect only when the old phone is still in the person's hands, and would slow every real recovery, when the phone is usually lost.
    - **Cost:** in a family whose only two admins are A and B, B's place can be handed back only through an invite made by someone other than A. Otherwise B comes back as someone new.
  - **On screen:** nobody is pre-selected (`likelyMember` is gone). The card shows the new phone's 8-digit code, which the waiting phone also shows, and asks the admin to call and compare it. Allow as asks once, naming the member, with more care for an admin. The creator, or an admin the relay would refuse, shows why instead of the button.
  - **Remains:** an admin can still hand a plain member's place to a phone they control, as an admin could already remove that member.
- **M2: Fixed** (`82ffcf4`).
  - **After approval:** nothing already on the phone is shared until the person answers "Share your existing entries with <family>?". The question says how many there are and who is in the family. Share all sends them; "Keep them on this phone only" makes those loans private, so they and their payments and files never leave. Held entries stay back even when edited meanwhile; new ones are shared as usual.
  - **The join question** now says "The invite link says it is from <inviter>. Tally can't check the names in a link. Join only if you know who sent it."
  - **Remains:** new entries made after joining a stranger's family are shared with it. That is what joining means.
- **M3: Fixed** (`82ffcf4`).
  - **Rollback:** a pulled row is taken only when it is the same version as the phone's or newer. An older one is refused and the phone's newer copy is sent back up, so a replayed old ciphertext changes nothing and a tombstone cannot be undone.
  - **Future dates:** `updatedAt` more than 10 minutes ahead is clamped, and writes stamp after the version they replace.
  - **Remains:** the relay can withhold updates, and can serve an old copy to a phone that never had the newer one (a new joiner, or a phone away the whole time). Detecting that needs signed history.
- **M4: Fixed** (`82ffcf4`). Pins come only from an admin's word: approval, the grant, checked boxes, or an admin's signed certificate in the member's record (`tally-pin|1|<family>|<member>|<pub>`). A certificate counts only if it is signed by a pinned key of someone this phone has seen as an admin. A member's record `pub` alone pins nothing.
  - A member record written over by someone else is refused, and a phone holding the proved copy sends it back up.
  - **Remains:** a member colluding with the relay could have themselves shown as an admin in `tally_state`. That is the same out-of-scope collusion as before.
  - **Not done:** an "I can't open my box" report from the member's phone. A box sealed to a wrong key can no longer come from a member's record.
- **L4: Partly fixed** (`82ffcf4`). Every pushed record is signed with the member's family key (envelope field `sig`; older readers ignore it). The merge takes:
  - a member record only from that member or an admin;
  - a tombstone only from the entry's maker, its loan's maker or an admin;
  - never a shared record that says `private: true`.

  The screens check the same rule before deleting ("Only <name>, who added this, or a family admin can delete it."). Making someone else's loan private now only hides it on that one phone.
  - **Remains, accepted:** editing someone else's entry is allowed (trust within the family) and is last-writer-wins. Who *first* made an entry (`by`) is only the writer's word for a record a phone first sees through someone else's edit.
  - **Mixed versions:** rows from 0.5.0 phones are unsigned. Newer phones ignore their member-record changes and their deletions until every phone is updated.
- **L5: Fixed** (`fd3c481`). `tally_state` shows a member who is not an admin only the invites they made. Cancelling was already limited to the maker or an admin.
- **L6: Fixed** (`ea6e5cf`). Sign-ups and the email provider are off on the project (pushed with `supabase config push`, which changed only those settings). Checked live: `/auth/v1/settings` shows `disable_signup: true` and no email provider, and a sign-up gets 422.
- **Info, unbounded responses: Fixed.** `HttpRelay` reads at most 10 MiB of any answer (`readCapped`), and the largest honest one is now a 6 MiB file.
- **Info, `tally_relay_usage` / `tally_ping` call volume: Fixed.**
  - The usage call reads the database size from a copy at most 5 minutes old.
  - The ping's sweeps run at most every 10 minutes, and it still answers 1.
  - Both stay without credentials (the workflow needs that). They give only relay-wide totals, now including the 24-hour growth.
- **Info, future migrations and default grants: Fixed** (`fd3c481`, `82ffcf4`). The default privileges for objects postgres creates no longer grant anything to `anon`, `authenticated` or PUBLIC. `SyncHardeningTest.everyNewRelayObjectRevokesTheDefaultGrants` checks that every migration revokes the grants of what it creates.
- **Info, invite secrets in memory after relock: Fixed** (`82ffcf4`). The engine forgets checked join requests and their secrets whenever the book leaves READY (`SyncEngine.forgetSecrets`).
- **Info, writes under the old key after a leave: Accepted.** It still waits for an admin's sync, and exploiting it still needs relay database access.
