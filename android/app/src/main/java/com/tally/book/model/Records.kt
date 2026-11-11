package com.tally.book.model

import com.tally.book.core.*

/*
 * The write side of the book (shape: docs/DESIGN.md "The local book"). Screens never stamp or mark anything by hand:
 * they call these inside Ledger.edit { }. Every write stamps updatedAt/updatedBy and marks the id in DB.sync.dirty, which
 * is what the sync layer reads. Deleting leaves a tombstone ({ id, deleted, updatedAt, updatedBy } plus the owner link,
 * so privacy can still be resolved); every read helper here hides tombstones.
 */

// ---- who am I ----

/** This phone's person ({ id, name }); made with a fresh id on first use. */
fun me(): Obj {
    val m = DB.o("me")
    if (m != null && m.s("id").isNotEmpty()) return m
    val made = m ?: obj()
    made["id"] = uid("m"); if (!made.containsKey("name")) made["name"] = ""
    DB["me"] = made
    return made
}

fun myId(): String = me().s("id")

/** Sets my name. When a family exists my entry in DB.members is kept in step (and marked dirty so the others see it). */
fun setMyName(name: String) {
    val m = me()
    val clean = name.trim()
    m["name"] = clean
    if (DB.o("family") == null) return
    val members = DB.aa("members")
    val existing = members.mapNotNull { it.asObj() }.firstOrNull { it.s("id") == m.s("id") }
    if (existing != null) { existing["name"] = clean; existing.remove("deleted"); touch(existing) }
    else {
        val rec = obj("id" to m.s("id"), "name" to clean)
        stampNew(rec); members.add(rec)
    }
}

/** A member's name for display; "Someone" when unknown or blank. */
fun memberName(id: String?): String {
    val key = id ?: ""
    if (key.isNotEmpty()) {
        val mine = DB.o("me")
        if (mine != null && mine.s("id") == key && mine.s("name").isNotBlank()) return mine.s("name")
        val m = DB.objs("members").firstOrNull { it.s("id") == key && !it.b("deleted") }
        if (m != null && m.s("name").isNotBlank()) return m.s("name")
    }
    return t("member.someone", "Someone")
}

/**
 * Whether anyone but me could have added a record: the phone is in a family, or a loan on it was made by another person (a family
 * that was left keeps its loans). When false "added by <name>" would always be me, so the home list leaves it out.
 */
fun bookHasOthers(): Boolean {
    if (DB.o("family") != null) return true
    val mine = myId()
    return DB.objs("loans").any { !it.b("deleted") && it.s("by").isNotEmpty() && it.s("by") != mine }
}

