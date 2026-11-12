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

    /** Fields that describe this phone's copy only and never travel (files: whether the local bytes are encrypted). */
    private val LOCAL_ONLY: Map<String, Set<String>> = mapOf("file" to setOf("enc"))

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
        return c
    }

    fun tombstone(rec: Obj): Obj = obj("id" to rec["id"], "deleted" to true, "updatedAt" to rec["updatedAt"], "updatedBy" to rec["updatedBy"])

    fun envelope(kind: String, rec: Obj): Obj = obj("k" to kind, "r" to rec)

    /**
     * What a dirty id needs: [json] is the envelope to send, or null for "nothing to send" (a private record the relay
     * never had, or an id no longer in the book). [kind] is the wire kind ("" when the id is gone).
     */
    class PushItem(val id: String, val kind: String, val envelope: Obj?, val json: String?) {
        val send: Boolean get() = json != null
    }

    fun planFor(db: Obj, id: String): PushItem {
        val f = findRecord(db, id) ?: return PushItem(id, "", null, null)
        val body = when {
            !isPrivate(db, f.kind, f.rec) -> wireRecord(f.kind, f.rec)
            pushed(db).b(id) -> tombstone(f.rec)
            else -> return PushItem(id, f.kind, null, null)
        }
        val env = envelope(f.kind, body)
        return PushItem(id, f.kind, env, JsonCodec.stringify(env))
    }

    /** Every dirty id and what to do with it, loans first (then members, payments, files). */
    fun collectPush(db: Obj): List<PushItem> {
        val ids = db.o("sync").o("dirty")?.filter { truthy(it.value) }?.keys?.toList() ?: return emptyList()
        val order = LISTS.keys.toList()
        return ids.map { planFor(db, it) }.sortedBy { if (it.kind.isEmpty()) order.size else order.indexOf(it.kind) }
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

    /** A pulled record, already opened and checked: [kind] is its wire kind, [rec] has a string id. */
    class WireRecord(val kind: String, val rec: Obj)

    class MergeOutcome(val taken: Int, val kept: Int, val ignored: Int, val filesRemoved: List<String>)

    /** Does (remote updatedAt, updatedBy) beat (local ...)? Later updatedAt wins; on a tie, the greater updatedBy. */
    fun remoteWins(remote: Obj, local: Obj): Boolean {
        val ru = remote.num("updatedAt"); val lu = local.num("updatedAt")
        if (ru != lu) return ru > lu
        return remote.s("updatedBy") > local.s("updatedBy")
    }

    /**
     * Merges pulled records into [db] (step 1 of "One refresh"):
     * - not in the book -> added;
     * - private on this phone -> left alone; if the row is not a tombstone, re-marked dirty so the tombstone goes back up
     *   (after a restore, a row newer than the private copy is taken instead, as below);
     * - not dirty -> the remote record replaces it;
     * - dirty -> last writer wins (updatedAt, then updatedBy); if remote wins, the dirty mark is cleared.
     * Local-only fields of the local copy are kept. Every pulled id is marked as held by the relay.
     */
    fun mergeRemote(db: Obj, records: List<WireRecord>): MergeOutcome {
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
            val local = findRecord(db, id)
            if (local == null) {
                db.aa(listKey).add(incoming); taken++; continue
            }
            if (local.kind != w.kind) { ignored++; continue }
            if (isPrivate(db, local.kind, local.rec, unknown = false) && !(restoring && remoteWins(incoming, local.rec))) {
                if (!incoming.b("deleted")) d[id] = true
                ignored++; continue
            }
            val isDirty = d.b(id)
            if (isDirty && !remoteWins(incoming, local.rec)) { kept++; continue }
            LOCAL_ONLY[w.kind]?.forEach { k -> if (local.rec.containsKey(k)) incoming[k] = local.rec[k] }
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
                isPrivate(db, "file", f.rec) -> {}
                else -> up.add(id)
            }
        }
        return BlobPlan(up, drop)
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
        if (pub != null) rec["pub"] = pub
        rec.remove("deleted"); rec.remove("left")
        rec["updatedAt"] = now.toDouble(); rec["updatedBy"] = id
        dirty(db)[id] = true
    }
}
