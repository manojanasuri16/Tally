package com.tally.book.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The family's cryptography (docs/DESIGN.md, "Sync" > "Keys").
 *
 * family.key (32 random bytes) -> HKDF-SHA256 (RFC 5869, no salt, L = 32):
 *   encKey    = HKDF(key, info "tally-enc")   AES-256-GCM key for every record and file
 *   authToken = hex(HKDF(key, info "tally-auth"))   proves membership; the relay stores only sha256(authToken)
 *
 * A sealed blob is base64 (standard alphabet, padded) of  version(1 byte) || iv(12) || ciphertext || tag(16).
 * The version byte is also bound into the GCM additional data, so it cannot be swapped without detection.
 * Record AAD "tally:<familyId>:<recordId>", file AAD "tally-file:<familyId>:<fileId>": a blob only opens under the
 * family and id it was sealed for.
 */
object SyncCrypto {
    const val VERSION: Int = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ---------- HKDF (RFC 5869) ----------

    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val s = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(s, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "bad HKDF length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val take = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take; i++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray = hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // ---------- keys ----------

    fun encKey(familyKey: ByteArray): ByteArray = hkdf(familyKey, null, "tally-enc".toByteArray(Charsets.UTF_8), 32)

    fun authToken(familyKey: ByteArray): String = hex(hkdf(familyKey, null, "tally-auth".toByteArray(Charsets.UTF_8), 32))

    /** What the relay stores: sha256 of the token's UTF-8 bytes, lowercase hex (the SQL computes the same). */
    fun tokenHash(token: String): String = hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun recordAad(familyId: String, recordId: String): ByteArray = "tally:$familyId:$recordId".toByteArray(Charsets.UTF_8)
    fun fileAad(familyId: String, fileId: String): ByteArray = "tally-file:$familyId:$fileId".toByteArray(Charsets.UTF_8)

    // ---------- sealing ----------

    /** base64(version || iv || ciphertext+tag). */
    fun seal(encKey: ByteArray, plain: ByteArray, aad: ByteArray): String {
        val iv = randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(byteArrayOf(VERSION.toByte())); c.updateAAD(aad)
        val ct = c.doFinal(plain)
        val out = ByteArray(1 + IV_LEN + ct.size)
        out[0] = VERSION.toByte()
        System.arraycopy(iv, 0, out, 1, IV_LEN)
        System.arraycopy(ct, 0, out, 1 + IV_LEN, ct.size)
        return Base64.getEncoder().encodeToString(out)
    }

    /** The plaintext, or null for anything that does not open: bad base64, unknown version, wrong key or AAD, tampering. */
    fun open(encKey: ByteArray, blob: String, aad: ByteArray): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(blob)
        if (raw.size < 1 + IV_LEN + TAG_BITS / 8 || raw[0].toInt() != VERSION) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, raw, 1, IV_LEN))
        c.updateAAD(byteArrayOf(raw[0])); c.updateAAD(aad)
        c.doFinal(raw, 1 + IV_LEN, raw.size - 1 - IV_LEN)
    }.getOrNull()

    fun sealRecord(encKey: ByteArray, familyId: String, recordId: String, json: String): String =
        seal(encKey, json.toByteArray(Charsets.UTF_8), recordAad(familyId, recordId))

    fun openRecord(encKey: ByteArray, familyId: String, recordId: String, blob: String): String? =
        open(encKey, blob, recordAad(familyId, recordId))?.let { String(it, Charsets.UTF_8) }

    fun sealFile(encKey: ByteArray, familyId: String, fileId: String, bytes: ByteArray): String = seal(encKey, bytes, fileAad(familyId, fileId))

    fun openFile(encKey: ByteArray, familyId: String, fileId: String, blob: String): ByteArray? = open(encKey, blob, fileAad(familyId, fileId))
}
