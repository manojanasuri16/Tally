package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class SyncEngineTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }

    /** A creates "Home" with some records; returns the invite. */
    private fun family(a: Phone): String {
        a.loan("l1", "SBI home loan")
        a.payment("p1", "l1", 23000)
        a.file("f1", "payment", "p1", byteArrayOf(1, 2, 3, 4))
        done(runBlocking { a.engine.createFamily("Home") })
        return a.invite()
    }

    /** Every record on the relay, opened with the keyring of the phone whose family this is. */
    private fun openAll(fam: Obj): List<String> {
        val keys = fam.o("keys")!!.entries.associate { (e, k) -> e.toInt() to SyncCrypto.encKey(Base64.getDecoder().decode(k as String)) }
        return relay.records[fam.s("id")]!!.map { (id, row) -> String(SyncCrypto.openE({ keys[it] }, row.blob, SyncCrypto.recordAad(fam.s("id"), id))!!) }
    }

    @Test fun createAndJoinConverge() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val invite = family(a)
        assertEquals("A", Invite.parse(invite)!!.inviter)       // the invite says who invites
        assertTrue(a.db.o("sync").o("dirty")!!.isEmpty())
        assertEquals(1, a.db.objs("members").size)

        val r = done(b.joinVia(a, invite))
        // f1 was added before B joined: B is not owed it (it stays on A's phone and in A's backups), and A alone stored nothing
        assertEquals(0, r.filesDown)
        assertNull(b.files.bytes["f1"])
        assertNotNull(b.rec("files", "f1"))
        assertTrue(relay.blobs[a.db.o("family").s("id")]!!.isEmpty())
        assertEquals("SBI home loan", b.rec("loans", "l1")!!.s("name"))
        assertEquals(2, b.db.objs("members").size)
        done(a.sync())
        assertEquals(a.shared(), b.shared())
        assertEquals(a.db.o("family").s("id"), b.db.o("family").s("id"))
        assertEquals(a.db.o("family").o("keys"), b.db.o("family").o("keys"))      // the key reached B sealed to B's own key pair
        assertNotEquals(a.db.o("family").o("me").s("secret"), b.db.o("family").o("me").s("secret"))   // each phone its own credential
        assertEquals("Home", b.db.o("family").s("name"))       // the family's name, not the inviter's

        // the relay holds nothing readable
        for ((_, row) in relay.records[a.db.o("family").s("id")]!!) {
            val raw = String(Base64.getDecoder().decode(row.blob), Charsets.ISO_8859_1)
            assertFalse(raw.contains("SBI")); assertFalse(raw.contains("23000"))
        }
        // the relay holds only hashes of the credentials, and never a key
        val fam = relay.fams[a.db.o("family").s("id")]!!
        assertEquals(SyncCrypto.tokenHash(a.db.o("family").o("me").s("secret")), fam.members["m-A"]!!.hash)
        assertEquals(setOf("m-A", "m-B"), fam.members.keys)
        assertEquals("admin", fam.members["m-A"]!!.role); assertEquals("member", fam.members["m-B"]!!.role)
    }

    @Test fun threePhonesLastWriterWins() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val invite = family(a)
        done(b.joinVia(a, invite))
        done(c.joinVia(a))
        a.edit("loans", "l1") { it["name"] = "by A" }
        b.edit("loans", "l1") { it["name"] = "by B" }          // later
        c.payment("p2", "l1", 500); b.payment("p3", "l1", 700)
        done(a.sync()); done(c.sync()); done(b.sync())
        done(a.sync()); done(c.sync())
        assertEquals("by B", a.rec("loans", "l1")!!.s("name"))
        assertEquals(a.shared(), b.shared()); assertEquals(a.shared(), c.shared())
        assertEquals(3, a.db.objs("payments").size)
        assertEquals(3, a.db.objs("members").size)
        // nothing left to push anywhere
        listOf(a, b, c).forEach { assertTrue(it.name, it.db.o("sync").o("dirty")!!.isEmpty()) }

        // a delete travels as a tombstone
        c.edit("payments", "p2") { it["deleted"] = true }
        done(c.sync()); done(a.sync())
        assertTrue(a.rec("payments", "p2")!!.b("deleted"))
    }

    @Test fun failureMidPullResumes() {
        val a = Phone("A", relay)
        val invite = family(a)
        for (i in 1..6) a.payment("px$i", "l1", i * 100)
        done(a.sync())
        val b = Phone("B", relay, pageSize = 2)
        assertEquals(SyncResult.JoinPending, runBlocking { b.engine.requestJoin(invite) })
        done(a.sync())
        done(runBlocking { a.engine.approve(a.engine.requests.value.single().id) })
        // B's sync: it enters the family, then page 1 ok, then page 2 fails
        relay.failPull.addAll(listOf(null, SyncError.Offline))
        val r = b.sync()
        assertEquals(SyncResult.Failed(SyncError.Offline), r)
        val cursor = b.db.o("sync").num("cursor").toLong()
        assertTrue(cursor > 0)
        // exactly the first page (two rows) is in the book, beside B's own member record
        assertEquals(2, listOf("loans", "payments", "files", "members").sumOf { k -> b.db.objs(k).count { it["id"] != b.me } })
        assertEquals(SyncResult.Failed(SyncError.Offline), b.engine.state.value.last)
        assertFalse(b.engine.state.value.running)
        // the family and dirty marks survived; the next sync finishes the job
        done(b.sync())
        done(a.sync())
        assertEquals(a.shared(), b.shared())
        assertEquals(7, b.db.objs("payments").size)
    }

    @Test fun failedPushKeepsDirtyMarks() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val invite = family(a)
        done(b.joinVia(a, invite))
        b.payment("p9", "l1", 999)
        relay.failPush.add(SyncError.Server(503, "paused"))
        assertEquals(SyncResult.Failed(SyncError.Server(503, "paused")), b.sync())
        assertTrue(b.db.o("sync").o("dirty")!!.b("p9"))
        done(b.sync())
        assertFalse(b.db.o("sync").o("dirty")!!.b("p9"))
        done(a.sync())
        assertEquals(999.0, a.rec("payments", "p9")!!.num("amount"), 0.0)
    }

    @Test fun editDuringPushStaysDirtyAndAnotherSyncIsBusy() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val invite = family(a)
        done(b.joinVia(a, invite))
        a.edit("loans", "l1") { it["name"] = "first" }
        var busy: SyncResult? = null
        relay.onPush = {
            a.edit("loans", "l1") { it["name"] = "second" }      // the user saved again while the push was in flight
            busy = a.engine.sync()
        }
        done(a.sync())
        assertEquals(SyncResult.Busy, busy)
        assertTrue(a.db.o("sync").o("dirty")!!.b("l1"))
        done(b.sync())
        assertEquals("first", b.rec("loans", "l1")!!.s("name"))
        done(a.sync()); done(b.sync())
        assertEquals("second", b.rec("loans", "l1")!!.s("name"))
        assertTrue(a.db.o("sync").o("dirty")!!.isEmpty())
    }

    @Test fun privateLoansNeverLeaveAndBecomingPrivateSendsTombstones() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        a.loan("lp", "Secret", private = true)
        a.payment("pp", "lp", 4242)
        a.file("fp", "loan", "lp", byteArrayOf(9, 9))
        val invite = family(a)
        val fid = a.db.o("family").s("id")
        assertNull(relay.records[fid]!!["lp"]); assertNull(relay.records[fid]!!["pp"]); assertNull(relay.records[fid]!!["fp"])
        assertNull(relay.blobs[fid]!!["fp"])
        done(b.joinVia(a, invite))
        assertNull(b.rec("loans", "lp"))
        assertTrue(a.db.o("sync").o("blobsUp")!!.b("fp"))         // kept for if it is ever shared

        // make the shared loan private: the model marks the loan, its payments and files dirty
        a.edit("loans", "l1") { it["private"] = true }
        a.db.oo("sync").oo("dirty")["p1"] = true; a.db.oo("sync").oo("dirty")["f1"] = true
        done(a.sync())
        for (json in openAll(a.db.o("family")!!)) {
            val r = JsonCodec.parseObj(json).o("r")!!
            if (r["id"] in setOf("l1", "p1", "f1")) assertEquals(setOf("id", "deleted", "updatedAt", "updatedBy"), r.keys)
            // the private name and amount as values: bare "SBI" or "4242" also turned up now and then by chance inside a
            // public key, a signature or a timestamp in the record, so look for what only the loan itself would carry
            assertFalse(json.contains("SBI home") || json.contains("\"Secret\"") || Regex("\"amount\":4242(\\.0)?[,}]").containsMatchIn(json))
        }
        // A keeps its copies (its own tombstones echo back and are ignored)
        done(a.sync())
        assertEquals("SBI home loan", a.rec("loans", "l1")!!.s("name"))
        assertFalse(a.rec("payments", "p1")!!.b("deleted"))
        // B sees them gone, and drops the file bytes
        done(b.sync())
        assertTrue(b.rec("loans", "l1")!!.b("deleted")); assertTrue(b.rec("payments", "p1")!!.b("deleted"))
        assertNull(b.files.bytes["f1"])

        // and back to shared: everything goes up again
        a.edit("loans", "l1") { it["private"] = false }
        a.db.oo("sync").oo("dirty")["p1"] = true; a.db.oo("sync").oo("dirty")["f1"] = true
        a.db.oo("sync").oo("blobsUp")["f1"] = true
        done(a.sync()); done(b.sync())
        assertEquals("SBI home loan", b.rec("loans", "l1")!!.s("name"))
        assertFalse(b.rec("payments", "p1")!!.b("deleted"))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.files.bytes["f1"])
    }

    @Test fun badInvitesAndWrongKeysChangeNothing() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val text = family(a)
        val invite = Invite.parse(text)!!
        val before = JsonCodec.stringify(b.db)
        assertEquals(SyncResult.BadInvite, runBlocking { b.engine.requestJoin("tally://join#nope") })
        // an invite as 0.2.0 made them (it carried the family key): refused with its own answer
        val old = Invite.PREFIX + invite.familyId + "." + Invite.b64url(ByteArray(32) { 3 }) + "." + Invite.b64url("Home".toByteArray())
        assertEquals(SyncResult.OldInvite, runBlocking { b.engine.requestJoin(old) })
        // a code with a secret the family never made: the relay knows no such invite
        val forged = Invite(invite.familyId, ByteArray(32) { 7 }, invite.expiresAt, invite.name)
        assertEquals(SyncResult.Failed(SyncError.InviteGone), runBlocking { b.engine.requestJoin(forged) })
        assertEquals(before, JsonCodec.stringify(b.db))
        // already in a family
        assertEquals(SyncResult.AlreadyInFamily, runBlocking { a.engine.createFamily("Other") })
        assertEquals(SyncResult.NoFamily, b.sync())
        // a row the relay serves under the wrong id does not open and is skipped, without stopping the sync
        val fid = invite.familyId
        val p1 = relay.records[fid]!!["p1"]!!
        relay.records[fid]!!["p1x"] = FakeRelay.Row("payment", p1.blob, 10_000)
        done(b.joinVia(a, text)).let { assertEquals(1, it.skipped) }
        assertNull(b.rec("payments", "p1x"))
    }

    @Test fun leaveKeepsRecordsAndSaveFailureStops() {
        val a = Phone("A", relay)
        family(a)
        a.payment("p5", "l1", 5)
        a.book.failSave = true
        assertEquals(SyncResult.Failed(SyncError.Storage), a.sync())
        a.book.failSave = false
        assertTrue(runBlocking { a.engine.leaveFamily() })
        assertNull(a.db["family"]); assertNull(a.db["sync"])
        assertEquals(2, a.db.objs("payments").size)
        assertEquals(SyncResult.NoFamily, a.sync())
        // and it can create a new family afterwards, pushing everything it has
        done(runBlocking { a.engine.createFamily("Again") })
        val fid = a.db.o("family").s("id")
        assertNotNull(relay.records[fid]!!["p5"])
        assertTrue(relay.blobs[fid]!!.isEmpty())                        // nobody else to send f1 to: the relay keeps no bytes
        assertTrue(a.db.o("sync").o("blobsUp")!!.isEmpty())
    }

    @Test fun aPrivatePaymentsFileIsNeverUploadedAndStaysQueued() {
        val a = Phone("A", relay)
        a.loan("lp", "Secret", private = true)
        a.payment("pp", "lp", 4242)
        a.file("fpp", "payment", "pp", byteArrayOf(7, 7, 7))
        family(a)
        val fid = a.db.o("family").s("id")
        repeat(3) { done(a.sync()) }
        assertNull(relay.blobs[fid]!!["fpp"])
        assertTrue(a.db.o("sync").o("blobsUp")!!.b("fpp"))             // kept, for if the loan is shared later
        assertNull(relay.records[fid]!!["fpp"])
        // shared later: the file goes up on the next sync and the queue empties
        a.edit("loans", "lp") { it["private"] = false }
        listOf("pp", "fpp").forEach { a.db.oo("sync").oo("dirty")[it] = true }
        done(a.sync())
        assertNull(relay.blobs[fid]!!["fpp"])                           // a family of one: nobody is owed it, so nothing is kept
        assertFalse(a.db.o("sync").o("blobsUp")!!.b("fpp"))
    }

    @Test fun aDeletedFileIsNotMissingBytesToDownload() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val invite = family(a)
        done(b.joinVia(a, invite))
        // what the UI does when a document is deleted: tombstone the record, leave the queue, drop the bytes
        a.put("files", obj("id" to "f1", "ownerType" to "payment", "ownerId" to "p1", "deleted" to true))
        a.files.bytes.remove("f1")
        a.db.oo("sync").oo("blobsUp").remove("f1")
        done(a.sync())
        val fid = a.db.o("family").s("id")
        relay.blobs[fid]!!.remove("f1")
        val before = relay.blobGets
        // a phone that joins now gets the tombstone, asks for no bytes, and does not count it as a file it failed to get
        val r = done(c.joinVia(a))
        assertEquals(0, r.filesDown); assertEquals(0, r.skipped)
        assertTrue(c.rec("files", "f1")!!.b("deleted"))
        assertFalse(runBlocking { c.engine.fetchFile("f1") })
        done(b.sync())
        assertNull(b.files.bytes["f1"])
        assertEquals(before, relay.blobGets)
        assertTrue(a.db.o("sync").o("blobsUp")!!.isEmpty())
    }

    @Test fun cancellingASyncDoesNotLeaveItRunning() {
        val gated = GateRelay(relay)
        val a = Phone("A", gated)
        family(a)
        gated.gate = kotlinx.coroutines.CompletableDeferred()
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { a.engine.sync() }
            assertTrue(a.engine.state.value.running)
            job.cancelAndJoin()
            assertFalse(a.engine.state.value.running)
        }
        gated.gate = null
        done(a.sync())          // and the next one is not Busy
    }
}
