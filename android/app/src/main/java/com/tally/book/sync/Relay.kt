package com.tally.book.sync

import com.tally.book.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * The relay (docs/DESIGN.md, "The relay"): the tally_* RPC functions of supabase/migrations (the tally_membership migration),
 * reached through PostgREST. It only ever sees family, member, invite and record ids, roles and statuses, epochs, seq
 * numbers, hashes of credentials, and sealed blobs.
 */

/** Why a relay call (or a sync) did not succeed. Small on purpose: each maps to one sentence in the UI. */
sealed class SyncError {
    /** No connection, DNS failure, timeout. Try again later. */
    object Offline : SyncError() { override fun toString() = "Offline" }
    /** The relay does not know these credentials (left the family, a wrong code) or the app's key. */
    object Unauthorised : SyncError() { override fun toString() = "Unauthorised" }
    /** An admin removed this phone's member from the family. */
    object Removed : SyncError() { override fun toString() = "Removed" }
    /** This member came back on a new phone (an admin allowed it): this phone's credentials no longer work. */
    object Replaced : SyncError() { override fun toString() = "Replaced" }
    /** Only an admin can do that. */
    object NotAdmin : SyncError() { override fun toString() = "NotAdmin" }
    /** Someone else changed the family meanwhile (a new key, a member who came or went): sync and try again. */
    object Stale : SyncError() { override fun toString() = "Stale" }
    /** A limit of the relay is reached (members, invites, requests, records, file space, new families). */
    object Limit : SyncError() { override fun toString() = "Limit" }
    /** A family rule says no (the last admin, the family's creator, removing yourself, someone no longer in the family). */
    object Refused : SyncError() { override fun toString() = "Refused" }
    /** The invite or request cannot be used any more (expired, used, cancelled, already decided). */
    object InviteGone : SyncError() { override fun toString() = "InviteGone" }
    /** Something from the relay did not check out (a request, key box or invite that was not made by who it says). */
    object Unverified : SyncError() { override fun toString() = "Unverified" }
    /** The relay failed (5xx, a paused project, an unexpected status). */
    data class Server(val status: Int, val message: String) : SyncError()
    /** A request or response that does not have the expected shape (too large, corrupt, not ours). */
    data class BadData(val message: String) : SyncError()
    /** No relay is configured in this build (empty TALLY_SUPABASE_URL). */
    object NotConfigured : SyncError() { override fun toString() = "NotConfigured" }
    /** The book could not be saved on this phone. */
    object Storage : SyncError() { override fun toString() = "Storage" }
}

sealed class RelayResult<out T> {
    data class Ok<T>(val value: T) : RelayResult<T>()
    data class Err(val error: SyncError) : RelayResult<Nothing>()
}

/** A relay row. [seq] is set on rows read by pull. */
data class RelayRow(val id: String, val kind: String, val blob: String, val seq: Long = 0)

/** One member's relay credentials. The secret is never logged. */
class Cred(val familyId: String, val memberId: String, val secret: String) {
    override fun toString() = "Cred($familyId, $memberId, <secret>)"
}

data class RelayMember(val id: String, val role: String, val since: Long, val has: Int) { val admin: Boolean get() = role == "admin" }
data class RelayBox(val epoch: Int, val box: String)
data class RelayInvite(val id: String, val by: String, val expiresAt: Long)
data class RelayRequest(val inviteId: String, val memberId: String, val blob: String, val at: Long, val expiresAt: Long, val inviteBox: String, val invitedBy: String)
/** What tally_join_check says: "approved" | "pending" | "denied" | "removed", and the member id it is about. */
data class JoinCheck(val status: String, val member: String)
/**
 * What tally_files says to one member: [owed] the files the relay holds that this member still has to fetch (oldest first),
 * [asks] the files other members asked for ("Ask for this file") that the relay does not hold.
 */
data class RelayFiles(val owed: List<String>, val asks: List<String>)
/** A key box for one member, as sent with a rotation, an approval or a repair. */
data class BoxFor(val member: String, val box: String)

