package com.tally.book.sync

import com.tally.book.core.*

/*
 * The sync rules as pure functions over the book's JSON tree (no Android, no network, no DB global), so they can be
 * unit-tested and run against any phone's book. docs/DESIGN.md, "Sync" > "One refresh".
 *
 * Wire shape: a record travels as the sealed JSON envelope { "k": "<wire kind>", "r": { ...the record... } }. The wire
 * kind is one of loan | payment | file | member and says which local list the record lives in (DB.loans, payments,
 * files, members). It is kept outside the record because loans and payments use their own "kind" field for something
 * else (bank/hand/gold, emi/interest/principal).
 *
 * Book fields this file owns: DB.sync = { cursor, lastAt, dirty: {id: true}, blobsUp: {id: true}, pushed: {id: true}, restored? }.
 *   pushed: ids the relay is known to hold (pushed by this phone, or pulled from it). A private record whose id is in
 *   pushed is sent as a bare tombstone so the copy on the relay is overwritten; one that is not is never sent at all.
 *
 * Privacy (CLAUDE.md "Sync"): collectPush never emits a private record's content. A record is private when it is a
 * private loan, or a payment or file whose loan is private; an owner that cannot be found counts as private (fail
 * closed) when deciding what to push. A record that is private on this phone is never replaced by a pulled row: if someone else's edit of it
 * arrives, it is ignored and the record is marked dirty, so this phone's tombstone goes back up and wins on the relay.
 *
 * After a restore (sync.restored, set by markRestored, cleared at the end of the next complete sync) the book is older than
 * what this phone already told the family: a loan that was private in the backup may have been shared since (by this very
 * phone, before the restore). Then a pulled row that is newer than the private copy here is the family's current state and
 * is taken, instead of being answered with a tombstone that would delete the family's newer loan. Rows that are not newer
 * are treated as always.
 */
object SyncModel {
    /** Wire kind -> the DB list that holds it. */
    val LISTS: Map<String, String> = linkedMapOf("loan" to "loans", "member" to "members", "payment" to "payments", "file" to "files")

    /**
     * Fields that describe this phone's copy only and never travel inside a record: files' "enc" (whether the local bytes are
     * encrypted), and on every kind "sig", the signature the record arrived with (sent again beside it when this phone pushes a
     * copy someone else wrote, so the others can still tell who wrote it).
     */
    private val LOCAL_ONLY: Map<String, Set<String>> = mapOf("file" to setOf("enc"))
    private const val SIG = "sig"

    /** How far in the future a pulled updatedAt may be before it is pulled back to now plus this (docs/DESIGN.md "Merge rules"). */
    const val SKEW_MS = 10 * 60 * 1000L

    /**
     * The updatedAt for a write now: never earlier than the version it replaces (so an edit made on a phone whose clock is behind
     * still wins over what it edited), never more than [SKEW_MS] ahead of this phone's clock.
     */
    fun stamp(prev: Double, now: Long): Double = maxOf(now.toDouble(), minOf(prev + 1, (now + SKEW_MS + 1).toDouble()))

    fun syncOf(db: Obj): Obj = db.oo("sync")
    fun dirty(db: Obj): Obj = syncOf(db).oo("dirty")
    fun pushed(db: Obj): Obj = syncOf(db).oo("pushed")
    fun blobsUp(db: Obj): Obj = syncOf(db).oo("blobsUp")
    fun cursor(db: Obj): Long = db.o("sync").num("cursor").toLong()

    /** Marks a book that was just restored from a backup (only one in a family has anything to catch up with). */
    fun markRestored(db: Obj) { if (db.o("family") != null) syncOf(db)["restored"] = true }
    fun restored(db: Obj): Boolean = db.o("sync").b("restored")

    class Found(val kind: String, val list: Arr, val index: Int, val rec: Obj)

    /** The record with this id in any of the four lists. */
    fun findRecord(db: Obj, id: String): Found? {
        for ((kind, key) in LISTS) {
            val list = db.a(key) ?: continue
            for (i in list.indices) {
                val r = list[i].asObj() ?: continue
                if (r["id"] == id) return Found(kind, list, i, r)
            }
        }
        return null
    }

    private fun inList(db: Obj, key: String, id: String): Obj? = db.a(key)?.firstOrNull { it.asObj()?.get("id") == id }.asObj()

    private fun loanPrivate(db: Obj, loanId: String, unknown: Boolean): Boolean = inList(db, "loans", loanId)?.b("private") ?: unknown

