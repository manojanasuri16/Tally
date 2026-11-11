package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime

/** An in-memory relay with the SQL functions' semantics: token hash check, a global seq taken at upsert, paging. */
class FakeRelay : Relay {
    class Row(val kind: String, val blob: String, val seq: Long)
    val families = HashMap<String, String>()
    val records = HashMap<String, LinkedHashMap<String, Row>>()
    val blobs = HashMap<String, HashMap<String, String>>()
    private var seq = 0L

    /** Fail the next calls of each kind with this error (consumed one per call). */
    val failPull = ArrayDeque<SyncError?>()
    val failPush = ArrayDeque<SyncError?>()
    /** Runs after a push is stored and before it returns (an edit made while the push was in flight). */
    var onPush: (suspend () -> Unit)? = null

    private fun ok(fam: String, token: String) = families[fam] != null && families[fam] == SyncCrypto.tokenHash(token)

    override suspend fun createFamily(familyId: String, tokenHash: String): RelayResult<Unit> {
        val cur = families[familyId]
        if (cur != null && cur != tokenHash) return RelayResult.Err(SyncError.Unauthorised)
        families[familyId] = tokenHash
        return RelayResult.Ok(Unit)
    }

    override suspend fun push(familyId: String, token: String, rows: List<RelayRow>): RelayResult<Int> {
        failPush.removeFirstOrNull()?.let { return RelayResult.Err(it) }
        if (!ok(familyId, token)) return RelayResult.Err(SyncError.Unauthorised)
        val m = records.getOrPut(familyId) { LinkedHashMap() }
        rows.forEach { m[it.id] = Row(it.kind, it.blob, ++seq) }
        onPush?.let { h -> onPush = null; h() }
        return RelayResult.Ok(rows.size)
    }

    override suspend fun pull(familyId: String, token: String, since: Long, max: Int): RelayResult<List<RelayRow>> {
        failPull.removeFirstOrNull()?.let { return RelayResult.Err(it) }
        if (!ok(familyId, token)) return RelayResult.Err(SyncError.Unauthorised)
        val out = (records[familyId] ?: emptyMap<String, Row>()).entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
            .take(max.coerceIn(1, 500)).map { RelayRow(it.key, it.value.kind, it.value.blob, it.value.seq) }
        return RelayResult.Ok(out)
    }

    override suspend fun blobPut(familyId: String, token: String, id: String, data: String): RelayResult<Unit> {
        if (!ok(familyId, token)) return RelayResult.Err(SyncError.Unauthorised)
        blobs.getOrPut(familyId) { HashMap() }[id] = data
        return RelayResult.Ok(Unit)
    }

    /** How many file downloads were asked for. */
    var blobGets = 0

    override suspend fun blobGet(familyId: String, token: String, id: String): RelayResult<String?> {
        blobGets++
        if (!ok(familyId, token)) return RelayResult.Err(SyncError.Unauthorised)
        return RelayResult.Ok(blobs[familyId]?.get(id))
    }
}

/** A relay whose pull can be held up until the test lets it go (or cancels the sync waiting on it). */
class GateRelay(private val inner: FakeRelay) : Relay by inner {
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun pull(familyId: String, token: String, since: Long, max: Int): RelayResult<List<RelayRow>> {
        gate?.await()
        return inner.pull(familyId, token, since, max)
    }
}

class MemBook(var db: Obj = obj("v" to 1)) : SyncBook {
    var failSave = false
    var saves = 0
    override suspend fun <T> read(block: (Obj) -> T): T = block(db)
    override suspend fun <T> edit(block: (Obj) -> T): T {
        if (failSave) throw SyncBook.SaveFailed()
        return block(db).also { saves++ }
    }
}

class MemFiles : LocalFiles {
    val bytes = HashMap<String, ByteArray>()
    override suspend fun has(id: String) = id in bytes
    override suspend fun read(id: String) = bytes[id]
    override suspend fun write(id: String, bytes: ByteArray, mime: String) { this.bytes[id] = bytes }
    override suspend fun delete(id: String) { bytes.remove(id) }
}

/** A phone: its book, its files and its engine, plus the write helpers the model would provide (stamp + dirty). */
class Phone(val name: String, relay: Relay, pageSize: Int = 500) {
    val book = MemBook(obj("v" to 1, "me" to obj("id" to "m-$name", "name" to name)))
    val files = MemFiles()
    val engine = SyncEngine(relay, book, files, Dispatchers.Unconfined, pageSize = pageSize, pushChunk = 3)
    val db get() = book.db
    val me get() = db.o("me").s("id")

    fun sync() = runBlocking { engine.sync() }

    fun put(list: String, rec: Obj): Obj {
        TestClock.tick()
        val l = db.aa(list)
        val i = l.indexOfFirst { it.asObj()?.get("id") == rec["id"] }
        rec["updatedAt"] = Clock.millis().toDouble(); rec["updatedBy"] = me
        if (!rec.containsKey("by")) { rec["by"] = me; rec["at"] = Clock.millis().toDouble() }
        if (i >= 0) l[i] = rec else l.add(rec)
        db.oo("sync").oo("dirty")[rec.s("id")] = true
        return rec
    }

    fun edit(list: String, id: String, change: (Obj) -> Unit): Obj {
        val r = db.objs(list).first { it["id"] == id }.copyObj()
        change(r)
        return put(list, r)
    }

    fun loan(id: String, name: String, private: Boolean = false) =
        put("loans", obj("id" to id, "kind" to "bank", "name" to name, "principal" to 500000, "private" to private))

    fun payment(id: String, loanId: String, amount: Int) =
        put("payments", obj("id" to id, "loanId" to loanId, "date" to "2026-11-11", "amount" to amount, "kind" to "emi"))

    fun file(id: String, ownerType: String, ownerId: String, data: ByteArray): Obj {
        files.bytes[id] = data
        db.oo("sync").oo("blobsUp")[id] = true
        return put("files", obj("id" to id, "ownerType" to ownerType, "ownerId" to ownerId, "name" to "$id.jpg", "mime" to "image/jpeg",
            "size" to data.size, "sha256" to SyncCrypto.sha256Hex(data), "enc" to false))
    }

    fun rec(list: String, id: String): Obj? = db.objs(list).firstOrNull { it["id"] == id }

    /** The shared part of the book in a comparable form: the four lists sorted by id, local-only fields dropped. */
    fun shared(): String = JsonCodec.stringify(obj(*listOf("loans", "payments", "files", "members").map { k ->
        k to ArrayList<Any?>(db.objs(k).sortedBy { it.s("id") }.map { r -> r.copyObj().also { it.remove("enc") } })
    }.toTypedArray()))
}

/** Pins Clock and moves it one second per tick, so every edit has a distinct, ordered updatedAt. */
object TestClock {
    var t: LocalDateTime = LocalDateTime.of(2026, 11, 11, 9, 0)
    fun start() { t = LocalDateTime.of(2026, 11, 11, 9, 0); Clock.fixed = t }
    fun tick() { t = t.plusSeconds(1); Clock.fixed = t }
    fun stop() { Clock.fixed = null }
}
