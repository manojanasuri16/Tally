# Tally: design

Tally is a family payment book for Android, kept mostly for loans. Each phone keeps the whole book locally and works offline. Phones in the same family exchange changes through a relay that only ever holds ciphertext.

It is a sibling of Balance (`~/Balance/android`, package `com.balance.ledger`) and reuses its core helpers, ledger storage, crypto, file store, UI kit and translations approach. Balance itself is never changed by this project.

- Package and `applicationId`: `com.tally.book` (never change after the first release).
- Users: parents first. Large type, few choices per screen, Telugu and Hindi from day one.

## What it does

- Three kinds of loan:
  - `bank`: an instalment loan: principal, yearly rate, number of instalments, instalment (EMI), and how often it falls (every month, two weeks, week or day). Interest on what is still owed (reducing) or flat. A floating rate can change from a date.
  - `hand`: money borrowed from a person (or, with `direction: "given"`, money lent to one). Only interest is paid (monthly or yearly), or none at all; the principal stays owed until it is returned, all at once or in parts.
  - `gold`: a **loan against something** (`pledge`: gold, an LIC policy, a fixed deposit, land papers, or nothing, as for a crop loan or Kisan credit card). Interest only (yearly by default), principal repaid on closure, with an optional date to repay or renew by.
- Log a payment against a loan: amount, date, what it was for (EMI, interest, principal), and optionally a note, a pasted bank SMS, and photos or documents.
- Keep each loan's documents, added when the loan is made or later on its screen.
- Family sync: pull down to refresh. Every loan and payment shows who entered it. Admins let people in (one-time invites, approved by an admin), can remove members (the family's key is then replaced), and make others admins.
- A loan is shared by default. A private loan, its payments and its files never leave the phone, except inside a backup file the person makes.
- Backups: once a day into a folder on the phone (the newest 14 kept), Back up now through the share sheet, and Restore on a new phone.

## The local book

Same conventions as Balance (`android/PORTING.md` there): `DB` is an `Obj`/`Arr` JSON tree, every number a `Double`, unknown fields round-trip, every change goes through `Ledger.edit { }`, ids come from `uid(prefix)`.

```
DB = {
  v: 1,
  me:      { id, name },                       // this phone's person; id = uid("m")
  family:  { v: 2, id, name, creator, me: { secret, notice, pub, priv }, keys: { "<epoch>": key }, relayEpoch,
             role, roster, invites, pins, usedInvites } | absent,   // sync/Membership.kt; absent until created or let in
  joining: { fam, name, inviter, invite, s, me, at, expires } | absent,  // a join request waiting for an admin
  familyGone: { name, why, at } | absent,       // "removed" | "denied" | "expired": told once on screen
  members: [ { id, name, pub, invite?, left?, at, updatedAt, updatedBy, deleted? } ],
  loans:   [ { id, kind, name, lender, principal, rate, startDate, note, private, closed, closedDate,
               interestMethod,                  // "reducing" (default: interest on what is still owed) | "flat" (on the original amount); every kind with interest
               paidUpTo,                        // optional ISO date, after startDate, not in the future: everything due on or before it was paid as agreed (a loan entered late)
               // bank only:
               tenure,                          // number of instalments (in the loan's frequency); 0 = work it out from emi
               emi,                             // the instalment; 0 = work it out from tenure (whole rupees)
               frequency,                       // "month" (default) | "fortnight" | "week" | "day"
               dueDay,                          // day of the month the instalment falls; monthly loans only
               rateChanges: [ { id, from, rate, emi? } ],   // floating rate: from that date the rate (and, with emi, the instalment) changes; reducing loans only
               // hand and gold:
               interestEvery,                   // "month" | "year" (hand: default month; gold: default year)
               rateUnit,                        // "year" (default: percent a year) | "month" (percent a month; "2 rupees interest" = 2)
               repayBy,                         // optional ISO date, after startDate: "to be returned by" (hand) / "repay or renew by" (gold)
               renewals: [ { id, date, from, to, by, at } ],  // renewLoan history: the repayBy moved from `from` to `to` on `date`
               direction,                       // hand only: absent/"taken" (money I took) | "given" (money I gave); never changed after the loan is made
               pledge,                          // gold only: "gold" (default) | "lic" | "fd" | "land" | "none"
               grams,                           // gold only, and only when pledge is "gold"
               by, at, updatedAt, updatedBy, deleted? } ],
  payments:[ { id, loanId, date, amount, kind,  // kind: "emi" | "interest" | "principal"
               note, sms, by, at, updatedAt, updatedBy, deleted? } ],
  files:   [ { id, ownerType, ownerId,          // ownerType: "loan" | "payment"
               name, mime, size, sha256, by, at, updatedAt, updatedBy, deleted? } ],
  sync:    { cursor, lastAt, dirty: { "<recordId>": true }, blobsUp: { "<fileId>": true },
             pushed: { "<recordId>": true } }        // ids the relay is known to hold (sync/SyncModel.kt)
}
```

- Money never nets out. For taken loans `totals()` gives `owed`, `dueThisMonth`, `paidThisMonth`, `byKind`, `pendingThisMonth`; for given loans `toReceive`, `expectedThisMonth`, `receivedThisMonth`, `pendingToReceive`. `dueList()` rows carry `given` and `kind` (`emi`/`interest`/`principal`). On screen a given loan reads "Still to come back", "Log money received", "Expected this month", "Received".
- The loan rules are below ("Loan rules"); the code is `model/LoanMath.kt` (arithmetic), `model/Loans.kt` (view, due list, totals, checks), `model/LoanLabels.kt` (labels and wording), `model/Reminders.kt`, and the writes in `model/Records.kt`.
- `by` / `updatedBy` are member ids; `at` / `updatedAt` are epoch milliseconds from `Clock.millis()`.
- Dates are ISO `YYYY-MM-DD`, device-local.
- Deleting sets `deleted: true` (a tombstone) so the delete can travel. Tombstones are hidden everywhere in the UI.
- Every write helper in the model stamps `updatedAt`/`updatedBy` and marks the id in `sync.dirty`. Screens never do this by hand.
- A payment or file is private exactly when its loan is private (`isPrivate(record)` resolves through the owner).

## Loan rules

All figures come from the payments actually logged; nothing is added to what is owed. Amounts are rupees (`Double`); an EMI and a period's interest are whole rupees, everything else is kept exact and rounded only on screen.

**What is owed** is the principal still outstanding. Interest or instalments that fell due and were not paid are **pending**: shown beside it, never added to it.

### Instalment loans (`bank`)

- **Instalments** fall every month (on `dueDay`, clamped to the month's last day; the first is the first due day after the start date), or every 14, 7 or 1 days from the start date (so a weekly or fortnightly loan keeps the start date's weekday; `dueDay` is ignored). `tenure` is the number of instalments. The periodic rate is the yearly rate / 12, 26, 52 or 365.
- **Reducing** (default): EMI = P·r·(1+r)^n / ((1+r)^n − 1), to the whole rupee. Each instalment's interest is what is owed × r at the rate in force on its due date; the rest of it is principal.
- **Flat**: total interest = P × yearly rate × (n / instalments a year); instalment = (P + total interest) / n, to the whole rupee. Every instalment carries the same interest part, P × yearly rate / instalments a year; the rest is principal.
- With only an EMI given, the number of instalments is worked out from it; with only a count, the EMI. The **last agreed instalment** takes the few rupees left over from rounding the EMI (when what is left differs from the EMI by at most n + 1 rupees), so a 12-instalment loan has exactly 12.
- **EMI payments cover instalments in order, oldest first.** A partial payment covers part of one (what is left on it is the next amount due); a double payment covers two. An instalment's interest is charged once, however many payments it takes. `principal` payments (part-payments) lower what is owed at once. `interest` payments on a bank loan are only counted as interest paid.
- **Part-payments:** reducing: the instalment stays, fewer are needed. Flat: the instalment and its interest part stay the same, fewer are needed, and the instalments no longer needed (with their interest) are simply not charged: instalments left = what is owed / principal part, rounded up, and the last is what is left plus its interest.
- **Rate changes** (`rateChanges`, reducing loans only): every instalment due on or after `from` uses the new rate for its interest; with `emi` it also uses the new instalment. Without `emi` the instalment stays and the number left follows. Instalments due before `from` are never recomputed. Two changes on one date are not allowed (`addRateChange` replaces one on the same date).
- **Pending:** instalments whose date is before today and that are not fully covered (`emisPending`, `amountPending`). The instalments left, the end date, and the amounts of the ones not paid yet are worked forward from what is owed now. An EMI too small to ever clear the loan sets `emiTooLow` (and validation refuses one at or below the first instalment's interest).

### Interest-only loans (`hand` with a rate, `gold`)

- **Periods** end every month or every year after the start date, on the start date's day (clamped).
- **Reducing** (default): a period's interest is worked on what was owed at its start (part repayments dated on or before that day count), so a part repayment lowers the interest from the next period. **Flat:** every period's interest is on the original amount until the loan is closed.
- **Interest payments cover periods in order, oldest first;** a partial payment covers part of one. **Pending** (`interestPending`, `periodsPending`): the uncovered part of every period that ended before today, since the start or since the last period fully paid. Interest is counted only for whole periods that ended while something was still owed: when the whole amount is returned mid-period, that last part-period is not worked out (settle it by hand as an interest payment).
- A loan with `rate: 0` (hand or gold) has no interest: no next due, never pending, never overdue, simply still owed.
- **Repay by** (`repayBy`, hand and gold): from the month it falls in, the remaining amount is a principal row in the due list, overdue once the date has passed while anything is owed. **Renewing** (`renewLoan`) moves the date and keeps a `renewals` entry; it does not touch interest: whatever interest is pending stays pending until it is paid as an interest payment.

### A loan entered after it started (`paidUpTo`)

- Every instalment (bank) or interest period (hand, gold) dated on or before `paidUpTo` counts as paid as agreed, with no payments logged for it; `loanView` gives `paidUpTo` and `paidUpToCount` (how many it covered). `suggestPaidUpTo(draft, today)` gives the form the most recent due date before today by the draft's schedule (for a bank loan at most its agreed number of instalments), "" when none.
- Bank loans: what is owed follows the schedule to that date: reducing, each covered instalment pays its interest at the rate in force on its date (rate changes before `paidUpTo` included) and the rest is principal; flat, P minus the covered principal parts.
- Hand and gold loans: the principal is not changed by `paidUpTo`. **If part of the amount was already returned before that date, enter what was still owed on that date as the amount**, since interest after it is worked on that.
- Logged payments dated after `paidUpTo` work as always. Payments dated on or before it are counted in `paidTotal` (and in the month's paid totals) but cover nothing and lower nothing: the date already accounts for them. Nothing is invented: `paidInterest`, `paidPrincipal` and `paidThisMonth` come from logged payments only.

### Due list, totals, suggestion, reminders

- A loan's instalment/interest row in `dueList()` is everything unpaid dated up to the end of this month (every weekly or daily instalment of the month), dated by the oldest one, split into `pending` (before today) and `upcoming`, with `count`. `totals().dueThisMonth` is the sum of the rows for money I took (so it includes what is pending; `pendingThisMonth` is that part).
- `suggestPayment()`: a bank loan, what is still to pay on the oldest unpaid instalment; hand and gold, one period's interest on what is owed now (pending interest is shown separately and the person decides); a no-interest loan, a part repayment of 0.
- `remindersFor(today, horizonDays 0..3)`: for every open loan on this phone, one overdue item (everything pending), one item for today, one per day within the horizon, and the repay-by date as a principal item; counted separately for money I took and money I gave (expected back). A reminder notification built on it names no loan, person or amount.

## Sync

Sync version 2 (Tally 0.3.0). Families made by 0.1.0/0.2.0 (one shared key inside the invite, one family token) are not carried over: see "Old families".

### Keys and epochs

- **The family keyring:** `(epoch, key)` pairs, each key 32 random bytes, epoch 1 made by the phone that creates the family. A new epoch is made by an admin's phone whenever someone is removed or leaves (see "Membership"). Phones keep every epoch they were given, so everything written before stays readable to current members; nothing is ever re-encrypted.
- **Why old records are not re-encrypted:** everything sealed under an old key was readable by whoever held that key, and a removed member keeps their copies anyway; re-sealing would cost every phone a full re-upload (and the free tier the bytes) and take nothing away from anyone. What matters is that nothing *new* is written under a key a removed member held, and the relay refuses exactly that (`p_epoch` must be the family's newest).
- `encKey(epoch)` = HKDF-SHA256(key of that epoch, no salt, info `"tally-enc"`, 32 bytes): AES-256-GCM for every record, file and invite box.
- Record AAD: `"tally:<familyId>:<recordId>"`. File AAD: `"tally-file:<familyId>:<fileId>"`. A blob cannot be replayed under another id or family.
- A sealed blob (version 2) is base64 (standard, padded) of `2 || epoch (4 bytes, big-endian) || iv (12) || ciphertext || GCM tag (16)`; the 5-byte header is fed to GCM ahead of the AAD, so neither the version nor the epoch can be changed undetected. Version 1 blobs (`1 || iv || ct`, one key, no epoch) are still readable by `SyncCrypto.open`, but nothing writes them and the v2 relay holds none.
- **Member identity:** for each family it joins, a phone makes an EC P-256 key pair (JDK/Android `KeyPairGenerator` + `KeyAgreement` ECDH; no new dependency) and two random 32-byte credentials: the **relay secret** (hex; the relay stores only its SHA-256) and the **notice token** (hex; can only ask "any join requests?"). All three live in `DB.family.me`.
- **Sealing to a member** (key boxes): ECIES. An ephemeral P-256 key pair, ECDH with the recipient's public key, HKDF-SHA256(Z, salt = ephemeral SPKI ‖ recipient SPKI, info `"tally-box-v1|" + context`), AES-256-GCM with the context as AAD. Blob: base64(`1 || ephemeral SPKI (91 bytes) || iv || ct`). Context: `"tally-box:<familyId>:<memberId>:<epoch>"`, so a box opens only for that member and epoch.
- **Where the private key lives, and why:** in the book (`DB.family.me.priv`, PKCS#8), next to the keyring. Android Keystore can do ECDH only from API 31 (minSdk is 29), and a Keystore key cannot go into a backup, while a restored phone must stay the same member (it needs the private key to open keys sealed to it after the backup was taken, and the secret to talk to the relay). The book is sealed with the app lock when one is set, so the private key is exactly as protected as the family keys it guards; without a lock both are plain on the phone (as the family key always was). A backup therefore holds the member's credentials and keyring (see "Backups").

### Invite

`tally://join#t2.<familyId>.<secret>.<expires>.<base64url(familyName)>[.<base64url(inviterName)>]` (base64url without padding), shown as a QR code and shareable as text. **It never carries a family key.**

- **Fresh each time:** every "Invite someone" makes a new invite: 32 random bytes `secret`, used once, valid 24 hours, cancellable. Any member can make one; only admins let anyone in.
- `expires`: epoch seconds, decimal; the phone that scans refuses an expired code before asking anything. The relay keeps its own 24 hours.
- From the secret: `inviteId` = base64url(HKDF(secret, `"tally-invite-id"`, 16 bytes)) (the only name the relay knows the invite by), `requestKey` = HKDF(secret, `"tally-join-req"`), `grantKey` = HKDF(secret, `"tally-grant"`). The secret itself never reaches the relay: the inviting phone registers the invite as `inviteId` plus an **invite box** (`{ s, by, exp }` sealed under the family's newest key, AAD `"tally-invite:<familyId>:<inviteId>"`), so the family's admins (members) can recover the secret and nobody else can.
- The family id is 16 random bytes, base64url (22 characters). Names: 1 to 80 characters, no control characters (a longer inviter name is shortened, a blank one left out). `Invite.parse` is strict (exactly the tag `t2`, canonical base64url, a 32-byte secret, a canonical decimal expiry, valid UTF-8 names); the whole text is at most 1024 characters. `Invite.find` picks the link out of a whole shared message.
- **Old invites** (`tally://join#<familyId>.<key>.<name>[.<inviter>]`, three or four parts, from 0.1.0/0.2.0) are recognised by `Invite.isOld` and refused with "This code is from an older Tally. Ask for a new one."
- One family name, typed by whoever starts the family; everyone who joins stores that same name, never the inviter's. An admin can rename it later ("The family's name" below).

### Joining (approval)

1. **Scan.** The joining phone parses the code, refuses it if expired, and asks "<Inviter> is inviting you to <family>. Join?" (both names from the code).
2. **Request.** It makes its identity (key pair, relay secret, notice token) and sends `tally_join_request(familyId, inviteId, memberId, sha256(secret), sha256(notice), blob)`, where `blob` = AES-GCM under `requestKey` of `{ name, pub }` with AAD `"tally-join:<familyId>:<inviteId>:<memberId>"`. The relay marks the invite used (one request per invite) and keeps the request for 72 hours. The phone keeps `DB.joining` (the family's name, the inviter, the invite id and secret, its new identity); nothing of the family is in the book yet.
3. **Notice.** Admins' phones see the request on their next sync (and the background check, "Notices", says that someone asked).
4. **Check (admin's phone).** From `tally_state` it gets the request with the invite's box. It refuses (and turns down at once, so nothing is shared) a request when: the invite box is not sealed under the family's current epoch, does not open, names another maker than the relay says, or its secret does not derive this invite id; the request blob does not open under that invite's `requestKey` for exactly this member id (so a swapped public key or member id fails); the member is already active; this phone has already decided that invite, or any member's record names it (a replay); two requests come for one invite. Otherwise it shows "<name> wants to join <family>. <inviter> invited them. Allow?" (the name from the sealed request, the inviter from the invite box).
5. **Allow.** The admin's phone seals a **grant** to the joiner's public key: every key the family has (epochs 1..now), the admin's pinned member keys, the creator's id, and a MAC under `grantKey` over all of it (bound to family, member, public key and invite id). `tally_request_decide(allow, epoch, box)` makes the joiner an active member with the credentials it sent, stores the grant as its box for the current epoch, and deletes the request and the invite. The admin's phone also writes the new member's record (name, public key, invite id) so every phone pins the key the admin checked. **Don't allow**: `tally_request_decide(allow = false)` keeps the request as `denied` until it expires; nothing is shared.
6. **In.** The joining phone polls `tally_join_status` on each sync (`pending` / `denied` / `approved` / `removed`, or gone). On `approved` it fetches its boxes, opens the grant with its private key and checks the MAC with the invite's `grantKey`; a grant that does not check out (a relay that sealed keys of its own to the joiner's public key) is refused and the phone keeps waiting. Then it enters the family as on create (every record marked dirty, every live file queued) and syncs. On `denied` or a request that expired it forgets the request and says so once (`DB.familyGone`).

### The family's name

An admin can rename the family (`SyncEngine.renameFamily(name)`; the same name rules as creating one). The name is a small **meta** blob on the relay, `tally_family.meta`: `{ name, v, at, by }` sealed by the admin's phone with `SyncCrypto.sealE` under the family's newest key, AAD `"tally-meta:<familyId>"`, so the relay never sees the name. It is stored only through `tally_family_meta_set`, which takes the family lock and refuses anyone who is not an admin (`TA006`) and any epoch but the newest (`TA002`, retried after a refresh). A record-level value was not used because any member can push records and the relay cannot read their kind, so "admins only" could not be enforced there.

`tally_state` hands the blob to every active member; each phone opens it with its keyring and takes the name when `v` (one more than the version the renaming phone had seen) is not older than its own `DB.family.nameV`, so an older name replayed by the relay is ignored and an unreadable blob changes nothing. A phone let in after a rename has every key in its grant, so the first state it reads gives it the new name, whatever its invite said; invites made after a rename carry the new name (`newInvite` uses `DB.family.name`). A rename after a removal is sealed under the new key, which the removed member never had. Covered by `SyncRenameTest` and the live check.

### Membership: admins, removing, leaving

The rules (enforced by the relay, `supabase/migrations/*_tally_membership.sql`, and mirrored by `FakeRelay` in the tests):

- Whoever creates the family is its **creator** and first **admin**.
- An admin can make any active member an admin, and take the role from any admin, except: never from the last admin, and never from the creator (only the creator can step down). (The owner's rule "nobody can remove the creator" is extended to the creator's admin role, so the creator cannot be quietly sidelined either.)
- An admin can remove any active member, another admin included, except the creator and themselves (they leave instead). Nobody can remove the creator; the creator can leave.
- Any member can leave on their own. If the last admin leaves, the longest-standing remaining member (earliest joined, then id) becomes admin. When the creator leaves the family has no creator any more (nobody is protected). When the last member leaves, the family and everything it holds on the relay is deleted.
- Any member can make an invite and cancel their own; an admin can cancel any. Only admins see and decide join requests.

**Removing** (`tally_member_remove`), in one relay transaction: the member's status becomes `removed`, so every call with their credentials fails from that commit on (`TA001`); the family moves to epoch `n+1`; the admin's phone has made the new key and sealed a **rotation box** to each remaining active member whose public key it has pinned (its own included): `{ keys from has+1 .. n+1, MAC under HKDF(key of epoch has, "tally-box-auth") }`, where `has` is the newest epoch that member was given. A box for the removed member, or for anyone not active, is refused. Every open invite and request is deleted. On the removed phone the next sync (any call) hears `removed`: it forgets the family (like leaving), keeps every record, and says once "You were removed from the family. Your loans stay on this phone." **What the removed person already has stays on their phone: the records, files and old keys they synced before. Nothing can claw that back.** What they can no longer do is read anything new (the relay will not serve them, and new data is under a key they never had) or write anything.

**Leaving** (`tally_leave`): the status becomes `left`, their invites go, the successor rule above applies, and the family is marked `rotate`: the next sync of an admin's phone makes a new epoch the same way (`tally_rotate`). Without internet the phone leaves locally at once and tells the relay on a later sync (`DB.leaving`).

**A phone that missed a rotation** finds its boxes in `tally_state` on the next sync and opens them in order (each proved by a key it already holds). A member the rotating phone could not seal to (no pinned key yet, or let in while the rotation was being made) has `has` < the family's epoch; the next sync of any admin's phone that has its pinned key gives it a box (`tally_box_put`). Until then that phone reads but pushes nothing (`SyncResult.Done(waitingForKey = true)`).

**Pins:** a member's public key is pinned the first time a phone learns it (its own; the admin's check at approval; a grant's pins; a member record's `pub`), and a pinned key is never replaced by a later record (only an admin approving that member id again replaces it). Rotation boxes are sealed only to pinned keys, never to anything the relay says.

**Concurrency:** every relay function that changes a family takes that family's advisory lock before checking the caller, so two admins acting at once are put in one order and see each other's result. Approvals, rotations, removals and pushes carry the epoch they were made for; a stale one fails with `TA002` and the phone refreshes and retries (up to three times). Two admins approving one request: the second gets "gone". One removing while another rotates: the second's epoch is stale; a rotation that lost to another one is simply dropped (the other one already rotated).

### Threat model

The relay is trusted for availability only, never for confidentiality or for who is who.

- **A hostile relay (or anyone who reads its database)** sees: family ids, member ids, roles, statuses, epochs, invite ids, record ids and kinds, sizes, timing, hashes of credentials, and ciphertext. It **cannot**: read any record, file, name, request or key (all sealed under keys it never has); forge a record (AES-GCM, AAD bound to family and id); swap a joiner's public key or member id in a request (the request is sealed under a key derived from the invite's secret, which it never sees); impersonate an admin to a joiner (the grant's MAC needs the invite's secret); hand a member a key of its own (a rotation box's MAC needs the member's previous key, a grant's the invite's secret); make an admin seal a new key to it (rotation boxes go only to pinned keys); get a replayed invite through (one request per invite; the admin's phone refuses invites it has decided and any second request). It **can**: refuse service, drop or delay anything, hide a rotation or a request, show stale state, and learn the metadata above. A phone that is shown a newer epoch it cannot verify simply waits (nothing is written under a key it cannot check).
- **A removed member** (with an honest relay): rejected at once for every call; cannot read anything written after the removal (new epoch), cannot write. Keeps what was already on their phone.
- **A removed member colluding with the relay operator** (out of scope, stated plainly): the relay could hide the rotation from a phone, which would then keep writing under the old key the removed member holds; or hide the removal record from another admin, which might later seal keys to them. Detecting a hiding relay would need signed membership history; not in this version.
- **A leaked QR / invite text** (someone photographs the code): they can send one join request, within 24 hours, before the real joiner does. An admin must still allow it, and sees the name they typed and who made the invite; the real joiner's request then fails ("does not work, ask for a new one"), which is the signal. Cancelling the invite stops it. A leaked code never gives anyone the family's data by itself.
- **A stolen unlocked phone or a plain backup** holds the member's credentials and keys: it is that member. An admin removing that member ends it for everything new.
- **A current member** can read everything they are given and write what they like (as in version 1); trust within the family is the point.

### The relay (Supabase)

`supabase/migrations/20261112013706_tally_membership.sql` (and `20261112020514_tally_not_admin_code.sql`, `20261112060000_tally_family_meta.sql`). Row level security is on for every table with no policies and no grants to `anon`/`authenticated`, so the Data API reaches nothing directly. All access is through `security definer` functions in `public` with `search_path = ''`; helpers live in the unexposed `tally_private` schema.

Tables: `tally_family` (id, creator, epoch, rotate, record_count, blob_bytes, meta), `tally_member` (family, id, secret_hash, notice_hash, role, status, has_epoch, joined_at), `tally_invite`, `tally_request`, `tally_box`, `tally_record`, `tally_blob`. The version-1 tables (`tally_families`, `tally_records`, `tally_blobs`, sequence `tally_seq`) are left in place with their rows, unreachable; a later migration can drop them.

Functions (every `p_secret` is checked by hashing it and comparing with the member's `secret_hash`; an unknown family, unknown member, wrong secret or a member who left all give the same `28000`; a removed member gets `TA001`):

| Function | Who | What |
|---|---|---|
| `tally_family_create(p_family, p_member, p_secret_hash, p_notice_hash)` | anyone | new family, caller = creator + admin, epoch 1; idempotent for the same creator |
| `tally_state(p_family, p_member, p_secret, p_after)` | member | epoch, creator, rotate, the sealed family name (`meta`, null until renamed), my role, active members (role, since, has), my boxes after `p_after`, live invites, open requests (admins only) |
| `tally_family_meta_set(…, p_epoch, p_meta)` | admin | stores the sealed family name (at most 4096 characters); `p_epoch` must be the newest |
| `tally_push(…, p_epoch, p_rows)` / `tally_pull(…, p_since, p_max)` | member | records, as in version 1; push refused unless `p_epoch` is the family's newest |
| `tally_blob_put(…, p_epoch, p_id, p_data)` / `tally_blob_get(…, p_id)` | member | file blobs, same epoch rule |
| `tally_invite_create(…, p_invite, p_box)` → expiry ms | member | one-time invite, 24 h |
| `tally_invite_cancel(…, p_invite)` | its maker, or an admin | |
| `tally_join_request(p_family, p_invite, p_member, p_secret_hash, p_notice_hash, p_blob)` → expiry ms | anyone with the invite id | uses the invite up; request kept 72 h |
| `tally_join_status(p_family, p_invite, p_member, p_secret)` | the joiner | approved / pending / denied / removed, `TA005` when gone |
| `tally_join_cancel(…)` | the joiner | withdraws the request |
| `tally_request_decide(…, p_invite, p_allow, p_epoch, p_box)` | admin | allow (member + grant box) or deny |
| `tally_member_role(…, p_target, p_admin)` | admin | the role rules above |
| `tally_member_remove(…, p_target, p_epoch, p_boxes)` | admin | remove + rotate, one transaction |
| `tally_rotate(…, p_epoch, p_boxes)` | admin | new epoch |
| `tally_box_put(…, p_epoch, p_boxes)` | admin | boxes for members who lack the newest |
| `tally_leave(p_family, p_member, p_secret)` → 'left' / 'deleted' | member | |
| `tally_pending(p_family, p_member, p_notice)` → count | the notice token | 0 unless an active admin |
| `tally_ping()` → 1 | anyone | keep-awake; also deletes expired requests and invites |

Error codes: `28000` unauthorised, `TA001` removed, `TA006` not an admin, `TA002` stale (sync and retry), `TA003` a limit, `TA004` a rule refuses, `TA005` invite or request not usable, `22023`/`54000` bad or too large input. (Not-an-admin was `42501` in the first migration; PostgREST sends that as HTTP 401, whose body `HttpURLConnection` drops for a fixed-length POST, so it became `TA006`.)

Ordering is as in version 1: every upsert takes the next value of `tally_record_seq` under the family lock, so seqs become visible in order within a family and paging by `seq > cursor` never skips a row.

The phone talks to PostgREST with `HttpURLConnection` (no SDK). The project URL and anon key come from Gradle properties `TALLY_SUPABASE_URL` / `TALLY_SUPABASE_ANON_KEY` into `BuildConfig`.

### Limits

Chosen for a family app on Supabase's free tier (500 MB database), so that a stranger cannot fill it:

| Limit | Value |
|---|---|
| active members per family | 20 |
| live (unused, unexpired) invites per family | 5 |
| open join requests per family | 5 |
| records per family | 20 000 |
| file blobs per family | 100 MiB in total (a file blob at most 6 MiB) |
| new families, across the whole relay | 20 per hour |
| invite lifetime / request lifetime | 24 hours / 72 hours |
| rows per push / per pull | 500 / 500 |
| record blob | 64 KiB |

A join request needs a live invite id (derived from a secret only the QR has), so strangers cannot send requests at all. Expired invites and requests are deleted by the next invite or request in that family and by the daily `tally_ping`. A family whose last member leaves is deleted with everything in it.

### One refresh

0. A leave the relay has not heard of is sent. A waiting join request is checked (`tally_join_status`); once allowed, the grant is checked and the family entered.
1. `tally_state`: new key boxes are opened (each proved with a key already held) and the keyring grows; the roster, my role and the creator are cached in `DB.family` for the screens, and a newer family name is taken. Removed → forget the family, keep the records, say so once.
2. **Pull** everything with `seq > sync.cursor`; each row is opened with the key of the epoch in its header, then merged exactly as in version 1 (not dirty → take; dirty → last writer by `updatedAt`, then `updatedBy`). Member public keys in pulled records are pinned. The cursor moves in the same save as the page it covers.
3. **An admin's phone:** rotates if the family is marked `rotate`, gives boxes to members lacking the newest key, and checks open join requests (`SyncEngine.requests`); requests that do not check out are turned down.
4. **Push** every dirty record that is not private, sealed under the newest epoch, with that epoch (a phone that lacks the newest key pushes nothing).
5. **Files:** upload the bytes of each id in `sync.blobsUp` (same epoch rule); download bytes for any live file that has none locally.

On the wire a record is the sealed envelope `{ "k": "loan"|"payment"|"file"|"member", "r": { ...the record... } }`, as in version 1, and the merge, privacy and restore rules below are unchanged. A member record now also carries `pub` (that member's public key in this family), `invite` (the invite it came in with) and, once removed or gone, `left: true`.

Refinements in `sync/`:
- A dirty mark is cleared after a successful push only if the record has not changed since it was collected (an edit during the push stays dirty).
- A record that is private on this phone is never replaced by a pulled row. If someone else's edit of it arrives, it is ignored and the record is marked dirty again, so this phone's tombstone goes back up and wins.
- Pushing a private record: if its id is in `sync.pushed` the push is a bare tombstone `{ id, deleted: true, updatedAt, updatedBy }`, otherwise nothing is sent. A payment or file whose owner cannot be found counts as private when pushing (fail closed).
- Creating or entering a family marks every record dirty and every live file for upload, so a phone that already had loans shares them. Leaving removes `family` and `sync` and keeps every record.

Making a shared loan private pushes tombstones for the loan, its payments and its files, and keeps the local copies. Making a private loan shared marks them all dirty.

Sync runs when asked (pull to refresh, the Sync button), once each time the book becomes ready (app open or after unlock) if a family exists or a join is waiting, and after every membership action. A sync started from a screen runs in the app's own scope (`Sync.detached`), so closing the screen does not stop it halfway.

A file's bytes are fetched on first open (`SyncEngine.fetchFile`). A private loan's files stay in `sync.blobsUp` and are never uploaded; a tombstoned file is dropped from the queue and never asked for.

### Notices

No push service and no Firebase. Join requests reach an admin as:
- `SyncEngine.requests` (a `StateFlow<List<JoinRequest>>`), refreshed by every sync, which runs when the app opens or is refreshed: the home card is built from it.
- A quiet background check (`sync/Notices.kt`): a WorkManager periodic job every 4 hours, network required, scheduled only while this phone is an admin. It calls `tally_pending` with the **notice token** (kept in `DevicePrefs` as `tally-notice`, because the worker runs while the book may be sealed; the book keeps its own copy so a restore brings it back on the first sync) and, when more requests wait than the app last saw, posts one notification on the channel "Family": "Someone wants to join your family" / "Open Tally to answer." It names nobody and shows nothing from the book, so it is safe on a locked phone. Tapping it opens the app on the Family page (`MainActivity.EXTRA_OPEN`, taken after unlock). On Android 13+ it needs `POST_NOTIFICATIONS`, asked through `ui/family/rememberNoticePermission()` at a moment the screens choose.

While the app is open (`ui/family/FastJoin.kt`, in the unlocked shell, only while the lifecycle is STARTED): an admin's phone calls `SyncEngine.refreshRequests()` every 5 seconds, and a phone waiting to join calls `checkJoin()`. `refreshRequests()` is the cheap path: one `tally_pending` count with the notice token, and only when that count moved it reads the family's state and re-checks the requests (no pull, no push, no "Syncing" state, never waits for a running sync, silent on failure). `PollPacer` slows to every 30 seconds after 2 minutes with no new request and no touch (`MainActivity.dispatchTouchEvent` calls `FastPoll.touch()`), and speeds up again on either; one call at a time. The background job above is unchanged.

### Payment reminders

`data/DueNotices.kt`: a daily WorkManager job around 9:00 device time posts at most one quiet notification, on its own channel ("Payment reminders", low importance): "Payments are overdue", "A payment is due today" (money I took) or "Money is expected today" (money I gave), with "Open Tally to see." It names no person, loan or amount, so it is the same on a locked phone and while amounts are hidden; tapping it opens the home screen. It is never posted while the app is on screen. Because the job runs while the book may be sealed by the app lock, it reads only a device copy of the **dates** on which something is due or expected (`DevicePrefs` `tally-due-cache`: from `dueDatesAhead(today, 60)` in `model/Reminders.kt`, which walks each open loan's own schedule; dates plus a taken/given flag, no names, ids or amounts; never in a backup; everything already overdue is kept as its single oldest date per direction, so the cache is at most about 60 dates each way), kept in step with the book a moment after each change (`DueCacheKeeper` in the shell). So it covers every due date in the 60 days after the last refresh; a date that has passed counts as overdue until the next refresh after it was paid (opening the app, or any change to the book). The setting "Payment reminders" (Family page, on unless switched off; `tally-due-notices`) is the only switch; on Android 13+ nothing shows until notifications are allowed, asked with the same plain question as the join notice, once, right after the first loan is saved, or when the switch is turned on.

### Old families

A book whose `DB.family` has no `v: 2` (it has `key`) was made by 0.1.0/0.2.0. Its relay functions are gone, so a sync answers `SyncResult.OldFamily` ("Sharing needs setting up again…"); `leaveFamily()` forgets it (records stay), and starting or joining a family again re-pushes every record under the new one.

### Files

- Stored locally as in Balance (`data/Files.kt`: byte-for-byte, SHA-256 checked).
- Photos taken or picked for a payment are scaled to at most 1600 px and saved as JPEG before they are stored, so the stored bytes are what syncs.
- Documents keep their original bytes. Limit: 4 MB per file.

## Screens

Follows Balance's `android/UI.md` kit, simplified, plus the apple-design rules (press feedback on touch-down, critically damped springs, 1:1 pull to refresh with rubber-banding, sheets that leave the way they came).

- **Welcome:** "What is your name?" → the book. Below it, a quiet "I have a backup" link: pick the file → its PIN if it is locked → one question → the book is back.
- **Loans (home):** total still owed, then "Due this month" (each row has a single Pay button), then the loans grouped by kind. One add button. Pull down to sync; a small line says when it last synced.
- **Loan:** what is owed, a warning row when something is pending ("Interest pending: ₹3,600 (3 months)" / "Instalments pending: ₹17,770 (2)"; a tap opens Log payment with that amount), next due date and amount, details (rate in words with its method, how often and on which day or weekday, payments left and the last date, what is pledged and grams, "Paid up to", renewals), the rate changes of a bank loan (each deletable, asks), payments (newest first, each with who logged it), documents, then the actions: **Rate changed** (a bank loan on reducing interest: new rate from a date, keep the same EMI for more months or give a new EMI), **Renewed** (a hand or gold loan with a repay-by date: the renewal date and the new date, a year on for gold), Edit, Close loan.
- **Add / edit loan (sheet):** kind first as big choices (Bank loan, Money I took from a person, Loan against something with "Gold, LIC policy, FD, land papers", Money I gave); then only that kind's fields. A loan against something asks what is pledged first (grams only for gold; a gold loan has "Repay or renew by" among the main fields with a one-line hint). Wherever a rate is entered, **Reducing / Flat** sits under it with one line that changes with the choice (hidden for No interest). A bank loan asks "How often do you pay?" (monthly, every two weeks, every week, every day): the number-of-payments label follows, the day of the month appears only for monthly, and weekly or fortnightly loans show "Every Tuesday" quietly. For a new loan whose start date is in the past, **Paid up to** is offered, pre-filled with the latest date already due and clearable; when shown, a hand or gold loan's amount says "enter what is still owed". Editing a loan never invents Paid up to. "Keep private" toggle, off by default.
- **Log payment (sheet):** amount (pre-filled with the EMI or interest due), date (today), kind, then optional: note, paste bank message, add photo, add document.
- **Family:** `ui/family/`. One of these at the top, by state (`FamilyScreen`): nothing yet (a short explanation, "Start sharing" which asks "Give your family a name", and "Join my family"); **waiting** ("Waiting for an admin to let you in", who invited you, "Check now" and "Cancel request"); **told once** that you were removed ("You were removed from <family>. Your loans are still on this phone."), not let in, or that the request ran out, with OK (`clearGone()`) and then the nothing-yet state; **an old family** (0.1.0/0.2.0: "Sharing needs to be set up again after this update. Your loans stay on this phone.", "Set up sharing" and "Join my family", both of which forget the dead family first); or **in a family**. In a family: the **members** (name, "You", an "Admin" label, "Created the family" under the creator, "joined <date>"). An admin taps a member (not themselves, not the creator) for a small sheet: "Make admin" or "Remove as admin", and "Remove from family" (asks: "<name> will stop seeing new loans and payments. What is already on their phone stays there."); a member who is not an admin sees the list with no actions (`MemberText.actions`). **Invite someone** (any member): a sheet with a fresh QR from `newInvite()`, "Valid for 24 hours, for one person", Share, the warning "Anyone with this code can ask to join. An admin must allow them.", and below "Open invites" (the maker's own, or all for an admin) with Cancel invite per row; the first invite an admin makes also asks, once ever, whether to be told when someone asks to join (a plain question first; Android 13+ then shows its own permission question; a no is final). **Sync now** with the last-synced time, and **Leave family** (asks; as the only admin it names who becomes admin; as the last member it says the family will be deleted). Admins also have **Rename family** (a sheet with the current name; `renameFamily`, refused for members). Below, always: My name, Language (English, Telugu, Hindi), App lock (turn on, change, turn off, through `LockFlow`), Backup (below), Hide amounts, Payment reminders (a switch), and the version. For an admin, **join request cards** ("<name> wants to join <family>. <inviter> invited them." with Allow and Don't allow, which asks once) stack at the very top, also on the home screen (`JoinRequestCards`, hooked in as `joinRequestsCard`), whatever the state of the loans; Allow answers with a short snack.
- **Join (`family/join`):** "Scan the code" (a full-screen CameraX scanner, `family/scan`, ZXing decoding on a background thread; the camera permission is asked there, with "Paste the code instead" if it is refused) or a Paste button and field (the whole shared message may be pasted: `Invite.find` picks the link out of it). A valid invite always goes through "<Inviter> is inviting you to <family name>. Join?" (or "Join <family name>?" when the invite does not say who sent it; both names come from the invite) before anything is saved; Join sends the request and lands on the Family page, which now says "Waiting for an admin to let you in"; the phone enters the family on a later sync once an admin allows it. A code from 0.1.0/0.2.0 says "This code is from an older Tally. Ask for a new one." An invite link that opens the app (`pendingInvite`) is taken after unlock and the welcome name, with the same question; if the phone is already in a family it says so and does nothing.
- **Home:** pull down runs one sync and speaks only if it failed (one plain sentence) or if there is no family yet (offering "Set up"). The quiet line under the figure reads "Shared with family · synced 5 min ago", "Syncing…", "Not shared yet" (tap: Family), "Waiting for an admin to let you in" (tap: check), "Sharing needs to be set up again after this update. Your loans stay on this phone." (tap: Family) or "Couldn't sync · tap to retry". The join request cards sit above the figure. "Expected this month" (money I gave) is hidden when nothing is expected; "Due this month" always shows. A due row's amount includes what is pending, with a small line under it: "includes ₹3,600 pending", and "4 payments" for a weekly, fortnightly or daily loan with several this month.
- **Home groups:** after the figure and the due lists: Bank, From a person, Loan against something, Money I gave, then **Only on this phone**, then Closed. Open private loans (taken and given) appear only in "Only on this phone", whose header shows what is still to pay and to come back on them, with the line "Your family doesn't see these."; the top totals and the due lists still include them. Closed private loans stay in Closed.

## Backups

`data/BackupCore.kt` (plain JVM: make, check, put in place) and `data/Backups.kt` (the folder, WorkManager, the share sheet, the app's locks); on screen `ui/family/BackupFlow.kt`.

### The file

`Tally-YYYY-MM-DD.tally-backup`, a zip whose entries are all stored (not compressed):

- `book.json`: the book exactly as stored on disk: plain JSON, or with a lock the sealed envelope `{ enc, s, i, d, t, l }`.
- `files/<id>.bin` and `files/<id>.json`: every stored file and its record exactly as on disk (the original bytes, or with a lock AES-GCM under `DB.filesKey`, which is inside the sealed book). So a backup taken with a lock opens on a new phone with the PIN alone.
- `manifest.json`, written last: `{ format: "tally-backup", version: 1, app, createdAt, date, sealed, counts?, skipped?, entries: [ { name, size, sha256 } ] }`. `counts` (`loans`, `payments`, `files`, tombstones not counted) only for a plain book.

A backup copies what is on disk, so it needs no secret and runs while the app is locked or not running. It is taken under `FileStore.filesExclusive` (no lock pass or downloaded file meanwhile), each record read under `FileStore.io` and the book under the ledger's disk lock, so it never copies a half-written file (AtomicFile on Android 10 rewrites in place behind a `.bak`; a `.bak` left by a crash is read as the good copy). An open book is saved first.

Not in a backup: anything device-only (language, the backup folder and its state, fingerprint unlock, the re-lock delay copy in `DevicePrefs`, the notice token copy for the background check, WorkManager's own records). Everything in the book is: loans, payments, private loans, files, `me`, `family` (its keyring, this member's relay secret, notice token and private key), a waiting `joining`, and `sync`.

Without a lock the file is plain and contains the family's keys and this member's credentials: whoever has it can read the family's loans and act as this member until an admin removes them. The Backup group's footer and the share question say so in one line ("anyone who gets this file can read your loans and join your family").

### Daily, and Back up now

- "Back up every day" asks for a folder once (`ACTION_OPEN_DOCUMENT_TREE`, a persisted permission kept in `DevicePrefs`; no storage permission, no account, no network). A WorkManager periodic job runs once a day, and on every app open a one-off job runs if the last backup that worked is more than 24 hours old (battery savers delay workers). At most one a day: a second backup the same day replaces that day's file. The file is written as `….tally-backup.partial` and renamed when complete; then the folder keeps the newest 14 files with Tally's own name and touches nothing else.
- A folder that is gone or no longer allowed sets "stopped": the Backup group and a quiet line on the home screen say "Backup stopped: choose the folder again". Another failure shows "It did not work last time" and tries again next time.
- "Back up now" saves into the folder (when one is chosen), makes the file in the app's cache and, after one question, opens the share sheet (Drive, WhatsApp, email). The cache copy is deleted at the next start.

### Restore

From the Family page ("Restore from a backup") or the welcome screen ("I have a backup"):

1. The picked file is copied into the cache (at most 2 GB) and checked completely before anything changes: a zip; only the names above (no folders, paths, traversal, duplicates); manifest format and version 1 (a newer version says "update Tally first"); every entry listed with the right size and SHA-256 and every listed entry present; caps (manifest 8 MB, book 64 MB, a file 16 MB, 20 000 entries); a book that is a Tally book or an envelope, as the manifest says.
2. Sealed: the PIN is asked (the lock screen's "sealed file" stage) and checked against the backup's envelope without touching the current lock.
3. One question (`Dialogs.ask(…, danger = true)`) with the backup's date, its loans and what is replaced, and the one limit: shared loans and payments the family deleted since stay deleted. A restore is not an undo for the family.
4. With the sync engine paused, the files lane held and saving frozen: the current book and files are written as the one safety copy (`filesDir/restore/before-restore.tally-backup`, replacing the last); the backup is unpacked into staging (hashes checked again); a journal marks it complete; the files folder and the book are swapped in. A failure before the swap changes nothing; a process that dies during the swap finishes it at the next start (`Backups.init`, before anything is read). The book is started fresh (`Ledger.start`) with the lock set as below, still inside the lane.
5. "Undo the last restore" puts the safety copy back the same way (its PIN if it was locked) and then deletes it.

The lock after a restore:

| Backup | Phone before | After |
|---|---|---|
| locked | no lock | locked with the backup's PIN |
| locked | locked | locked with the backup's PIN (fingerprint unlock is turned off; the app says so) |
| plain | no lock | no lock |
| plain | locked | stays locked with the phone's own PIN: the book is sealed with it as it is written, and the lock pass seals the files. Restoring must never silently remove a lock the person set. |
| undo | any | exactly as before the restore (locked with that PIN, or unlocked) |

### Restore and sync

The restored book keeps its `sync` (cursor, dirty marks, `pushed`, `blobsUp`), so the next sync pulls everything the family wrote since the backup's cursor and the normal rules apply: records the phone had not changed take the family's copy; edits still dirty in the backup meet the family's by last writer; deletions stay; a file whose bytes are not here is fetched as usual.

One case needs more: a loan that was private in the backup but shared since (by this phone, before the restore). The normal rule would answer the family's newer copy with a tombstone and delete the loan on every phone. So a restore sets `sync.restored`, and until the next complete sync a pulled row that is newer than a private copy here (loan, payment or file) is taken. Covered by `SyncRestoreTest`. Limits: a loan made private after the backup comes back as the family's tombstone (it was deleted for them); the same backup restored on two phones at once makes them one member (one set of credentials, one key pair).

Membership after a restore: the restored phone is the same member (same member id, relay secret and key pair), so it keeps working; keys made after the backup are in its boxes on the relay and open on the first sync (`SyncMembershipTest.aRestoredPhoneIsStillTheSameMember`). If the member was removed since, the first sync says so and forgets the family. A backup taken while a join request was waiting restores that request.

## Not in this version

- Detecting a relay that hides membership changes (signed membership history).
- Background sync (only the join-request check runs in the background).
- Screens for membership (the next step, built on `SyncEngine`'s API): the home card for join requests, Allow / Don't allow, member rows with "Make admin", "Remove", the waiting-to-join state, the open invites list.