    /**
     * Private exactly when its loan is private (resolved through payment -> loan, file -> owner). [unknown] is the
     * answer when the owner cannot be resolved: true (fail closed) for deciding what to push; the merge passes false,
     * because a record whose owner arrived as a bare tombstone is not a private one to protect.
     */
    fun isPrivate(db: Obj, kind: String, rec: Obj, unknown: Boolean = true): Boolean = when (kind) {
        "loan" -> rec.b("private")
        "payment" -> loanPrivate(db, rec.s("loanId"), unknown)
        "file" -> when (rec.s("ownerType")) {
            "loan" -> loanPrivate(db, rec.s("ownerId"), unknown)
            "payment" -> inList(db, "payments", rec.s("ownerId"))?.let { loanPrivate(db, it.s("loanId"), unknown) } ?: unknown
            else -> unknown
        }
        "member" -> false
        else -> unknown
    }

    /** The record as it travels: a deep copy without this phone's local-only fields. */
    fun wireRecord(kind: String, rec: Obj): Obj {
        val c = rec.copyObj()
        LOCAL_ONLY[kind]?.forEach { c.remove(it) }
        c.remove(SIG)
        return c
    }

    fun tombstone(rec: Obj): Obj = obj("id" to rec["id"], "deleted" to true, "updatedAt" to rec["updatedAt"], "updatedBy" to rec["updatedBy"])

    fun envelope(kind: String, rec: Obj): Obj = obj("k" to kind, "r" to rec)

    /**
     * What a dirty id needs: [json] is the envelope to send, or null for "nothing to send" (a private record the relay
     * never had, or an id no longer in the book). [kind] is the wire kind ("" when the id is gone).
     */
    class PushItem(val id: String, val kind: String, val envelope: Obj?, val json: String?, val storedSig: Obj? = null, val writer: String = "") {
        val send: Boolean get() = json != null
    }

    /**
     * What to send for [id]. [storedSig] is the signature the record arrived with, when this phone sends someone else's copy
     * unchanged ([writer] is its updatedBy); the engine signs everything else itself.
     */
    fun planFor(db: Obj, id: String): PushItem {
        val f = findRecord(db, id) ?: return PushItem(id, "", null, null)
        if (isHeld(db, f.kind, f.rec)) return PushItem(id, "", null, null)
        val full = !isPrivate(db, f.kind, f.rec)
        val body = when {
            full -> wireRecord(f.kind, f.rec)
            pushed(db).b(id) -> tombstone(f.rec)
            else -> return PushItem(id, f.kind, null, null)
        }
        val env = envelope(f.kind, body)
        return PushItem(id, f.kind, env, JsonCodec.stringify(env), if (full) f.rec.o(SIG) else null, f.rec.s("updatedBy"))
    }

    /** Every dirty id and what to do with it, loans first (then members, payments, files). Held entries wait (they stay dirty). */
    fun collectPush(db: Obj): List<PushItem> {
        val ids = db.o("sync").o("dirty")?.filter { truthy(it.value) }?.keys?.toList() ?: return emptyList()
        val order = LISTS.keys.toList()
        val held = ids.filter { id -> findRecord(db, id)?.let { isHeld(db, it.kind, it.rec) } == true }.toSet()
        return ids.filter { it !in held }.map { planFor(db, it) }.sortedBy { if (it.kind.isEmpty()) order.size else order.indexOf(it.kind) }
    }

    // ---------- entries held back after joining (docs/DESIGN.md "Joining", step 6) ----------

    /** Ids that were on this phone when it joined a family and wait for the person's answer before anything of them is shared. */
    fun held(db: Obj): Obj? = db.o("sync")?.o("held")

    /** Held: the record, or the loan it belongs to, is in [held]. */
    fun isHeld(db: Obj, kind: String, rec: Obj): Boolean {
        val h = held(db) ?: return false
        if (h.isEmpty()) return false
        if (h.b(rec.s("id"))) return true
        val loanId = when (kind) {
            "payment" -> rec.s("loanId")
            "file" -> when (rec.s("ownerType")) {
                "loan" -> rec.s("ownerId")
                "payment" -> inList(db, "payments", rec.s("ownerId"))?.s("loanId").orEmpty()
                else -> ""
            }
            else -> ""
        }
        return loanId.isNotEmpty() && h.b(loanId)
    }

    /** The shared (not private) live loans that were on this phone before it joined: what the question counts. */
    fun heldLoans(db: Obj): List<Obj> {
        val h = held(db) ?: return emptyList()
        return db.objs("loans").filter { h.b(it.s("id")) && !it.b("deleted") && !it.b("private") }
    }