/** The local calendar date of an epoch-millisecond stamp (`at`, `updatedAt`), as ISO. */
fun isoOfMillis(ms: Double): String =
    java.time.Instant.ofEpochMilli(ms.toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

// ---- stamping ----

/** Marks a record as changed by me now, and for the sync layer. */
fun touch(rec: Obj) {
    rec["updatedAt"] = Clock.millis().toDouble()
    rec["updatedBy"] = myId()
    val id = rec.s("id")
    if (id.isNotEmpty()) DB.oo("sync").oo("dirty")[id] = true
}

/** A record seen for the first time: who made it and when, then [touch]. */
fun stampNew(rec: Obj) {
    rec["by"] = myId()
    rec["at"] = Clock.millis().toDouble()
    touch(rec)
}

// ---- reads (tombstones hidden) ----

private fun live(key: String): List<Obj> = DB.objs(key).filter { !it.b("deleted") }

fun loans(): List<Obj> = live("loans")
fun loan(id: String?): Obj? = if (id.isNullOrEmpty()) null else loans().firstOrNull { it.s("id") == id }

/** Newest first: by date, then by when it was entered. */
fun paymentsOf(loanId: String): List<Obj> =
    live("payments").filter { it.s("loanId") == loanId }
        .sortedWith(compareByDescending<Obj> { it.s("date") }.thenByDescending { it.num("at") })

/* filesOf(ownerType, ownerId) lives in FileMeta.kt; filesAll() there hides tombstones. */

// ---- privacy ----

/** A loan by its own `private` flag; a payment through its loan; a file through its loan or, when a payment owns it, payment then loan. */
fun isPrivate(db: Obj, rec: Obj): Boolean {
    fun loanPrivate(id: String): Boolean = db.objs("loans").firstOrNull { it.s("id") == id }?.b("private") ?: false
    return when {
        rec.containsKey("loanId") -> loanPrivate(rec.s("loanId"))
        rec.s("ownerType") == "loan" -> loanPrivate(rec.s("ownerId"))
        rec.s("ownerType") == "payment" -> {
            val p = db.objs("payments").firstOrNull { it.s("id") == rec.s("ownerId") }
            p != null && loanPrivate(p.s("loanId"))
        }
        else -> rec.b("private")
    }
}

// ---- tombstones ----

/** Empties [rec] in place down to a tombstone, keeping the fields in [keep]. */
private fun tombstone(rec: Obj, vararg keep: String) {
    val kept = keep.filter { rec.containsKey(it) }.associateWith { rec[it] }
    val id = rec["id"]
    rec.clear(); rec["id"] = id
    kept.forEach { (k, v) -> rec[k] = v }
    rec["deleted"] = true
    touch(rec)
}

private fun replaceById(list: Arr, rec: Obj) {
    val i = list.indexOfFirst { it.asObj()?.s("id") == rec.s("id") }
    if (i >= 0) list[i] = rec else list.add(rec)
}

// ---- loans ----

/** Inserts or replaces a loan by id (a new id is made when the draft has none). Returns the stored record. */
fun saveLoan(draft: Obj): Obj {
    val rec = draft.copyObj()
    if (rec.s("id").isEmpty()) rec["id"] = uid("l")
    rec.remove("deleted")
    val old = DB.objs("loans").firstOrNull { it.s("id") == rec.s("id") }
    if (old == null || old.b("deleted")) {
        if (old != null) { rec["by"] = old["by"] ?: myId(); rec["at"] = old["at"] ?: Clock.millis().toDouble() }
        if (rec.s("by").isEmpty()) stampNew(rec) else touch(rec)
    } else {
        if (rec.s("by").isEmpty()) rec["by"] = old["by"] ?: myId()
        if (!rec.containsKey("at")) rec["at"] = old["at"] ?: Clock.millis().toDouble()
        touch(rec)
    }
    replaceById(DB.aa("loans"), rec)
    if (old != null && !old.b("deleted") && old.b("private") != rec.b("private")) touchFamilyOf(rec.s("id"))
    return rec
}

/** Tombstones a loan, its payments and its files (including files owned by its payments). */
fun deleteLoan(id: String) {
    val l = DB.objs("loans").firstOrNull { it.s("id") == id && !it.b("deleted") } ?: return
    val payIds = DB.objs("payments").filter { it.s("loanId") == id }.map { it.s("id") }.toSet()
    DB.objs("files").filter { !it.b("deleted") && ((it.s("ownerType") == "loan" && it.s("ownerId") == id) ||
        (it.s("ownerType") == "payment" && it.s("ownerId") in payIds)) }
        .forEach { tombstone(it, "ownerType", "ownerId") }
    DB.objs("payments").filter { it.s("loanId") == id && !it.b("deleted") }.forEach { tombstone(it, "loanId") }
    /* the private flag stays on the loan tombstone so its payments' tombstones still resolve as private */
    tombstone(l, "private")
    if (!l.b("private")) l.remove("private")
}

fun closeLoan(id: String, date: String) {
    val l = loan(id) ?: return
    l["closed"] = true; l["closedDate"] = date; touch(l)
}

fun reopenLoan(id: String) {
    val l = loan(id) ?: return
    l["closed"] = false; l["closedDate"] = ""; touch(l)
}

/** Flips the private flag and touches the loan, all its payments and all its files, so sync sees every one of them. */
fun setLoanPrivate(id: String, private: Boolean) {
    val l = loan(id) ?: return
    l["private"] = private
    touch(l)
    touchFamilyOf(id)
}

/** Touches every live payment and file of a loan (not the loan itself). */
private fun touchFamilyOf(loanId: String) {
    val pays = live("payments").filter { it.s("loanId") == loanId }
    val payIds = pays.map { it.s("id") }.toSet()
    pays.forEach { touch(it) }
    live("files").filter { (it.s("ownerType") == "loan" && it.s("ownerId") == loanId) ||
        (it.s("ownerType") == "payment" && it.s("ownerId") in payIds) }.forEach { touch(it) }
    loan(loanId)?.let { touch(it) }
}

// ---- payments ----

fun savePayment(draft: Obj): Obj {
    val rec = draft.copyObj()
    if (rec.s("id").isEmpty()) rec["id"] = uid("p")
    rec.remove("deleted")
    val old = DB.objs("payments").firstOrNull { it.s("id") == rec.s("id") && !it.b("deleted") }
    if (old == null) stampNew(rec)
    else {
        if (rec.s("by").isEmpty()) rec["by"] = old["by"] ?: myId()
        if (!rec.containsKey("at")) rec["at"] = old["at"] ?: Clock.millis().toDouble()
        touch(rec)
    }
    replaceById(DB.aa("payments"), rec)
    return rec
}

fun deletePayment(id: String) {
    val p = DB.objs("payments").firstOrNull { it.s("id") == id && !it.b("deleted") } ?: return
    DB.objs("files").filter { !it.b("deleted") && it.s("ownerType") == "payment" && it.s("ownerId") == id }
        .forEach { tombstone(it, "ownerType", "ownerId") }
    tombstone(p, "loanId")
}

// ---- files (metadata only; the bytes are the data layer's) ----

fun fileAdded(meta: Obj) {
    val rec = meta.copyObj()
    if (rec.s("id").isEmpty()) rec["id"] = uid("f")
    rec.remove("deleted")
    stampNew(rec)
    replaceById(DB.aa("files"), rec)
}

fun deleteFileRecord(id: String) {
    val f = DB.objs("files").firstOrNull { it.s("id") == id && !it.b("deleted") } ?: return
    tombstone(f, "ownerType", "ownerId")
}