/** The family as one member sees it (tally_state). [requests] is empty unless the caller is an admin. */
data class RelayState(
    val epoch: Int, val creator: String?, val rotate: Boolean, val myRole: String,
    val members: List<RelayMember>, val boxes: List<RelayBox>, val invites: List<RelayInvite>, val requests: List<RelayRequest>,
    /** The family's name as an admin last set it, sealed (Membership.sealMeta); null until someone renames the family. */
    val meta: String? = null,
)

interface Relay {
    suspend fun createFamily(familyId: String, memberId: String, secretHash: String, noticeHash: String?): RelayResult<Unit>
    suspend fun state(c: Cred, after: Int): RelayResult<RelayState>
    suspend fun push(c: Cred, epoch: Int, rows: List<RelayRow>): RelayResult<Int>
    suspend fun pull(c: Cred, since: Long, max: Int): RelayResult<List<RelayRow>>
    suspend fun blobPut(c: Cred, epoch: Int, id: String, data: String): RelayResult<Unit>
    suspend fun blobGet(c: Cred, id: String): RelayResult<String?>
    /** This phone stored [id]'s bytes and checked them: the member is no longer owed it (the relay deletes it once nobody is). */
    suspend fun blobAck(c: Cred, id: String): RelayResult<Unit>
    /** "Ask for this file": "ready" (the relay still holds it; fetch now) or "asked" (a phone that has it uploads it again). */
    suspend fun fileAsk(c: Cred, id: String): RelayResult<String>
    /** The files this member is owed, and the files others asked for. */
    suspend fun files(c: Cred): RelayResult<RelayFiles>
    /** Registers a one-time invite; returns when it expires (epoch ms). */
    suspend fun inviteCreate(c: Cred, inviteId: String, box: String): RelayResult<Long>
    suspend fun inviteCancel(c: Cred, inviteId: String): RelayResult<Unit>
    /** Not a member yet: asks to join with an invite. Returns when the request expires (epoch ms). */
    suspend fun joinRequest(familyId: String, inviteId: String, memberId: String, secretHash: String, noticeHash: String?, blob: String): RelayResult<Long>
    /** "approved" | "pending" | "denied" | "removed"; Err(InviteGone) when the request is gone. */
    suspend fun joinStatus(familyId: String, inviteId: String, memberId: String, secret: String): RelayResult<String>
    suspend fun joinCancel(familyId: String, inviteId: String, memberId: String, secret: String): RelayResult<Unit>
    /**
     * Like [joinStatus], for a request that may have been answered by letting the phone back in as an existing member: the status
     * and the member id the phone now is (the relay's word only: the grant proves it). Err(InviteGone) when the request is gone.
     */
    suspend fun joinCheck(familyId: String, inviteId: String, memberId: String, secret: String): RelayResult<JoinCheck>
    /**
     * An admin lets the phone that asked with [inviteId] back in as the active member [target] (back on a new phone): the target
     * gets that phone's credentials, its old ones are told "replaced", and the family moves to [epoch] (current + 1) with
     * [boxes] (the grant for [target] among them).
     */
    suspend fun reclaim(c: Cred, inviteId: String, target: String, epoch: Int, boxes: List<BoxFor>): RelayResult<Unit>
    suspend fun decide(c: Cred, inviteId: String, allow: Boolean, epoch: Int, box: String?): RelayResult<Unit>
    suspend fun setRole(c: Cred, target: String, admin: Boolean): RelayResult<Unit>
    /** Stores the family's sealed name (admins only; [epoch] must be the family's newest). */
    suspend fun setMeta(c: Cred, epoch: Int, meta: String): RelayResult<Unit>
    suspend fun remove(c: Cred, target: String, epoch: Int, boxes: List<BoxFor>): RelayResult<Unit>
    suspend fun rotate(c: Cred, epoch: Int, boxes: List<BoxFor>): RelayResult<Unit>
    suspend fun boxPut(c: Cred, epoch: Int, boxes: List<BoxFor>): RelayResult<Int>
    /** "left" | "deleted" (nobody was left, so the family is gone from the relay). */
    suspend fun leave(c: Cred): RelayResult<String>
    /** How many join requests wait (0 unless an admin), with the notice credential only. */
    suspend fun pending(familyId: String, memberId: String, notice: String): RelayResult<Int>
}