    /**
     * A phone that has just been let into a family: everything already in its book is held back until the person answers
     * "Share your existing entries with <family>?". Returns how many shared loans wait; 0 means nothing needed asking (the book
     * had none), and then nothing is held.
     */
    fun holdExisting(db: Obj): Int {
        val ids = obj()
        for ((_, key) in LISTS) if (key != "members") for (r in db.objs(key)) (r["id"] as? String)?.let { ids[it] = true }
        val loans = db.objs("loans").count { !it.b("deleted") && !it.b("private") }
        if (loans == 0) return 0
        syncOf(db)["held"] = ids
        return loans
    }

    /** "Share all": the held entries go up with the next sync. */
    fun shareHeld(db: Obj) {
        syncOf(db).remove("held")
        markAllDirty(db)
    }

    /** "Keep them on this phone only": every held shared loan becomes private (its payments and files follow it). Never pushed. */
    fun keepHeld(db: Obj, me: String, now: Long) {
        for (l in heldLoans(db)) {
            l["private"] = true
            l["updatedAt"] = stamp(l.num("updatedAt"), now); l["updatedBy"] = me
        }
        syncOf(db).remove("held")
        markAllDirty(db)
    }

    /**
     * After [items] went to the relay (or, for items with nothing to send, were handled): an id stays dirty if it changed
     * since it was collected (an edit made while the push was in flight), otherwise its mark is cleared. Every sent id
     * is now on the relay.
     */
    fun afterPush(db: Obj, items: List<PushItem>) {
        val d = dirty(db); val p = pushed(db)
        for (it in items) {
            if (it.send) p[it.id] = true
            if (planFor(db, it.id).json == it.json) d.remove(it.id)
        }
    }

    /** A pulled record, already opened and checked: [kind] is its wire kind, [rec] has a string id, [sig] the signature beside it. */
    class WireRecord(val kind: String, val rec: Obj, val sig: Obj? = null)

    /**
     * Who wrote a pulled record, as far as this phone can prove it (sync/Membership.kt [Membership.BookTrust] is the app's):
     * [signer] is the member whose signature over the record checks out against a key pinned here (null: unsigned, an unknown
     * key or a bad signature); [admin] is the relay's word on roles.
     */
    interface Trust {
        fun signer(w: WireRecord): String?
        fun admin(member: String): Boolean
        /** Every pulled member record is shown here first, so a key an admin vouched for can be pinned before it is needed. */
        fun learn(rec: Obj) {}
        /** Nobody proved, nobody an admin. */
        object None : Trust { override fun signer(w: WireRecord): String? = null; override fun admin(member: String) = false }
    }

    /**
     * Whether this phone's person may delete [rec] (kind loan, payment or file) here: always outside a family and for a private
     * entry; in a family only its maker, the maker of its loan, or an admin, as every other phone checks when the delete arrives.
     * The screens ask this first, so an honest phone never deletes what the others would keep.
     */
    fun mayDeleteHere(db: Obj, kind: String, rec: Obj): Boolean {
        if (db.o("family") == null || isPrivate(db, kind, rec)) return true
        val me = db.o("me")?.s("id").orEmpty()
        val admins = db.o("family")?.objs("roster").orEmpty().filter { it.s("role") == "admin" }.map { it.s("id") }.toSet()
        if (admins.isEmpty() && db.o("family")?.s("creator") == me) return true      // never synced yet: the creator is the admin
        val trust = object : Trust {
            override fun signer(w: WireRecord): String? = me
            override fun admin(member: String) = member in admins
        }
        return mayDelete(db, kind, rec, me, trust)
    }

    /** -1, 0 or 1: is [a] older than, the same version as, or newer than [b] (updatedAt, then updatedBy). */
    fun compare(a: Obj, b: Obj): Int {
        val au = a.num("updatedAt"); val bu = b.num("updatedAt")
        if (au != bu) return if (au > bu) 1 else -1
        return a.s("updatedBy").compareTo(b.s("updatedBy")).coerceIn(-1, 1)
    }

    /**
     * Whether [signer] may delete [local] (kind loan, payment or file): whoever made it, whoever made the loan it belongs to
     * (making a loan private deletes its payments and files for the family), or an admin. Nobody unproven.
     */
    fun mayDelete(db: Obj, kind: String, local: Obj, signer: String?, trust: Trust): Boolean {
        if (signer.isNullOrEmpty()) return false
        if (trust.admin(signer)) return true
        if (local.s("by") == signer) return true
        val loanId = when (kind) {
            "payment" -> local.s("loanId")
            "file" -> when (local.s("ownerType")) {
                "loan" -> local.s("ownerId")
                "payment" -> inList(db, "payments", local.s("ownerId"))?.s("loanId").orEmpty()
                else -> ""
            }
            else -> ""
        }
        if (kind == "file" && local.s("ownerType") == "payment" && inList(db, "payments", local.s("ownerId"))?.s("by") == signer) return true
        return loanId.isNotEmpty() && inList(db, "loans", loanId)?.s("by") == signer
    }

