package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime

/**
 * An in-memory relay with the SQL functions' semantics (supabase/migrations, the tally_membership migration): per-member
 * credentials (only hashes kept), roles and statuses, the epoch rule on writes, one-time invites, requests, key boxes,
 * the role rules (last admin, the creator, removing yourself), limits, and a global seq taken at upsert. Each call is atomic,
 * as the family lock makes them on the real relay.
 */
class FakeRelay : Relay {
    class Row(val kind: String, val blob: String, val seq: Long)
    class Member(val id: String, var hash: String, var notice: String?, var role: String, var status: String, var has: Int, var joined: Long)
    class InviteRow(val id: String, val by: String, val box: String, var used: Boolean, val expires: Long, val created: Long)
    class Req(val invite: String, val member: String, val hash: String, val notice: String?, val blob: String, var status: String, val at: Long, val expires: Long)
    class Fam(val id: String, var creator: String?, var epoch: Int = 1, var rotate: Boolean = false) {
        val members = LinkedHashMap<String, Member>()
        val invites = LinkedHashMap<String, InviteRow>()
        val requests = LinkedHashMap<String, Req>()
        val boxes = HashMap<String, HashMap<Int, String>>()
    }

    val fams = HashMap<String, Fam>()
    val records = HashMap<String, LinkedHashMap<String, Row>>()
    val blobs = HashMap<String, HashMap<String, String>>()
    private var seq = 0L
    private var joinOrder = 0L

    /** Fail the next calls of each kind with this error (consumed one per call). */
    val failPull = ArrayDeque<SyncError?>()
    val failPush = ArrayDeque<SyncError?>()
    /** Runs after a push is stored and before it returns (an edit made while the push was in flight). */
    var onPush: (suspend () -> Unit)? = null
    /** Runs once before the next call of the named function ("remove", "decide", "rotate", "push"...): another phone acting first. */
    val before = HashMap<String, suspend () -> Unit>()
    /** A hostile relay: rewrites the requests it shows admins, or the key boxes it hands a member. */
    var tamperRequests: ((List<RelayRequest>) -> List<RelayRequest>)? = null
    var tamperBoxes: ((String, List<RelayBox>) -> List<RelayBox>)? = null
    /** A hostile relay that ignores "used once" and accepts more requests for the same invite. */
    var ignoreUsed = false
    /** How many file downloads were asked for. */
    var blobGets = 0

    private class E(val e: SyncError) : Exception()
    private fun fail(e: SyncError): Nothing = throw E(e)
    private suspend fun <T> r(fn: String, block: () -> T): RelayResult<T> {
        before.remove(fn)?.invoke()
        return try { RelayResult.Ok(block()) } catch (x: E) { RelayResult.Err(x.e) }
    }
    private fun now() = Clock.millis()
    private fun hash(s: String) = SyncCrypto.tokenHash(s)

    private fun auth(c: Cred): Pair<Fam, Member> {
        val f = fams[c.familyId] ?: fail(SyncError.Unauthorised)
        val m = f.members[c.memberId] ?: fail(SyncError.Unauthorised)
        if (m.hash != hash(c.secret)) fail(SyncError.Unauthorised)
        if (m.status == "removed") fail(SyncError.Removed)
        if (m.status != "active") fail(SyncError.Unauthorised)
        return f to m
    }
    private fun admin(c: Cred): Pair<Fam, Member> { val p = auth(c); if (p.second.role != "admin") fail(SyncError.NotAdmin); return p }
    private fun active(f: Fam) = f.members.values.filter { it.status == "active" }
    private fun tidy(f: Fam) {
        f.requests.values.removeIf { it.expires < now() }
        f.invites.values.removeIf { it.expires < now() && !f.requests.containsKey(it.id) }
    }

    private fun putBoxes(f: Fam, epoch: Int, boxes: List<BoxFor>) {
        for (b in boxes) if (f.members[b.member]?.status != "active") fail(SyncError.Stale)
        if (boxes.map { it.member }.toSet().size != boxes.size) fail(SyncError.BadData("twice"))
        for (b in boxes) {
            val mine = f.boxes.getOrPut(b.member) { HashMap() }
            if (!mine.containsKey(epoch)) mine[epoch] = b.box
            f.members[b.member]!!.has = maxOf(f.members[b.member]!!.has, epoch)
        }
    }
    private fun newEpoch(f: Fam, caller: String, epoch: Int, boxes: List<BoxFor>) {
        if (epoch != f.epoch + 1) fail(SyncError.Stale)
        if (boxes.none { it.member == caller }) fail(SyncError.BadData("caller box"))
        putBoxes(f, epoch, boxes)
        f.epoch = epoch; f.rotate = false
        f.requests.clear(); f.invites.clear()
    }

