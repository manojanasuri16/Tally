package com.tally.book.sync

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
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
 *
 * Version 2 (docs/DESIGN.md, "Keys and epochs"): the family has a keyring of (epoch, key); a v2 blob is
 *   base64(2 || epoch (4 bytes, big-endian) || iv(12) || ciphertext || tag(16)), the 5-byte header fed to GCM ahead of the AAD,
 * and encKey = HKDF(key of that epoch, "tally-enc"). Version 1 blobs (one key, no epoch) are still opened by [open].
 *
 * Sealing to a member (key boxes): ECIES over P-256. An ephemeral key pair, ECDH with the recipient's public key,
 * HKDF-SHA256(Z, salt = ephemeral SPKI || recipient SPKI, info = "tally-box-v1|" + context), AES-256-GCM with the context as
 * AAD. Blob: base64(1 || ephemeral SPKI (91 bytes) || iv(12) || ciphertext || tag). It hides the contents from the relay;
 * who sent it is proved inside, by a MAC under a key only family members (or the invite's holders) have (sync/Membership.kt).
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

    // ---------- version 2: a keyring with epochs ----------

    const val VERSION_EPOCH: Int = 2
    private const val V2_HEAD = 5

    private fun v2Header(epoch: Int): ByteArray =
        byteArrayOf(VERSION_EPOCH.toByte(), (epoch ushr 24).toByte(), (epoch ushr 16).toByte(), (epoch ushr 8).toByte(), epoch.toByte())

    /** base64(2 || epoch || iv || ciphertext+tag) under the encKey of that epoch. */
    fun sealE(encKey: ByteArray, epoch: Int, plain: ByteArray, aad: ByteArray): String {
        require(epoch >= 1) { "bad epoch" }
        val head = v2Header(epoch)
        val iv = randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(head); c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return Base64.getEncoder().encodeToString(head + iv + ct)
    }

    /** The epoch a v2 blob says it was sealed under, 0 for a version 1 blob, -1 for anything else. Not authenticated until opened. */
    fun epochOf(blob: String): Int {
        val raw = runCatching { Base64.getDecoder().decode(blob) }.getOrNull() ?: return -1
        if (raw.isEmpty()) return -1
        return when (raw[0].toInt()) {
            VERSION -> 0
            VERSION_EPOCH -> if (raw.size < V2_HEAD + IV_LEN + TAG_BITS / 8) -1 else {
                val e = ((raw[1].toInt() and 0xff) shl 24) or ((raw[2].toInt() and 0xff) shl 16) or ((raw[3].toInt() and 0xff) shl 8) or (raw[4].toInt() and 0xff)
                if (e >= 1) e else -1
            }
            else -> -1
        }
    }

    /** Opens a v2 blob with the keyring ([keyFor] gives the encKey of an epoch, or null): null when it does not open. */
    fun openE(keyFor: (Int) -> ByteArray?, blob: String, aad: ByteArray): ByteArray? = runCatching {
        val epoch = epochOf(blob)
        if (epoch < 1) return null
        val k = keyFor(epoch) ?: return null
        val raw = Base64.getDecoder().decode(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, raw, V2_HEAD, IV_LEN))
        c.updateAAD(raw, 0, V2_HEAD); c.updateAAD(aad)
        c.doFinal(raw, V2_HEAD + IV_LEN, raw.size - V2_HEAD - IV_LEN)
    }.getOrNull()

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun hkdfInfo(ikm: ByteArray, info: String, length: Int = 32): ByteArray = hkdf(ikm, null, info.toByteArray(Charsets.UTF_8), length)

    /** Constant-time comparison. */
    fun same(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---------- member identity keys (P-256) and sealing to a public key ----------

    class Identity(val publicSpki: ByteArray, val privatePkcs8: ByteArray)

    private val P256: ECParameterSpec by lazy {
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"), random)
        (g.generateKeyPair().public as ECPublicKey).params
    }

    fun newIdentity(): Identity {
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"), random)
        val kp = g.generateKeyPair()
        return Identity(kp.public.encoded, kp.private.encoded)
    }

    /** A P-256 public key in X.509 SubjectPublicKeyInfo form, or null for anything else (another curve, garbage, a bad point). */
    fun publicKey(spki: ByteArray): PublicKey? = runCatching {
        if (spki.size != SPKI_LEN) return null
        val k = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey
        val p = k.params
        if (p.curve != P256.curve || p.generator != P256.generator || p.order != P256.order) return null
        k
    }.getOrNull()

    private fun privateKey(pkcs8: ByteArray): PrivateKey? = runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }.getOrNull()

    /** A P-256 SubjectPublicKeyInfo is always 91 bytes. */
    const val SPKI_LEN = 91
    private const val BOX_VERSION = 1

    private fun boxKey(z: ByteArray, ephSpki: ByteArray, toSpki: ByteArray, context: String): ByteArray =
        hkdf(z, ephSpki + toSpki, ("tally-box-v1|$context").toByteArray(Charsets.UTF_8), 32)

    /** Seals [plain] so that only the holder of the private key for [toSpki] can open it, bound to [context]. */
    fun sealTo(toSpki: ByteArray, plain: ByteArray, context: String): String {
        val to = requireNotNull(publicKey(toSpki)) { "bad public key" }
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"), random)
        val eph = g.generateKeyPair()
        val ka = KeyAgreement.getInstance("ECDH"); ka.init(eph.private); ka.doPhase(to, true)
        val z = ka.generateSecret()
        val ephSpki = eph.public.encoded
        val k = boxKey(z, ephSpki, toSpki, context)
        val iv = randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(byteArrayOf(BOX_VERSION.toByte())); c.updateAAD(context.toByteArray(Charsets.UTF_8))
        val ct = c.doFinal(plain)
        return Base64.getEncoder().encodeToString(byteArrayOf(BOX_VERSION.toByte()) + ephSpki + iv + ct)
    }

    /** Opens a box sealed to [me] for [context], or null (another recipient, another context, tampered, malformed). */
    fun openBox(me: Identity, blob: String, context: String): ByteArray? = runCatching {
        val raw = Base64.getDecoder().decode(blob)
        if (raw.size < 1 + SPKI_LEN + IV_LEN + TAG_BITS / 8 || raw[0].toInt() != BOX_VERSION) return null
        val ephSpki = raw.copyOfRange(1, 1 + SPKI_LEN)
        val eph = publicKey(ephSpki) ?: return null
        val priv = privateKey(me.privatePkcs8) ?: return null
        val ka = KeyAgreement.getInstance("ECDH"); ka.init(priv); ka.doPhase(eph, true)
        val k = boxKey(ka.generateSecret(), ephSpki, me.publicSpki, context)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(TAG_BITS, raw, 1 + SPKI_LEN, IV_LEN))
        c.updateAAD(byteArrayOf(raw[0])); c.updateAAD(context.toByteArray(Charsets.UTF_8))
        c.doFinal(raw, 1 + SPKI_LEN + IV_LEN, raw.size - 1 - SPKI_LEN - IV_LEN)
    }.getOrNull()

    fun openFile(encKey: ByteArray, familyId: String, fileId: String, blob: String): ByteArray? = open(encKey, blob, fileAad(familyId, fileId))

    // ---------- signatures (ECDSA P-256, SHA-256) with a member's key ----------

    /** An ECDSA signature (DER) over [data] with the member's private key, or null when the key is unusable. */
    fun sign(privatePkcs8: ByteArray, data: ByteArray): ByteArray? = runCatching {
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey(privatePkcs8) ?: return null)
        s.update(data)
        s.sign()
    }.getOrNull()

    /** True when [sig] is a valid signature over [data] by the P-256 key [spki]. */
    fun verify(spki: ByteArray, data: ByteArray, sig: ByteArray): Boolean = runCatching {
        val k = publicKey(spki) ?: return false
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initVerify(k)
        s.update(data)
        s.verify(sig)
    }.getOrDefault(false)

    // ---------- the proof of work for starting a family (the tally_hardening migration) ----------

    /** True when SHA-256("tally-pow|<family>|<member>|<nonce>") begins with [bits] zero bits. */
    fun powOk(family: String, member: String, nonce: String, bits: Int): Boolean {
        val h = MessageDigest.getInstance("SHA-256").digest("tally-pow|$family|$member|$nonce".toByteArray(Charsets.UTF_8))
        var left = bits; var i = 0
        while (left >= 8) { if (h[i].toInt() != 0) return false; left -= 8; i++ }
        return left == 0 || (h[i].toInt() and 0xff) ushr (8 - left) == 0
    }

    /** The first decimal nonce that satisfies [powOk] (the relay asks for 20 bits: about a million hashes). */
    fun pow(family: String, member: String, bits: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = "tally-pow|$family|$member|".toByteArray(Charsets.UTF_8)
        var n = 0L
        while (true) {
            md.reset(); md.update(prefix); val h = md.digest(n.toString().toByteArray(Charsets.UTF_8))
            var left = bits; var i = 0; var ok = true
            while (left >= 8) { if (h[i].toInt() != 0) { ok = false; break }; left -= 8; i++ }
            if (ok && (left == 0 || (h[i].toInt() and 0xff) ushr (8 - left) == 0)) return n.toString()
            n++
        }
    }
}
