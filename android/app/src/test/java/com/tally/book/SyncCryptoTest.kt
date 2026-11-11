package com.tally.book

import com.tally.book.sync.SyncCrypto
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SyncCryptoTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun range(from: Int, to: Int): ByteArray = ByteArray(to - from + 1) { (from + it).toByte() }

    // RFC 5869 appendix A, test cases 1-3 (SHA-256).
    @Test fun hkdfRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = range(0x00, 0x0c)
        val info = range(0xf0, 0xf9)
        val prk = SyncCrypto.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", SyncCrypto.hex(prk))
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            SyncCrypto.hex(SyncCrypto.hkdf(ikm, salt, info, 42)))
    }

    @Test fun hkdfRfc5869Case2() {
        val ikm = range(0x00, 0x4f); val salt = range(0x60, 0xaf); val info = range(0xb0, 0xff)
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", SyncCrypto.hex(SyncCrypto.hkdfExtract(salt, ikm)))
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            SyncCrypto.hex(SyncCrypto.hkdf(ikm, salt, info, 82)))
    }

    @Test fun hkdfRfc5869Case3() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", SyncCrypto.hex(SyncCrypto.hkdfExtract(null, ikm)))
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            SyncCrypto.hex(SyncCrypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42)))
    }

    @Test fun keysAreDistinctAndStable() {
        val key = ByteArray(32) { it.toByte() }
        val enc = SyncCrypto.encKey(key)
        val tok = SyncCrypto.authToken(key)
        assertEquals(32, enc.size)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(tok))
        assertNotEquals(SyncCrypto.hex(enc), tok)
        assertArrayEquals(enc, SyncCrypto.encKey(key.copyOf()))
        assertEquals(tok, SyncCrypto.authToken(key.copyOf()))
        assertEquals(SyncCrypto.hex(SyncCrypto.hkdf(key, null, "tally-auth".toByteArray(), 32)), tok)
        assertEquals(64, SyncCrypto.tokenHash(tok).length)
        assertNotEquals(tok, SyncCrypto.tokenHash(tok))
    }

    private val k1 = SyncCrypto.encKey(ByteArray(32) { 1 })
    private val k2 = SyncCrypto.encKey(ByteArray(32) { 2 })

    @Test fun recordRoundTripAndFormat() {
        val json = """{"k":"loan","r":{"id":"l1","name":"SBI home loan ₹"}}"""
        val blob = SyncCrypto.sealRecord(k1, "fam", "l1", json)
        val raw = Base64.getDecoder().decode(blob)
        assertEquals(SyncCrypto.VERSION, raw[0].toInt())
        assertEquals(1 + 12 + json.toByteArray().size + 16, raw.size)
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("SBI"))
        assertEquals(json, SyncCrypto.openRecord(k1, "fam", "l1", blob))
        // fresh IV every time
        assertNotEquals(blob, SyncCrypto.sealRecord(k1, "fam", "l1", json))
    }

    @Test fun wrongKeyOrAadDoesNotOpen() {
        val blob = SyncCrypto.sealRecord(k1, "fam", "l1", "{}")
        assertNull(SyncCrypto.openRecord(k2, "fam", "l1", blob))
        assertNull(SyncCrypto.openRecord(k1, "fam", "l2", blob))        // replayed under another id
        assertNull(SyncCrypto.openRecord(k1, "other", "l1", blob))      // or another family
        assertNull(SyncCrypto.openFile(k1, "fam", "l1", blob))          // a record is not a file
        val f = SyncCrypto.sealFile(k1, "fam", "f1", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), SyncCrypto.openFile(k1, "fam", "f1", f))
        assertNull(SyncCrypto.openRecord(k1, "fam", "f1", f))
    }

    @Test fun tamperedOrMalformedDoesNotOpen() {
        val blob = SyncCrypto.sealRecord(k1, "fam", "l1", """{"a":1}""")
        val raw = Base64.getDecoder().decode(blob)
        for (i in raw.indices) {
            val t = raw.copyOf(); t[i] = (t[i].toInt() xor 0x01).toByte()
            assertNull("flip at $i", SyncCrypto.openRecord(k1, "fam", "l1", Base64.getEncoder().encodeToString(t)))
        }
        assertNull(SyncCrypto.openRecord(k1, "fam", "l1", Base64.getEncoder().encodeToString(raw.copyOf(raw.size - 1))))
        assertNull(SyncCrypto.openRecord(k1, "fam", "l1", "not base64!"))
        assertNull(SyncCrypto.openRecord(k1, "fam", "l1", ""))
        val v2 = raw.copyOf(); v2[0] = 2
        assertNull(SyncCrypto.openRecord(k1, "fam", "l1", Base64.getEncoder().encodeToString(v2)))
    }
}