    class MergeOutcome(val taken: Int, val kept: Int, val ignored: Int, val filesRemoved: List<String>)

    /** Does (remote updatedAt, updatedBy) beat (local ...)? Later updatedAt wins; on a tie, the greater updatedBy. */
    fun remoteWins(remote: Obj, local: Obj): Boolean {
        val ru = remote.num("updatedAt"); val lu = local.num("updatedAt")
        if (ru != lu) return ru > lu
        return remote.s("updatedBy") > local.s("updatedBy")
    }

    /**
     * Merges pulled records into [db] (step 2 of "One refresh"; docs/DESIGN.md "Merge rules"):
     * - an updatedAt more than [SKEW_MS] ahead of [now] is pulled back to now + [SKEW_MS] (a future date never wins for ever);
     * - a member record is taken only when its writer is proved (signed with a pinned key) and is that member or an admin;
     * - a shared record that says `private: true` is never taken (an honest phone never sends one: it sends a bare tombstone);
     * - not in the book -> added;
     * - private on this phone -> left alone; if the row is not a tombstone, re-marked dirty so the tombstone goes back up
     *   (after a restore, a row newer than the private copy is taken instead);
     * - a tombstone over a live entry is taken only from its maker, the maker of its loan, or an admin (proved); otherwise it
     *   is refused and this phone keeps the entry ([mayDeleteHere] keeps honest phones from deleting what others would keep);
     * - dirty -> the newer of the two wins (updatedAt, then updatedBy); if remote wins, the dirty mark is cleared;
     * - not dirty -> taken when it is the same version or newer; an older row (a relay replaying an old copy) is refused and
     *   this phone's newer copy is marked dirty, so it goes back up.
     * Local-only fields of the local copy are kept; the row's signature is kept beside a taken record. Every pulled id is marked
     * as held by the relay.
     */
    fun mergeRemote(db: Obj, records: List<WireRecord>, trust: Trust, now: Long): MergeOutcome {
        var taken = 0; var kept = 0; var ignored = 0
        val removed = ArrayList<String>()
        val d = dirty(db); val p = pushed(db)
        val restoring = restored(db)
        for (w in records) {
            val listKey = LISTS[w.kind]
            val id = w.rec["id"] as? String
            if (listKey == null || id == null) { ignored++; continue }
            p[id] = true
            val incoming = wireRecord(w.kind, w.rec)
            var sig = w.sig
            val limit = (now + SKEW_MS).toDouble()
            if (incoming.num("updatedAt") > limit) { incoming["updatedAt"] = limit; sig = null }
            if (w.kind != "member" && !incoming.b("deleted") && incoming.b("private")) { ignored++; continue }
            val local = findRecord(db, id)
            if (w.kind == "member") {
                trust.learn(incoming)
                val by = trust.signer(w)
                if (by == null || (by != id && !trust.admin(by))) {
                    // someone wrote over this member's record on the relay: the proved copy held here goes back up
                    if (local != null && local.kind == "member" && !local.rec.b("deleted")) d[id] = true
                    ignored++; continue
                }
                // an admin's certificate for this member's key, kept on a copy that wins here, so it keeps travelling
                if (local != null && local.kind == "member" && incoming["cert"] != null && incoming.s("pub") == local.rec.s("pub"))
                    local.rec["cert"] = incoming["cert"]
            }
            if (local == null) {
                if (sig != null) incoming[SIG] = sig
                db.aa(listKey).add(incoming); taken++; continue
            }
            if (local.kind != w.kind) { ignored++; continue }
            if (isPrivate(db, local.kind, local.rec, unknown = false) && !(restoring && remoteWins(incoming, local.rec))) {
                if (!incoming.b("deleted")) d[id] = true
                ignored++; continue
            }
            if (w.kind != "member" && incoming.b("deleted") && !local.rec.b("deleted") &&
                !mayDelete(db, w.kind, local.rec, trust.signer(w), trust)) {
                // kept here; not sent back up (the deleting phone would only send its tombstone again, for ever)
                ignored++; continue
            }
            val isDirty = d.b(id)
            val c = compare(incoming, local.rec)
            if (isDirty && c <= 0) { kept++; continue }
            if (!isDirty && c < 0) { d[id] = true; ignored++; continue }
            LOCAL_ONLY[w.kind]?.forEach { k -> if (local.rec.containsKey(k)) incoming[k] = local.rec[k] }
            if (sig != null) incoming[SIG] = sig
            if (w.kind == "file" && incoming.b("deleted") && !local.rec.b("deleted")) removed.add(id)
            local.list[local.index] = incoming
            if (isDirty) d.remove(id)
            taken++
        }
        return MergeOutcome(taken, kept, ignored, removed)
    }

