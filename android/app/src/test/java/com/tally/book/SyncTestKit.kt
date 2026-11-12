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
 * as the family lock makes them on the real relay. Files in transit as in the tally_files_in_transit migration: who is owed
 * each file (the then-active members but the uploader, or the askers), acknowledgements, 30 days, asks, the per-family cap.
 * The tally_hardening migration: the proof of work and the limits on new families (per [source] and relay-wide), each family's
 * byte budget and the relay's ceiling, 4 MiB pull pages, the daily download allowance, 300 epochs, box tidying, the reclaim
 * rules for the creator and admins, and members who are not admins seeing only their own invites.
 */
class FakeRelay : Relay {
    class Row(val kind: String, val blob: String, val seq: Long)
    class Member(val id: String, var hash: String, var notice: String?, var role: String, var status: String, var has: Int, var joined: Long)
    class InviteRow(val id: String, val by: String, val box: String, var used: Boolean, val expires: Long, val created: Long)
    class Req(val invite: String, val member: String, val hash: String, val notice: String?, val blob: String, var status: String, val at: Long, val expires: Long)
    class Fam(val id: String, var creator: String?, var epoch: Int = 1, var rotate: Boolean = false, var meta: String? = null) {
        val members = LinkedHashMap<String, Member>()
        val invites = LinkedHashMap<String, InviteRow>()
        val requests = LinkedHashMap<String, Req>()
        val boxes = HashMap<String, HashMap<Int, String>>()
        /** tally_replaced: (member id, secret hash) pairs a new phone replaced. */
        val replaced = HashSet<Pair<String, String>>()
    }

    val fams = HashMap<String, Fam>()
    val records = HashMap<String, LinkedHashMap<String, Row>>()
    val blobs = HashMap<String, HashMap<String, String>>()
    /** tally_blob's uploader and expires_at, per family and file id. */
    class BlobMeta(val uploader: String, var expires: Long)
    val blobMeta = HashMap<String, HashMap<String, BlobMeta>>()
    /** tally_blob_owed: per family, file id -> members still owed it. */
    val owed = HashMap<String, HashMap<String, MutableSet<String>>>()
    /** tally_file_ask: per family, file id -> (member -> when asked). */
    val asks = HashMap<String, HashMap<String, HashMap<String, Long>>>()
    /** Files in flight per family (50 MiB in SQL; a test may lower it). */
    var blobCap = SyncEngine.MAX_FAMILY_BLOBS.toLong()
    /** A broken relay that hands out these bytes instead of the stored ones. */
    var corruptBlob: ((String) -> String)? = null
    val acks = ArrayList<Pair<String, String>>()
    private var seq = 0L
    private var joinOrder = 0L

