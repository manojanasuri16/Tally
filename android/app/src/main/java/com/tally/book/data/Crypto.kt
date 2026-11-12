package com.tally.book.data

import com.tally.book.core.*
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The lock (PIN, pattern or password) IS the key material:
 * PBKDF2-HMAC-SHA256 (150k rounds) over the UTF-8 bytes of what was entered, then AES-GCM-256.
 * The envelope is { enc:1, s:salt, i:iv, d:ciphertext+tag, t:type, l:pinLength }.
 */
// The phone's lock (t: "phone") is the same envelope with a random 20-digit recovery code as the secret (data/LockRules.kt);
// the phone's own prompt opens a Keystore-sealed copy of the derived key (ui/lock/PhoneLock.kt).
object Crypto {
    const val LOCK_ROUNDS = 150_000
    private val random = SecureRandom()

    /** The current lock's key, salt, kind and PIN length . Null key = no lock. */
    @Volatile var key: ByteArray? = null
    @Volatile var salt: ByteArray? = null
    @Volatile var type: String = "pin"
    @Volatile var pinLen: Int = 0

    val locked: Boolean get() = key != null

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

    /** `seal(obj)` under the current lock. Call with the JSON text already made (on the main thread). */
    fun sealText(json: String): Obj {
        val k = key ?: error("no lock")
        val iv = randomBytes(12)
        val ct = encrypt(k, iv, json.toByteArray(Charsets.UTF_8))
        return obj("enc" to 1, "s" to b64(salt!!), "i" to b64(iv), "d" to b64(ct), "t" to type, "l" to if (type == "pin") pinLen else 0)
    }

    /** `unseal(env, secret)`: opens the envelope and makes its lock the current one. Throws on a wrong secret. */
    fun unseal(env: Map<String, Any?>, secret: String): Any? {
        val s = unb64(env.s("s"))
        val k = deriveKeyBits(secret, s)
        val pt = decrypt(k, unb64(env.s("i")), unb64(env.s("d")))
        key = k; salt = s; type = env.s("t").ifEmpty { "pin" }; pinLen = if (type == "pin") secret.length else 0
        return JsonCodec.parse(String(pt, Charsets.UTF_8))
    }

    /** `unsealWithKey(env, keyBytes)`: the fingerprint path, which already has the key. */
    fun unsealWithKey(env: Map<String, Any?>, keyBytes: ByteArray): Any? {
        val pt = decrypt(keyBytes, unb64(env.s("i")), unb64(env.s("d")))
        key = keyBytes; salt = unb64(env.s("s")); type = env.s("t").ifEmpty { "pin" }; pinLen = if (type == "pin") n(env["l"]).toInt() else 0
        return JsonCodec.parse(String(pt, Charsets.UTF_8))
    }

    /** An envelope opened with a secret, without making its lock the current one (a backup being restored). */
    class Opened(val key: ByteArray, val salt: ByteArray, val type: String, val pinLen: Int, val text: String)

    /** Opens [env] with [secret] and changes nothing; null on a wrong secret or a damaged envelope. */
    fun openEnvelope(env: Map<String, Any?>, secret: String): Opened? = runCatching {
        val s = unb64(env.s("s"))
        val k = deriveKeyBits(secret, s)
        val pt = decrypt(k, unb64(env.s("i")), unb64(env.s("d")))
        val t = env.s("t").ifEmpty { "pin" }
        Opened(k, s, t, if (t == "pin") secret.length else 0, String(pt, Charsets.UTF_8))
    }.getOrNull()

    /** Opens [env] with key bytes already known (the phone's lock opening its own earlier copy) and changes nothing; null when they do not fit. */
    fun openEnvelopeWithKey(env: Map<String, Any?>, keyBytes: ByteArray): Opened? = runCatching {
        val pt = decrypt(keyBytes, unb64(env.s("i")), unb64(env.s("d")))
        val t = env.s("t").ifEmpty { "pin" }
        Opened(keyBytes, unb64(env.s("s")), t, if (t == "pin") n(env["l"]).toInt() else 0, String(pt, Charsets.UTF_8))
    }.getOrNull()

    /** Makes an opened envelope's lock the current one. */
    fun adopt(o: Opened) { key = o.key; salt = o.salt; type = o.type; pinLen = o.pinLen }

    /** Sets a new lock (or changes it). The next save seals with it. */
    fun setLock(secret: String, kind: String) {
        val s = randomBytes(16)
        key = deriveKeyBits(secret, s); salt = s; type = kind; pinLen = if (kind == "pin") secret.length else 0
    }

    fun clearLock() { key = null; salt = null; type = "pin"; pinLen = 0 }
}
