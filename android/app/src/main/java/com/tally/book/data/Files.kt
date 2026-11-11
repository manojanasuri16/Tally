package com.tally.book.data

import android.content.Context
import android.os.StatFs
import android.util.AtomicFile
import com.tally.book.core.*
import com.tally.book.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/*
 * Document files at their original bytes, in
 * filesDir/files/, encrypted at rest while a lock is set. The rules (docs/features/files.md):
 * - a document is stored exactly as it arrived (no resize, re-encode or compression);
 * - its SHA-256 is taken at upload and checked on every open, after every restore and before the store
 *   rewrites it; a file that fails is reported, never silently replaced;
 * With a lock each file is AES-GCM under one random key, DB.filesKey, which is sealed with the rest of DB,
 * so a lock change touches no file. The file id is the additional data. The key is device-only.
 *
 * A record is two files: <id>.bin (the bytes, or the ciphertext) and <id>.json ({ enc, iv, ts, mime }).
 * DB is only read and written on the main thread; the byte work runs on IO.
 */
object FileStore {
    class Rec(val id: String, val enc: Boolean, val iv: ByteArray?, val data: ByteArray, val ts: Long, val mime: String = "")
    class Fail(val code: String) : Exception(code)

    private var dir: File? = null
    fun init(ctx: Context) { dir = File(ctx.filesDir, "files").apply { mkdirs() } }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun binOf(id: String) = File(dir, safe(id) + ".bin")
    private fun metaOf(id: String) = File(dir, safe(id) + ".json")

    private val lane = Mutex()
    /** filesExclusive(): one lane for every bulk rewrite (the encryption pass, a restore, Erase). */
    suspend fun <T> filesExclusive(fn: suspend () -> T): T = lane.withLock { fn() }

    // ---------- records ----------

