package com.tally.book.sync

import com.tally.book.core.*
import java.util.Base64

/*
 * Membership, as pure functions over the book's JSON tree and the relay's answers (no Android, no network): the family as
 * this phone holds it, its keyring, key boxes, join requests and invite boxes. docs/DESIGN.md, "Membership".
 *
 * DB.family (version 2):
 *   { v: 2, id, name, creator,                      creator: member id of whoever started it (null once they left)
 *     nameV,                                         how many times an admin renamed it (the relay's sealed meta; 0 = the name from create or the invite)
 *     me: { secret, notice, pub, priv },             relay credential (hex), notice credential (hex), P-256 key pair (base64url SPKI / PKCS#8)
 *     keys: { "<epoch>": base64(32 bytes) }, relayEpoch,   the keyring, and the family's newest epoch as last seen on the relay
 *     role, roster: [ { id, role, since, has } ], invites: [ { id, by, expires } ],   the relay's view, cached for the screens
 *     pins: { "<memberId>": base64url(SPKI) },       members' public keys, pinned the first time this phone learns them
 *     usedInvites: { "<inviteId>": true } }          invites this phone already decided (a replayed request is refused)
 * DB.joining: a join request waiting for an admin (docs/DESIGN.md, "Joining").
 * DB.familyGone: { name, why: "removed" | "denied" | "expired", at }: told once on screen, then cleared.
 * DB.leaving: { fam, member, secret }: a leave the relay has not heard about yet (no internet when leaving).
 * A DB.family without v: 2 (it has `key`) was made by 0.1.0/0.2.0: [isOld]. It is no longer synced.
 */
object Membership {
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** This phone's view of its family, read from the book. */
    class Fam(
        val id: String, val name: String, val creator: String?,
        val cred: Cred, val notice: String, val identity: SyncCrypto.Identity,
        val keys: Map<Int, ByteArray>, val relayEpoch: Int, val role: String,
        val pins: Map<String, ByteArray>,
    ) {
        val newest: Int get() = keys.keys.maxOrNull() ?: 0
        val admin: Boolean get() = role == "admin"
        /** True when the relay has a newer key than this phone holds: it can read but must not write. */
        val waitingForKey: Boolean get() = newest < relayEpoch
        private val encKeys = HashMap<Int, ByteArray>()
        fun encKey(epoch: Int): ByteArray? = keys[epoch]?.let { k -> encKeys.getOrPut(epoch) { SyncCrypto.encKey(k) } }
        val myId: String get() = cred.memberId
    }

    fun isOld(db: Obj): Boolean { val f = db.o("family") ?: return false; return f.num("v") != 2.0 }

    fun hasFamily(db: Obj): Boolean = db.o("family")?.s("id")?.isNotEmpty() == true

    fun famOf(db: Obj): Fam? {
        val f = db.o("family") ?: return null
        if (f.num("v") != 2.0) return null
        val id = f.s("id"); if (!Invite.isFamilyId(id)) return null
        val me = f.o("me") ?: return null
        val meId = db.o("me")?.s("id").orEmpty(); if (meId.isEmpty()) return null
        val pub = Invite.unb64url(me.s("pub")) ?: return null
        val priv = Invite.unb64url(me.s("priv")) ?: return null
        val keys = HashMap<Int, ByteArray>()
        f.o("keys")?.forEach { (e, v) ->
            val n = e.toIntOrNull(); val k = (v as? String)?.let { runCatching { unb64.decode(it) }.getOrNull() }
            if (n != null && n >= 1 && k != null && k.size == 32) keys[n] = k
        }
        val pins = HashMap<String, ByteArray>()
        f.o("pins")?.forEach { (m, v) -> (v as? String)?.let { Invite.unb64url(it) }?.let { pins[m] = it } }
        return Fam(
            id, f.s("name"), f["creator"] as? String, Cred(id, meId, me.s("secret")), me.s("notice"),
            SyncCrypto.Identity(pub, priv), keys, maxOf(f.num("relayEpoch").toInt(), 1), f.s("role").ifEmpty { "member" }, pins,
        )
    }

    fun newCredential(): String = SyncCrypto.hex(SyncCrypto.randomBytes(32))

    fun meObj(secret: String, notice: String, id: SyncCrypto.Identity): Obj =
        obj("secret" to secret, "notice" to notice, "pub" to Invite.b64url(id.publicSpki), "priv" to Invite.b64url(id.privatePkcs8))