    override suspend fun createFamily(familyId: String, memberId: String, secretHash: String, noticeHash: String?) = r("create") {
        val cur = fams[familyId]
        if (cur != null) {
            val m = cur.members[memberId]
            if (cur.creator == memberId && m != null && m.hash == secretHash && m.status == "active") return@r
            fail(SyncError.Unauthorised)
        }
        val f = Fam(familyId, memberId)
        f.members[memberId] = Member(memberId, secretHash, noticeHash, "admin", "active", 1, ++joinOrder)
        fams[familyId] = f
        Unit
    }

    override suspend fun state(c: Cred, after: Int) = r("state") {
        val (f, me) = auth(c)
        val boxes = (f.boxes[me.id] ?: emptyMap<Int, String>()).filter { it.key > after }.toSortedMap().map { RelayBox(it.key, it.value) }
        val reqs = if (me.role != "admin") emptyList() else f.requests.values.filter { it.status == "pending" && it.expires > now() }.mapNotNull { q ->
            val i = f.invites[q.invite] ?: return@mapNotNull null
            RelayRequest(q.invite, q.member, q.blob, q.at, q.expires, i.box, i.by)
        }
        RelayState(
            f.epoch, f.creator, f.rotate, me.role,
            active(f).sortedBy { it.joined }.map { RelayMember(it.id, it.role, it.joined, it.has) },
            tamperBoxes?.invoke(me.id, boxes) ?: boxes,
            f.invites.values.filter { !it.used && it.expires > now() }.map { RelayInvite(it.id, it.by, it.expires) },
            tamperRequests?.invoke(reqs) ?: reqs,
        )
    }

    override suspend fun push(c: Cred, epoch: Int, rows: List<RelayRow>): RelayResult<Int> {
        failPush.removeFirstOrNull()?.let { return RelayResult.Err(it) }
        val out = r("push") {
            val (f, _) = auth(c)
            if (epoch != f.epoch) fail(SyncError.Stale)
            val m = records.getOrPut(f.id) { LinkedHashMap() }
            rows.forEach { m[it.id] = Row(it.kind, it.blob, ++seq) }
            if (m.size > 20_000) fail(SyncError.Limit)
            rows.size
        }
        if (out is RelayResult.Ok) onPush?.let { h -> onPush = null; h() }
        return out
    }

    override suspend fun pull(c: Cred, since: Long, max: Int): RelayResult<List<RelayRow>> {
        failPull.removeFirstOrNull()?.let { return RelayResult.Err(it) }
        return r("pull") {
            val (f, _) = auth(c)
            (records[f.id] ?: emptyMap<String, Row>()).entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
                .take(max.coerceIn(1, 500)).map { RelayRow(it.key, it.value.kind, it.value.blob, it.value.seq) }
        }
    }

    override suspend fun blobPut(c: Cred, epoch: Int, id: String, data: String) = r("blobPut") {
        val (f, _) = auth(c)
        if (epoch != f.epoch) fail(SyncError.Stale)
        blobs.getOrPut(f.id) { HashMap() }[id] = data
        Unit
    }

    override suspend fun blobGet(c: Cred, id: String): RelayResult<String?> {
        blobGets++
        return r("blobGet") { val (f, _) = auth(c); blobs[f.id]?.get(id) }
    }

    override suspend fun inviteCreate(c: Cred, inviteId: String, box: String) = r("inviteCreate") {
        val (f, me) = auth(c)
        tidy(f)
        if (f.invites.values.count { !it.used && it.expires > now() } >= 5) fail(SyncError.Limit)
        if (f.invites.containsKey(inviteId)) fail(SyncError.InviteGone)
        val exp = now() + 24 * 3600_000L
        f.invites[inviteId] = InviteRow(inviteId, me.id, box, false, exp, now())
        exp
    }

