package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import com.tally.book.ui.loans.FileAway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Files pass through the relay only in transit (docs/DESIGN.md "Files"; the tally_files_in_transit migration,
 * mirrored by FakeRelay): a file is owed to the members active when it was uploaded, each phone acknowledges it once stored
 * and checked, the relay lets it go when nobody is owed it or after 30 days, and "Ask for this file" brings it back.
 */
class SyncFilesTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }
    private val photo = byteArrayOf(1, 2, 3, 4, 5)

    /** A (admin) with a shared loan l1, then each of [others] let in; everyone synced. Returns the family id. */
    private fun family(a: Phone, vararg others: Phone): String {
        a.loan("l1", "SBI home loan")
        a.payment("p1", "l1", 23000)
        done(runBlocking { a.engine.createFamily("Home") })
        others.forEach { done(it.joinVia(a)) }
        done(a.sync())
        return a.db.o("family").s("id")
    }

    private fun owedTo(fid: String, id: String): Set<String> = relay.owed[fid]?.get(id).orEmpty()

    @Test fun uploadedThenCollectedByBothOthersThenDeleted() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val fid = family(a, b, c)
        a.file("f1", "payment", "p1", photo)
        assertEquals(1, done(a.sync()).filesUp)
        assertNotNull(relay.blobs[fid]!!["f1"])
        assertEquals(setOf("m-B", "m-C"), owedTo(fid, "f1"))               // the then-active members, not the uploader
        assertTrue(relay.blobBytes(fid) > 0)

        assertEquals(1, done(b.sync()).filesDown)
        assertArrayEquals(photo, b.files.bytes["f1"])
        assertEquals(setOf("m-C"), owedTo(fid, "f1"))
        assertNotNull(relay.blobs[fid]!!["f1"])

        assertEquals(1, done(c.sync()).filesDown)
        assertArrayEquals(photo, c.files.bytes["f1"])
        assertNull(relay.blobs[fid]!!["f1"])                                 // everyone has it: gone from the relay
        assertEquals(0L, relay.blobBytes(fid))
        // every phone keeps its own copy; a later sync asks for nothing more
        val gets = relay.blobGets
        listOf(a, b, c).forEach { done(it.sync()) }
        assertEquals(gets, relay.blobGets)
        assertArrayEquals(photo, a.files.bytes["f1"])
    }

    @Test fun aMemberAwayForThirtyOneDaysFindsItExpiredAndSeesWhoHasIt() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val fid = family(a, b, c)
        a.file("f1", "payment", "p1", photo)
        done(a.sync()); done(b.sync())
        assertEquals(setOf("m-C"), owedTo(fid, "f1"))
        TestClock.advance(31 * 24)
        relay.ping()                                                        // the daily tally_ping sweeps it
        assertNull(relay.blobs[fid]!!["f1"])
        val r = done(c.sync())
        assertEquals(0, r.filesDown); assertEquals(0, r.skipped)
        assertNull(c.files.bytes["f1"])
        val rec = c.rec("files", "f1")!!                                    // the record is there for ever, with who added it
        assertEquals("m-A", rec.s("by"))
        assertFalse(runBlocking { c.engine.fetchFile("f1") })
        assertEquals("On A's phone", FileAway.where(rec.s("by"), c.me, "A"))
        assertEquals("On your other phone", FileAway.where(rec.s("by"), a.me, "A"))
    }

    @Test fun expiryWithoutAPingStillHidesIt() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val fid = family(a, b)
        a.file("f1", "payment", "p1", photo)
        done(a.sync())
        TestClock.advance(30 * 24 + 1)
        val credB = Cred(fid, b.me, b.db.o("family").o("me").s("secret"))
        assertEquals(RelayResult.Ok(null), runBlocking { relay.blobGet(credB, "f1") })
        assertEquals(0, done(b.sync()).filesDown)
        assertNull(relay.blobs[fid]!!["f1"])                                 // the family's next file call swept it
    }

    @Test fun aMemberWhoJoinsLaterIsNotOwedOldFiles() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val fid = family(a, b)
        a.file("f1", "payment", "p1", photo)
        done(a.sync())                                                      // still waiting for B
        done(c.joinVia(a))
        assertEquals(setOf("m-B"), owedTo(fid, "f1"))
        assertNull(c.files.bytes["f1"])
        assertNotNull(c.rec("files", "f1"))
        done(b.sync())
        assertNull(relay.blobs[fid]!!["f1"])                                 // B was the last one owed it
        assertEquals("On A's phone", FileAway.where(c.rec("files", "f1")!!.s("by"), c.me, "A"))
    }

    @Test fun aRemovedOrLeavingMemberIsOwedNothing() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay); val d = Phone("D", relay)
        val fid = family(a, b, c, d)
        a.file("f1", "payment", "p1", photo)
        done(a.sync())
        assertEquals(setOf("m-B", "m-C", "m-D"), owedTo(fid, "f1"))
        done(runBlocking { a.engine.removeMember(c.me) })
        assertEquals(setOf("m-B", "m-D"), owedTo(fid, "f1"))
        assertTrue(runBlocking { d.engine.leaveFamily() })
        assertEquals(setOf("m-B"), owedTo(fid, "f1"))
        done(b.sync())
        assertNull(relay.blobs[fid]!!["f1"])
    }

    @Test fun aMemberBackOnANewPhoneStillOwesUnderTheSameId() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        val fid = family(a, b, c)
        a.file("f1", "payment", "p1", photo)
        done(a.sync())
        val b2 = Phone("B2", relay); b2.db.o("me")!!["name"] = "B"         // B's phone is lost; B comes back
        done(b2.comeBackVia(a, b.me))
        assertEquals("m-B", b2.me)
        assertArrayEquals(photo, b2.files.bytes["f1"])                      // still owed to m-B: the new phone fetched it
        assertEquals(setOf("m-C"), owedTo(fid, "f1"))
        done(c.sync())
        assertNull(relay.blobs[fid]!!["f1"])
    }

    @Test fun askForThisFileBringsItBackFromAHolder() {
        val a = Phone("A", relay); val b = Phone("B", relay); val d = Phone("D", relay)
        val fid = family(a, b)
        a.file("f1", "payment", "p1", photo)
        done(a.sync()); done(b.sync())
        assertNull(relay.blobs[fid]!!["f1"])
        done(d.joinVia(a))
        assertNull(d.files.bytes["f1"])
        assertFalse(runBlocking { d.engine.fetchFile("f1") })
        assertEquals(FileAsk.Asked, runBlocking { d.engine.askForFile("f1") })
        assertEquals(setOf("m-D"), relay.asks[fid]!!["f1"]!!.keys)
        assertEquals(0, done(d.sync()).filesDown)                           // nobody has offered it yet

        val up = done(b.sync())                                             // B is not the one who added it, but holds it
        assertEquals(1, up.filesUp)
        assertEquals(setOf("m-D"), owedTo(fid, "f1"))                       // owed to the asker only, not to A
        assertTrue(relay.asks[fid]!!["f1"].isNullOrEmpty())
        done(a.sync())                                                      // nothing more to offer, nothing to fetch
        assertEquals(setOf("m-D"), owedTo(fid, "f1"))
        assertEquals(1, done(d.sync()).filesDown)
        assertArrayEquals(photo, d.files.bytes["f1"])
        assertNull(relay.blobs[fid]!!["f1"])
        // asking for a file that is still on the relay just adds the asker: it is here at once
        a.file("f2", "payment", "p1", byteArrayOf(8, 8))
        done(a.sync())
        val e = Phone("E", relay); done(e.joinVia(a))
        assertEquals(FileAsk.Here, runBlocking { e.engine.askForFile("f2") })
        assertArrayEquals(byteArrayOf(8, 8), e.files.bytes["f2"])
        assertFalse("m-E" in owedTo(fid, "f2"))
    }

    @Test fun aDownloadIsAcknowledgedOnlyOnceStoredAndChecked() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val fid = family(a, b)
        a.file("f1", "payment", "p1", photo)
        done(a.sync())
        // the relay serves damaged bytes: they do not open, nothing is stored or acknowledged
        relay.corruptBlob = { it.dropLast(8) + "AAAAAAA=" }
        assertEquals(1, done(b.sync()).skipped)
        assertNull(b.files.bytes["f1"])
        assertEquals(setOf("m-B"), owedTo(fid, "f1"))
        relay.corruptBlob = null
        // the phone's storage damages what it writes: the read-back check catches it, the copy goes, no acknowledgement
        b.files.corruptWrites = true
        assertEquals(1, done(b.sync()).skipped)
        assertNull(b.files.bytes["f1"])
        assertFalse(relay.acks.contains("m-B" to "f1"))
        assertNotNull(relay.blobs[fid]!!["f1"])
        b.files.corruptWrites = false
        assertEquals(1, done(b.sync()).filesDown)
        assertTrue(relay.acks.contains("m-B" to "f1"))
        assertNull(relay.blobs[fid]!!["f1"])
    }

    @Test fun aFileDeletedBeforeItWasFetchedIsReleasedWithoutDownloading() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val fid = family(a, b)
        a.file("f1", "payment", "p1", photo)
        done(a.sync())
        a.put("files", obj("id" to "f1", "ownerType" to "payment", "ownerId" to "p1", "deleted" to true))
        done(a.sync())
        val gets = relay.blobGets
        done(b.sync())
        assertEquals(gets, relay.blobGets)
        assertNull(relay.blobs[fid]!!["f1"])
    }

    @Test fun aPrivateFileIsNeverUploadedNorOfferedAgain() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        val fid = family(a, b)
        a.loan("lp", "Secret", private = true)
        a.file("fp", "loan", "lp", byteArrayOf(9, 9))
        repeat(2) { done(a.sync()) }
        assertNull(relay.blobs[fid]!!["fp"]); assertNull(relay.records[fid]!!["fp"])
        val credB = Cred(fid, b.me, b.db.o("family").o("me").s("secret"))
        assertEquals(RelayResult.Err(SyncError.Refused), runBlocking { relay.fileAsk(credB, "fp") })   // no such file on the relay
        // even an ask the relay made up is never answered with a private file
        relay.asks.getOrPut(fid) { HashMap() }["fp"] = hashMapOf("m-B" to Clock.millis())
        assertEquals(0, done(a.sync()).filesUp)
        assertNull(relay.blobs[fid]!!["fp"])
        assertTrue(a.db.o("sync").o("blobsUp")!!.b("fp"))                   // still queued, for if the loan is shared later
    }

    @Test fun capsPerFileAndPerFamily() {
        assertEquals(6 * 1024 * 1024, SyncEngine.MAX_FILE_BLOB)
        assertEquals(50 * 1024 * 1024, SyncEngine.MAX_FAMILY_BLOBS)
        assertEquals(30, SyncEngine.FILE_DAYS)
        val a = Phone("A", relay); val b = Phone("B", relay)
        val fid = family(a, b)
        // a file whose sealed form is over 6 MiB is never sent and stays queued
        a.file("big", "payment", "p1", ByteArray(5 * 1024 * 1024) { it.toByte() })
        assertEquals(1, done(a.sync()).skipped)
        assertNull(relay.blobs[fid]!!["big"])
        assertTrue(a.db.o("sync").o("blobsUp")!!.b("big"))
        a.db.o("sync").o("blobsUp")!!.remove("big"); a.put("files", obj("id" to "big", "deleted" to true, "ownerType" to "payment", "ownerId" to "p1"))
        done(a.sync()); done(b.sync())
        // the family's files in flight: at the cap the rest wait, queued, and go once B has collected the first ones
        relay.blobCap = 120                                                  // two small sealed files fit, not three
        listOf("s1", "s2", "s3").forEach { a.file(it, "payment", "p1", byteArrayOf(1, 2, 3)) }
        val r = done(a.sync())
        assertEquals(2, r.filesUp); assertEquals(1, r.skipped)
        assertEquals(2, relay.blobs[fid]!!.size)
        assertEquals(1, a.db.o("sync").o("blobsUp")!!.size)
        done(b.sync())
        assertEquals(1, done(a.sync()).filesUp)
        done(b.sync())
        listOf("s1", "s2", "s3").forEach { assertNotNull(it, b.files.bytes[it]) }
        assertTrue(relay.blobs[fid]!!.isEmpty())
    }

    @Test fun theWordsForAFileKeptElsewhere() {
        assertEquals("On A's phone · 12 Nov 2026", FileAway.line(FileAway.where("m-A", "m-B", "A"), "12 Nov 2026"))
        val other = FileAway.explain("m-A", "m-B", "A", canAsk = true)
        assertTrue(other.startsWith("This file is kept on A's phone and in their backups."))
        assertTrue(other.contains("You can ask for it."))
        assertFalse(FileAway.explain("m-A", "m-B", "A", canAsk = false).contains("ask for it"))
        assertTrue(FileAway.explain("m-B", "m-B", "B", canAsk = true).startsWith("This file is kept on your other phone"))
        assertNull(FileAway.afterAsk(FileAsk.Here))
        assertTrue(FileAway.afterAsk(FileAsk.Asked)!!.startsWith("Asked."))
        assertTrue(FileAway.afterAsk(FileAsk.Failed(SyncError.Offline))!!.contains("internet"))
        listOf(FileAway.shareNote(), FileAway.explain("m-A", "m-B", "A", true)).forEach { w ->
            listOf("relay", "key", "encrypt").forEach { bad -> assertFalse(w, w.lowercase().contains(bad)) }
        }
    }
}
