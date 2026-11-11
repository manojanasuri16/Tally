# Tally: design

Tally is a family payment book for Android, kept mostly for loans. Each phone keeps the whole book locally and works offline. Phones in the same family exchange changes through a relay that only ever holds ciphertext.

It is a sibling of Balance (`~/Balance/android`, package `com.balance.ledger`) and reuses its core helpers, ledger storage, crypto, file store, UI kit and translations approach. Balance itself is never changed by this project.

- Package and `applicationId`: `com.tally.book` (never change after the first release).
- Users: parents first. Large type, few choices per screen, Telugu and Hindi from day one.

## What it does

- Three kinds of loan:
  - `bank`: principal, yearly rate, tenure in months, monthly EMI, due day.
  - `hand`: money borrowed from a person. Only interest is paid (monthly or yearly); the full principal stays due until a lump-sum repayment.
  - `gold`: a gold loan. Interest is paid once a year; principal is repaid on closure.
- Log a payment against a loan: amount, date, what it was for (EMI, interest, principal), and optionally a note, a pasted bank SMS, and photos or documents.
- Keep each loan's documents.
- Family sync: pull down to refresh. Every loan and payment shows who entered it.
- A loan is shared by default. A private loan, its payments and its files never leave the phone.

## The local book

Same conventions as Balance (`android/PORTING.md` there): `DB` is an `Obj`/`Arr` JSON tree, every number a `Double`, unknown fields round-trip, every change goes through `Ledger.edit { }`, ids come from `uid(prefix)`.

```
DB = {
  v: 1,
  me:      { id, name },                       // this phone's person; id = uid("m")
  family:  { id, key, name } | absent,          // key: base64 of 32 random bytes; absent until created or joined
  members: [ { id, name, at, updatedAt, updatedBy, deleted? } ],
  loans:   [ { id, kind, name, lender, principal, rate, startDate, tenureMonths, emi, dueDay,
               interestEvery,                   // "month" | "year" (hand: default month; gold: default year)
               rateUnit,                        // "year" (default: percent a year) | "month" (percent a month; "2 rupees interest" = 2) (hand, gold)
               grams, note, private, closed, closedDate,
               by, at, updatedAt, updatedBy, deleted? } ],
  payments:[ { id, loanId, date, amount, kind,  // kind: "emi" | "interest" | "principal"
               note, sms, by, at, updatedAt, updatedBy, deleted? } ],
  files:   [ { id, ownerType, ownerId,          // ownerType: "loan" | "payment"
               name, mime, size, sha256, by, at, updatedAt, updatedBy, deleted? } ],
  sync:    { cursor, lastAt, dirty: { "<recordId>": true }, blobsUp: { "<fileId>": true },
             pushed: { "<recordId>": true } }        // ids the relay is known to hold (sync/SyncModel.kt)
}
```

- `by` / `updatedBy` are member ids; `at` / `updatedAt` are epoch milliseconds from `Clock.millis()`.
- Dates are ISO `YYYY-MM-DD`, device-local.
- Deleting sets `deleted: true` (a tombstone) so the delete can travel. Tombstones are hidden everywhere in the UI.
- Every write helper in the model stamps `updatedAt`/`updatedBy` and marks the id in `sync.dirty`. Screens never do this by hand.
- A payment or file is private exactly when its loan is private (`isPrivate(record)` resolves through the owner).

## Sync

### Keys