    /** Puts a version-2 family in the book (create or an approved join). The sync state starts over, as in version 1. */
    fun enter(db: Obj, id: String, name: String, creator: String?, me: Obj, keys: Map<Int, ByteArray>, role: String, pins: Map<String, ByteArray>, now: Long, invite: String? = null) {
        val k = obj(); keys.forEach { (e, v) -> k[e.toString()] = b64.encodeToString(v) }
        val p = obj(); pins.forEach { (m, v) -> p[m] = Invite.b64url(v) }
        db["family"] = obj(
            "v" to 2, "id" to id, "name" to name, "creator" to creator, "me" to me, "keys" to k,
            "relayEpoch" to (keys.keys.maxOrNull() ?: 1), "role" to role, "roster" to arr(), "invites" to arr(), "pins" to p, "usedInvites" to obj(),
        )
        val old = db.o("sync")
        db["sync"] = obj(
            "cursor" to 0, "lastAt" to 0,
            "dirty" to (old.o("dirty")?.copyObj() ?: obj()),
            "blobsUp" to (old.o("blobsUp")?.copyObj() ?: obj()),
            "pushed" to obj(),
        )
        db.remove("joining"); db.remove("familyGone")
        val pub = me.s("pub")
        SyncModel.upsertMe(db, now, pub)
        val meId = db.o("me")!!.s("id")
        // the invite this member came in with travels in its record, so every admin refuses that invite if it comes back
        if (invite != null) db.objs("members").firstOrNull { it.s("id") == meId }?.set("invite", invite)
        db.o("family")!!.oo("pins")[meId] = pub
        SyncModel.markAllDirty(db)
    }

    /** Leaves the family on this phone: forgets the family and the sync state, keeps every record. [why] is told once on screen. */
    fun forget(db: Obj, why: String?, now: Long) {
        val name = db.o("family")?.s("name") ?: db.o("joining")?.s("name").orEmpty()
        db.remove("family"); db.remove("sync"); db.remove("joining")
        if (why != null) db["familyGone"] = obj("name" to name, "why" to why, "at" to now.toDouble())
    }

    fun addKeys(db: Obj, keys: Map<Int, ByteArray>) {
        val k = db.o("family")!!.oo("keys")
        keys.forEach { (e, v) -> if (!k.containsKey(e.toString())) k[e.toString()] = b64.encodeToString(v) }
    }

    /** Pins a member's public key unless one is already pinned; [force] replaces it (an admin approving that member again). */
    fun pin(db: Obj, member: String, pub: ByteArray, force: Boolean = false) {
        val p = db.o("family")?.oo("pins") ?: return
        if (force || !p.containsKey(member)) p[member] = Invite.b64url(pub)
    }

    /** Pins the public key of every member record that has one and is not pinned yet (trust on first use). */
    fun pinFromRecords(db: Obj) {
        for (m in db.objs("members")) {
            val id = m.s("id"); val pub = Invite.unb64url(m.s("pub")) ?: continue
            if (id.isNotEmpty() && SyncCrypto.publicKey(pub) != null) pin(db, id, pub)
        }
    }

    /** Caches what the relay said about the family. */
    fun applyRoster(db: Obj, st: RelayState) {
        val f = db.o("family") ?: return
        f["relayEpoch"] = maxOf(st.epoch, f.num("relayEpoch").toInt())
        f["role"] = st.myRole
        f["creator"] = st.creator
        f["roster"] = arr().also { a -> st.members.forEach { m -> a.add(obj("id" to m.id, "role" to m.role, "since" to m.since.toDouble(), "has" to m.has)) } }
        f["invites"] = arr().also { a -> st.invites.forEach { i -> a.add(obj("id" to i.id, "by" to i.by, "expires" to i.expiresAt.toDouble())) } }
    }

    // ---------- the family's name ----------

    fun metaAad(fam: String) = "tally-meta:$fam".toByteArray(Charsets.UTF_8)

    /**
     * The family's name for the relay, sealed under the newest key: { name, v, at, by }. [v] counts renames, so a phone never goes
     * back to an older name a relay replays. Only an admin can store it there (`tally_family_meta_set`).
     */
    fun sealMeta(fam: Fam, name: String, v: Int, at: Long): String {
        val json = JsonCodec.stringify(obj("name" to name, "v" to v, "at" to at.toDouble(), "by" to fam.myId))
        return SyncCrypto.sealE(fam.encKey(fam.relayEpoch)!!, fam.relayEpoch, json.toByteArray(Charsets.UTF_8), metaAad(fam.id))
    }