/** PostgREST RPC over HttpURLConnection: POST {url}/rest/v1/rpc/<fn> with the publishable key. */
class HttpRelay(
    private val baseUrl: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : Relay {

    private fun cred(c: Cred, vararg more: Pair<String, Any?>): Obj =
        obj("p_family" to c.familyId, "p_member" to c.memberId, "p_secret" to c.secret, *more)

    private fun boxes(list: List<BoxFor>): Arr { val a = arr(); list.forEach { a.add(obj("member" to it.member, "box" to it.box)) }; return a }

    private fun long(v: Any?, what: String): Long = (v as? Double)?.toLong() ?: throw BadResponse("$what: not a number")
    private fun text(v: Any?, what: String): String = v as? String ?: throw BadResponse("$what: not text")

    override suspend fun createFamily(familyId: String, memberId: String, secretHash: String, noticeHash: String?): RelayResult<Unit> =
        call("tally_family_create", obj("p_family" to familyId, "p_member" to memberId, "p_secret_hash" to secretHash, "p_notice_hash" to noticeHash)) { }

    override suspend fun state(c: Cred, after: Int): RelayResult<RelayState> = call("tally_state", cred(c, "p_after" to after)) { v ->
        val o = v.asObj() ?: throw BadResponse("state: not an object")
        fun list(k: String) = o.a(k) ?: throw BadResponse("state: no $k")
        RelayState(
            epoch = (o["epoch"] as? Double)?.toInt() ?: throw BadResponse("state: epoch"),
            creator = o["creator"] as? String,
            rotate = o["rotate"] == true,
            myRole = o.o("me")?.get("role") as? String ?: throw BadResponse("state: role"),
            members = list("members").map { e -> val m = e.asObj() ?: throw BadResponse("state: member")
                RelayMember(text(m["id"], "member"), text(m["role"], "role"), long(m["since"], "since"), long(m["has"], "has").toInt()) },
            boxes = list("boxes").map { e -> val b = e.asObj() ?: throw BadResponse("state: box")
                RelayBox(long(b["epoch"], "epoch").toInt(), text(b["box"], "box")) },
            invites = list("invites").map { e -> val i = e.asObj() ?: throw BadResponse("state: invite")
                RelayInvite(text(i["id"], "invite"), text(i["by"], "by"), long(i["expires"], "expires")) },
            requests = list("requests").map { e -> val r = e.asObj() ?: throw BadResponse("state: request")
                RelayRequest(text(r["invite"], "invite"), text(r["member"], "member"), text(r["blob"], "blob"), long(r["at"], "at"),
                    long(r["expires"], "expires"), text(r["inviteBox"], "inviteBox"), text(r["invitedBy"], "invitedBy")) },
            meta = o["meta"] as? String,
        )
    }

    override suspend fun push(c: Cred, epoch: Int, rows: List<RelayRow>): RelayResult<Int> {
        val list = arr(); rows.forEach { list.add(obj("id" to it.id, "kind" to it.kind, "blob" to it.blob)) }
        return call("tally_push", cred(c, "p_epoch" to epoch, "p_rows" to list)) { v -> long(v, "push").toInt() }
    }

    override suspend fun pull(c: Cred, since: Long, max: Int): RelayResult<List<RelayRow>> =
        call("tally_pull", cred(c, "p_since" to since.toDouble(), "p_max" to max)) { v ->
            val a = v.asArr() ?: throw BadResponse("pull: not a list")
            a.map { e ->
                val o = e.asObj() ?: throw BadResponse("pull: bad row")
                val id = o["id"] as? String; val kind = o["kind"] as? String; val blob = o["blob"] as? String; val seq = o["seq"] as? Double
                if (id == null || kind == null || blob == null || seq == null) throw BadResponse("pull: bad row")
                RelayRow(id, kind, blob, seq.toLong())
            }
        }

    override suspend fun blobPut(c: Cred, epoch: Int, id: String, data: String): RelayResult<Unit> =
        call("tally_blob_put", cred(c, "p_epoch" to epoch, "p_id" to id, "p_data" to data)) { }

    override suspend fun blobGet(c: Cred, id: String): RelayResult<String?> =
        call("tally_blob_get", cred(c, "p_id" to id)) { v ->
            when (v) { null -> null; is String -> v; else -> throw BadResponse("blob: not text") }
        }

    override suspend fun blobAck(c: Cred, id: String): RelayResult<Unit> =
        call("tally_blob_ack", cred(c, "p_id" to id)) { }

    override suspend fun fileAsk(c: Cred, id: String): RelayResult<String> =
        call("tally_file_ask", cred(c, "p_id" to id)) { v -> text(v, "ask") }

    override suspend fun files(c: Cred): RelayResult<RelayFiles> = call("tally_files", cred(c)) { v ->
        val o = v.asObj() ?: throw BadResponse("files: not an object")
        fun ids(k: String) = (o.a(k) ?: throw BadResponse("files: no $k")).map { text(it, k) }
        RelayFiles(ids("owed"), ids("asks"))
    }

    override suspend fun inviteCreate(c: Cred, inviteId: String, box: String): RelayResult<Long> =
        call("tally_invite_create", cred(c, "p_invite" to inviteId, "p_box" to box)) { v -> long(v, "invite") }

    override suspend fun inviteCancel(c: Cred, inviteId: String): RelayResult<Unit> =
        call("tally_invite_cancel", cred(c, "p_invite" to inviteId)) { }

    override suspend fun joinRequest(familyId: String, inviteId: String, memberId: String, secretHash: String, noticeHash: String?, blob: String): RelayResult<Long> =
        call("tally_join_request", obj("p_family" to familyId, "p_invite" to inviteId, "p_member" to memberId,
            "p_secret_hash" to secretHash, "p_notice_hash" to noticeHash, "p_blob" to blob)) { v -> long(v, "request") }

    override suspend fun joinStatus(familyId: String, inviteId: String, memberId: String, secret: String): RelayResult<String> =
        call("tally_join_status", obj("p_family" to familyId, "p_invite" to inviteId, "p_member" to memberId, "p_secret" to secret)) { v -> text(v, "status") }

    override suspend fun joinCancel(familyId: String, inviteId: String, memberId: String, secret: String): RelayResult<Unit> =
        call("tally_join_cancel", obj("p_family" to familyId, "p_invite" to inviteId, "p_member" to memberId, "p_secret" to secret)) { }

    override suspend fun joinCheck(familyId: String, inviteId: String, memberId: String, secret: String): RelayResult<JoinCheck> =
        call("tally_join_check", obj("p_family" to familyId, "p_invite" to inviteId, "p_member" to memberId, "p_secret" to secret)) { v ->
            val o = v.asObj() ?: throw BadResponse("join check: not an object")
            JoinCheck(text(o["status"], "status"), text(o["member"], "member"))
        }

    override suspend fun reclaim(c: Cred, inviteId: String, target: String, epoch: Int, boxes: List<BoxFor>): RelayResult<Unit> =
        call("tally_member_reclaim", cred(c, "p_invite" to inviteId, "p_target" to target, "p_epoch" to epoch, "p_boxes" to boxes(boxes))) { }

    override suspend fun decide(c: Cred, inviteId: String, allow: Boolean, epoch: Int, box: String?): RelayResult<Unit> =
        call("tally_request_decide", cred(c, "p_invite" to inviteId, "p_allow" to allow, "p_epoch" to epoch, "p_box" to box)) { }

    override suspend fun setRole(c: Cred, target: String, admin: Boolean): RelayResult<Unit> =
        call("tally_member_role", cred(c, "p_target" to target, "p_admin" to admin)) { }

    override suspend fun setMeta(c: Cred, epoch: Int, meta: String): RelayResult<Unit> =
        call("tally_family_meta_set", cred(c, "p_epoch" to epoch, "p_meta" to meta)) { }

    override suspend fun remove(c: Cred, target: String, epoch: Int, boxes: List<BoxFor>): RelayResult<Unit> =
        call("tally_member_remove", cred(c, "p_target" to target, "p_epoch" to epoch, "p_boxes" to boxes(boxes))) { }

    override suspend fun rotate(c: Cred, epoch: Int, boxes: List<BoxFor>): RelayResult<Unit> =
        call("tally_rotate", cred(c, "p_epoch" to epoch, "p_boxes" to boxes(boxes))) { }

    override suspend fun boxPut(c: Cred, epoch: Int, boxes: List<BoxFor>): RelayResult<Int> =
        call("tally_box_put", cred(c, "p_epoch" to epoch, "p_boxes" to boxes(boxes))) { v -> long(v, "boxes").toInt() }

    override suspend fun leave(c: Cred): RelayResult<String> = call("tally_leave", cred(c)) { v -> text(v, "leave") }

    override suspend fun pending(familyId: String, memberId: String, notice: String): RelayResult<Int> =
        call("tally_pending", obj("p_family" to familyId, "p_member" to memberId, "p_notice" to notice)) { v -> long(v, "pending").toInt() }

    private class BadResponse(msg: String) : Exception(msg)

    private suspend fun <T> call(fn: String, body: Obj, read: (Any?) -> T): RelayResult<T> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) return@withContext RelayResult.Err(SyncError.NotConfigured)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(baseUrl.trimEnd('/') + "/rest/v1/rpc/" + fn).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val bytes = JsonCodec.stringify(body).toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (status in 200..299) {
                val v = if (text.isBlank()) null else JsonCodec.parse(text)
                RelayResult.Ok(read(v))
            } else RelayResult.Err(mapError(status, text))
        } catch (e: BadResponse) {
            RelayResult.Err(SyncError.BadData(e.message ?: "bad response"))
        } catch (e: IOException) {
            RelayResult.Err(SyncError.Offline)
        } catch (e: Exception) {
            RelayResult.Err(SyncError.BadData(e.javaClass.simpleName))
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        /**
         * PostgREST error body { code, message, ... } to a SyncError (the codes are listed at the top of the tally_membership
         * migration, TA007 "replaced" in tally_rejoin; "not an admin" became TA006 in tally_not_admin_code, because PostgREST sends 42501 as HTTP 401, whose body
         * HttpURLConnection drops for a fixed-length request).
         */
        fun mapError(status: Int, text: String): SyncError {
            val o = runCatching { JsonCodec.parse(text).asObj() }.getOrNull()
            val code = o?.get("code") as? String ?: ""
            val msg = (o?.get("message") as? String ?: text).take(200)
            return when {
                code == "28000" -> SyncError.Unauthorised
                code == "TA001" -> SyncError.Removed
                code == "TA007" -> SyncError.Replaced
                code == "TA006" || code == "42501" -> SyncError.NotAdmin
                code == "TA002" -> SyncError.Stale
                code == "TA003" -> SyncError.Limit
                code == "TA004" -> SyncError.Refused
                code == "TA005" -> SyncError.InviteGone
                code == "22023" || code == "54000" || code == "22P02" || code == "23514" -> SyncError.BadData(msg)
                status == 401 || status == 403 -> SyncError.Unauthorised
                status == 400 || status == 413 -> SyncError.BadData(msg)
                else -> SyncError.Server(status, msg)
            }
        }
    }
}