    override suspend fun inviteCancel(c: Cred, inviteId: String) = r("inviteCancel") {
        val (f, me) = auth(c)
        val i = f.invites[inviteId] ?: return@r
        if (i.by != me.id && me.role != "admin") fail(SyncError.NotAdmin)
        f.requests.remove(inviteId); f.invites.remove(inviteId)
        Unit
    }

    override suspend fun joinRequest(familyId: String, inviteId: String, memberId: String, secretHash: String, noticeHash: String?, blob: String) = r("joinRequest") {
        val f = fams[familyId] ?: fail(SyncError.InviteGone)
        tidy(f)
        val i = f.invites[inviteId] ?: fail(SyncError.InviteGone)
        if ((i.used && !ignoreUsed) || i.expires <= now()) fail(SyncError.InviteGone)
        // (in SQL the invite is marked used first, but a refusal rolls the whole call back: the same as checking first)
        if (f.members[memberId]?.status == "active") fail(SyncError.Refused)
        if (f.requests.values.count { it.status == "pending" } >= 5) fail(SyncError.Limit)
        i.used = true
        val exp = now() + 72 * 3600_000L
        // the hostile relay of [ignoreUsed] keeps the later request under its own key, so both can be shown
        val key = if (f.requests.containsKey(inviteId)) "$inviteId#${f.requests.size}" else inviteId
        f.requests[key] = Req(inviteId, memberId, secretHash, noticeHash, blob, "pending", now(), exp)
        exp
    }

    override suspend fun joinStatus(familyId: String, inviteId: String, memberId: String, secret: String) = r("joinStatus") {
        val f = fams[familyId] ?: fail(SyncError.InviteGone)
        val m = f.members[memberId]
        if (m != null && m.hash == hash(secret)) {
            if (m.status == "active") return@r "approved"
            if (m.status == "removed") return@r "removed"
        }
        val q = f.requests.values.firstOrNull { it.invite == inviteId && it.member == memberId && it.hash == hash(secret) && it.expires > now() }
            ?: fail(SyncError.InviteGone)
        q.status
    }

    override suspend fun joinCancel(familyId: String, inviteId: String, memberId: String, secret: String) = r("joinCancel") {
        fams[familyId]?.requests?.values?.removeIf { it.invite == inviteId && it.member == memberId && it.hash == hash(secret) }
        Unit
    }

    override suspend fun decide(c: Cred, inviteId: String, allow: Boolean, epoch: Int, box: String?) = r("decide") {
        val (f, _) = admin(c)
        val q = f.requests.values.firstOrNull { it.invite == inviteId && it.status == "pending" && it.expires > now() } ?: fail(SyncError.InviteGone)
        if (!allow) { q.status = "denied"; return@r }
        if (epoch != f.epoch) fail(SyncError.Stale)
        if (box == null) fail(SyncError.BadData("box"))
        if (active(f).size >= 20) fail(SyncError.Limit)
        val cur = f.members[q.member]
        if (cur?.status == "active") fail(SyncError.Refused)
        if (cur != null) {
            f.boxes.remove(q.member)
            cur.hash = q.hash; cur.notice = q.notice; cur.role = "member"; cur.status = "active"; cur.has = 0; cur.joined = ++joinOrder
        } else f.members[q.member] = Member(q.member, q.hash, q.notice, "member", "active", 0, ++joinOrder)
        f.boxes.getOrPut(q.member) { HashMap() }[epoch] = box
        f.members[q.member]!!.has = epoch
        f.requests.values.removeIf { it.invite == inviteId }
        f.invites.remove(inviteId)
        Unit
    }

    override suspend fun setRole(c: Cred, target: String, admin: Boolean) = r("setRole") {
        val (f, me) = admin(c)
        val t = f.members[target]?.takeIf { it.status == "active" } ?: fail(SyncError.Refused)
        if (admin) { t.role = "admin"; return@r }
        if (t.role != "admin") return@r
        if (f.creator == target && target != me.id) fail(SyncError.Refused)
        if (active(f).count { it.role == "admin" } <= 1) fail(SyncError.Refused)
        t.role = "member"
        Unit
    }

    override suspend fun remove(c: Cred, target: String, epoch: Int, boxes: List<BoxFor>) = r("remove") {
        val (f, me) = admin(c)
        if (target == me.id || f.members[target]?.status != "active") fail(SyncError.Refused)
        if (f.creator != null && f.creator == target) fail(SyncError.Refused)
        // all or nothing, like the SQL transaction
        val before = f.members[target]!!.status
        f.members[target]!!.status = "removed"
        try { newEpoch(f, me.id, epoch, boxes) } catch (x: E) { f.members[target]!!.status = before; throw x }
        Unit
    }

