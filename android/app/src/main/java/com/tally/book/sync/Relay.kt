package com.tally.book.sync

import com.tally.book.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/*
 * The relay (docs/DESIGN.md, "The relay"): five token-checked RPC functions on Supabase, reached through PostgREST.
 * It only ever sees family ids, record ids and kinds, seq numbers and sealed blobs.
 */

/** Why a relay call (or a sync) did not succeed. Small on purpose: each maps to one sentence in the UI. */
sealed class SyncError {
    /** No connection, DNS failure, timeout. Try again later. */
    object Offline : SyncError() { override fun toString() = "Offline" }
    /** The relay rejected the family token (left the family, a wrong invite) or the app's key. */
    object Unauthorised : SyncError() { override fun toString() = "Unauthorised" }
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

interface Relay {
    suspend fun createFamily(familyId: String, tokenHash: String): RelayResult<Unit>
    suspend fun push(familyId: String, token: String, rows: List<RelayRow>): RelayResult<Int>
    suspend fun pull(familyId: String, token: String, since: Long, max: Int): RelayResult<List<RelayRow>>
    suspend fun blobPut(familyId: String, token: String, id: String, data: String): RelayResult<Unit>
    suspend fun blobGet(familyId: String, token: String, id: String): RelayResult<String?>
}

/** PostgREST RPC over HttpURLConnection: POST {url}/rest/v1/rpc/<fn> with the publishable key. */
class HttpRelay(
    private val baseUrl: String,
    private val apiKey: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : Relay {

    override suspend fun createFamily(familyId: String, tokenHash: String): RelayResult<Unit> =
        call("tally_family_create", obj("family_id" to familyId, "token_hash" to tokenHash)) { }

    override suspend fun push(familyId: String, token: String, rows: List<RelayRow>): RelayResult<Int> {
        val list = arr(); rows.forEach { list.add(obj("id" to it.id, "kind" to it.kind, "blob" to it.blob)) }
        return call("tally_push", obj("family_id" to familyId, "token" to token, "rows" to list)) { v ->
            (v as? Double)?.toInt() ?: throw BadResponse("push: not a count")
        }
    }

    override suspend fun pull(familyId: String, token: String, since: Long, max: Int): RelayResult<List<RelayRow>> =
        call("tally_pull", obj("family_id" to familyId, "token" to token, "since" to since.toDouble(), "max" to max)) { v ->
            val a = v.asArr() ?: throw BadResponse("pull: not a list")
            a.map { e ->
                val o = e.asObj() ?: throw BadResponse("pull: bad row")
                val id = o["id"] as? String; val kind = o["kind"] as? String; val blob = o["blob"] as? String; val seq = o["seq"] as? Double
                if (id == null || kind == null || blob == null || seq == null) throw BadResponse("pull: bad row")
                RelayRow(id, kind, blob, seq.toLong())
            }
        }

    override suspend fun blobPut(familyId: String, token: String, id: String, data: String): RelayResult<Unit> =
        call("tally_blob_put", obj("family_id" to familyId, "token" to token, "id" to id, "data" to data)) { }

    override suspend fun blobGet(familyId: String, token: String, id: String): RelayResult<String?> =
        call("tally_blob_get", obj("family_id" to familyId, "token" to token, "id" to id)) { v ->
            when (v) { null -> null; is String -> v; else -> throw BadResponse("blob: not text") }
        }

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
        /** PostgREST error body { code, message, ... } to a SyncError. The SQL raises 28000 for a wrong token. */
        fun mapError(status: Int, text: String): SyncError {
            val o = runCatching { JsonCodec.parse(text).asObj() }.getOrNull()
            val code = o?.get("code") as? String ?: ""
            val msg = (o?.get("message") as? String ?: text).take(200)
            return when {
                code == "28000" -> SyncError.Unauthorised
                code == "22023" || code == "54000" || code == "22P02" || code == "23514" -> SyncError.BadData(msg)
                status == 401 || status == 403 -> SyncError.Unauthorised
                status == 400 || status == 413 -> SyncError.BadData(msg)
                else -> SyncError.Server(status, msg)
            }
        }
    }
}
