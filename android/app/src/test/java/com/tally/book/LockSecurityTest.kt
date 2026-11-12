package com.tally.book

import com.tally.book.core.*
import com.tally.book.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * The on-device fixes of the 2026-11-12 security review (docs/SECURITY_REVIEW.md, "Fixes (on the device)"):
 * H2 (the seal: a random data key, a PIN that opens it only on its phone, a recovery code that opens it anywhere, and the
 * upgrade of books and backups sealed the old way), L3 (older plain or old-lock backups sealed again), M5 (how long a sheet
 * the app opened keeps it open), L1 (when screenshots are blocked) and the wrong-try waits. "This phone" is [MemoryDevice]; a
 * new instance is another phone.
 */
class LockSecurityTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 11)
    private val now = 1_794_380_400_000L
    private lateinit var home: DeviceSecret

    @Before fun setUp() { TestClock.start(); Crypto.clearLock(); home = Crypto.device }
    @After fun tearDown() { TestClock.stop(); Crypto.clearLock(); Crypto.device = home }

    private val bookText = """{"v":1,"me":{"id":"m1","name":"Ravi"},"loans":[{"id":"l1","name":"City Bank","kind":"bank"}],"payments":[],"files":[],"members":[]}"""

    /** An envelope as Tally 0.5 wrote it: { enc: 1 } under PBKDF2(secret, s) itself. */
    private fun v1(secret: String, kind: String, text: String = bookText): Pair<Obj, ByteArray> {
        val s = Crypto.randomBytes(16); val k = Crypto.deriveKeyBits(secret, s); val iv = Crypto.randomBytes(12)
        val env = obj("enc" to 1, "s" to Crypto.b64(s), "i" to Crypto.b64(iv), "d" to Crypto.b64(Crypto.encrypt(k, iv, text.toByteArray())), "t" to kind, "l" to if (kind == "pin") secret.length else 0)
        return env to k
    }

    private fun onAnotherPhone(block: () -> Unit) { val d = Crypto.device; Crypto.device = MemoryDevice(); try { block() } finally { Crypto.device = d } }

    // ---------- H2: the seal ----------

    @Test fun aCopiedBookCannotBeOpenedWithThePinOffThePhone() {
        val code = RecoveryCode.make()
        Crypto.setLock("2468", "pin", code)
        val env = Crypto.sealText(bookText)
        assertEquals(2.0, n(env["enc"]), 0.0)
        val disk = JsonCodec.stringify(env)
        listOf("City Bank", "Ravi", code).forEach { assertFalse(it, disk.contains(it)) }
        Crypto.clearLock()
        // before the fix the PIN alone opened the file anywhere (PBKDF2 of it was the key); now it needs this phone's secret
        onAnotherPhone {
            assertNull(Crypto.openEnvelope(env, "2468"))
            assertFalse(Crypto.pinFits(env))
            // the recovery code (about 66 bits, plus PBKDF2) opens it anywhere
            assertNull(Crypto.openEnvelope(env, code))                    // typed as a PIN it is not one
            assertEquals(bookText, Crypto.openEnvelope(env, LockRules.secretFor(LockRules.RECOVERY, RecoveryCode.format(code)), viaCode = true)!!.text)
        }
        // on its own phone the PIN still opens it, and only the right one
        assertEquals(bookText, Crypto.openEnvelope(env, "2468")!!.text)
        assertNull(Crypto.openEnvelope(env, "2469"))
        assertNull(Crypto.openEnvelope(env, "2468", viaCode = true))
    }

    @Test fun changingThePinKeepsTheDataKeyAndTheRecoveryCode() {
        val code = RecoveryCode.make()
        Crypto.setLock("2468", "pin", code)
        val dk = Crypto.key!!.copyOf()
        val oldEnv = Crypto.sealText(bookText)
        Crypto.adopt(Crypto.makeOwn("135790", "pin", code))
        assertArrayEquals(dk, Crypto.key)                                     // same data key: older backups still open with the code
        val env = Crypto.sealText(bookText)
        assertNotEquals(oldEnv.s("s"), env.s("s"))                           // a new lock id: the fingerprint copy no longer fits
        assertNull(Crypto.openEnvelope(env, "2468"))
        assertEquals(6, Crypto.openEnvelope(env, "135790")!!.pinLen)
        assertNotNull(Crypto.openEnvelope(env, code, viaCode = true))
        assertNotNull(Crypto.openEnvelope(oldEnv, code, viaCode = true))
        assertNotNull(Crypto.checkSecret("135790")); assertNull(Crypto.checkSecret("2468"))
        assertNotNull(Crypto.checkSecret(code, viaCode = true))
        // a new code (the lock was turned off and on): everything new
        Crypto.clearLock()
        Crypto.setLock("2468", "pin")
        assertFalse(dk.contentEquals(Crypto.key))
    }

    @Test fun switchingToThePhonesLockKeepsTheCodeAndTheFingerprintCopyIsThePinsKey() {
        val code = RecoveryCode.make()
        Crypto.setLock("2468", "pin", code)
        val pinKey = Crypto.checkSecret("2468")!!
        val env = Crypto.sealText(bookText)
        // the fingerprint keeps the PIN's key; with this phone's secret it opens the book, and on another phone it does not
        Crypto.clearLock()
        assertEquals(bookText, Crypto.openEnvelopeWithKey(env, pinKey)!!.text)
        onAnotherPhone { assertNull(Crypto.openEnvelopeWithKey(env, pinKey)) }
        Crypto.unseal(env, "2468")
        val made = Crypto.makePhone(code)
        assertArrayEquals(Crypto.key, made.lock.key)
        Crypto.adopt(made.lock)
        assertTrue(Crypto.checkCopyKey(made.copyKey))
        val penv = Crypto.sealText(bookText)
        assertNull(penv["p"])
        assertEquals(bookText, Crypto.openEnvelopeWithKey(penv, made.copyKey)!!.text)
        onAnotherPhone { assertEquals(bookText, Crypto.openEnvelope(penv, code)!!.text) }
    }

    @Test fun reLockingZeroesTheKey() {
        Crypto.setLock("2468", "pin")
        val k = Crypto.key!!
        Crypto.forget()
        assertTrue(Crypto.locked)                       // still locked: the widget and notices stay quiet
        assertNull(Crypto.key)
        assertTrue(k.all { it == 0.toByte() })
        assertEquals("pin", Crypto.type)
    }

    // ---------- H2: books and backups sealed the old way ----------

    @Test fun anOldOwnLockBookIsUpgradedOnFirstUnlockAndItsFingerprintCopyStillFits() {
        val (env, oldKey) = v1("2468", "pin")
        val back = Crypto.unseal(env, "2468", code = "12345678901234567890").asObj()
        assertEquals("City Bank", back.objs("loans")[0].s("name"))
        val env2 = Crypto.sealText(bookText)
        assertEquals(2.0, n(env2["enc"]), 0.0)
        assertEquals(env.s("s"), env2.s("s"))                                 // same lock id
        Crypto.clearLock()
        assertEquals(bookText, Crypto.openEnvelope(env2, "2468")!!.text)      // the same PIN, on this phone
        assertEquals(bookText, Crypto.openEnvelopeWithKey(env2, oldKey)!!.text)   // the 0.5 fingerprint copy still opens it
        assertEquals(bookText, Crypto.openEnvelope(env2, "12345678901234567890", viaCode = true)!!.text)
        onAnotherPhone { assertNull(Crypto.openEnvelope(env2, "2468")) }      // the copied file no longer falls to the PIN
        // the old envelope still opens the old way (nothing is lost if the write after the upgrade never happened)
        assertTrue(Crypto.openEnvelope(env, "2468")!!.legacy)
    }

    @Test fun anOldPhoneLockBookKeepsItsCodeAndItsKeystoreCopy() {
        val code = RecoveryCode.make()
        val (env, copy) = v1(code, LockRules.PHONE)
        val o = Crypto.openEnvelopeWithKey(env, copy)!!          // the phone's prompt opened the 0.5 copy
        assertTrue(o.legacy)
        Crypto.adopt(Crypto.upgrade(o, null))
        val env2 = Crypto.sealText(bookText)
        assertEquals(env.s("s"), env2.s("s"))
        assertEquals(bookText, Crypto.openEnvelopeWithKey(env2, copy)!!.text)
        onAnotherPhone { assertEquals(bookText, Crypto.openEnvelope(env2, code)!!.text) }
    }

    // ---------- backups: a new phone, and sealing older ones again (L3) ----------

    private fun place(name: String): BackupPlace {
        val root = tmp.newFolder(name)
        return BackupPlace(File(root, "ledger.json"), File(root, "files").apply { mkdirs() }, File(root, "restore"))
    }

    private fun backup(p: BackupPlace): File {
        val f = File(tmp.root, "b-" + Random.nextInt(1 shl 30) + BackupFormat.EXT)
        BackupCore.writeFile(f, BackupCore.storedBytes(p.book)!!, p.filesDir, Any(), "test", now, day)
        return f
    }

    private val photo = Random(9).nextBytes(50_000)
    private val bookWithFile = """{"v":1,"me":{"id":"m1","name":"Ravi"},"loans":[{"id":"l1","name":"City Bank","kind":"bank"}],"payments":[],"files":[{"id":"f1","ownerType":"loan","ownerId":"l1","name":"card.jpg","enc":false}],"members":[]}"""

    private fun plainBackup(): File {
        val a = place("plain")
        a.book.writeText(bookWithFile)
        File(a.filesDir, "f1.bin").writeBytes(photo)
        File(a.filesDir, "f1.json").writeText(JsonCodec.stringify(obj("id" to "f1", "enc" to 0, "iv" to "", "ts" to 1, "mime" to "image/jpeg", "size" to photo.size)))
        return backup(a)
    }

    private fun sealBook(code: String): (String) -> ByteArray = { text ->
        val b = JsonCodec.parseObj(text); b["recoveryCode"] = code
        JsonCodec.stringify(Crypto.sealText(JsonCodec.stringify(b))).toByteArray()
    }

    /** Opens a resealed backup on a new phone with [code] and returns the photo's bytes. */
    private fun photoOnNewPhone(file: File, code: String): ByteArray {
        var out = ByteArray(0)
        onAnotherPhone {
            val c = BackupCore.check(file)
            assertTrue(c.sealed)
            assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("City Bank"))
            val o = Crypto.openEnvelope(c.envelope!!, code, viaCode = true)!!
            val book = JsonCodec.parseObj(o.text)
            assertTrue(book.objs("files")[0].b("enc"))
            val b = place("new" + Random.nextInt(1 shl 20))
            BackupCore.install(file, c, c.book, b, false, "test", now, day)
            val rec = JsonCodec.parseObj(File(b.filesDir, "f1.json").readText())
            assertTrue(rec.b("enc"))
            out = Crypto.decrypt(Crypto.unb64(book.s("filesKey")), Crypto.unb64(rec.s("iv")), File(b.filesDir, "f1.bin").readBytes(), "tally-file:f1".toByteArray())
        }
        return out
    }

    @Test fun aPlainBackupIsSealedAgainAfterALockIsSet() {
        val file = plainBackup()
        val code = RecoveryCode.make()
        Crypto.setLock("2468", "pin", code)
        val id = Crypto.b64(Crypto.salt!!)
        val c = BackupCore.check(file)
        val text = BackupCore.resealText(c, id, Crypto::openKnown)!!
        val out = File(tmp.root, "out" + BackupFormat.EXT)
        out.outputStream().use { BackupCore.reseal(file, c, text, sealBook(code), it) }
        val c2 = BackupCore.check(out)
        assertEquals(c.date, c2.date); assertEquals(c.createdAt, c2.createdAt)       // the same backup, now sealed
        assertNull(BackupCore.resealText(c2, id, Crypto::openKnown))                // and now current: nothing more to do
        assertArrayEquals(photo, photoOnNewPhone(out, code))
        // on this phone the PIN opens it too
        assertNotNull(Crypto.openEnvelope(c2.envelope!!, "2468"))
    }

    @Test fun anOldStyleBackupIsSealedAgainWithTheKeyFromTheUpgrade() {
        val (env, oldKey) = v1("2468", "pin", bookWithFile)
        val a = place("old")
        a.book.writeText(JsonCodec.stringify(env))
        File(a.filesDir, "f1.bin").writeBytes(photo)       // a 0.5 backup with a plain file (taken before the files pass)
        File(a.filesDir, "f1.json").writeText(JsonCodec.stringify(obj("id" to "f1", "enc" to 0, "iv" to "", "ts" to 1, "mime" to "image/jpeg", "size" to photo.size)))
        val file = backup(a)
        // the first unlock after the update: upgraded, and the old key remembered for this session
        val code = RecoveryCode.make()
        val o = Crypto.openEnvelope(env, "2468")!!
        Crypto.adopt(Crypto.upgrade(o, code)); Crypto.remember(o.key)
        val c = BackupCore.check(file)
        val text = BackupCore.resealText(c, Crypto.b64(Crypto.salt!!), Crypto::openKnown)!!
        val out = File(tmp.root, "out2" + BackupFormat.EXT)
        out.outputStream().use { BackupCore.reseal(file, c, text, sealBook(code), it) }
        assertArrayEquals(photo, photoOnNewPhone(out, code))
        // a backup of a lock this phone never knew is left alone
        Crypto.forget()
        assertNull(BackupCore.resealText(c, "x", Crypto::openKnown))
    }

    @Test fun anOldStyleBackupRestoresOnANewPhoneAndMovesToTheNewSeal() {
        val (env, _) = v1("2468", "pin")
        onAnotherPhone {
            val o = Crypto.openEnvelope(env, "2468")!!        // a 0.5 backup: its PIN still opens it anywhere (it was made that way)
            val code = RecoveryCode.make()
            Crypto.adopt(Crypto.upgrade(o, code))
            val env2 = Crypto.sealText(bookText)
            assertEquals(bookText, Crypto.openEnvelope(env2, "2468")!!.text)   // from now on bound to this (new) phone
        }
    }

    // ---------- wrong tries ----------

    private class MapStore : Attempts.Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun set(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
    }

    @Test fun wrongTriesWaitLongerAndTheWaitSurvivesARestart() {
        val store = MapStore()
        var gate = Attempts.Gate(store)
        var t = 1_000_000L
        repeat(4) { assertEquals(0L, gate.failed(t)); t += 1000 }
        assertEquals(0L, gate.remaining(t))
        assertEquals(30_000L, gate.failed(t))                       // the fifth
        assertEquals(30_000L, gate.remaining(t))
        gate = Attempts.Gate(store)                                 // the app (or the phone) restarted
        assertEquals(20_000L, gate.remaining(t + 10_000))
        assertEquals(0L, gate.remaining(t + 30_000))
        t += 30_000
        assertEquals(60_000L, gate.failed(t))
        assertEquals(5 * 60_000L, gate.failed(t + 60_000))
        assertEquals(15 * 60_000L, gate.failed(t + 400_000))
        assertEquals(60 * 60_000L, gate.failed(t + 2_000_000))
        assertEquals(60 * 60_000L, gate.failed(t + 6_000_000))      // capped at an hour; nothing is ever wiped
        // a clock set back does not skip the wait
        assertEquals(60 * 60_000L, gate.remaining(t))
        assertEquals(60 * 60_000L - 1000, gate.remaining(t + 1000))
        gate.succeeded()
        assertEquals(0, gate.fails()); assertEquals(0L, gate.remaining(t))
        assertEquals("0:30", Attempts.format(30_000)); assertEquals("4:59", Attempts.format(298_001)); assertEquals("1:00:00", Attempts.format(3_600_000))
    }

    // ---------- M5 and L1 ----------

    @Test fun aSheetTheAppOpenedKeepsItOpenOnlyForAShortWhile() {
        val left = 10_000L
        // no re-lock delay set (0): coming back after a minute re-locks, unless the app opened a viewer or sheet itself
        assertTrue(LockRules.relockOnReturn(left, left + 60_000, 0, 0))
        assertFalse(LockRules.relockOnReturn(left, left + 60_000, 0, left - 50))
        assertFalse(LockRules.relockOnReturn(left, left + LockRules.HOLD_MS, 0, left))
        // the viewer was left open and the phone put down: hours later the lock comes back
        assertTrue(LockRules.relockOnReturn(left, left + 3 * 3_600_000L, 0, left - 50))
        assertTrue(LockRules.relockOnReturn(left, left + LockRules.HOLD_MS + 1_000, 0, left))
        // within the re-lock delay nothing re-locks; a longer delay also stretches the hold
        assertFalse(LockRules.relockOnReturn(left, left + 30_000, 60_000, 0))
        assertFalse(LockRules.relockOnReturn(left, left + 9 * 60_000, 10 * 60_000, left))
        assertTrue(LockRules.relockOnReturn(left, left + 11 * 60_000, 10 * 60_000, left))
        // never left (first start)
        assertFalse(LockRules.relockOnReturn(0, 5_000_000, 0, 0))
    }

    @Test fun screenshotsAreBlockedWhileLockedHiddenOrASecretIsShown() {
        assertFalse(LockRules.secureWindow(false, false, false))
        assertTrue(LockRules.secureWindow(true, false, false))
        assertTrue(LockRules.secureWindow(false, true, false))
        assertTrue(LockRules.secureWindow(false, false, true))
    }
}
