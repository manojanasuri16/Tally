package com.tally.book.data

import com.tally.book.core.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The book's seal (docs/DESIGN.md, "App lock", "The seal").
 *
 * Version 2 (Tally 0.6 on): the book is AES-256-GCM under a random 256-bit data key, and only wrapped copies of that key are
 * stored with it, so the book is never as weak as a short PIN:
 *
 *   { enc: 2, s, i, d, t, l,
 *     r: { s, i, d },          the data key under PBKDF2(recovery code, r.s): opens the book (and every backup) anywhere
 *     p: { s, i, d, dv } }     Tally's own lock only: the data key under HKDF(this phone's secret, salt = PBKDF2(PIN, p.s)):
 *                              opens it only on the phone whose secret ([DeviceSecret], kept under an Android Keystore key)
 *                              made it; dv names that phone (not secret)
 *
 * `s` is the lock's id: the salt of the wrap a device copy opens (p.s for Tally's own lock, r.s for the phone's lock). A device
 * copy (fingerprint, the phone's lock) keeps { s, k } with k = PBKDF2(secret, s): the PIN's key, or the recovery code's key.
 * `t` is the kind (pin, pattern, password, phone), `l` a PIN's length.
 *
 * Version 1 (up to 0.5): { enc: 1, s, i, d, t, l }, the book under PBKDF2(secret, s) itself. Still read; the first unlock
 * re-seals it as version 2 ([upgrade]): the old key becomes the PIN's key (so a fingerprint copy still fits) or, for the phone's
 * lock, the recovery code's key (so the phone's copy still fits).
 */

/** This phone's own 32 secret bytes, kept under an Android Keystore key (ui/lock/DeviceKey.kt); a fake in tests. Never in the book or a backup. */
interface DeviceSecret {
    /** The bytes, made the first time they are asked for. */
    fun get(): ByteArray
}

/** A device secret held in memory only: the JVM tests' "phone" (a new instance is a new phone). */
class MemoryDevice : DeviceSecret {
    private val b = Crypto.randomBytes(32)
    override fun get(): ByteArray = b
}

object Crypto {
    const val LOCK_ROUNDS = 150_000
    const val VERSION = 2
    private val random = SecureRandom()

    /** This phone's secret (the Keystore-backed one is put here at start). */
    @Volatile var device: DeviceSecret = MemoryDevice()

    /** The book's data key while it is open. Null with no lock, and after a re-lock (it is zeroed then: [forget]). */
    @Volatile var key: ByteArray? = null
    /** The lock's id (the envelope's `s`): which device copy fits. Kept while locked again (it is not secret). */
    @Volatile var salt: ByteArray? = null
    @Volatile var type: String = "pin"
    @Volatile var pinLen: Int = 0
    /** { r, p? }: the wrapped copies of the data key written into every envelope. */
    @Volatile var wraps: Obj? = null
    /** A lock is set but its key was forgotten (re-locked): still [locked]. */
    @Volatile private var asleep = false

