package com.tally.book.sync

import com.tally.book.core.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Family sync and membership (docs/DESIGN.md, "Sync" and "Membership").
 *
 * One refresh:
 *   0. a leave the relay has not heard about yet is sent; a join request waiting for an admin is checked (and, once
 *      approved, the keys it was given are checked and the family entered);
 *   1. the family's state: role, members, epoch, and any key boxes for this phone (new keys after a rotation it missed);
 *      a phone that was removed hears it here (or from any later call), forgets the family and keeps its records;
 *   2. pull every row with seq > sync.cursor, page by page, each page opened (with the key of the epoch it was sealed under),
 *      merged and saved together with the new cursor;
 *   3. an admin's phone rotates the key if someone left, gives keys to members who lack the newest one, and checks join
 *      requests (any that do not check out are turned down);
 *   4. push every dirty record under the newest key (private ones only as tombstones, and only if the relay holds them);
 *      a phone that does not hold the newest key yet pushes nothing;
 *   5. upload the sealed bytes of each file in sync.blobsUp; download the bytes of any live file that has none here.
 * Only one sync, create, join, leave or membership change runs at a time. Network work happens in the Relay (on IO),
 * sealing on [cpu], and every read or write of the book goes through [SyncBook].
 */

/** Where the engine reads and writes the book. The app's is [LedgerBook]; tests use an in-memory one. */
interface SyncBook {
    suspend fun <T> read(block: (Obj) -> T): T
    /** Applies [block] and saves the book; throws [SyncBook.SaveFailed] when it cannot be saved. */
    suspend fun <T> edit(block: (Obj) -> T): T
    class SaveFailed : Exception("the book could not be saved")
}

/** This phone's copies of document bytes (data/Files.kt in the app). */
interface LocalFiles {
    suspend fun has(id: String): Boolean
    /** The original bytes, checked against the record's SHA-256, or null. */
    suspend fun read(id: String): ByteArray?
    /** Stores bytes that were already checked against the record's SHA-256. */
    suspend fun write(id: String, bytes: ByteArray, mime: String)
    suspend fun delete(id: String)
}

/** What the background check needs to ask the relay "any join requests?", kept outside the (possibly sealed) book. */
data class NoticeCred(val familyId: String, val memberId: String, val notice: String, val admin: Boolean)

/** Where the engine leaves [NoticeCred] (the app: DevicePrefs, plus scheduling the background check). */
interface NoticeStore {
    /** [cred] null: no family (or not one this phone can check). [pending]: requests the app has just seen (so the background check does not repeat them). */
    fun update(cred: NoticeCred?, pending: Int)
    object None : NoticeStore { override fun update(cred: NoticeCred?, pending: Int) {} }
}

sealed class SyncResult {
    /** [waitingForKey]: the family has a newer key that has not reached this phone yet, so nothing was pushed. */
    data class Done(val pulled: Int, val pushed: Int, val filesUp: Int, val filesDown: Int, val skipped: Int, val waitingForKey: Boolean = false) : SyncResult()
    data class Failed(val error: SyncError) : SyncResult()
    /** No family on this phone: nothing to sync. */
    object NoFamily : SyncResult() { override fun toString() = "NoFamily" }
    /** Another sync, create, join or leave is running. */
    object Busy : SyncResult() { override fun toString() = "Busy" }
    /** Create or join while this phone is already in a family (or waiting to get into one): leave it first. */
    object AlreadyInFamily : SyncResult() { override fun toString() = "AlreadyInFamily" }
    /** The invite text (or family name) is malformed. */
    object BadInvite : SyncResult() { override fun toString() = "BadInvite" }
    /** The invite is from 0.1.0/0.2.0, which carried the family key: no longer accepted. */
    object OldInvite : SyncResult() { override fun toString() = "OldInvite" }
    /** The join request is with the family; an admin has not decided yet. */
    object JoinPending : SyncResult() { override fun toString() = "JoinPending" }
    /** An admin did not let this phone in (or the request expired). The family state is gone; see [MembershipView.gone]. */
    object JoinDenied : SyncResult() { override fun toString() = "JoinDenied" }
    /** An admin removed this phone's member. The family is forgotten here, every record kept; see [MembershipView.gone]. */
    object Removed : SyncResult() { override fun toString() = "Removed" }
    /** The book has a family made by 0.1.0/0.2.0, which the relay no longer serves: leave it, then start or join again. */
    object OldFamily : SyncResult() { override fun toString() = "OldFamily" }
}

