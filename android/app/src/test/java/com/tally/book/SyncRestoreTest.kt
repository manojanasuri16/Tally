package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * A book restored from a backup, then synced (docs/DESIGN.md, "Backups" > "Restore and sync"). The restored book keeps its
 * sync state (cursor, dirty marks, pushed, blobsUp), so the next sync simply catches up from the backup's cursor.
 */
class SyncRestoreTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }

    private class Snapshot(val db: Obj, val files: Map<String, ByteArray>)
    private fun backup(p: Phone) = Snapshot(p.db.copyObj(), p.files.bytes.mapValues { it.value.copyOf() })

    /** What a restore does to the book the engine sees: the backup's book and files in place, marked as restored. */
    private fun restore(p: Phone, s: Snapshot, withFiles: Boolean = true) {
        p.book.db = s.db.copyObj()
        p.files.bytes.clear()
        if (withFiles) s.files.forEach { (k, v) -> p.files.bytes[k] = v.copyOf() }
        SyncModel.markRestored(p.db)
        p.engine.resetState()
    }

    /** The family: A with a shared loan (l1, p1, f1) and l2, B joined; both synced, so both phones hold f1 and the relay none. */
    private fun family(a: Phone, b: Phone) {
        a.loan("l1", "SBI home loan")
        a.loan("l2", "Gold loan")
        a.payment("p1", "l1", 23000)
        done(runBlocking { a.engine.createFamily("Home") })
        done(b.joinVia(a))
        done(runBlocking { a.engine.setAdmin("m-B", true) })        // B deletes A's payment below: only its maker or an admin may
        a.file("f1", "payment", "p1", byteArrayOf(1, 2, 3, 4))
        done(a.sync()); done(b.sync())
    }

    private fun sharedWithout(p: Phone, vararg ids: String): String = JsonCodec.stringify(obj(*listOf("loans", "payments", "files", "members").map { k ->
        k to ArrayList<Any?>(p.db.objs(k).filter { it.s("id") !in ids }.sortedBy { it.s("id") }.map { r -> r.copyObj().also { it.remove("enc") } })
    }.toTypedArray()))

    /** Everything that happens on the two phones after A's backup was taken. */
    private fun lifeAfterTheBackup(a: Phone, b: Phone) {
        done(a.sync())                                              // A's edits from before the backup go up
        done(b.sync())
        b.edit("loans", "l1") { it["name"] = "SBI home loan (B)" }  // newer than A's edit in the backup
        b.edit("payments", "p1") { it["deleted"] = true }          // the family deletes a payment
        b.loan("l3", "New by B")
        done(b.sync())
        // A shares the loan that was private in the backup (the model touches the loan and its payments)
        a.edit("loans", "lq") { it["private"] = false }
        a.edit("payments", "pq") { }
        done(a.sync()); done(b.sync())
        b.edit("loans", "lq") { it["name"] = "Shared now (B)" }
        done(b.sync())
    }

    @Test fun anOldBackupOnTheSamePhoneCatchesUpWithTheFamily() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        a.loan("lq", "Private then", private = true)
        a.payment("pq", "lq", 100)
        a.edit("loans", "l1") { it["name"] = "SBI (A, before the backup)" }   // still dirty in the backup
        a.edit("loans", "l2") { it["name"] = "Gold loan (A)" }                // dirty too; nobody changes it later
        val snap = backup(a)
        assertTrue(snap.db.o("sync").o("dirty")!!.b("l1"))
        lifeAfterTheBackup(a, b)

        restore(a, snap)
        done(a.sync()); done(b.sync()); done(a.sync())
        assertEquals(a.shared(), b.shared())
        assertEquals("SBI home loan (B)", a.rec("loans", "l1")!!.s("name"))   // the later writer wins over the backup's dirty edit
        assertEquals("Gold loan (A)", b.rec("loans", "l2")!!.s("name"))
        assertTrue(a.rec("payments", "p1")!!.b("deleted"))                   // not brought back by the restore
        assertNotNull(a.rec("loans", "l3"))
        // the loan that was private in the backup and shared since: the family's newer copy wins, nothing is deleted
        assertEquals("Shared now (B)", a.rec("loans", "lq")!!.s("name"))
        assertFalse(a.rec("loans", "lq")!!.b("private"))
        assertFalse(b.rec("loans", "lq")!!.b("deleted"))
        assertFalse(b.rec("payments", "pq")!!.b("deleted"))
        assertFalse(a.db.o("sync")!!.b("restored"))                          // caught up: back to the normal rules
        listOf(a, b).forEach { assertTrue(it.name, it.db.o("sync").o("dirty")!!.isEmpty()) }
    }

    @Test fun withoutTheRestoreMarkTheStalePrivateCopyNoLongerDeletesTheFamilysLoan() {
        // why markRestored exists: the normal rule answers a newer row for a private record with a tombstone. Since the review's M3
        // that tombstone is stamped before the family's newer copy and every other phone refuses it, so the family keeps the loan
        // even without the mark (the restored phone itself still needs the mark to take the loan back)
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        a.loan("lq", "Private then", private = true)
        a.payment("pq", "lq", 100)
        val snap = backup(a)
        lifeAfterTheBackup(a, b)
        a.book.db = snap.db.copyObj()                                      // restored, but not marked
        done(a.sync()); done(b.sync())
        assertFalse(b.rec("loans", "lq")!!.b("deleted"))
        assertTrue(a.rec("loans", "lq")!!.b("private"))
    }

    @Test fun aFreshInstallRestoredFromABackupMatchesTheFamilyAndKeepsPrivateLoans() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        a.loan("lp", "Secret", private = true)
        a.payment("pp", "lp", 4242)
        a.file("fp", "loan", "lp", byteArrayOf(9, 9))
        a.edit("loans", "l2") { it["name"] = "Gold loan (A, never synced)" }
        val snap = backup(a)                                               // then A's phone is lost
        b.edit("loans", "l1") { it["name"] = "SBI (B)" }
        done(b.sync())

        val c = Phone("C", relay)                                          // the new phone
        restore(c, snap)
        assertEquals("m-A", c.me)                                          // the person comes back, not a new one
        done(c.sync()); done(b.sync()); done(c.sync())
        assertEquals(sharedWithout(b), sharedWithout(c, "lp", "pp", "fp"))
        assertEquals("SBI (B)", c.rec("loans", "l1")!!.s("name"))
        assertEquals("Gold loan (A, never synced)", b.rec("loans", "l2")!!.s("name"))   // the backup's unsynced edit still goes up
        // private loans intact here and never pushed
        assertEquals("Secret", c.rec("loans", "lp")!!.s("name"))
        assertArrayEquals(byteArrayOf(9, 9), c.files.bytes["fp"])
        val fid = c.db.o("family").s("id")
        for (id in listOf("lp", "pp", "fp")) { assertNull(relay.records[fid]!![id]); assertNull(b.rec("loans", id)) }
        assertNull(relay.blobs[fid]!!["fp"])
        assertEquals(1, c.db.objs("members").count { !it.b("deleted") && it.s("id") == "m-A" })
    }

    @Test fun aFileWhoseBytesAreMissingAfterARestoreIsAskedForAndComesBack() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.files.bytes["f1"])
        val fid = a.db.o("family").s("id")
        assertTrue(relay.blobs[fid]!!.isEmpty())                           // B collected it: the relay let it go
        val snap = backup(a)
        val c = Phone("C", relay)
        restore(c, snap, withFiles = false)
        assertNull(c.files.bytes["f1"])
        assertFalse(runBlocking { c.engine.fetchFile("f1") })              // on first open: not on the relay any more
        assertEquals(FileAsk.Asked, runBlocking { c.engine.askForFile("f1") })
        done(b.sync())                                                     // B holds it and offers it again, owed to the asker only
        assertNotNull(relay.blobs[fid]!!["f1"])
        assertEquals(setOf("m-A"), relay.owed[fid]!!["f1"])
        val r = done(c.sync())                                             // the restored phone fetches it, checks it, says so
        assertEquals(1, r.filesDown)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), c.files.bytes["f1"])
        assertTrue(relay.blobs[fid]!!.isEmpty())
    }

    @Test fun theRestoreMarkNeedsAFamilyAndOutlivesAFailedSync() {
        val solo = obj("v" to 1)
        SyncModel.markRestored(solo)
        assertFalse(SyncModel.restored(solo))
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        val snap = backup(a)
        restore(a, snap)
        relay.failPull.add(SyncError.Offline)
        assertTrue(a.sync() is SyncResult.Failed)
        assertTrue(SyncModel.restored(a.db))                               // still catching up
        done(a.sync())
        assertFalse(SyncModel.restored(a.db))
    }
}