    val locked: Boolean get() = key != null || asleep

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** PBKDF2-HMAC-SHA256, 32-byte output, over the raw UTF-8 bytes of [secret] (as WebCrypto does). */
    fun deriveKeyBits(secret: String, salt: ByteArray, iterations: Int = LOCK_ROUNDS): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))   // a lock is never empty
        // block 1 is enough for 32 bytes
        mac.update(salt); mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        for (i in 2..iterations) {
            u = mac.doFinal(u)
            for (j in out.indices) out[j] = (out[j].toInt() xor u[j].toInt()).toByte()
        }
        return out
    }

    fun encrypt(keyBytes: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun decrypt(keyBytes: ByteArray, iv: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(sealed)
    }

    // ---------- the wraps ----------

    /** HKDF-SHA256 (RFC 5869), 32 bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String): ByteArray {
        val ext = Mac.getInstance("HmacSHA256")
        ext.init(SecretKeySpec(if (salt == null || salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = ext.doFinal(ikm)
        val exp = Mac.getInstance("HmacSHA256")
        exp.init(SecretKeySpec(prk, "HmacSHA256"))
        exp.update(info.toByteArray(Charsets.UTF_8)); exp.update(1.toByte())
        return exp.doFinal()
    }

    /** The key that wraps the data key for a PIN on this phone: HKDF(this phone's secret, salt = the PIN's key). */
    private fun deviceKek(pinKey: ByteArray): ByteArray = hkdf(device.get(), pinKey, "tally-lock-pin")

    /** This phone's name in an envelope (`p.dv`): 72 bits derived from its secret, so a backup from another phone asks for the recovery code at once. */
    fun deviceId(): String = b64(hkdf(device.get(), null, "tally-device-id").copyOf(9))

    private fun wrap(kek: ByteArray, salt: ByteArray, dk: ByteArray, tag: String): Obj {
        val iv = randomBytes(12)
        return obj("s" to b64(salt), "i" to b64(iv), "d" to b64(encrypt(kek, iv, dk, "tally-lock:$tag".toByteArray())))
    }

    private fun unwrap(kek: ByteArray, w: Map<String, Any?>, tag: String): ByteArray =
        decrypt(kek, unb64(w.s("i")), unb64(w.s("d")), "tally-lock:$tag".toByteArray()).also { require(it.size == 32) }

    fun version(env: Map<String, Any?>): Int = n(env["enc"]).toInt()
    private fun kindOf(env: Map<String, Any?>): String = env.s("t").ifEmpty { "pin" }

    /**
     * Whether a typed PIN, pattern or password can open [env] on this phone. False for a version 2 envelope of Tally's own lock
     * made on another phone (a backup): only its recovery code opens it here.
     */
    fun pinFits(env: Map<String, Any?>): Boolean {
        if (version(env) < VERSION || kindOf(env) == LockRules.PHONE) return true
        val p = env.o("p") ?: return false
        return p.s("dv") == runCatching { deviceId() }.getOrNull()
    }

    // ---------- an opened envelope, and a lock ready to be used ----------

    /**
     * An envelope opened (or a lock just made) without making it the current lock. [key] is the data key; for a version 1
     * envelope ([legacy]) it is the old PBKDF2 key itself and [wraps] is null until [upgrade].
     */
    class Opened(val key: ByteArray, val salt: ByteArray, val type: String, val pinLen: Int, val text: String, val wraps: Obj? = null) {
        val legacy: Boolean get() = wraps == null
    }

    /** Makes an opened envelope's lock (version 2) the current one. A different data key before it is kept in [former] for this session. */
    fun adopt(o: Opened) {
        require(o.wraps != null) { "upgrade first" }
        key?.let { if (!it.contentEquals(o.key)) remember(it) }
        key = o.key; salt = o.salt; type = o.type; pinLen = o.pinLen; wraps = o.wraps; asleep = false
    }

    /**
     * Keys of this book's earlier seals known in this session (a version 1 key from the upgrade, a data key before a change), so
     * an older backup of this book can be sealed again under the current lock (L3: Backups.resealOlder). Zeroed on re-lock.
     */
    private val former = ArrayList<ByteArray>()

    fun remember(k: ByteArray) { synchronized(former) { if (former.none { it.contentEquals(k) }) former.add(k.copyOf()) } }

    /** [env]'s plain text when the current data key or a [former] key opens it (either version); null otherwise. */
    fun openKnown(env: Map<String, Any?>): String? {
        val keys = synchronized(former) { listOfNotNull(key) + former.toList() }
        for (k in keys) {
            val text = runCatching { String(decrypt(k, unb64(env.s("i")), unb64(env.s("d"))), Charsets.UTF_8) }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    private fun forgetFormer() { synchronized(former) { former.forEach { it.fill(0) }; former.clear() } }

    /**
     * Opens [env] with what was typed and changes nothing: the PIN, pattern or password, or with [viaCode] (and always for the
     * phone's lock) the recovery code. Null on a wrong secret, a damaged envelope, or a PIN on a phone it was not made on.
     */
    fun openEnvelope(env: Map<String, Any?>, secret: String, viaCode: Boolean = false): Opened? = runCatching {
        val t = kindOf(env)
        if (version(env) < VERSION) {
            val s = unb64(env.s("s"))
            val k = deriveKeyBits(secret, s)
            val pt = decrypt(k, unb64(env.s("i")), unb64(env.s("d")))
            return@runCatching Opened(k, s, t, if (t == "pin") secret.length else 0, String(pt, Charsets.UTF_8))
        }
        val dk = if (viaCode || t == LockRules.PHONE) {
            val r = env.o("r")!!
            unwrap(deriveKeyBits(secret, unb64(r.s("s"))), r, "r")
        } else {
            if (!pinFits(env)) return@runCatching null
            val p = env.o("p")!!
            unwrap(deviceKek(deriveKeyBits(secret, unb64(p.s("s")))), p, "p")
        }
        opened(env, dk)
    }.getOrNull()

    /** Opens [env] with a device copy's key (fingerprint, the phone's lock): the PIN's or the recovery code's PBKDF2 key. Changes nothing. */
    fun openEnvelopeWithKey(env: Map<String, Any?>, copyKey: ByteArray): Opened? = runCatching {
        val t = kindOf(env)
        if (version(env) < VERSION) {
            val pt = decrypt(copyKey, unb64(env.s("i")), unb64(env.s("d")))
            return@runCatching Opened(copyKey, unb64(env.s("s")), t, if (t == "pin") n(env["l"]).toInt() else 0, String(pt, Charsets.UTF_8))
        }
        val dk = if (t == LockRules.PHONE) unwrap(copyKey, env.o("r")!!, "r")
        else { if (!pinFits(env)) return@runCatching null; unwrap(deviceKek(copyKey), env.o("p")!!, "p") }
        opened(env, dk)
    }.getOrNull()

    /** A version 2 envelope's plain text with a data key already known (re-sealing an older backup of this book); null when it does not fit. */
    fun openWithDataKey(env: Map<String, Any?>, dk: ByteArray): String? = runCatching {
        if (version(env) < VERSION) null else String(decrypt(dk, unb64(env.s("i")), unb64(env.s("d"))), Charsets.UTF_8)
    }.getOrNull()

    private fun opened(env: Map<String, Any?>, dk: ByteArray): Opened {
        val t = kindOf(env)
        val pt = decrypt(dk, unb64(env.s("i")), unb64(env.s("d")))
        val w = obj("r" to env.o("r")!!.copyObj())
        env.o("p")?.let { w["p"] = it.copyObj() }
        return Opened(dk, unb64(env.s("s")), t, if (t == "pin") n(env["l"]).toInt() else 0, String(pt, Charsets.UTF_8), w)
    }

    /** `unseal(env, secret)`: opens the envelope and makes its lock the current one (a version 1 one is upgraded with [code]). Throws on a wrong secret. */
    fun unseal(env: Map<String, Any?>, secret: String, viaCode: Boolean = false, code: String? = null): Any? {
        val o = openEnvelope(env, secret, viaCode) ?: error("wrong")
        adopt(if (o.legacy) upgrade(o, code ?: RecoveryCode.make()) else o)
        return JsonCodec.parse(o.text)
    }

    /** `unsealWithKey(env, copyKey)`: the device-copy path. Throws when it does not fit. */
    fun unsealWithKey(env: Map<String, Any?>, copyKey: ByteArray, code: String? = null): Any? {
        val o = openEnvelopeWithKey(env, copyKey) ?: error("wrong")
        adopt(if (o.legacy) upgrade(o, code ?: RecoveryCode.make()) else o)
        return JsonCodec.parse(o.text)
    }

    // ---------- making a lock ----------

    /** The current lock's data key, when [code] opens its recovery wrap (so a change keeps the data key and the code's wrap). */
    private fun keptFor(code: String?): Pair<ByteArray, Obj>? {
        val k = key ?: return null
        val r = wraps?.o("r") ?: return null
        if (code == null) return null
        val dk = runCatching { unwrap(deriveKeyBits(code, unb64(r.s("s"))), r, "r") }.getOrNull() ?: return null
        return if (dk.contentEquals(k)) dk to r.copyObj() else null
    }

    /**
     * Tally's own lock of [kind] with [secret], whose recovery code is [code]. When the current lock already has that code, its
     * data key and recovery wrap stay (a change of PIN, a switch from the phone's lock); otherwise both are new.
     */
    fun makeOwn(secret: String, kind: String, code: String): Opened {
        val (dk, r) = keptFor(code) ?: randomBytes(32).let { dk -> val rs = randomBytes(16); dk to wrap(deriveKeyBits(code, rs), rs, dk, "r") }
        val ps = randomBytes(16)
        val p = wrap(deviceKek(deriveKeyBits(secret, ps)), ps, dk, "p").also { it["dv"] = deviceId() }
        return Opened(dk, ps, kind, if (kind == "pin") secret.length else 0, "", obj("r" to r, "p" to p))
    }

    /** The phone's lock with [code]: [Made.copyKey] is what the phone's Keystore copy keeps. The data key stays when the code does. */
    class Made(val lock: Opened, val copyKey: ByteArray)

    fun makePhone(code: String): Made {
        val kept = keptFor(code)
        val dk = kept?.first ?: randomBytes(32)
        val rs = kept?.second?.s("s")?.let { unb64(it) } ?: randomBytes(16)
        val rk = deriveKeyBits(code, rs)
        val r = kept?.second ?: wrap(rk, rs, dk, "r")
        return Made(Opened(dk, rs, LockRules.PHONE, 0, "", obj("r" to r)), rk)
    }

    /** The phone's Keystore copy key for the current phone's lock, from its recovery [code]; null when the code does not fit. */
    fun phoneCopyKey(code: String): ByteArray? {
        val r = wraps?.o("r") ?: return null
        val rk = deriveKeyBits(code, unb64(r.s("s")))
        val dk = runCatching { unwrap(rk, r, "r") }.getOrNull() ?: return null
        return if (dk.contentEquals(key)) rk else null
    }

    /**
     * A version 1 envelope's lock as a version 2 one, under a new random data key. Tally's own lock: the old key is the PIN's key
     * (salt unchanged, so a fingerprint copy still fits) and [code] is a new recovery code. The phone's lock: the old key is the
     * recovery code's key (salt unchanged, so the phone's copy still fits).
     */
    fun upgrade(o: Opened, code: String?): Opened {
        if (!o.legacy) return o
        val dk = randomBytes(32)
        val w = if (o.type == LockRules.PHONE) obj("r" to wrap(o.key, o.salt, dk, "r"))
        else {
            val rs = randomBytes(16)
            obj(
                "r" to wrap(deriveKeyBits(code ?: error("a recovery code is needed"), rs), rs, dk, "r"),
                "p" to wrap(deviceKek(o.key), o.salt, dk, "p").also { it["dv"] = deviceId() },
            )
        }
        return Opened(dk, o.salt, o.type, o.pinLen, o.text, w)
    }

    /**
     * Checks a secret against the current lock without changing it ("confirm it's you"): the PIN, pattern or password, or with
     * [viaCode] (always for the phone's lock) the recovery code. Returns the device copy key it gives (the PIN's key, for setting
     * up the fingerprint), or null when it is wrong.
     */
    fun checkSecret(secret: String, viaCode: Boolean = false): ByteArray? = runCatching {
        val k = key ?: return@runCatching null
        val w = wraps ?: return@runCatching null
        if (viaCode || type == LockRules.PHONE) {
            val r = w.o("r")!!
            val rk = deriveKeyBits(secret, unb64(r.s("s")))
            if (unwrap(rk, r, "r").contentEquals(k)) rk else null
        } else {
            val p = w.o("p") ?: return@runCatching null
            val pk = deriveKeyBits(secret, unb64(p.s("s")))
            if (unwrap(deviceKek(pk), p, "p").contentEquals(k)) pk else null
        }
    }.getOrNull()

    /** Whether a device copy's key (from the fingerprint or the phone's prompt) belongs to the current lock. */
    fun checkCopyKey(copyKey: ByteArray): Boolean = runCatching {
        val k = key ?: return@runCatching false
        val w = wraps ?: return@runCatching false
        val dk = if (type == LockRules.PHONE) unwrap(copyKey, w.o("r")!!, "r") else unwrap(deviceKek(copyKey), w.o("p")!!, "p")
        dk.contentEquals(k)
    }.getOrDefault(false)

    /** Whether the current lock's PIN wrap was made on this phone (false after a restore from another phone, opened with the code). */
    fun currentPinFits(): Boolean = type == LockRules.PHONE || wraps?.o("p")?.s("dv") == runCatching { deviceId() }.getOrNull()

    // ---------- sealing ----------

    /** `seal(obj)` under the current lock. Call with the JSON text already made (on the main thread). */
    fun sealText(json: String): Obj {
        val k = key ?: error("no lock")
        val w = wraps ?: error("no lock")
        val iv = randomBytes(12)
        val ct = encrypt(k, iv, json.toByteArray(Charsets.UTF_8))
        val env = obj("enc" to VERSION, "s" to b64(salt!!), "i" to b64(iv), "d" to b64(ct), "t" to type, "l" to if (type == "pin") pinLen else 0)
        env["r"] = w.o("r")!!.copyObj()
        w.o("p")?.let { env["p"] = it.copyObj() }
        return env
    }

    /** Sets a new lock of Tally's own kind (tests and simple callers). The next save seals with it. */
    fun setLock(secret: String, kind: String, code: String = RecoveryCode.make()) {
        adopt(if (kind == LockRules.PHONE) makePhone(secret).lock else makeOwn(secret, kind, code))
    }

    /** No lock at all. */
    fun clearLock() { key = null; salt = null; type = "pin"; pinLen = 0; wraps = null; asleep = false; forgetFormer() }

    /** Re-locked: the data key (and any [former] one) is zeroed and forgotten; the lock stays set ([locked]) and the next unlock reads it again. */
    fun forget() {
        if (!locked) return
        key?.fill(0)
        key = null; asleep = true
        forgetFormer()
    }
}