- `family.key` (32 random bytes) is made on the phone that creates the family and travels only inside the invite.
- From it, by HKDF-SHA256:
  - `encKey` = HKDF(key, info "tally-enc"): AES-256-GCM for every record and file.
  - `authToken` = HKDF(key, info "tally-auth"), hex: proves membership to the relay. The relay stores only its SHA-256.
  - HKDF has no salt (RFC 5869's all-zero default) and a 32-byte output; the relay stores `sha256(authToken)` as hex (`sync/SyncCrypto.kt`).
- Record AAD: `"tally:<familyId>:<recordId>"`. File AAD: `"tally-file:<familyId>:<fileId>"`. A blob cannot be replayed under another id.
- A sealed blob is base64 (standard, padded) of `version (1 byte, now 1) || iv (12 random bytes) || ciphertext || GCM tag (16)`. The version byte is also fed to GCM as additional data ahead of the AAD, so it cannot be changed undetected; a later format bumps it.

### Invite

`tally://join#<familyId>.<base64url(key)>.<base64url(familyName)>` (base64url without padding), shown as a QR code and shareable as text. The other phone scans it, or pastes it into "Join with a code". The fragment is never sent to any server. Whoever holds an invite can join, so it is shown only on demand, and every member appears in the members list.

- The family id is 16 random bytes, base64url (22 characters): it is the only thing between a stranger and the endpoint's rows besides the token, so it is never a `uid()`.
- The name is 1 to 80 characters, no control characters. `Invite.parse` is strict and returns null for anything else (extra parts, padding, a key that is not 32 bytes, invalid UTF-8).

### The relay (Supabase)

Row level security is on for every table with no policies, so the anon key can reach nothing directly. All access is through `security definer` functions that check the token:

- `tally_family_create(family_id, token_hash)`
- `tally_push(family_id, token, rows jsonb)` → rows are `{ id, kind, blob }`; each upsert takes the next value of a per-database sequence as `seq`
- `tally_pull(family_id, token, since bigint, max int)` → `{ id, kind, blob, seq }` ordered by `seq`
- `tally_blob_put(family_id, token, id, data)` / `tally_blob_get(family_id, token, id)` for encrypted file bytes (base64 text)
- `tally_ping()` returns 1 after reading one row; no token. A daily GitHub Action (`.github/workflows/keep-awake.yml`) calls it so the free project is never paused.

Every tokened function hashes the token with SHA-256 and compares it to the family's `token_hash`; an unknown family and a wrong token give the same error (SQLSTATE 28000). `tally_family_create` is idempotent for the same hash and refuses a different hash for an existing id. Limits: ids `[A-Za-z0-9_-]{1,64}` (family ids exactly 22), kind one of the four, at most 500 rows per push and 500 per pull, a record blob at most 64 KiB, a file blob at most 6 MiB. Pushes to one family take a per-family advisory lock before taking seq values, so seqs become visible in order within a family and paging by `seq > cursor` never skips a row (the reasoning is in the migration). There is no delete function: a family's rows stay on the relay after everyone leaves.

The relay sees: family id, record ids, record kind, sizes and timing. It never sees names, amounts, notes, messages or file contents.

The phone talks to PostgREST with `HttpURLConnection` (no SDK). The project URL and anon key come from Gradle properties `TALLY_SUPABASE_URL` / `TALLY_SUPABASE_ANON_KEY` into `BuildConfig`. The SQL lives in `supabase/migrations/`.

### One refresh

1. **Pull** everything with `seq > sync.cursor`. For each row, decrypt and:
   - not dirty locally → take the remote record;
   - dirty locally → keep whichever has the later `updatedAt` (ties: the greater `updatedBy`); if remote wins, clear the dirty mark.
   Advance `cursor`.
2. **Push** every dirty record that is not private. On success clear its dirty mark.
3. **Files:** upload the bytes of each id in `sync.blobsUp`; download bytes for any file record that has none locally (lazily is fine: on first open).

On the wire a record is the sealed envelope `{ "k": "loan"|"payment"|"file"|"member", "r": { ...the record... } }`. The wire kind sits outside the record because loans and payments have their own `kind` field. It must match the relay row's `kind` and `r.id` must match the row id, or the row is skipped. Fields that describe only this phone's copy (a file's `enc`) are not sent, and a pulled record keeps the local value. Merge is per record, last writer wins. Payments are separate records, so two people logging payments never collide.

Refinements in `sync/`:
- The cursor moves in the same save as the page it covers, so a sync that stops halfway resumes. A dirty mark is cleared after a successful push only if the record has not changed since it was collected (an edit during the push stays dirty).
- A record that is private on this phone is never replaced by a pulled row. If someone else's edit of it arrives, it is ignored and the record is marked dirty again, so this phone's tombstone goes back up and wins.
- Pushing a private record: if its id is in `sync.pushed` the push is a bare tombstone `{ id, deleted: true, updatedAt, updatedBy }`, otherwise nothing is sent. A payment or file whose owner cannot be found counts as private when pushing (fail closed).
- Creating or joining marks every record dirty and every live file for upload, so a phone that already had loans shares them. Joining checks the invite with one pull before anything is saved. Leaving removes `family` and `sync` and keeps every record.

Making a shared loan private pushes tombstones for the loan, its payments and its files, and keeps the local copies. Making a private loan shared marks them all dirty.

Sync runs only when asked (pull to refresh, or the Sync button) and once on app open if a family exists. No background work.

### Files

- Stored locally as in Balance (`data/Files.kt`: byte-for-byte, SHA-256 checked).
- Photos taken or picked for a payment are scaled to at most 1600 px and saved as JPEG before they are stored, so the stored bytes are what syncs.
- Documents keep their original bytes. Limit: 4 MB per file.

## Screens

Follows Balance's `android/UI.md` kit, simplified, plus the apple-design rules (press feedback on touch-down, critically damped springs, 1:1 pull to refresh with rubber-banding, sheets that leave the way they came).

- **Welcome:** "What is your name?" → the book. Nothing else.
- **Loans (home):** total still owed, then "Due this month" (each row has a single Pay button), then the loans grouped by kind. One add button. Pull down to sync; a small line says when it last synced.
- **Loan:** what is owed, next due date and amount, payments (newest first, each with who logged it), documents, Edit, Close loan.
- **Add / edit loan (sheet):** kind first as three big choices; then only that kind's fields. "Keep private" toggle, off by default.
- **Log payment (sheet):** amount (pre-filled with the EMI or interest due), date (today), kind, then optional: note, paste bank message, add photo, add document.
- **Family:** members, Invite (QR + share), Join (scan or paste), my name, language, app lock.

## Not in version 1

- Removing a member (needs a new family key on the remaining phones).
- Background sync and notifications.
- Approving a join request.