    /**
     * Records this phone wrote under [from] (its own id before it was in any family) are this person's under [to]: the id an
     * admin let it back in as. Only `by` and `updatedBy` change; the records are marked dirty when the family is entered.
     */
    fun rewriteAuthor(db: Obj, from: String, to: String) {
        if (from.isEmpty() || from == to) return
        for (key in listOf("loans", "payments", "files")) for (r in db.objs(key)) {
            if (r.s("by") == from) r["by"] = to
            if (r.s("updatedBy") == from) r["updatedBy"] = to
            for (rn in r.objs("renewals")) if (rn.s("by") == from) rn["by"] = to
        }
        db.a("members")?.removeAll { it.asObj()?.s("id") == from }
    }

    /** Marks every record dirty and every live file for upload: a book that has just created or joined a family. */
    fun markAllDirty(db: Obj) {
        val d = dirty(db); val up = blobsUp(db)
        for ((kind, key) in LISTS) for (r in db.objs(key)) {
            val id = r["id"] as? String ?: continue
            d[id] = true
            if (kind == "file" && !r.b("deleted")) up[id] = true
        }
    }

    /** sync.blobsUp split into ids to upload now, ids to drop (no such live file) and ids to keep for later (private). */
    class BlobPlan(val upload: List<String>, val drop: List<String>)

    fun planBlobs(db: Obj): BlobPlan {
        val up = ArrayList<String>(); val drop = ArrayList<String>()
        val ids = db.o("sync").o("blobsUp")?.filter { truthy(it.value) }?.keys?.toList() ?: emptyList()
        for (id in ids) {
            val f = findRecord(db, id)
            when {
                f == null || f.kind != "file" || f.rec.b("deleted") -> drop.add(id)
                isPrivate(db, "file", f.rec) || isHeld(db, "file", f.rec) -> {}
                else -> up.add(id)
            }
        }
        return BlobPlan(up, drop)
    }

    /**
     * Of [ids] (files someone asked for), those this phone may offer again: live file records that are not private. Whether the
     * bytes are here is the engine's to check.
     */
    fun shareable(db: Obj, ids: List<String>): List<String> = ids.filter { id ->
        val f = findRecord(db, id)
        f != null && f.kind == "file" && !f.rec.b("deleted") && !isPrivate(db, "file", f.rec) && !isHeld(db, "file", f.rec)
    }

    class FileWant(val id: String, val sha256: String, val size: Long, val mime: String)

    /** Live file records (their bytes may or may not be on this phone yet). */
    fun liveFiles(db: Obj): List<FileWant> = db.objs("files").filter { !it.b("deleted") && it["id"] is String }
        .map { FileWant(it.s("id"), it.s("sha256"), it.num("size").toLong(), it.s("mime")) }

    /**
     * Adds or refreshes this phone's member record ([me] = DB.me) and marks it dirty. [pub] is this phone's public key in
     * the family (base64url SPKI), carried in the record so the other phones can seal new keys to it (docs/DESIGN.md, "Membership").
     */
    fun upsertMe(db: Obj, now: Long, pub: String? = null) {
        val me = db.oo("me")
        if (me.s("id").isEmpty()) me["id"] = uid("m")
        val id = me.s("id")
        val list = db.aa("members")
        val cur = list.firstOrNull { it.asObj()?.get("id") == id }.asObj()
        if (cur != null && cur.s("name") == me.s("name") && !cur.b("deleted") && !cur.b("left") && (pub == null || cur.s("pub") == pub)) {
            dirty(db)[id] = true; return
        }
        val rec = cur ?: obj("id" to id, "at" to now.toDouble()).also { list.add(it) }
        rec["name"] = me.s("name")
        if (pub != null && rec.s("pub") != pub) { rec["pub"] = pub; rec.remove("cert") }   // a certificate is for one key only
        rec.remove("deleted"); rec.remove("left")
        rec["updatedAt"] = stamp(rec.num("updatedAt"), now); rec["updatedBy"] = id
        dirty(db)[id] = true
    }
}
