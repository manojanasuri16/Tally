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
               interestEvery,                   // "month" | "year" (hand, gold)
               grams, note, private, closed, closedDate,
               by, at, updatedAt, updatedBy, deleted? } ],
  payments:[ { id, loanId, date, amount, kind,  // kind: "emi" | "interest" | "principal"
               note, sms, by, at, updatedAt, updatedBy, deleted? } ],
  files:   [ { id, ownerType, ownerId,          // ownerType: "loan" | "payment"
               name, mime, size, sha256, by, at, updatedAt, updatedBy, deleted? } ],
  sync:    { cursor, lastAt, dirty: { "<recordId>": true }, blobsUp: { "<fileId>": true } }
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
- Record AAD: `"tally:<familyId>:<recordId>"`. File AAD: `"tally-file:<familyId>:<fileId>"`. A blob cannot be replayed under another id.

### Invite

`tally://join#<familyId>.<base64url(key)>.<base64url(familyName)>`, shown as a QR code and shareable as text. The other phone scans it, or pastes it into "Join with a code". The fragment is never sent to any server. Whoever holds an invite can join, so it is shown only on demand, and every member appears in the members list.

### The relay (Supabase)

Row level security is on for every table with no policies, so the anon key can reach nothing directly. All access is through `security definer` functions that check the token:

- `tally_family_create(family_id, token_hash)`
- `tally_push(family_id, token, rows jsonb)` → rows are `{ id, kind, blob }`; each upsert takes the next value of a per-database sequence as `seq`
- `tally_pull(family_id, token, since bigint, max int)` → `{ id, kind, blob, seq }` ordered by `seq`
- `tally_blob_put(family_id, token, id, data)` / `tally_blob_get(family_id, token, id)` for encrypted file bytes (base64 text)

The relay sees: family id, record ids, record kind, sizes and timing. It never sees names, amounts, notes, messages or file contents.

The phone talks to PostgREST with `HttpURLConnection` (no SDK). The project URL and anon key come from Gradle properties `TALLY_SUPABASE_URL` / `TALLY_SUPABASE_ANON_KEY` into `BuildConfig`. The SQL lives in `supabase/migrations/`.

### One refresh

1. **Pull** everything with `seq > sync.cursor`. For each row, decrypt and:
   - not dirty locally → take the remote record;
   - dirty locally → keep whichever has the later `updatedAt` (ties: the greater `updatedBy`); if remote wins, clear the dirty mark.
   Advance `cursor`.
2. **Push** every dirty record that is not private. On success clear its dirty mark.
3. **Files:** upload the bytes of each id in `sync.blobsUp`; download bytes for any file record that has none locally (lazily is fine: on first open).

A record is one JSON object `{ kind: "loan"|"payment"|"file"|"member", ...fields }`. Merge is per record, last writer wins. Payments are separate records, so two people logging payments never collide.

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