    override suspend fun rotate(c: Cred, epoch: Int, boxes: List<BoxFor>) = r("rotate") {
        val (f, me) = admin(c)
        newEpoch(f, me.id, epoch, boxes)
    }

    override suspend fun boxPut(c: Cred, epoch: Int, boxes: List<BoxFor>) = r("boxPut") {
        val (f, _) = admin(c)
        if (epoch != f.epoch) fail(SyncError.Stale)
        val n = boxes.count { f.boxes[it.member]?.containsKey(epoch) != true }
        putBoxes(f, epoch, boxes)
        n
    }

    override suspend fun leave(c: Cred) = r("leave") {
        val (f, me) = auth(c)
        me.status = "left"; me.role = "member"
        val mine = f.invites.values.filter { it.by == me.id }.map { it.id }
        mine.forEach { f.invites.remove(it); f.requests.remove(it) }
        if (f.creator == me.id) f.creator = null
        if (active(f).isEmpty()) {
            fams.remove(f.id); records.remove(f.id); blobs.remove(f.id)
            return@r "deleted"
        }
        if (active(f).none { it.role == "admin" }) active(f).minBy { it.joined }.role = "admin"
        f.rotate = true
        "left"
    }

    override suspend fun pending(familyId: String, memberId: String, notice: String) = r("pending") {
        val m = fams[familyId]?.members?.get(memberId) ?: fail(SyncError.Unauthorised)
        if (m.notice == null || m.notice != hash(notice)) fail(SyncError.Unauthorised)
        if (m.status != "active" || m.role != "admin") 0
        else fams[familyId]!!.requests.values.count { it.status == "pending" && it.expires > now() }
    }
}

/** A relay whose pull can be held up until the test lets it go (or cancels the sync waiting on it). */
class GateRelay(private val inner: FakeRelay) : Relay by inner {
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override suspend fun pull(c: Cred, since: Long, max: Int): RelayResult<List<RelayRow>> {
        gate?.await()
        return inner.pull(c, since, max)
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

class MemNotices : NoticeStore {
    var cred: NoticeCred? = null
    var pending = -1
    override fun update(cred: NoticeCred?, pending: Int) { this.cred = cred; this.pending = pending }
}

/** A phone: its book, its files and its engine, plus the write helpers the model would provide (stamp + dirty). */
class Phone(val name: String, relay: Relay, pageSize: Int = 500) {
    val book = MemBook(obj("v" to 1, "me" to obj("id" to "m-$name", "name" to name)))
    val files = MemFiles()
    val notices = MemNotices()
    val engine = SyncEngine(relay, book, files, Dispatchers.Unconfined, pageSize = pageSize, pushChunk = 3, notices = notices)
    val db get() = book.db
    val me get() = db.o("me").s("id")
    val view get() = engine.membership(db)

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

    /** A fresh invite from this phone (it must be in a family). */
    fun invite(): String = when (val r = runBlocking { engine.newInvite() }) {
        is InviteResult.Made -> r.text
        is InviteResult.Failed -> error("no invite: ${r.result}")
    }

    /** This phone asks to join with [invite]; [admin] syncs, sees the request and allows it; this phone syncs in. */
    fun joinVia(admin: Phone, invite: String = admin.invite()): SyncResult {
        val asked = runBlocking { engine.requestJoin(invite) }
        check(asked == SyncResult.JoinPending) { "request: $asked" }
        check(admin.sync() is SyncResult.Done)
        val req = admin.engine.requests.value.single { it.name == name }
        val ok = runBlocking { admin.engine.approve(req.id) }
        check(ok is SyncResult.Done) { "approve: $ok" }
        return sync()
    }
}

/** Pins Clock and moves it one second per tick, so every edit has a distinct, ordered updatedAt. */
object TestClock {
    var t: LocalDateTime = LocalDateTime.of(2026, 11, 11, 9, 0)
    fun start() { t = LocalDateTime.of(2026, 11, 11, 9, 0); Clock.fixed = t }
    fun tick() { t = t.plusSeconds(1); Clock.fixed = t }
    fun advance(hours: Long) { t = t.plusHours(hours); Clock.fixed = t }
    fun stop() { Clock.fixed = null }
}