    /** The name and its version from the relay's sealed meta, or null when it does not open under a key held here or is not a valid name. */
    fun openMeta(fam: Fam, blob: String): Pair<String, Int>? {
        val o = SyncCrypto.openE({ fam.encKey(it) }, blob, metaAad(fam.id))
            ?.let { runCatching { JsonCodec.parseObj(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return null
        val name = Invite.cleanName(o.s("name"))?.takeIf { it == o.s("name") } ?: return null
        val v = o.num("v").toInt()
        return if (v >= 1) name to v else null
    }

    /** Takes the family's name from the relay's [blob] when its version is at least the one held here (`DB.family.nameV`). */
    fun applyMeta(db: Obj, blob: String?) {
        if (blob.isNullOrEmpty()) return
        val fam = famOf(db) ?: return
        val (name, v) = openMeta(fam, blob) ?: return
        val f = db.o("family") ?: return
        if (v >= f.num("nameV").toInt()) { f["name"] = name; f["nameV"] = v }
    }

    // ---------- key boxes ----------

    /**
     * What a key box holds: the family keys for epochs from+1 .. top, for one member (to, with public key pub). Proof that a
     * member made it: a MAC under a key the relay never has: for from >= 1 the key of epoch `from` (the recipient already
     * holds it), for from = 0 (letting someone in) the invite's grant key. A grant also carries the approving admin's pins and
     * the family's creator, so the new member starts with the same view of who is who.
     */
    class Box(
        val fam: String, val to: String, val pub: ByteArray, val from: Int, val keys: Map<Int, ByteArray>,
        val pins: Map<String, ByteArray> = emptyMap(), val creator: String? = null, val by: String, val invite: String? = null,
    ) {
        val top: Int get() = keys.keys.max()
    }

    fun boxContext(fam: String, to: String, epoch: Int) = "tally-box:$fam:$to:$epoch"

    fun boxAuthKey(fromKey: ByteArray): ByteArray = SyncCrypto.hkdfInfo(fromKey, "tally-box-auth")

    private fun macInput(b: Box): ByteArray {
        val sb = StringBuilder("tally-box|1|").append(b.fam).append('|').append(b.to).append('|').append(Invite.b64url(b.pub))
            .append('|').append(b.from).append('|')
        b.keys.toSortedMap().forEach { (e, k) -> sb.append(e).append('=').append(Invite.b64url(k)).append(';') }
        sb.append('|')
        b.pins.toSortedMap().forEach { (m, p) -> sb.append(m).append('=').append(Invite.b64url(p)).append(';') }
        sb.append('|').append(b.creator ?: "").append('|').append(b.by).append('|').append(b.invite ?: "")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Seals [b] to its recipient, with the MAC under [authKey]. */
    fun sealBox(b: Box, authKey: ByteArray): String {
        val keys = obj(); b.keys.toSortedMap().forEach { (e, k) -> keys[e.toString()] = Invite.b64url(k) }
        val pins = obj(); b.pins.forEach { (m, p) -> pins[m] = Invite.b64url(p) }
        val json = obj(
            "t" to "tally-keys", "fam" to b.fam, "to" to b.to, "pub" to Invite.b64url(b.pub), "from" to b.from, "keys" to keys,
            "pins" to pins, "creator" to b.creator, "by" to b.by, "invite" to b.invite,
            "mac" to SyncCrypto.hex(SyncCrypto.hmac(authKey, macInput(b))),
        )
        return SyncCrypto.sealTo(b.pub, JsonCodec.stringify(json).toByteArray(Charsets.UTF_8), boxContext(b.fam, b.to, b.top))
    }

    /**
     * Opens and checks a box addressed to this phone: it must open with this phone's private key for exactly this family,
     * member and epoch, hold contiguous 32-byte keys ending at that epoch, and carry a MAC that [authFor] (from, invite) can
     * check. Null for anything else, including a box the relay forged with this phone's public key.
     */
    fun openBox(fam: String, me: String, identity: SyncCrypto.Identity, row: RelayBox, authFor: (Int, String?) -> ByteArray?): Box? = runCatching {
        val plain = SyncCrypto.openBox(identity, row.box, boxContext(fam, me, row.epoch)) ?: return null
        val o = JsonCodec.parseObj(String(plain, Charsets.UTF_8))
        if (o.s("t") != "tally-keys" || o.s("fam") != fam || o.s("to") != me) return null
        val pub = Invite.unb64url(o.s("pub")) ?: return null
        if (!SyncCrypto.same(pub, identity.publicSpki)) return null
        val from = (o["from"] as? Double)?.toInt() ?: return null
        val keys = HashMap<Int, ByteArray>()
        o.o("keys")?.forEach { (e, v) ->
            val n = e.toIntOrNull() ?: return null
            val k = (v as? String)?.let { Invite.unb64url(it) }?.takeIf { it.size == 32 } ?: return null
            keys[n] = k
        }
        if (keys.isEmpty() || from < 0) return null
        if (keys.keys.sorted() != ((from + 1)..row.epoch).toList()) return null
        val pins = HashMap<String, ByteArray>()
        o.o("pins")?.forEach { (m, v) -> pins[m] = (v as? String)?.let { Invite.unb64url(it) } ?: return null }
        val box = Box(fam, me, pub, from, keys, pins, o["creator"] as? String, o.s("by"), o["invite"] as? String)
        val auth = authFor(from, box.invite) ?: return null
        val mac = runCatching { hexBytes(o.s("mac")) }.getOrNull() ?: return null
        if (!SyncCrypto.same(mac, SyncCrypto.hmac(auth, macInput(box)))) return null
        box
    }.getOrNull()

    private fun hexBytes(s: String): ByteArray {
        require(s.length == 64)
        return ByteArray(32) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** The box giving [to] (who holds keys up to [has]) every key from has+1 to [top] (all of which [fam] holds, or [extra] adds). */
    fun boxFor(fam: Fam, to: String, pub: ByteArray, has: Int, top: Int, extra: Map<Int, ByteArray> = emptyMap()): String? {
        if (has < 1 || has >= top) return null
        val all = HashMap(fam.keys).apply { putAll(extra) }
        val keys = ((has + 1)..top).associateWith { all[it] ?: return null }
        val auth = all[has]?.let { boxAuthKey(it) } ?: return null
        return sealBox(Box(fam.id, to, pub, has, keys, by = fam.myId), auth)
    }

    // ---------- join requests and invite boxes ----------

    fun requestAad(fam: String, inviteId: String, member: String) = "tally-join:$fam:$inviteId:$member".toByteArray(Charsets.UTF_8)

    /** The joiner's request: its name and public key, sealed under the invite's request key, bound to family, invite and member id. */
    fun sealRequest(secret: ByteArray, fam: String, inviteId: String, member: String, name: String, pub: ByteArray): String {
        val json = JsonCodec.stringify(obj("name" to name, "pub" to Invite.b64url(pub)))
        return SyncCrypto.seal(Invite.requestKey(secret), json.toByteArray(Charsets.UTF_8), requestAad(fam, inviteId, member))
    }

    class Asked(val name: String, val pub: ByteArray)

    fun openRequest(secret: ByteArray, fam: String, inviteId: String, member: String, blob: String): Asked? = runCatching {
        val plain = SyncCrypto.open(Invite.requestKey(secret), blob, requestAad(fam, inviteId, member)) ?: return null
        val o = JsonCodec.parseObj(String(plain, Charsets.UTF_8))
        val name = Invite.cleanName(o.s("name"))?.takeIf { it == o.s("name") } ?: return null
        val pub = Invite.unb64url(o.s("pub"))?.takeIf { SyncCrypto.publicKey(it) != null } ?: return null
        Asked(name, pub)
    }.getOrNull()

    fun inviteAad(fam: String, inviteId: String) = "tally-invite:$fam:$inviteId".toByteArray(Charsets.UTF_8)

    /** The invite's secret for the family's members (admins need it to check the request), sealed under the newest key. */
    fun sealInviteBox(fam: Fam, secret: ByteArray, expiresAt: Long): String {
        val json = JsonCodec.stringify(obj("s" to Invite.b64url(secret), "by" to fam.myId, "exp" to expiresAt.toDouble()))
        return SyncCrypto.sealE(fam.encKey(fam.relayEpoch)!!, fam.relayEpoch, json.toByteArray(Charsets.UTF_8), inviteAad(fam.id, Invite.idOf(secret)))
    }

    /** A request an admin's phone has checked: who asks, who invited them, and the invite's secret (for the grant's MAC). */
    class Checked(val request: RelayRequest, val name: String, val pub: ByteArray, val secret: ByteArray, val invitedBy: String)

    /**
     * Checks a request as an admin's phone sees it. Refused (null): an invite box not sealed under the family's current key
     * (made before a rotation, or forged with an old key), not opening, naming another maker than the relay says, or whose
     * secret does not give this invite id; a request that does not open under that invite's key for this member id (a
     * relay that swapped the member id or public key, or wrote its own); a member who is in the family already; an invite
     * this phone, or any member's record, has already used.
     */
    fun check(db: Obj, fam: Fam, r: RelayRequest): Checked? {
        if (SyncCrypto.epochOf(r.inviteBox) != fam.relayEpoch) return null
        val inv = SyncCrypto.openE({ fam.encKey(it) }, r.inviteBox, inviteAad(fam.id, r.inviteId))
            ?.let { runCatching { JsonCodec.parseObj(String(it, Charsets.UTF_8)) }.getOrNull() } ?: return null
        val secret = Invite.unb64url(inv.s("s"))?.takeIf { it.size == 32 } ?: return null
        if (inv.s("by") != r.invitedBy || Invite.idOf(secret) != r.inviteId) return null
        val asked = openRequest(secret, fam.id, r.inviteId, r.memberId, r.blob) ?: return null
        val f = db.o("family") ?: return null
        if (f.o("usedInvites")?.b(r.inviteId) == true) return null
        if (db.objs("members").any { it.s("invite") == r.inviteId }) return null
        if (f.objs("roster").any { it.s("id") == r.memberId }) return null
        if (r.memberId == fam.myId) return null
        return Checked(r, asked.name, asked.pub, secret, r.invitedBy)
    }

    /** The grant for a checked request: every key the family has, the admin's pins and the creator, MAC'd with the invite's grant key. */
    fun grant(fam: Fam, c: Checked): String? {
        val keys = (1..fam.relayEpoch).associateWith { fam.keys[it] ?: return null }
        val pins = HashMap(fam.pins).apply { put(c.request.memberId, c.pub) }
        return sealBox(Box(fam.id, c.request.memberId, c.pub, 0, keys, pins, fam.creator, fam.myId, c.request.inviteId), Invite.grantKey(c.secret))
    }
}

// ---------- what the screens see ----------

/** One member of the family as shown on the Family page. */
data class FamilyMember(val id: String, val name: String, val admin: Boolean, val creator: Boolean, val me: Boolean, val since: Long)

/** A join request an admin's phone has checked: "<name> wants to join <family>. <invitedBy> invited them." [id] is the invite id. */
data class JoinRequest(val id: String, val name: String, val invitedById: String, val invitedBy: String, val at: Long, val expiresAt: Long)

/** An invite that has not been used yet. */
data class OpenInvite(val id: String, val byId: String, val byName: String, val mine: Boolean, val expiresAt: Long)

/** This phone asked to join and waits for an admin. */
data class PendingJoin(val familyName: String, val inviter: String?, val since: Long, val expiresAt: Long)

/** Why this phone is no longer in (or did not get into) a family: "removed" | "denied" | "expired". Told once, then [SyncEngine.clearGone]. */
data class FamilyGone(val familyName: String, val why: String, val at: Long)

/** Everything the Family page and the home card need about membership, from the book (requests come from [SyncEngine.requests]). */
data class MembershipView(
    val familyId: String?, val familyName: String, val oldFamily: Boolean,
    val myId: String, val iAmAdmin: Boolean, val iAmCreator: Boolean,
    val members: List<FamilyMember>, val invites: List<OpenInvite>,
    val joining: PendingJoin?, val gone: FamilyGone?, val waitingForKey: Boolean,
) {
    val inFamily: Boolean get() = familyId != null && !oldFamily

    companion object {
        fun of(db: Obj): MembershipView {
            val f = db.o("family")
            val myId = db.o("me")?.s("id").orEmpty()
            val names = db.objs("members").associate { it.s("id") to it.s("name") }
            fun nameOf(id: String) = names[id].orEmpty()
            val fam = Membership.famOf(db)
            val creator = f?.get("creator") as? String
            val members = f?.objs("roster").orEmpty().map { m ->
                val id = m.s("id")
                FamilyMember(id, nameOf(id), m.s("role") == "admin", id == creator, id == myId, m.num("since").toLong())
            }.ifEmpty {
                // never synced yet: just me
                if (fam != null) listOf(FamilyMember(myId, nameOf(myId).ifEmpty { db.o("me")?.s("name").orEmpty() }, fam.admin, myId == creator, true, 0)) else emptyList()
            }
            val invites = f?.objs("invites").orEmpty().map { i ->
                OpenInvite(i.s("id"), i.s("by"), nameOf(i.s("by")), i.s("by") == myId, i.num("expires").toLong())
            }
            val j = db.o("joining")?.let { PendingJoin(it.s("name"), (it["inviter"] as? String), it.num("at").toLong(), it.num("expires").toLong()) }
            val g = db.o("familyGone")?.let { FamilyGone(it.s("name"), it.s("why"), it.num("at").toLong()) }
            return MembershipView(
                familyId = f?.s("id")?.takeIf { it.isNotEmpty() }, familyName = f?.s("name").orEmpty(), oldFamily = Membership.isOld(db),
                myId = myId, iAmAdmin = fam?.admin == true, iAmCreator = creator != null && creator == myId,
                members = members, invites = invites, joining = j, gone = g, waitingForKey = fam?.waitingForKey == true,
            )
        }
    }
}
