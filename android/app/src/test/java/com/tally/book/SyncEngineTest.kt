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
        return a.engine.inviteText(a.db)!!
    }

    private fun openAll(fam: Obj): List<String> {
        val enc = SyncCrypto.encKey(Base64.getDecoder().decode(fam.s("key")))
        return relay.records[fam.s("id")]!!.map { (id, row) -> SyncCrypto.openRecord(enc, fam.s("id"), id, row.blob)!! }
    }

    @Test fun createAndJoinConverge() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val invite = family(a)
        assertTrue(a.db.o("sync").o("dirty")!!.isEmpty())
        assertEquals(1, a.db.objs("members").size)

        val r = done(runBlocking { b.engine.joinFamily(invite) })
        assertEquals(1, r.filesDown)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.files.bytes["f1"])
        assertEquals("SBI home loan", b.rec("loans", "l1")!!.s("name"))
        assertEquals(2, b.db.objs("members").size)
        done(a.sync())
        assertEquals(a.shared(), b.shared())
        assertEquals(a.db.o("family"), b.db.o("family"))

        // the relay holds nothing readable
        for ((_, row) in relay.records[a.db.o("family").s("id")]!!) {
            val raw = String(Base64.getDecoder().decode(row.blob), Charsets.ISO_8859_1)
            assertFalse(raw.contains("SBI")); assertFalse(raw.contains("23000"))
        }
        assertEquals(SyncCrypto.tokenHash(SyncCrypto.authToken(Base64.getDecoder().decode(a.db.o("family").s("key")))), relay.families.values.single())
    }

    @Test fun threePhonesLastWriterWins() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val invite = family(a)
        done(runBlocking { b.engine.joinFamily(invite) })
        done(runBlocking { c.engine.joinFamily(invite) })
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
        // join's check pull, then page 1 ok, then page 2 fails
        relay.failPull.addAll(listOf(null, null, SyncError.Offline))
        val r = runBlocking { b.engine.joinFamily(invite) }
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
        done(runBlocking { b.engine.joinFamily(invite) })
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
        done(runBlocking { b.engine.joinFamily(invite) })
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
        done(runBlocking { b.engine.joinFamily(invite) })
        assertNull(b.rec("loans", "lp"))
        assertTrue(a.db.o("sync").o("blobsUp")!!.b("fp"))         // kept for if it is ever shared

        // make the shared loan private: the model marks the loan, its payments and files dirty
        a.edit("loans", "l1") { it["private"] = true }
        a.db.oo("sync").oo("dirty")["p1"] = true; a.db.oo("sync").oo("dirty")["f1"] = true
        done(a.sync())
        for (json in openAll(a.db.o("family")!!)) {
            val r = JsonCodec.parseObj(json).o("r")!!
            if (r["id"] in setOf("l1", "p1", "f1")) assertEquals(setOf("id", "deleted", "updatedAt", "updatedBy"), r.keys)
            assertFalse(json.contains("SBI") || json.contains("Secret") || json.contains("4242"))
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
        val invite = Invite.parse(family(a))!!
        val before = JsonCodec.stringify(b.db)
        assertEquals(SyncResult.BadInvite, runBlocking { b.engine.joinFamily("tally://join#nope") })
        val forged = Invite(invite.familyId, ByteArray(32) { 7 }, invite.name)
        assertEquals(SyncResult.Failed(SyncError.Unauthorised), runBlocking { b.engine.joinFamily(forged) })
        assertEquals(before, JsonCodec.stringify(b.db))
        // already in a family
        assertEquals(SyncResult.AlreadyInFamily, runBlocking { a.engine.createFamily("Other") })
        assertEquals(SyncResult.NoFamily, b.sync())
        // a row the relay serves under the wrong id does not open and is skipped, without stopping the sync
        val fid = invite.familyId
        val p1 = relay.records[fid]!!["p1"]!!
        relay.records[fid]!!["p1x"] = FakeRelay.Row("payment", p1.blob, 10_000)
        done(runBlocking { b.engine.joinFamily(invite) }).let { assertEquals(1, it.skipped) }
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
        assertNotNull(relay.blobs[fid]!!["f1"])
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
        assertNotNull(relay.blobs[fid]!!["fpp"])
        assertFalse(a.db.o("sync").o("blobsUp")!!.b("fpp"))
    }

    @Test fun aDeletedFileIsNotMissingBytesToDownload() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val invite = family(a)
        done(runBlocking { b.engine.joinFamily(invite) })
        // what the UI does when a document is deleted: tombstone the record, leave the queue, drop the bytes
        a.put("files", obj("id" to "f1", "ownerType" to "payment", "ownerId" to "p1", "deleted" to true))
        a.files.bytes.remove("f1")
        a.db.oo("sync").oo("blobsUp").remove("f1")
        done(a.sync())
        val fid = a.db.o("family").s("id")
        relay.blobs[fid]!!.remove("f1")
        val before = relay.blobGets
        // a phone that joins now gets the tombstone, asks for no bytes, and does not count it as a file it failed to get
        val r = done(runBlocking { c.engine.joinFamily(invite) })
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