    suspend fun fileRecKeys(): List<String> = withContext(Dispatchers.IO) {
        dir?.listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f -> runCatching { JsonCodec.parseObj(f.readText()).s("id") }.getOrNull()?.takeIf { it.isNotEmpty() } } ?: emptyList()
    }

    suspend fun fileRecGet(id: String): Rec? = withContext(Dispatchers.IO) {
        val m = metaOf(id); val b = binOf(id)
        if (!m.exists() || !b.exists()) return@withContext null
        runCatching {
            val j = JsonCodec.parseObj(m.readText())
            Rec(id, j.b("enc"), j.s("iv").takeIf { it.isNotEmpty() }?.let { Crypto.unb64(it) }, AtomicFile(b).readFully(), n(j["ts"]).toLong(), j.s("mime"))
        }.getOrNull()
    }

    suspend fun fileRecPut(r: Rec) = withContext(Dispatchers.IO) {
        val d = dir ?: throw Fail("failed")
        d.mkdirs()
        val af = AtomicFile(binOf(r.id))
        val s = af.startWrite()
        try { s.write(r.data); af.finishWrite(s) } catch (e: Throwable) { af.failWrite(s); throw e }
        val meta = obj("id" to r.id, "enc" to if (r.enc) 1 else 0, "iv" to (r.iv?.let { Crypto.b64(it) } ?: ""), "ts" to r.ts.toDouble(), "mime" to r.mime, "size" to r.data.size)
        val am = AtomicFile(metaOf(r.id))
        val s2 = am.startWrite()
        try { s2.write(JsonCodec.stringify(meta).toByteArray()); am.finishWrite(s2) } catch (e: Throwable) { am.failWrite(s2); throw e }
    }

    suspend fun fileRecDel(id: String) = withContext(Dispatchers.IO) { binOf(id).delete(); metaOf(id).delete(); Unit }

    /** Size of a stored record without reading it (health scan). */
    private suspend fun fileRecSize(id: String): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val ts = runCatching { n(JsonCodec.parseObj(metaOf(id).readText())["ts"]).toLong() }.getOrDefault(0L)
        binOf(id).length() to ts
    }

    /** a restore replaces the whole set, and Erase removes it */
    suspend fun clearAllFileRecords() = filesExclusive {
        withContext(Dispatchers.IO) { dir?.listFiles()?.forEach { it.delete() } }
        fileStates.clear()
    }

    // ---------- hashing ----------

    fun fileSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---------- the files key ----------

    private val keyMaking = Mutex()
    /** filesKey(create): the key bytes; a new one is persisted, sealed, before anything depends on it. */
    suspend fun filesKey(create: Boolean): ByteArray? = keyMaking.withLock {
        withContext(Dispatchers.Main) {
            if (!truthy(DB["filesKey"])) {
                if (!create) return@withContext null
                val k = Crypto.b64(Crypto.randomBytes(32))
                DB["filesKey"] = k
                if (!Ledger.persist()) { if (DB["filesKey"] == k) DB.remove("filesKey"); throw Fail("failed") }
            }
            Crypto.unb64(DB.s("filesKey"))
        }
    }

    private fun fileAad(id: String) = ("tally-file:$id").toByteArray(Charsets.UTF_8)
    fun filePlainRec(id: String, bytes: ByteArray, mime: String?) = Rec(id, false, null, bytes, Clock.millis(), mime ?: "")
    suspend fun fileSealRec(id: String, bytes: ByteArray): Rec {
        val key = filesKey(true)!!
        val iv = Crypto.randomBytes(12)
        return Rec(id, true, iv, withContext(Dispatchers.Default) { Crypto.encrypt(key, iv, bytes, fileAad(id)) }, Clock.millis())
    }
    /** a record's original bytes; throws Fail("unreadable") when it can't be decrypted */
    suspend fun fileRecBytes(r: Rec, id: String): ByteArray {
        if (!r.enc) return r.data
        val key = filesKey(false) ?: throw Fail("unreadable")
        return try { withContext(Dispatchers.Default) { Crypto.decrypt(key, r.iv!!, r.data, fileAad(id)) } } catch (e: Exception) { throw Fail("unreadable") }
    }

    // ---------- reading and verifying ----------

    /** fileStates[id]: ok | missing | mismatch | unreadable (session only). */
    val fileStates = HashMap<String, String>()
    private val verifyQueue = ArrayList<String>()
    private var verifyBusy = false
    private val scope = MainScope()

    /** fileLoad(id): the bytes and whether they match the original hash. Throws Fail missing / unreadable. */
    suspend fun fileLoad(id: String): Pair<ByteArray, Boolean> {
        val meta = fileById(id)
        val rec = if (meta != null) fileRecGet(id) else null
        if (rec == null) { fileStates[id] = "missing"; throw Fail("missing") }
        val buf = try { fileRecBytes(rec, id) } catch (e: Fail) { fileStates[id] = "unreadable"; throw e }
        val ok = withContext(Dispatchers.Default) { fileSha256(buf) } == meta!!.s("sha256")
        fileStates[id] = if (ok) "ok" else "mismatch"
        return buf to ok
    }

    suspend fun readFileBytes(id: String, allowMismatch: Boolean = false): ByteArray {
        val (buf, ok) = fileLoad(id)
        if (!ok && !allowMismatch) throw Fail("mismatch")
        return buf
    }

    suspend fun verifyFile(id: String): String { runCatching { fileLoad(id) }; return fileStates[id] ?: "missing" }

    /** The state to draw now; one not checked this session is checked in the background, then the screen redraws. */
    fun fileState(id: String): String {
        fileStates[id]?.let { return it }
        if (id !in verifyQueue) verifyQueue.add(id)
        if (!verifyBusy) scope.launch {
            verifyBusy = true
            try { while (verifyQueue.isNotEmpty()) { val x = verifyQueue.removeAt(0); filesExclusive { verifyFile(x) } } } finally { verifyBusy = false }
            Ledger.bump()
        }
        return "checking"
    }

    // ---------- room on the device ----------

    fun filesStorageInfo(): Obj? = runCatching {
        val st = StatFs((dir ?: return null).absolutePath)
        val quota = st.totalBytes.toDouble(); val free = st.availableBytes.toDouble()
        obj("usage" to quota - free, "quota" to quota, "free" to free)
    }.getOrNull()

    fun filesRoomFor(size: Double): Obj {
        val s = filesStorageInfo() ?: return obj("ok" to true, "warn" to false)
        val free = s.num("free"); val quota = s.num("quota")
        if (free < size * 2 + 1048576) return obj("ok" to false, "warn" to true).apply { putAll(s) }
        return obj("ok" to true, "warn" to (free - size < maxOf(50.0 * 1048576, quota * 0.1))).apply { putAll(s) }
    }

    // ---------- adding, replacing, editing, removing ----------

    /** fileIngest(): { ok, rec } or { ok:false, error } (empty | type | mismatch | tooBig | noRoom | verify | failed). */
    suspend fun fileIngest(name: String, mime: String?, bytes: ByteArray, id: String): Obj {
        val check = fileTypeCheck(name, mime, bytes.size.toDouble())
        if (!check.b("ok")) return obj("ok" to false, "error" to check["reason"])
        if (bytes.size > fileCapMB() * 1048576) return obj("ok" to false, "error" to "tooBig")
        if (!filesRoomFor(bytes.size.toDouble()).b("ok")) return obj("ok" to false, "error" to "noRoom")
        val ext = check.s("ext")
        return try {
            if (!fileSniff(bytes.copyOfRange(0, minOf(1024, bytes.size)), ext)) return obj("ok" to false, "error" to "mismatch")
            val sha = withContext(Dispatchers.Default) { fileSha256(bytes) }
            fileRecPut(if (Crypto.locked) fileSealRec(id, bytes) else filePlainRec(id, bytes, check.s("mime")))
            val back = fileRecGet(id)
            if (back == null || withContext(Dispatchers.Default) { fileSha256(fileRecBytes(back, id)) } != sha) { fileRecDel(id); return obj("ok" to false, "error" to "verify") }
            var nm = name.trim().ifEmpty { "document-" + localISO() }
            if (fileExt(nm) != ext && FILE_TYPES[fileExt(nm)] == null) nm += ".$ext"
            obj("ok" to true, "rec" to obj("name" to nm, "mime" to check["mime"], "size" to bytes.size, "sha256" to sha, "enc" to back.enc))
        } catch (e: Exception) { runCatching { fileRecDel(id) }; obj("ok" to false, "error" to "failed") }
    }

    suspend fun addFile(ownerType: String, ownerId: String, name: String, mime: String?, bytes: ByteArray, info: Obj? = null): Obj {
        val id = fileNewId()
        val r = fileIngest(name, mime, bytes, id)
        if (!r.b("ok")) return r
        val meta = obj("id" to id, "ownerType" to ownerType, "ownerId" to ownerId, "label" to str(info?.get("label")).trim(), "docKind" to fileKindClean(info?.get("docKind")))
        meta.putAll(r.o("rec")!!)
        meta["addedAt"] = Clock.millis().toDouble(); meta["docDate"] = info?.get("docDate") ?: ""; meta["note"] = str(info?.get("note"))
        if (DB["files"] !is List<*>) DB["files"] = arr()
        DB.aa("files").add(meta)
        fileStates[id] = "ok"
        UiHooks.save()
        return obj("ok" to true, "meta" to meta)
    }

    suspend fun replaceFile(id: String, name: String, mime: String?, bytes: ByteArray): Obj {
        val meta = fileById(id) ?: return obj("ok" to false, "error" to "failed")
        val nid = fileNewId()
        val r = fileIngest(name, mime, bytes, nid)
        if (!r.b("ok")) return r
        meta.putAll(r.o("rec")!!); meta["id"] = nid; meta["addedAt"] = Clock.millis().toDouble()
        fileStates.remove(id); fileStates[nid] = "ok"
        UiHooks.save()
        runCatching { fileRecDel(id) }
        return obj("ok" to true, "meta" to meta)
    }

    fun updateFileMeta(id: String, patch: Obj?): Obj? {
        val meta = fileById(id) ?: return null
        patch ?: return null
        listOf("label", "docKind", "docDate", "note", "ownerType", "ownerId").forEach { k ->
            if (!patch.containsKey(k)) return@forEach
            var v = patch[k]?.let { jsString(it) } ?: ""
            if (k == "label" || k == "note") v = v.trim()
            if (k == "docKind") v = fileKindClean(v)
            meta[k] = v
        }
        UiHooks.save()
        return meta
    }

    suspend fun removeFile(id: String): Boolean {
        if (fileById(id) == null) return false
        DB["files"] = ArrayList<Any?>(filesAll().filter { it["id"] != id })
        fileStates.remove(id)
        UiHooks.save()
        runCatching { fileRecDel(id) }
        return true
    }

    suspend fun removeOwnerFiles(ownerType: String, ownerId: String): Int {
        val ids = filesOf(ownerType, ownerId).map { it.s("id") }
        ids.forEach { removeFile(it) }
        return ids.size
    }

    // ---------- keeping the stored copies in step with the lock ----------

    private var sweptFor: String? = null
    private var syncQueued = false

    /** filesAfterPersist(): called after every save and once at start; cheap when nothing needs doing. */
    fun filesAfterPersist() {
        val k = Crypto.key
        val kid = k?.let { Crypto.b64(it) }
        if (k != null && sweptFor != kid) { sweptFor = kid; filesSyncEncryption(); return }
        val list = filesAll()
        if (list.isEmpty() && !truthy(DB["filesKey"])) return
        val want = k != null
        if (list.all { it.b("enc") == want } && (want || !truthy(DB["filesKey"]))) return
        filesSyncEncryption()
    }

    fun filesSyncEncryption() {
        if (syncQueued) return
        syncQueued = true
        scope.launch {
            filesExclusive { syncQueued = false; runCatching { filesSyncPass() } }
        }
    }

    private suspend fun filesSyncPass() {
        val want = Crypto.locked
        val encOf = HashMap<String, Boolean>()
        var changed = false; var stillEnc = false
        for (id in fileRecKeys()) {
            if (Crypto.locked != want) { syncQueued = false; filesSyncEncryption(); return }
            val rec = fileRecGet(id) ?: continue
            var enc = rec.enc
            if (enc != want) {
                val meta = fileById(id)
                try {
                    val buf = fileRecBytes(rec, id)
                    if (meta == null || withContext(Dispatchers.Default) { fileSha256(buf) } == meta.s("sha256")) {
                        fileRecPut(if (want) fileSealRec(id, buf) else filePlainRec(id, buf, meta?.s("mime")))
                        enc = want
                    }
                } catch (_: Exception) {}
            }
            encOf[id] = enc
            if (enc) stillEnc = true
        }
        filesAll().forEach { m -> val e = encOf[m.s("id")]; if (e != null && m.b("enc") != e) { m["enc"] = e; changed = true } }
        if (!want && !stillEnc && truthy(DB["filesKey"])) { DB.remove("filesKey"); changed = true }
        if (changed) Ledger.persist()
    }

    // ---------- files-health.js ----------

    /** filesHealthScan(): { unavailable (rows), strays ({ id, size, ts }), total } */
    suspend fun filesHealthScan(): Obj {
        val rows = filesAll()
        val keys = runCatching { filesExclusive { fileRecKeys() } }.getOrDefault(emptyList())
        val have = keys.toSet(); val ids = rows.map { it.s("id") }.toSet()
        val unavailable = rows.filter { it.s("id") !in have }
        val strays = arr()
        for (k in keys) {
            if (k in ids) continue
            val (size, ts) = runCatching { fileRecSize(k) }.getOrDefault(0L to 0L)
            strays.add(obj("id" to k, "size" to size.toDouble(), "ts" to ts.toDouble()))
        }
        return obj("unavailable" to ArrayList<Any?>(unavailable), "strays" to strays, "total" to rows.size)
    }

    /** filesHealthClean(): deletes stray records (never one with a row, never one younger than a minute). */
    suspend fun filesHealthClean(): Obj = filesExclusive {
        val ids = filesAll().map { it.s("id") }.toSet()
        var removed = 0; var bytes = 0.0
        for (k in fileRecKeys()) {
            if (k in ids) continue
            val (size, ts) = fileRecSize(k)
            if (Clock.millis() - ts < 60000) continue
            if (filesAll().any { it["id"] == k }) continue
            fileRecDel(k); removed++; bytes += size
        }
        obj("removed" to removed, "bytes" to bytes)
    }
}