    /** Zero bits of proof of work a new family needs (20 in SQL; tests keep it small). */
    var powBits = 8
    /** The address the next calls come from (tally_source: a peppered hash; null = no usable header). */
    var source: String? = "phone"
    /** tally_create_log: (when, source). */
    val createLog = ArrayList<Pair<Long, String?>>()
    /** The relay's ceiling across all families (300 MiB in SQL). */
    var ceiling = 300L * 1024 * 1024
    /** The family byte budgets (5 MiB for a family of one, 20 MiB otherwise) and the daily download allowance. */
    var budgetOne = 5L * 1024 * 1024
    var budgetMany = 20L * 1024 * 1024
    var egressCap = 200L * 1024 * 1024
    /** Bytes in a pull page (4 MiB in SQL). */
    var pageBytes = 4L * 1024 * 1024
    /** Download bytes per family per day: family -> (day, bytes). */
    val egress = HashMap<String, Pair<Long, Long>>()
    private fun recordBytes(famId: String) = records[famId]?.values?.sumOf { it.blob.length.toLong() } ?: 0L
    private fun boxBytes(f: Fam) = f.boxes.values.sumOf { m -> m.values.sumOf { it.length.toLong() } }
    private fun held(): Long = fams.values.sumOf { recordBytes(it.id) + boxBytes(it) } + blobs.values.sumOf { b -> b.values.sumOf { it.length.toLong() } }
    fun budget(f: Fam) = if (f.members.size <= 1) budgetOne else budgetMany
    private fun spend(f: Fam, bytes: Long) {
        val day = now() / (24 * 3600_000L)
        val (d, b) = egress[f.id] ?: (day to 0L)
        val used = (if (d == day) b else 0L) + bytes
        if (used > egressCap) fail(SyncError.Limit)
        egress[f.id] = day to used
    }
    /** tally_box_tidy: boxes of members no longer active go; each member keeps its newest 20. */
    private fun tidyBoxes(f: Fam) {
        f.boxes.keys.toList().forEach { m -> if (f.members[m]?.status != "active") f.boxes.remove(m) }
        f.boxes.values.forEach { b -> b.keys.sortedDescending().drop(20).forEach { b.remove(it) } }
    }

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
    /** A hostile relay that hands out this sealed family name instead of the one it holds (an old one replayed). */
    var replayMeta: String? = null
    /** A hostile relay that ignores "used once" and accepts more requests for the same invite. */
    var ignoreUsed = false
    /** A hostile relay that rewrites what tally_join_check tells a joining phone (another member id, say). */
    var tamperJoinCheck: ((JoinCheck) -> JoinCheck)? = null
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
        if (m.hash != hash(c.secret)) {
            if ((m.id to hash(c.secret)) in f.replaced) fail(SyncError.Replaced)
            fail(SyncError.Unauthorised)
        }
        if (m.status == "removed") fail(SyncError.Removed)
        if (m.status != "active") fail(SyncError.Unauthorised)
        return f to m
    }
    private fun admin(c: Cred): Pair<Fam, Member> { val p = auth(c); if (p.second.role != "admin") fail(SyncError.NotAdmin); return p }
    private fun active(f: Fam) = f.members.values.filter { it.status == "active" }
    private val DAYS30 = 30 * 24 * 3600_000L

    /** tally_blob_sweep: files nobody is owed or past their 30 days go; asks older than 30 days go. */
    private fun sweep(famId: String) {
        val b = blobs[famId] ?: return
        val o = owed.getOrPut(famId) { HashMap() }; val m = blobMeta.getOrPut(famId) { HashMap() }
        for (id in b.keys.toList()) if (o[id].isNullOrEmpty() || (m[id]?.expires ?: 0) <= now()) { b.remove(id); o.remove(id); m.remove(id) }
        asks[famId]?.values?.forEach { it.values.removeIf { at -> at <= now() - DAYS30 } }
        asks[famId]?.values?.removeIf { it.isEmpty() }
    }
    fun blobBytes(famId: String): Long = blobs[famId]?.values?.sumOf { it.length.toLong() } ?: 0L
    /** The trigger on tally_member: someone no longer active is owed nothing and asks for nothing. */
    private fun memberGone(f: Fam, id: String) {
        owed[f.id]?.values?.forEach { it.remove(id) }
        asks[f.id]?.values?.forEach { it.remove(id) }
        sweep(f.id)
    }
    /** tally_ping's file part, with the clock as it stands. */
    fun ping() { blobs.keys.toList().forEach { sweep(it) } }
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
        tidyBoxes(f)
    }
    private fun newEpoch(f: Fam, caller: String, epoch: Int, boxes: List<BoxFor>) {
        if (epoch != f.epoch + 1) fail(SyncError.Stale)
        if (epoch > 300) fail(SyncError.Limit)
        if (boxes.none { it.member == caller }) fail(SyncError.BadData("caller box"))
        putBoxes(f, epoch, boxes)
        f.epoch = epoch; f.rotate = false
        f.requests.clear(); f.invites.clear()
    }

    override suspend fun createFamily(familyId: String, memberId: String, secretHash: String, noticeHash: String?, pow: String) = r("create") {
        if (!Regex("^[0-9]{1,20}$").matches(pow) || !SyncCrypto.powOk(familyId, memberId, pow, powBits)) fail(SyncError.BadData("pow"))
        val cur = fams[familyId]
        if (cur != null) {
            val m = cur.members[memberId]
            if (cur.creator == memberId && m != null && m.hash == secretHash && m.status == "active") return@r
            fail(SyncError.Unauthorised)
        }
        val t = now(); val hour = 3600_000L
        createLog.removeIf { it.first < t - 48 * hour }
        val src = source
        if (src != null && (createLog.count { it.second == src && it.first > t - hour } >= 5 || createLog.count { it.second == src && it.first > t - 24 * hour } >= 20)) fail(SyncError.Limit)
        if (createLog.count { it.first > t - hour } >= 100 || createLog.count { it.first > t - 24 * hour } >= 500) fail(SyncError.Limit)
        if (held() > ceiling) fail(SyncError.Limit)
        createLog.add(t to src)
        val f = Fam(familyId, memberId)
        f.members[memberId] = Member(memberId, secretHash, noticeHash, "admin", "active", 1, ++joinOrder)
        fams[familyId] = f
        blobs[familyId] = HashMap()
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
            f.invites.values.filter { !it.used && it.expires > now() && (me.role == "admin" || it.by == me.id) }.map { RelayInvite(it.id, it.by, it.expires) },
            tamperRequests?.invoke(reqs) ?: reqs,
            replayMeta ?: f.meta,
        )
    }

    override suspend fun push(c: Cred, epoch: Int, rows: List<RelayRow>): RelayResult<Int> {
        failPush.removeFirstOrNull()?.let { return RelayResult.Err(it) }
        val out = r("push") {
            val (f, _) = auth(c)
            if (epoch != f.epoch) fail(SyncError.Stale)
            val m = records.getOrPut(f.id) { LinkedHashMap() }
            val last = rows.associateBy { it.id }
            val delta = last.values.sumOf { it.blob.length.toLong() } - last.keys.sumOf { m[it]?.blob?.length?.toLong() ?: 0L }
            val count = m.size + last.keys.count { it !in m }
            if (count > 20_000) fail(SyncError.Limit)
            if (delta > 0 && (recordBytes(f.id) + delta + boxBytes(f) > budget(f) || held() + delta > ceiling)) fail(SyncError.Limit)
            rows.forEach { m[it.id] = Row(it.kind, it.blob, ++seq) }
            rows.size
        }
        if (out is RelayResult.Ok) onPush?.let { h -> onPush = null; h() }
        return out
    }

    override suspend fun pull(c: Cred, since: Long, max: Int): RelayResult<List<RelayRow>> {
        failPull.removeFirstOrNull()?.let { return RelayResult.Err(it) }
        return r("pull") {
            val (f, _) = auth(c)
            val page = (records[f.id] ?: emptyMap<String, Row>()).entries.filter { it.value.seq > since }.sortedBy { it.value.seq }
                .take(max.coerceIn(1, 500))
            var cum = 0L
            val out = page.filterIndexed { i, e -> cum += e.value.blob.length; i == 0 || cum <= pageBytes }
            spend(f, out.sumOf { it.value.blob.length.toLong() })
            out.map { RelayRow(it.key, it.value.kind, it.value.blob, it.value.seq) }
        }
    }

    override suspend fun blobPut(c: Cred, epoch: Int, id: String, data: String) = r("blobPut") {
        if (data.isEmpty() || data.length > 6 * 1024 * 1024) fail(SyncError.BadData("data"))
        val (f, me) = auth(c)
        if (epoch != f.epoch) fail(SyncError.Stale)
        sweep(f.id)
        if (held() + data.length > ceiling) fail(SyncError.Limit)
        val b = blobs.getOrPut(f.id) { HashMap() }; val o = owed.getOrPut(f.id) { HashMap() }
        val m = blobMeta.getOrPut(f.id) { HashMap() }; val a = asks.getOrPut(f.id) { HashMap() }
        // all or nothing, like the SQL transaction (the cap check comes last)
        val saved = Triple(b[id], o[id]?.toMutableSet(), m[id]); val savedAsks = a[id]?.let { HashMap(it) }
        val existed = id in b
        val askers = a[id]?.keys?.filter { it != me.id && f.members[it]?.status == "active" }.orEmpty()
        b[id] = data; m[id] = BlobMeta(me.id, now() + DAYS30)
        if (askers.isNotEmpty()) o.getOrPut(id) { HashSet() }.addAll(askers)
        else if (!existed) o[id] = active(f).map { it.id }.filter { it != me.id }.toMutableSet()
        a.remove(id)
        sweep(f.id)
        if (blobBytes(f.id) > blobCap) {
            if (saved.first != null) { b[id] = saved.first!!; o[id] = saved.second ?: HashSet(); m[id] = saved.third!! } else { b.remove(id); o.remove(id); m.remove(id) }
            if (savedAsks != null) a[id] = savedAsks
            fail(SyncError.Limit)
        }
        Unit
    }

    override suspend fun blobGet(c: Cred, id: String): RelayResult<String?> {
        blobGets++
        return r("blobGet") {
            val (f, _) = auth(c)
            val d = blobs[f.id]?.get(id)?.takeIf { (blobMeta[f.id]?.get(id)?.expires ?: 0) > now() }
            if (d != null) spend(f, d.length.toLong())
            if (d != null) corruptBlob?.invoke(d) ?: d else null
        }
    }

    override suspend fun blobAck(c: Cred, id: String) = r("blobAck") {
        val (f, me) = auth(c)
        owed[f.id]?.get(id)?.remove(me.id)
        asks[f.id]?.get(id)?.remove(me.id)
        acks.add(me.id to id)
        sweep(f.id)
        Unit
    }

    override suspend fun fileAsk(c: Cred, id: String) = r("fileAsk") {
        val (f, me) = auth(c)
        if (records[f.id]?.get(id)?.kind != "file") fail(SyncError.Refused)
        sweep(f.id)
        if (blobs[f.id]?.containsKey(id) == true) { owed.getOrPut(f.id) { HashMap() }.getOrPut(id) { HashSet() }.add(me.id); return@r "ready" }
        val a = asks.getOrPut(f.id) { HashMap() }
        if (a[id]?.containsKey(me.id) != true && a.values.sumOf { it.size } >= 500) fail(SyncError.Limit)
        a.getOrPut(id) { HashMap() }[me.id] = now()
        "asked"
    }

    override suspend fun files(c: Cred) = r("files") {
        val (f, me) = auth(c)
        sweep(f.id)
        val mine = owed[f.id].orEmpty().filter { me.id in it.value }.keys
        val others = asks[f.id].orEmpty().filter { (_, who) -> who.keys.any { it != me.id && f.members[it]?.status == "active" } }.keys
        RelayFiles(blobs[f.id].orEmpty().keys.filter { it in mine }.sortedBy { blobMeta[f.id]?.get(it)?.expires }, others.sorted())
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

    /** tally_join_check: any member with this secret (approved / removed, with its id), else the request's own status. */
    override suspend fun joinCheck(familyId: String, inviteId: String, memberId: String, secret: String) = r("joinCheck") {
        val f = fams[familyId] ?: fail(SyncError.InviteGone)
        val m = f.members.values.firstOrNull { it.hash == hash(secret) && (it.status == "active" || it.status == "removed") }
        val out = if (m != null) JoinCheck(if (m.status == "active") "approved" else "removed", m.id)
        else {
            val q = f.requests.values.firstOrNull { it.invite == inviteId && it.member == memberId && it.hash == hash(secret) && it.expires > now() }
                ?: fail(SyncError.InviteGone)
            JoinCheck(q.status, memberId)
        }
        tamperJoinCheck?.invoke(out) ?: out
    }

    /** tally_member_reclaim: an admin; an open request; an active target; a box for the target; then the new epoch, all or nothing. */
    override suspend fun reclaim(c: Cred, inviteId: String, target: String, epoch: Int, boxes: List<BoxFor>) = r("reclaim") {
        val (f, me) = admin(c)
        val q = f.requests.values.firstOrNull { it.invite == inviteId && it.status == "pending" && it.expires > now() } ?: fail(SyncError.InviteGone)
        val t = f.members[target]?.takeIf { it.status == "active" } ?: fail(SyncError.Refused)
        // the creator's place only to the creator's own new phone; another admin's only when the caller did not make the invite
        if (f.creator != null && f.creator == target && target != me.id) fail(SyncError.Refused)
        if (t.role == "admin" && target != me.id) { val maker = f.invites[inviteId]?.by; if (maker == null || maker == me.id) fail(SyncError.Refused) }
        if (boxes.none { it.member == target }) fail(SyncError.BadData("target box"))
        val saved = Triple(t.hash, t.notice, t.has); val savedBoxes = f.boxes[target]?.let { HashMap(it) }
        val rep = target to t.hash
        val had = rep in f.replaced
        f.replaced.add(rep)
        f.boxes.remove(target)
        t.hash = q.hash; t.notice = q.notice; t.has = 0
        try { newEpoch(f, me.id, epoch, boxes) } catch (x: E) {
            t.hash = saved.first; t.notice = saved.second; t.has = saved.third
            if (savedBoxes != null) f.boxes[target] = savedBoxes
            if (!had) f.replaced.remove(rep)
            throw x
        }
        Unit
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
        tidyBoxes(f)
        f.requests.values.removeIf { it.invite == inviteId }
        f.invites.remove(inviteId)
        Unit
    }

    /** tally_family_meta_set: admins only, the newest epoch, at most 4096 characters. */
    override suspend fun setMeta(c: Cred, epoch: Int, meta: String) = r("setMeta") {
        val (f, _) = admin(c)
        if (epoch != f.epoch) fail(SyncError.Stale)
        if (meta.isEmpty() || meta.length > 4096) fail(SyncError.BadData("meta"))
        f.meta = meta
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
        memberGone(f, target)
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
        memberGone(f, me.id)
        val mine = f.invites.values.filter { it.by == me.id }.map { it.id }
        mine.forEach { f.invites.remove(it); f.requests.remove(it) }
        if (f.creator == me.id) f.creator = null
        if (active(f).isEmpty()) {
            fams.remove(f.id); records.remove(f.id); blobs.remove(f.id); owed.remove(f.id); asks.remove(f.id); blobMeta.remove(f.id)
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
    /** A phone whose storage damages what it writes (the read-back check must catch it). */
    var corruptWrites = false
    override suspend fun has(id: String) = id in bytes
    override suspend fun read(id: String) = bytes[id]
    override suspend fun write(id: String, bytes: ByteArray, mime: String) {
        this.bytes[id] = if (corruptWrites) bytes.copyOf().also { if (it.isNotEmpty()) it[0] = (it[0] + 1).toByte() } else bytes
    }
    override suspend fun delete(id: String) { bytes.remove(id) }
}

class MemNotices : NoticeStore {
    var cred: NoticeCred? = null
    var pending = -1
    override fun update(cred: NoticeCred?, pending: Int) { this.cred = cred; this.pending = pending }
}

/** A phone: its book, its files and its engine, plus the write helpers the model would provide (stamp + dirty). */
class Phone(val name: String, relay: Relay, pageSize: Int = 500, powBits: Int = 8) {
    val book = MemBook(obj("v" to 1, "me" to obj("id" to "m-$name", "name" to name)))
    val files = MemFiles()
    val notices = MemNotices()
    val engine = SyncEngine(relay, book, files, Dispatchers.Unconfined, pageSize = pageSize, pushChunk = 3, notices = notices, powBits = powBits)
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

    /** Answers "Share your existing entries?" after joining (true: Share all). */
    fun share(yes: Boolean = true) = runBlocking { engine.answerShare(yes) }

    /** The shared part of the book in a comparable form: the four lists sorted by id, local-only fields dropped. */
    fun shared(): String = JsonCodec.stringify(obj(*listOf("loans", "payments", "files", "members").map { k ->
        k to ArrayList<Any?>(db.objs(k).sortedBy { it.s("id") }.map { r -> r.copyObj().also { it.remove("enc"); it.remove("sig") } })
    }.toTypedArray()))

    /** A fresh invite from this phone (it must be in a family). */
    fun invite(): String = when (val r = runBlocking { engine.newInvite() }) {
        is InviteResult.Made -> r.text
        is InviteResult.Failed -> error("no invite: ${r.result}")
    }

    /** This phone, saying it was already in the family, asks with [invite]; [admin] lets it in as [target]; this phone syncs in. */
    fun comeBackVia(admin: Phone, target: String, invite: String = admin.invite()): SyncResult {
        val asked = runBlocking { engine.requestJoin(invite, back = true) }
        check(asked == SyncResult.JoinPending) { "request: $asked" }
        check(admin.sync() is SyncResult.Done)
        val req = admin.engine.requests.value.single { it.back }
        val ok = runBlocking { admin.engine.approveAs(req.id, target) }
        check(ok is SyncResult.Done || (target == admin.me && ok == SyncResult.Replaced)) { "approve: $ok" }
        return sync()
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