/** A new invite: show [text] as a QR code / share it; it works once, until [expiresAt]. */
sealed class InviteResult {
    class Made(val text: String, val inviteId: String, val expiresAt: Long) : InviteResult() { override fun toString() = "Made($inviteId)" }
    data class Failed(val result: SyncResult) : InviteResult()
}

/** For the UI: whether a sync is running, how the last one ended, and when the book last synced (epoch ms, 0 = never). */
data class SyncState(val running: Boolean = false, val last: SyncResult? = null, val lastAt: Long = 0)

class SyncEngine(
    private val relay: Relay,
    private val book: SyncBook,
    private val files: LocalFiles,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val pageSize: Int = 500,
    private val pushChunk: Int = 200,
    private val notices: NoticeStore = NoticeStore.None,
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    /** Join requests waiting for this phone (an admin's) to decide, checked; empty for a member. Refreshed by every sync. */
    private val _requests = MutableStateFlow<List<JoinRequest>>(emptyList())
    val requests: StateFlow<List<JoinRequest>> = _requests
    private val checked = HashMap<String, Membership.Checked>()

    private class Stop(val error: SyncError) : Exception()

    private fun <T> RelayResult<T>.orStop(): T = when (this) {
        is RelayResult.Ok -> value
        is RelayResult.Err -> throw Stop(error)
    }

    /** Everything about membership the screens show (members, roles, invites, a pending join, why the family went). */
    fun membership(db: Obj): MembershipView = MembershipView.of(db)

    // ---------- create ----------

    /** Makes a new family with this phone as its creator and first admin, registers it with the relay, then syncs. */
    suspend fun createFamily(name: String): SyncResult = exclusive {
        val nm = Invite.cleanName(name) ?: return@exclusive SyncResult.BadInvite
        if (book.read { Membership.hasFamily(it) || it.o("joining") != null }) return@exclusive SyncResult.AlreadyInFamily
        val meId = book.edit { ensureMe(it) }
        val famId = Invite.newFamilyId()
        val (identity, secret, notice) = withContext(cpu) { Triple(SyncCrypto.newIdentity(), Membership.newCredential(), Membership.newCredential()) }
        val key = SyncCrypto.randomBytes(32)
        relay.createFamily(famId, meId, SyncCrypto.tokenHash(secret), SyncCrypto.tokenHash(notice)).orStop()
        book.edit { db ->
            Membership.enter(db, famId, nm, meId, Membership.meObj(secret, notice, identity), mapOf(1 to key), "admin", emptyMap(), Clock.millis())
        }
        runSync()
    }

    private fun ensureMe(db: Obj): String {
        val me = db.oo("me")
        if (me.s("id").isEmpty()) me["id"] = uid("m")
        return me.s("id")
    }

    // ---------- joining ----------

    /** Asks to join with the invite in [inviteText] (a whole shared message is fine). */
    suspend fun requestJoin(inviteText: String): SyncResult {
        val inv = Invite.find(inviteText) ?: return if (Invite.isOld(inviteText)) SyncResult.OldInvite else SyncResult.BadInvite
        return requestJoin(inv)
    }

    /**
     * Sends a join request for [invite]: this phone's new credentials (hashes only), and its name and public key sealed with
     * the invite's secret. Nothing of the family is on this phone until an admin allows it; then the next sync (or [checkJoin])
     * brings the keys in. Returns [SyncResult.JoinPending], or why not.
     */
    suspend fun requestJoin(invite: Invite): SyncResult = exclusive {
        if (book.read { Membership.hasFamily(it) || it.o("joining") != null }) return@exclusive SyncResult.AlreadyInFamily
        if (invite.expired(Clock.millis())) return@exclusive SyncResult.Failed(SyncError.InviteGone)
        val (meId, myName) = book.edit { db -> ensureMe(db) to (Invite.inviterName(db.o("me").s("name")) ?: "?") }
        val (identity, secret, notice) = withContext(cpu) { Triple(SyncCrypto.newIdentity(), Membership.newCredential(), Membership.newCredential()) }
        val blob = withContext(cpu) { Membership.sealRequest(invite.secret, invite.familyId, invite.inviteId, meId, myName, identity.publicSpki) }
        val expires = relay.joinRequest(invite.familyId, invite.inviteId, meId, SyncCrypto.tokenHash(secret), SyncCrypto.tokenHash(notice), blob).orStop()
        book.edit { db ->
            db["joining"] = obj(
                "fam" to invite.familyId, "name" to invite.name, "inviter" to invite.inviter, "invite" to invite.inviteId,
                "s" to Invite.b64url(invite.secret), "me" to Membership.meObj(secret, notice, identity),
                "at" to Clock.millis().toDouble(), "expires" to expires.toDouble(),
            )
            db.remove("familyGone")
        }
        SyncResult.JoinPending
    }

    /** Asks the relay whether the waiting join request was decided; enters the family if it was allowed. */
    suspend fun checkJoin(): SyncResult = exclusive { joinStep() ?: runSync() }

    /** Withdraws a waiting join request (best effort on the relay) and forgets it here. */
    suspend fun cancelJoin(): Boolean = withLockOrFalse {
        val j = book.read { it.o("joining")?.copyObj() } ?: return@withLockOrFalse true
        relay.joinCancel(j.s("fam"), j.s("invite"), book.read { it.o("me").s("id") }, j.o("me").s("secret"))
        book.edit { it.remove("joining") }
        true
    }

    /** Null when there is no waiting request or it was just approved (the family is now in the book); otherwise the outcome. */
    private suspend fun joinStep(): SyncResult? {
        val j = book.read { it.o("joining")?.copyObj() } ?: return null
        val meId = book.read { it.o("me").s("id") }
        val fam = j.s("fam"); val inviteId = j.s("invite")
        val me = j.o("me") ?: return null
        val status = when (val r = relay.joinStatus(fam, inviteId, meId, me.s("secret"))) {
            is RelayResult.Ok -> r.value
            is RelayResult.Err -> if (r.error == SyncError.InviteGone) "gone" else throw Stop(r.error)
        }
        when (status) {
            "pending" -> return SyncResult.JoinPending
            "denied", "gone" -> { book.edit { Membership.forget(it, if (status == "denied") "denied" else "expired", Clock.millis()) }; return SyncResult.JoinDenied }
            "removed" -> { book.edit { Membership.forget(it, "removed", Clock.millis()) }; return SyncResult.Removed }
            "approved" -> {}
            else -> throw Stop(SyncError.BadData("join status"))
        }
        val secret = Invite.unb64url(j.s("s")) ?: throw Stop(SyncError.BadData("joining"))
        val identity = SyncCrypto.Identity(Invite.unb64url(me.s("pub"))!!, Invite.unb64url(me.s("priv"))!!)
        val st = relay.state(Cred(fam, meId, me.s("secret")), 0).orStop()
        // the grant: from = 0, the invite's MAC, every key up to the epoch it was made at
        val grant = withContext(cpu) {
            st.boxes.sortedByDescending { it.epoch }.firstNotNullOfOrNull { row ->
                Membership.openBox(fam, meId, identity, row) { from, inv -> if (from == 0 && inv == inviteId) Invite.grantKey(secret) else null }
            }
        } ?: throw Stop(SyncError.Unverified)          // a relay that forged the keys: stay waiting, nothing entered
        book.edit { db ->
            Membership.enter(db, fam, j.s("name"), grant.creator, me, grant.keys, st.myRole, grant.pins, Clock.millis(), inviteId)
            Membership.applyRoster(db, st)
        }
        return null
    }

    // ---------- leaving ----------

    /**
     * Leaves the family on this phone: tells the relay (the key is then rotated by an admin; with nobody left the family is
     * deleted from the relay), forgets the family and the sync state, keeps every record. Without internet it leaves here at
     * once and tells the relay on a later sync. A waiting join request is withdrawn instead. False only if the book could not be saved.
     */
    suspend fun leaveFamily(): Boolean = withLockOrFalse {
        val (fam, joining) = book.read { Membership.famOf(it) to (it.o("joining") != null) }
        if (joining && fam == null) {
            val j = book.read { it.o("joining")!!.copyObj() }
            relay.joinCancel(j.s("fam"), j.s("invite"), book.read { it.o("me").s("id") }, j.o("me").s("secret"))
        }
        var later = false
        if (fam != null) {
            val r = relay.leave(fam.cred)
            later = r is RelayResult.Err && (r.error == SyncError.Offline || r.error is SyncError.Server || r.error == SyncError.NotConfigured)
        }
        book.edit { db ->
            Membership.forget(db, null, Clock.millis())
            if (later && fam != null) db["leaving"] = obj("fam" to fam.id, "member" to fam.myId, "secret" to fam.cred.secret)
        }
        notices.update(null, 0)
        _requests.value = emptyList(); checked.clear()
        _state.value = SyncState()
        true
    }

    /** Clears the one-time "you were removed / not let in" notice ([MembershipView.gone]). */
    suspend fun clearGone() { runCatching { book.edit { it.remove("familyGone") } } }

    private suspend fun sendPendingLeave() {
        val l = book.read { it.o("leaving")?.copyObj() } ?: return
        val r = relay.leave(Cred(l.s("fam"), l.s("member"), l.s("secret")))
        if (r is RelayResult.Err && (r.error == SyncError.Offline || r.error is SyncError.Server)) return
        book.edit { it.remove("leaving") }
    }

    // ---------- invites ----------

    /**
     * A fresh one-time invite for this family (any member). The text is for the Invite sheet only: show it as a QR code or hand
     * it to the share sheet, never store it. The invite's secret goes to the relay only sealed under the family key.
     */
    suspend fun newInvite(): InviteResult {
        var made: InviteResult.Made? = null
        val r = exclusive {
            var fam = book.read { Membership.famOf(it) } ?: return@exclusive noFamily()
            if (fam.waitingForKey) { refreshState(fam); fam = book.read { Membership.famOf(it) }!! }
            if (fam.waitingForKey) return@exclusive SyncResult.Failed(SyncError.Stale)
            val secret = Invite.newSecret()
            val myName = book.read { it.o("me").s("name") }
            val exp = retryStale(fam) { f ->
                val box = withContext(cpu) { Membership.sealInviteBox(f, secret, Clock.millis() + DAY) }
                relay.inviteCreate(f.cred, Invite.idOf(secret), box)
            }
            made = InviteResult.Made(Invite.make(fam.id, secret, exp, fam.name.ifEmpty { "Family" }, myName), Invite.idOf(secret), exp)
            book.edit { db -> db.o("family")?.aa("invites")?.add(obj("id" to Invite.idOf(secret), "by" to fam.myId, "expires" to exp.toDouble())) }
            SyncResult.Done(0, 0, 0, 0, 0)
        }
        return made ?: InviteResult.Failed(r)
    }

    /** Cancels an invite that has not been used (its maker, or an admin). */
    suspend fun cancelInvite(inviteId: String): SyncResult = exclusive {
        val fam = book.read { Membership.famOf(it) } ?: return@exclusive noFamily()
        relay.inviteCancel(fam.cred, inviteId).orStop()
        book.edit { db -> db.o("family")?.a("invites")?.removeAll { it.asObj()?.s("id") == inviteId } }
        refresh()
    }

    // ---------- admin: requests, roles, removing ----------

    /** Lets the person who asked with invite [requestId] in: their phone gets the family keys sealed to its own public key. */
    suspend fun approve(requestId: String): SyncResult = exclusive {
        val c = checked[requestId] ?: return@exclusive SyncResult.Failed(SyncError.InviteGone)
        var fam = book.read { Membership.famOf(it) } ?: return@exclusive noFamily()
        if (!fam.admin) return@exclusive SyncResult.Failed(SyncError.NotAdmin)
        retryStale(fam) { f ->
            fam = f
            if (f.waitingForKey) return@retryStale RelayResult.Err(SyncError.Stale)
            val box = withContext(cpu) { Membership.grant(f, c) } ?: return@retryStale RelayResult.Err(SyncError.Stale)
            relay.decide(f.cred, requestId, true, f.relayEpoch, box)
        }
        val now = Clock.millis()
        book.edit { db ->
            Membership.pin(db, c.request.memberId, c.pub, force = true)
            db.o("family")!!.oo("usedInvites")[requestId] = true
            // the new member's record, with the public key this phone checked, so every phone pins the right one
            val list = db.aa("members")
            val i = list.indexOfFirst { it.asObj()?.s("id") == c.request.memberId }
            val rec = obj("id" to c.request.memberId, "name" to c.name, "pub" to Invite.b64url(c.pub), "invite" to requestId,
                "at" to now.toDouble(), "updatedAt" to now.toDouble(), "updatedBy" to fam.myId)
            if (i >= 0) list[i] = rec else list.add(rec)
            SyncModel.dirty(db)[c.request.memberId] = true
        }
        dropRequest(requestId)
        runSync()
    }

    /** Turns a join request down: nothing is shared, and the asking phone is told. */
    suspend fun deny(requestId: String): SyncResult = exclusive {
        val fam = book.read { Membership.famOf(it) } ?: return@exclusive noFamily()
        if (!fam.admin) return@exclusive SyncResult.Failed(SyncError.NotAdmin)
        relay.decide(fam.cred, requestId, false, fam.relayEpoch, null).orStop()
        book.edit { db -> db.o("family")!!.oo("usedInvites")[requestId] = true }
        dropRequest(requestId)
        refresh()
    }

    /** Makes [memberId] an admin ([admin] true) or takes the role away. The relay refuses the last admin and the creator (by others). */
    suspend fun setAdmin(memberId: String, admin: Boolean): SyncResult = exclusive {
        val fam = book.read { Membership.famOf(it) } ?: return@exclusive noFamily()
        relay.setRole(fam.cred, memberId, admin).orStop()
        refresh()
    }

    /**
     * Removes [memberId] (an admin's phone): in one relay transaction the member is shut out and the family moves to a new key,
     * sealed to every remaining member this phone has a pinned public key for (others get it from the next admin sync). From
     * then on nothing written can be read with any key the removed member held. What their phone already has stays there.
     */
    suspend fun removeMember(memberId: String): SyncResult = exclusive {
        var fam = book.read { Membership.famOf(it) } ?: return@exclusive noFamily()
        if (!fam.admin) return@exclusive SyncResult.Failed(SyncError.NotAdmin)
        if (memberId == fam.myId) return@exclusive SyncResult.Failed(SyncError.Refused)
        var newKey: Pair<Int, ByteArray>? = null
        attempts@ for (attempt in 1..3) {
            val st = refreshState(fam)
            fam = book.read { Membership.famOf(it) }!!
            if (fam.waitingForKey) throw Stop(SyncError.Stale)
            val e = fam.relayEpoch + 1
            val key = SyncCrypto.randomBytes(32)
            val boxes = withContext(cpu) { rotationBoxes(fam, st.members.filter { it.id != memberId }, e, key) }
            when (val r = relay.remove(fam.cred, memberId, e, boxes)) {
                is RelayResult.Ok -> { newKey = e to key; break@attempts }
                is RelayResult.Err -> if (r.error != SyncError.Stale || attempt == 3) throw Stop(r.error)
            }
        }
        val (e, key) = newKey!!
        book.edit { db ->
            Membership.addKeys(db, mapOf(e to key))
            db.o("family")!!["relayEpoch"] = e
            markLeft(db, memberId, fam.myId)
        }
        runSync()
    }

    /** Boxes for a new epoch [e] with [key]: one for each of [members] whose public key is pinned here (always this phone's own). */
    private fun rotationBoxes(fam: Membership.Fam, members: List<RelayMember>, e: Int, key: ByteArray): List<BoxFor> =
        members.mapNotNull { m ->
            val pub = if (m.id == fam.myId) fam.identity.publicSpki else fam.pins[m.id] ?: return@mapNotNull null
            val has = if (m.id == fam.myId) fam.newest else minOf(m.has, fam.newest)
            Membership.boxFor(fam, m.id, pub, has, e, mapOf(e to key))?.let { BoxFor(m.id, it) }
        }

    private fun markLeft(db: Obj, memberId: String, by: String) {
        val rec = db.objs("members").firstOrNull { it.s("id") == memberId } ?: return
        rec["left"] = true; rec["updatedAt"] = Clock.millis().toDouble(); rec["updatedBy"] = by
        SyncModel.dirty(db)[memberId] = true
    }

    private fun dropRequest(id: String) {
        checked.remove(id)
        _requests.value = _requests.value.filter { it.id != id }
    }

    // ---------- restore support ----------

    /** Runs [block] (a restore replacing the book) once no sync, create, join or leave is running, and with none starting meanwhile. */
    suspend fun <T> paused(block: suspend () -> T): T = lock.withLock { block() }

    /** Forgets how the last sync ended (the book it was about has been replaced). */
    fun resetState() { _state.value = SyncState(); _requests.value = emptyList(); checked.clear() }

    // ---------- one refresh ----------

    /** One refresh. Returns [SyncResult.Busy] at once if another is running. */
    suspend fun sync(): SyncResult = exclusive { runSync() }

    /** Downloads one file's bytes now (opening a document whose bytes have not arrived yet). True when they are here. */
    suspend fun fetchFile(id: String): Boolean {
        if (files.has(id)) return true
        val (fam, want) = book.read { db -> Membership.famOf(db) to SyncModel.liveFiles(db).firstOrNull { it.id == id } }
        if (fam == null || want == null) return false
        return runCatching { download(fam, want) == 1 }.getOrDefault(false)
    }

    private suspend fun withLockOrFalse(block: suspend () -> Boolean): Boolean {
        lock.lock()
        return try { block() } catch (e: Stop) { false } catch (e: SyncBook.SaveFailed) { false } finally { lock.unlock() }
    }

    private suspend fun exclusive(block: suspend () -> SyncResult): SyncResult {
        if (!lock.tryLock()) return SyncResult.Busy
        _state.value = _state.value.copy(running = true)
        val result = try {
            block()
        } catch (e: Stop) {
            if (e.error == SyncError.Removed) gone() else SyncResult.Failed(e.error)
        } catch (e: SyncBook.SaveFailed) {
            SyncResult.Failed(SyncError.Storage)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // the caller went away mid-sync: do not leave the UI showing "Syncing…" for ever
            _state.value = _state.value.copy(running = false)
            throw e
        } finally {
            lock.unlock()
        }
        val lastAt = runCatching { book.read { it.o("sync").num("lastAt").toLong() } }.getOrDefault(0L)
        _state.value = SyncState(running = false, last = result, lastAt = lastAt)
        return result
    }

    /** This phone's member was removed: forget the family, keep the records, say so once. */
    private suspend fun gone(): SyncResult {
        runCatching { book.edit { db -> if (Membership.hasFamily(db) || db.o("joining") != null) Membership.forget(db, "removed", Clock.millis()) } }
        notices.update(null, 0)
        _requests.value = emptyList(); checked.clear()
        return SyncResult.Removed
    }

    private suspend fun noFamily(): SyncResult = if (book.read { Membership.isOld(it) }) SyncResult.OldFamily else SyncResult.NoFamily

    /** Runs [call] with the current family; on Stale, refreshes the family's state and tries again (at most 3 times). */
    private suspend fun <T> retryStale(start: Membership.Fam, call: suspend (Membership.Fam) -> RelayResult<T>): T {
        var fam = start
        for (attempt in 1..3) {
            when (val r = call(fam)) {
                is RelayResult.Ok -> return r.value
                is RelayResult.Err -> {
                    if (r.error != SyncError.Stale || attempt == 3) throw Stop(r.error)
                    refreshState(fam)
                    fam = book.read { Membership.famOf(it) } ?: throw Stop(SyncError.Stale)
                }
            }
        }
        throw Stop(SyncError.Stale)
    }

    /** The relay's view of the family, applied to the book: new keys from this phone's boxes, roles, members. */
    private suspend fun refreshState(fam: Membership.Fam): RelayState {
        val st = relay.state(fam.cred, fam.newest).orStop()
        val got = withContext(cpu) { openBoxes(fam, st.boxes) }
        book.edit { db ->
            checkFamily(db, fam)
            if (got.isNotEmpty()) Membership.addKeys(db, got)
            Membership.applyRoster(db, st)
        }
        return st
    }

    /** The keys in [rows] this phone can open and check, in epoch order: each box is proved with a key held before it. */
    private fun openBoxes(fam: Membership.Fam, rows: List<RelayBox>): Map<Int, ByteArray> {
        val keys = HashMap(fam.keys)
        val got = HashMap<Int, ByteArray>()
        var progress = true
        val left = rows.filter { it.epoch > fam.newest }.sortedBy { it.epoch }.toMutableList()
        while (progress && left.isNotEmpty()) {
            progress = false
            val it = left.iterator()
            while (it.hasNext()) {
                val row = it.next()
                if (keys.containsKey(row.epoch)) { it.remove(); continue }
                val box = Membership.openBox(fam.id, fam.myId, fam.identity, row) { from, _ -> keys[from]?.let { k -> Membership.boxAuthKey(k) } }
                    ?: continue
                box.keys.forEach { (e, k) -> if (!keys.containsKey(e)) { keys[e] = k; got[e] = k } }
                it.remove(); progress = true
            }
        }
        return got
    }

    /** A sync without the push and the files: the family's state, then the admin's work. For after a membership change. */
    private suspend fun refresh(): SyncResult = runSync()

    private suspend fun runSync(): SyncResult {
        sendPendingLeave()
        joinStep()?.let { return it }
        if (book.read { Membership.isOld(it) }) return SyncResult.OldFamily
        var fam = book.read { Membership.famOf(it) } ?: return SyncResult.NoFamily

        // 1. the family's state and any new keys
        val st = refreshState(fam)
        fam = book.read { Membership.famOf(it) }!!

        // 2. pull
        var pulled = 0; var skipped = 0
        var cursor = book.read { SyncModel.cursor(it) }
        while (true) {
            val rows = relay.pull(fam.cred, cursor, pageSize).orStop()
            if (rows.isEmpty()) break
            val f = fam
            val opened = withContext(cpu) { rows.map { open(f, it) } }
            val next = maxOf(cursor, rows.maxOf { it.seq })
            val outcome = book.edit { db ->
                checkFamily(db, f)
                val o = SyncModel.mergeRemote(db, opened.filterNotNull())
                Membership.pinFromRecords(db)
                SyncModel.syncOf(db)["cursor"] = next.toDouble()
                o
            }
            outcome.filesRemoved.forEach { runCatching { files.delete(it) } }
            cursor = next; pulled += rows.size; skipped += opened.count { it == null }
            if (rows.size < pageSize) break
        }
        fam = book.read { Membership.famOf(it) }!!

        // 3. an admin's work: rotate after a leave, keys for members who lack the newest, join requests
        if (fam.admin && !fam.waitingForKey) {
            var open = st.requests
            if (st.rotate) {
                val e = fam.relayEpoch + 1
                val key = SyncCrypto.randomBytes(32)
                val boxes = withContext(cpu) { rotationBoxes(fam, st.members, e, key) }
                when (val r = relay.rotate(fam.cred, e, boxes)) {
                    is RelayResult.Ok -> book.edit { db -> Membership.addKeys(db, mapOf(e to key)); db.o("family")!!["relayEpoch"] = e }
                    is RelayResult.Err -> if (r.error != SyncError.Stale) throw Stop(r.error)   // another admin rotated first
                }
                open = refreshState(fam).requests        // a rotation drops every open request and invite
                fam = book.read { Membership.famOf(it) }!!
            } else {
                val lacking = st.members.filter { it.id != fam.myId && it.has < fam.relayEpoch && fam.pins.containsKey(it.id) }
                if (lacking.isNotEmpty()) {
                    val f = fam
                    val boxes = withContext(cpu) { lacking.mapNotNull { m -> Membership.boxFor(f, m.id, f.pins[m.id]!!, m.has, f.relayEpoch)?.let { BoxFor(m.id, it) } } }
                    if (boxes.isNotEmpty()) when (val r = relay.boxPut(fam.cred, fam.relayEpoch, boxes)) {
                        is RelayResult.Ok -> {}
                        is RelayResult.Err -> if (r.error != SyncError.Stale) throw Stop(r.error)
                    }
                }
            }
            checkRequests(fam, open)
        } else {
            _requests.value = emptyList(); checked.clear()
        }

        // 4. push, under the newest key, unless this phone does not hold it yet
        var pushed = 0
        var filesUp = 0
        val waiting = fam.waitingForKey
        if (!waiting) {
            val f = fam
            val items = book.read { SyncModel.collectPush(it) }
            val nothing = items.filter { !it.send }
            if (nothing.isNotEmpty()) book.edit { db -> checkFamily(db, f); SyncModel.afterPush(db, nothing) }
            val enc = f.encKey(f.relayEpoch)!!
            val sealed = withContext(cpu) {
                items.filter { it.send }.map { it to SyncCrypto.sealE(enc, f.relayEpoch, it.json!!.toByteArray(Charsets.UTF_8), SyncCrypto.recordAad(f.id, it.id)) }
            }
            val fits = sealed.filter { it.second.length <= MAX_RECORD_BLOB }
            skipped += sealed.size - fits.size    // too large to send; stays dirty
            for (chunk in fits.chunked(pushChunk)) {
                relay.push(f.cred, f.relayEpoch, chunk.map { (item, blob) -> RelayRow(item.id, item.kind, blob) }).orStop()
                book.edit { db -> checkFamily(db, f); SyncModel.afterPush(db, chunk.map { it.first }) }
                pushed += chunk.size
            }

            // 5. files up
            val plan = book.read { SyncModel.planBlobs(it) }
            if (plan.drop.isNotEmpty()) book.edit { db -> plan.drop.forEach { SyncModel.blobsUp(db).remove(it) } }
            for (id in plan.upload) {
                val bytes = files.read(id) ?: continue
                val data = withContext(cpu) { SyncCrypto.sealE(enc, f.relayEpoch, bytes, SyncCrypto.fileAad(f.id, id)) }
                if (data.length > MAX_FILE_BLOB) { skipped++; continue }
                relay.blobPut(f.cred, f.relayEpoch, id, data).orStop()
                book.edit { db -> checkFamily(db, f); SyncModel.blobsUp(db).remove(id) }
                filesUp++
            }
        }

        // 5b. files down
        var filesDown = 0
        val wants = book.read { SyncModel.liveFiles(it) }
        for (w in wants) {
            if (files.has(w.id)) continue
            when (download(fam, w)) { 1 -> filesDown++; -1 -> skipped++ }
        }

        val f = fam
        book.edit { db ->
            checkFamily(db, f)
            SyncModel.syncOf(db)["lastAt"] = Clock.millis().toDouble()
            if (!waiting) SyncModel.syncOf(db).remove("restored")      // a restored book has caught up with everything on the relay
        }
        notices.update(NoticeCred(fam.id, fam.myId, fam.notice, fam.admin), _requests.value.size)
        return SyncResult.Done(pulled, pushed, filesUp, filesDown, skipped, waitingForKey = waiting)
    }

    /** Checks the open requests; those that do not check out are turned down at once (nothing is shared with them). */
    private suspend fun checkRequests(fam: Membership.Fam, rows: List<RelayRequest>) {
        val db0 = book.read { it.copyObj() }
        val good = ArrayList<Membership.Checked>()
        // one invite, one request: a relay showing two for the same invite (a replayed or leaked code) gets neither through
        val twice = rows.groupingBy { it.inviteId }.eachCount().filter { it.value > 1 }.keys
        for (r in rows) {
            val c = if (r.inviteId in twice) null else withContext(cpu) { Membership.check(db0, fam, r) }
            if (c != null) good.add(c)
            else {
                relay.decide(fam.cred, r.inviteId, false, fam.relayEpoch, null)
                book.edit { db -> db.o("family")?.oo("usedInvites")?.set(r.inviteId, true) }
            }
        }
        checked.clear(); good.forEach { checked[it.request.inviteId] = it }
        val names = db0.objs("members").associate { it.s("id") to it.s("name") }
        _requests.value = good.map { c ->
            JoinRequest(c.request.inviteId, c.name, c.invitedBy, names[c.invitedBy].orEmpty(), c.request.at, c.request.expiresAt)
        }
    }

    /** 1 = stored, 0 = not on the relay yet, -1 = there but unusable (does not open, or the hash does not match). */
    private suspend fun download(fam: Membership.Fam, w: SyncModel.FileWant): Int {
        val data = relay.blobGet(fam.cred, w.id).orStop() ?: return 0
        val bytes = withContext(cpu) {
            SyncCrypto.openE({ fam.encKey(it) }, data, SyncCrypto.fileAad(fam.id, w.id))?.takeIf { SyncCrypto.sha256Hex(it) == w.sha256 }
        } ?: return -1
        files.write(w.id, bytes, w.mime)
        return 1
    }

    private fun checkFamily(db: Obj, fam: Membership.Fam) {
        if (db.o("family")?.s("id") != fam.id) throw Stop(SyncError.BadData("the family changed during sync"))
    }

    /** A pulled row opened and checked, or null: it must open under this family and id, and say the same kind and id inside. */
    private fun open(fam: Membership.Fam, row: RelayRow): SyncModel.WireRecord? {
        val text = SyncCrypto.openE({ fam.encKey(it) }, row.blob, SyncCrypto.recordAad(fam.id, row.id))?.let { String(it, Charsets.UTF_8) } ?: return null
        val env = runCatching { JsonCodec.parseObj(text) }.getOrNull() ?: return null
        val k = env["k"] as? String ?: return null
        val r = env.o("r") ?: return null
        if (k != row.kind || k !in SyncModel.LISTS || r["id"] != row.id) return null
        return SyncModel.WireRecord(k, r)
    }

    companion object {
        /** The relay's limits (the tally_membership migration in supabase/migrations). */
        const val MAX_RECORD_BLOB = 65_536
        const val MAX_FILE_BLOB = 6 * 1024 * 1024
        private const val DAY = 24 * 60 * 60 * 1000L
    }
}
