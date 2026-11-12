package com.tally.book

import com.tally.book.core.*
import com.tally.book.data.*
import com.tally.book.data.PhoneSeal.Outcome
import com.tally.book.model.NextPayment
import com.tally.book.widget.NextPaymentData
import kotlinx.coroutines.runBlocking
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
 * The two kinds of app lock (docs/DESIGN.md, "App lock"): Tally's own lock and the phone's lock with its recovery code, switching
 * between them, the "key gone" case, backups in each mode restored on a new phone, the widget's privacy flag and the
 * hide/unhide rules. The Android Keystore is replaced by [FakeBox], which behaves as the phone's lock does: it opens only what it
 * sealed, can be cancelled, and is gone for good once the screen lock is removed.
 */
class LockModesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LocalDate.of(2026, 11, 11)
    private val now = 1_794_380_400_000L

    @Before fun setUp() { TestClock.start(); Crypto.clearLock() }
    @After fun tearDown() { TestClock.stop(); Crypto.clearLock() }

    /** The Keystore key behind the phone's lock, as a fake: AES-GCM under a key only this "phone" has. */
    class FakeBox : PhoneSeal.Box {
        private var key: ByteArray? = Crypto.randomBytes(32)
        var cancel = false
        var prompts = 0
        /** The person removed the phone's screen lock: Android destroys the key. */
        fun screenLockRemoved() { key = null }
        override suspend fun seal(plain: ByteArray): Outcome {
            prompts++
            if (cancel) return Outcome.Cancelled
            val k = key ?: return Outcome.Gone
            val iv = Crypto.randomBytes(12)
            return Outcome.Ok(iv + Crypto.encrypt(k, iv, plain))
        }
        override suspend fun open(stored: ByteArray): Outcome {
            prompts++
            if (cancel) return Outcome.Cancelled
            val k = key ?: return Outcome.Gone
            return runCatching { Outcome.Ok(Crypto.decrypt(k, stored.copyOfRange(0, 12), stored.copyOfRange(12, stored.size))) }.getOrElse { Outcome.Gone }
        }
    }

    private val bookText = """{"v":1,"me":{"id":"m1","name":"Ravi"},"loans":[{"id":"l1","name":"City Bank","kind":"bank"}],"family":{"v":2,"me":{"priv":"PRIVATE","secret":"SECRET"},"keys":{"1":"FAMILYKEY"}}}"""

    /** What LockModes.usePhone does once the code was typed back and the prompt said yes: the phone's lock becomes the lock. */
    private fun turnOnPhone(box: FakeBox, code: String): ByteArray = runBlocking {
        val made = Crypto.makePhone(code)
        val r = PhoneSeal.wrap(box, made.lock.salt, made.copyKey) as Outcome.Ok
        Crypto.adopt(made.lock)
        r.bytes
    }

    // ---------- the phone's lock ----------

    @Test fun phoneLockSealsTheBookAndThePhonesPromptOpensIt() = runBlocking {
        val box = FakeBox()
        val code = RecoveryCode.make()
        val stored = turnOnPhone(box, code)
        val env = Crypto.sealText(bookText)
        assertEquals("phone", env.s("t")); assertEquals(0.0, n(env["l"]), 0.0)
        // nothing readable on disk: not the loans, not the family's keys or this member's private key
        val disk = JsonCodec.stringify(env)
        listOf("City Bank", "PRIVATE", "SECRET", "FAMILYKEY", code).forEach { assertFalse(it, disk.contains(it)) }
        Crypto.clearLock()

        val r = PhoneSeal.unlockKey(box, stored, env)
        assertTrue(r is Outcome.Ok)
        val back = Crypto.unsealWithKey(env, (r as Outcome.Ok).bytes).asObj()
        assertEquals("City Bank", back.objs("loans")[0].s("name"))
        assertEquals("FAMILYKEY", back.o("family").o("keys").s("1"))       // the family's keys stay inside the sealed book
        assertTrue(Crypto.locked); assertEquals("phone", Crypto.type); assertEquals(0, Crypto.pinLen)
    }

    @Test fun theRecoveryCodeOpensTheBookWhenTheScreenLockWasRemoved() = runBlocking {
        val box = FakeBox()
        val code = RecoveryCode.make()
        val stored = turnOnPhone(box, code)
        val env = Crypto.sealText(bookText)
        Crypto.clearLock()

        box.screenLockRemoved()
        assertSame(Outcome.Gone, PhoneSeal.unlockKey(box, stored, env))         // says what happened, never throws
        // the code as written on paper, with spaces, opens it
        val typed = RecoveryCode.format(code)
        assertTrue(typed.contains(' '))
        val back = Crypto.unseal(env, LockRules.secretFor(LockRules.RECOVERY, typed)).asObj()
        assertEquals("Ravi", back.o("me").s("name"))
        assertEquals("phone", Crypto.type)                                       // still the phone's lock, now waiting to be set up again

        // a wrong code opens nothing
        Crypto.clearLock()
        val wrong = (code.substring(0, 19) + ((code.last() - '0' + 1) % 10)).let { RecoveryCode.normalize(it) }
        assertThrows(Exception::class.java) { Crypto.unseal(env, wrong) }
        assertFalse(Crypto.locked)
    }

    @Test fun setUpAgainAfterRecoveryKeepsTheSameCode() = runBlocking {
        val box = FakeBox()
        val code = RecoveryCode.make()
        turnOnPhone(box, code)
        val env = Crypto.sealText(bookText)
        Crypto.clearLock()
        val newPhone = FakeBox()          // the screen lock was set again (a new Keystore key) or this is another phone
        Crypto.unseal(env, code)
        val again = PhoneSeal.wrap(newPhone, Crypto.salt!!, Crypto.phoneCopyKey(code)!!) as Outcome.Ok
        Crypto.clearLock()
        val k = PhoneSeal.unlockKey(newPhone, again.bytes, env) as Outcome.Ok
        assertNotNull(Crypto.unsealWithKey(env, k.bytes))
        assertNotNull(Crypto.openEnvelope(env, code))                            // and the paper still works
    }

    @Test fun noCopyOrACopyOfAnotherLockIsGoneNotAWrongKey() = runBlocking {
        val box = FakeBox()
        val stored = turnOnPhone(box, RecoveryCode.make())
        val oldEnv = Crypto.sealText(bookText)
        assertSame(Outcome.Gone, PhoneSeal.unlockKey(box, null, oldEnv))
        // a restore put a book sealed with another lock in place: the old copy does not fit it
        Crypto.setLock(RecoveryCode.make(), LockRules.PHONE)
        val otherEnv = Crypto.sealText(bookText)
        assertSame(Outcome.Gone, PhoneSeal.unlockKey(box, stored, otherEnv))
        assertTrue(PhoneSeal.unlockKey(box, stored, oldEnv) is Outcome.Ok)
    }

    @Test fun aCancelledPromptChangesNothing() = runBlocking {
        val box = FakeBox()
        val stored = turnOnPhone(box, RecoveryCode.make())
        val env = Crypto.sealText(bookText)
        box.cancel = true
        assertSame(Outcome.Cancelled, PhoneSeal.unlockKey(box, stored, env))
        assertSame(Outcome.Cancelled, PhoneSeal.wrap(box, Crypto.salt!!, Crypto.key!!))
        box.cancel = false
        assertTrue(PhoneSeal.unlockKey(box, stored, env) is Outcome.Ok)
    }

    @Test fun openWithAKnownKeyChangesNothing() = runBlocking {
        val box = FakeBox()
        val stored = turnOnPhone(box, RecoveryCode.make())
        val env = Crypto.sealText(bookText)
        val key = (PhoneSeal.unlockKey(box, stored, env) as Outcome.Ok).bytes
        Crypto.clearLock()
        val o = Crypto.openEnvelopeWithKey(env, key)!!
        assertEquals("phone", o.type); assertTrue(o.text.contains("City Bank"))
        assertFalse(Crypto.locked)
        assertNull(Crypto.openEnvelopeWithKey(env, Crypto.randomBytes(32)))
    }

    // ---------- switching keeps the book readable ----------

    @Test fun switchingBetweenEveryKindKeepsTheBookReadable() = runBlocking {
        // own PIN
        Crypto.setLock("2468", "pin")
        var env = Crypto.sealText(bookText)
        assertEquals("pin", env.s("t")); assertEquals(4.0, n(env["l"]), 0.0)    // the PIN path is unchanged
        Crypto.clearLock(); assertEquals(bookText, JsonCodec.stringify(Crypto.unseal(env, "2468")))

        // PIN -> the phone's lock
        val box = FakeBox(); val code = RecoveryCode.make()
        val stored = turnOnPhone(box, code)
        env = Crypto.sealText(bookText)
        Crypto.clearLock()
        assertThrows(Exception::class.java) { Crypto.unseal(env, "2468") }   // the old PIN no longer opens it
        assertEquals(bookText, JsonCodec.stringify(Crypto.unsealWithKey(env, (PhoneSeal.unlockKey(box, stored, env) as Outcome.Ok).bytes)))

        // the phone's lock -> a pattern
        Crypto.setLock("0-1-2-5-8", "pattern")
        env = Crypto.sealText(bookText)
        Crypto.clearLock()
        assertSame(Outcome.Gone, PhoneSeal.unlockKey(box, stored, env))      // the phone's copy does not fit any more
        assertNull(Crypto.openEnvelope(env, code))
        assertEquals(bookText, JsonCodec.stringify(Crypto.unseal(env, "0-1-2-5-8")))
        assertEquals("pattern", Crypto.type)

        // a pattern -> a password -> off
        Crypto.setLock("long password", "password")
        env = Crypto.sealText(bookText)
        Crypto.clearLock(); assertEquals(bookText, JsonCodec.stringify(Crypto.unseal(env, "long password")))
        Crypto.clearLock()
        assertFalse(Crypto.locked)
    }

    // ---------- backups ----------

    private fun place(name: String): BackupPlace {
        val root = tmp.newFolder(name)
        return BackupPlace(File(root, "ledger.json"), File(root, "files").apply { mkdirs() }, File(root, "restore"))
    }

    @Test fun aPhoneLockBackupRestoresOnANewPhoneWithTheRecoveryCode() = runBlocking {
        val a = place("a")
        val box = FakeBox()
        val code = RecoveryCode.make()
        turnOnPhone(box, code)
        val filesKey = Crypto.randomBytes(32)
        val db = JsonCodec.parseObj(bookText).apply { this["filesKey"] = Crypto.b64(filesKey); this["lockType"] = "phone"; this["recoveryCode"] = code }
        a.book.writeText(JsonCodec.stringify(Crypto.sealText(JsonCodec.stringify(db))))
        val photo = Random(5).nextBytes(100_000)
        val iv = Crypto.randomBytes(12)
        File(a.filesDir, "f1.bin").writeBytes(Crypto.encrypt(filesKey, iv, photo, "tally-file:f1".toByteArray()))
        File(a.filesDir, "f1.json").writeText(JsonCodec.stringify(obj("id" to "f1", "enc" to 1, "iv" to Crypto.b64(iv), "ts" to 1, "mime" to "", "size" to 1)))
        val file = File(tmp.root, "phone" + BackupFormat.EXT)
        BackupCore.writeFile(file, BackupCore.storedBytes(a.book)!!, a.filesDir, Any(), "test", now, day)
        Crypto.clearLock()          // a new phone: no Keystore key, no lock

        val c = BackupCore.check(file)
        assertTrue(c.sealed)
        assertEquals("phone", c.envelope!!.s("t"))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        listOf("City Bank", code, "FAMILYKEY").forEach { assertFalse(it, raw.contains(it)) }
        assertNull(Crypto.openEnvelope(c.envelope!!, "2468"))
        // typed from paper on a Telugu keyboard, with spaces: still the code
        val telugu = RecoveryCode.format(code).map { if (it in '0'..'9') '౦' + (it - '0') else it }.joinToString("")
        val opened = Crypto.openEnvelope(c.envelope!!, LockRules.secretFor(LockRules.RECOVERY, telugu))!!
        assertEquals("phone", opened.type)
        val back = JsonCodec.parseObj(opened.text)
        assertEquals(code, back.s("recoveryCode"))
        assertTrue(BackupCore.isBook(back))

        val b = place("b")
        BackupCore.install(file, c, c.book, b, false, "test", now, day)
        Crypto.adopt(opened)
        assertTrue(Crypto.locked); assertEquals("phone", Crypto.type)
        val rec = JsonCodec.parseObj(File(b.filesDir, "f1.json").readText())
        val bytes = Crypto.decrypt(Crypto.unb64(back.s("filesKey")), Crypto.unb64(rec.s("iv")), File(b.filesDir, "f1.bin").readBytes(), "tally-file:f1".toByteArray())
        assertArrayEquals(photo, bytes)
        // on the new phone the old phone's copy of the key is of no use; the book opens with the code until the prompt is set up again
        val env = JsonCodec.parseObj(b.book.readText())
        assertSame(Outcome.Gone, PhoneSeal.unlockKey(FakeBox(), null, env))
        assertNotNull(Crypto.openEnvelope(env, code))
    }

    @Test fun anOwnLockBackupStillRestoresWithItsPin() {
        val a = place("a")
        Crypto.setLock("2468", "pin")
        a.book.writeText(JsonCodec.stringify(Crypto.sealText(bookText)))
        val file = File(tmp.root, "pin" + BackupFormat.EXT)
        BackupCore.writeFile(file, BackupCore.storedBytes(a.book)!!, a.filesDir, Any(), "test", now, day)
        Crypto.clearLock()
        val c = BackupCore.check(file)
        assertEquals("pin", c.envelope!!.s("t"))
        assertNull(Crypto.openEnvelope(c.envelope!!, RecoveryCode.normalize("2468")+"0"))
        assertEquals(4, Crypto.openEnvelope(c.envelope!!, LockRules.secretFor("pin", "2468"))!!.pinLen)
    }

    // ---------- outside the app ----------

    @Test fun theWidgetTreatsBothKindsAsLocked() {
        val next = NextPayment("City Bank", LocalDate.of(2026, 12, 5), 21696.0, 0, false)
        assertNotNull(NextPaymentData.snapshotText(true, Crypto.locked, false, next))
        Crypto.setLock("2468", "pin")
        assertNull("own lock", NextPaymentData.snapshotText(true, Crypto.locked, false, next))
        turnOnPhone(FakeBox(), RecoveryCode.make())
        assertTrue(Crypto.locked)
        assertNull("phone's lock", NextPaymentData.snapshotText(true, Crypto.locked, false, next))
        assertEquals(LockKind.PHONE, LockRules.kindOf(Crypto.locked, Crypto.type))
    }

    // ---------- the rules ----------

    @Test fun kindsAndWhenThePhonesLockIsOffered() {
        assertEquals(LockKind.OFF, LockRules.kindOf(false, "phone"))
        assertEquals(LockKind.OWN, LockRules.kindOf(true, "pin"))
        assertEquals(LockKind.OWN, LockRules.kindOf(true, "pattern"))
        assertEquals(LockKind.OWN, LockRules.kindOf(true, "password"))
        assertEquals(LockKind.PHONE, LockRules.kindOf(true, "phone"))
        assertEquals(PhoneLockOffer.OLD_ANDROID, LockRules.phoneLockOffer(29, true))
        assertEquals(PhoneLockOffer.NO_SCREEN_LOCK, LockRules.phoneLockOffer(30, false))
        assertEquals(PhoneLockOffer.OK, LockRules.phoneLockOffer(30, true))
        assertEquals(PhoneLockOffer.OK, LockRules.phoneLockOffer(36, true))
    }

    @Test fun showingAmountsAgainAsksWhatThereIsToAsk() {
        assertEquals(UnhideAsk.APP_LOCK, LockRules.unhideAsk(LockKind.OWN, false))
        assertEquals(UnhideAsk.APP_LOCK, LockRules.unhideAsk(LockKind.PHONE, true))
        assertEquals(UnhideAsk.PHONE, LockRules.unhideAsk(LockKind.OFF, true))
        assertEquals(UnhideAsk.NOTHING, LockRules.unhideAsk(LockKind.OFF, false))
    }

    @Test fun recoveryCodes() {
        val a = RecoveryCode.make(); val b = RecoveryCode.make()
        assertEquals(20, a.length); assertTrue(a.all { it in '0'..'9' }); assertNotEquals(a, b)
        assertEquals("1234 5678 9012 3456 7890", RecoveryCode.format("12345678901234567890"))
        assertEquals("12345678901234567890", RecoveryCode.normalize(" 1234-5678 9012 3456 7890 "))
        assertEquals("12", RecoveryCode.normalize("౧౨"))          // Telugu digits
        assertEquals("12", RecoveryCode.normalize("१२"))          // Devanagari digits
        assertTrue(RecoveryCode.valid("1234 5678 9012 3456 7890")); assertFalse(RecoveryCode.valid("1234"))
        assertEquals(20, LockRules.minLength(LockRules.RECOVERY)); assertEquals(4, LockRules.minLength("pin")); assertEquals(6, LockRules.minLength("password"))
        assertEquals("ab cd", LockRules.secretFor("password", "ab cd"))       // a password is taken as typed
    }
}
