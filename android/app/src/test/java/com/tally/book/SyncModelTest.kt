package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.SyncModel
import com.tally.book.sync.SyncModel.WireRecord
import org.junit.Assert.*
import org.junit.Test

class SyncModelTest {
    private fun book() = JsonCodec.parseObj("""
        {"v":1,"me":{"id":"mA","name":"A"},
         "loans":[{"id":"l1","kind":"bank","name":"SBI","private":false,"by":"mA","updatedAt":100,"updatedBy":"mA","future":{"x":1}},
                  {"id":"l2","kind":"hand","name":"Uncle","private":true,"updatedAt":100,"updatedBy":"mA"}],
         "payments":[{"id":"p1","loanId":"l1","amount":100,"kind":"emi","updatedAt":100,"updatedBy":"mA"},
                     {"id":"p2","loanId":"l2","amount":200,"kind":"interest","updatedAt":100,"updatedBy":"mA"},
                     {"id":"p3","loanId":"gone","amount":1,"updatedAt":100,"updatedBy":"mA"}],
         "files":[{"id":"f1","ownerType":"payment","ownerId":"p2","name":"x.jpg","enc":true,"updatedAt":100,"updatedBy":"mA"},
                  {"id":"f2","ownerType":"loan","ownerId":"l1","name":"y.pdf","enc":true,"updatedAt":100,"updatedBy":"mA"}],
         "sync":{"cursor":0,"dirty":{},"pushed":{},"blobsUp":{}}}""")

    private fun wire(kind: String, json: String) = WireRecord(kind, JsonCodec.parseObj(json))

    /** A trust where every row is proved to come from its updatedBy (as a correctly signed row would be); [admins] are admins. */
    private class Proved(val admins: Set<String> = emptySet()) : SyncModel.Trust {
        override fun signer(w: WireRecord): String? = w.rec.s("updatedBy").ifEmpty { null }
        override fun admin(member: String) = member in admins
    }
    private val NOW = 1_000_000L
    private fun merge(db: Obj, rows: List<WireRecord>, trust: SyncModel.Trust = Proved()) = SyncModel.mergeRemote(db, rows, trust, NOW)
    private fun loan(db: Obj, id: String) = db.objs("loans").first { it["id"] == id }

    @Test fun privacyResolvesThroughTheOwner() {
        val db = book()
        fun p(kind: String, list: String, id: String) = SyncModel.isPrivate(db, kind, db.objs(list).first { it["id"] == id })
        assertFalse(p("loan", "loans", "l1")); assertTrue(p("loan", "loans", "l2"))
        assertFalse(p("payment", "payments", "p1")); assertTrue(p("payment", "payments", "p2"))
        assertTrue("an unknown loan fails closed", p("payment", "payments", "p3"))
        assertTrue("file -> payment -> private loan", p("file", "files", "f1"))
        assertFalse(p("file", "files", "f2"))
    }

    @Test fun remoteNewerReplacesCleanRecordAndKeepsUnknownFields() {
        val db = book()
        val out = merge(db, listOf(wire("loan", """{"id":"l1","kind":"bank","name":"SBI home","updatedAt":200,"updatedBy":"mB","newField":[1,2]}""")))
        assertEquals(1, out.taken)
        assertEquals("SBI home", loan(db, "l1").s("name"))
        assertEquals("[1,2]", JsonCodec.stringify(loan(db, "l1")["newField"]))
        assertTrue(SyncModel.pushed(db).b("l1"))
    }

    @Test fun anOlderCopyFromTheRelayIsRefusedAndTheNewerOneGoesBackUp() {
        // M3: a relay replaying an old ciphertext (with a fresh seq) can no longer roll a record back on a phone that has newer
        val db = book()
        val out = merge(db, listOf(wire("loan", """{"id":"l1","name":"old","updatedAt":50,"updatedBy":"mB"}""")))
        assertEquals(1, out.ignored)
        assertEquals("SBI", loan(db, "l1").s("name"))
        assertTrue("this phone's newer copy is sent again", SyncModel.dirty(db).b("l1"))
        // a deleted record stays deleted when the relay serves the live copy from before
        val db2 = book()
        merge(db2, listOf(wire("loan", """{"id":"l1","deleted":true,"updatedAt":150,"updatedBy":"mA"}""")))
        assertTrue(loan(db2, "l1").b("deleted"))
        merge(db2, listOf(wire("loan", """{"id":"l1","name":"SBI","updatedAt":100,"updatedBy":"mA"}""")))
        assertTrue(loan(db2, "l1").b("deleted"))
        // the same version again is fine (this phone's own push coming back)
        val db3 = book()
        assertEquals(1, merge(db3, listOf(wire("loan", """{"id":"l1","kind":"bank","name":"SBI","private":false,"updatedAt":100,"updatedBy":"mA"}"""))).taken)
        assertFalse(SyncModel.dirty(db3).b("l1"))
    }

    @Test fun aFutureDateIsPulledBackSoItCannotWinForEver() {
        val db = book()
        merge(db, listOf(wire("loan", """{"id":"l1","name":"from the year 3000","updatedAt":32503680000000,"updatedBy":"mB"}""")))
        assertEquals("from the year 3000", loan(db, "l1").s("name"))
        assertEquals((NOW + SyncModel.SKEW_MS).toDouble(), loan(db, "l1").num("updatedAt"), 0.0)
        // the next edit here is stamped after it, even on a clock that is behind, and so wins everywhere
        val next = SyncModel.stamp(loan(db, "l1").num("updatedAt"), NOW)
        assertTrue(next > loan(db, "l1").num("updatedAt"))
        assertEquals((NOW + SyncModel.SKEW_MS + 1).toDouble(), next, 0.0)
        // a write never goes further ahead than the skew, whatever it replaces
        assertEquals((NOW + SyncModel.SKEW_MS + 1).toDouble(), SyncModel.stamp(9e15, NOW), 0.0)
        assertEquals(NOW.toDouble(), SyncModel.stamp(5.0, NOW), 0.0)
    }

    @Test fun aSharedRecordSayingPrivateIsNeverTaken() {
        // L4: flipping private on someone's shared loan used to make their phone delete it for everyone on its next edit
        val db = book()
        val out = merge(db, listOf(wire("loan", """{"id":"l1","kind":"bank","name":"SBI","private":true,"updatedAt":500,"updatedBy":"mB"}""")))
        assertEquals(1, out.ignored)
        assertFalse(loan(db, "l1").b("private"))
        assertTrue(SyncModel.collectPush(db).isEmpty())
    }

    @Test fun onlyTheMakerTheLoansMakerOrAnAdminCanDelete() {
        val db = book()
        loan(db, "l1")["by"] = "mA"
        db.objs("payments").first { it["id"] == "p1" }["by"] = "mC"
        // another member deletes A's loan: refused and kept here (not sent back up, which would only bounce for ever)
        var out = merge(db, listOf(wire("loan", """{"id":"l1","deleted":true,"updatedAt":300,"updatedBy":"mB"}""")))
        assertEquals(1, out.ignored); assertFalse(loan(db, "l1").b("deleted")); assertFalse(SyncModel.dirty(db).b("l1"))
        // nobody proved (an unsigned or forged row): refused too
        out = merge(db, listOf(wire("loan", """{"id":"l1","deleted":true,"updatedAt":300,"updatedBy":"mA"}""")), SyncModel.Trust.None)
        assertFalse(loan(db, "l1").b("deleted"))
        // the loan's maker deletes C's payment (making the loan private does that): taken
        merge(db, listOf(wire("payment", """{"id":"p1","deleted":true,"updatedAt":300,"updatedBy":"mA"}""")))
        assertTrue(db.objs("payments").first { it["id"] == "p1" }.b("deleted"))
        // an admin may delete anything; the maker may delete their own
        SyncModel.dirty(db).remove("l1")
        merge(db, listOf(wire("loan", """{"id":"l1","deleted":true,"updatedAt":400,"updatedBy":"mZ"}""")), Proved(setOf("mZ")))
        assertTrue(loan(db, "l1").b("deleted"))
        // edits by anyone still merge as before (trust within the family)
        merge(db, listOf(wire("payment", """{"id":"p3","loanId":"gone","amount":2,"updatedAt":400,"updatedBy":"mQ"}""")))
        assertEquals(2.0, db.objs("payments").first { it["id"] == "p3" }.num("amount"), 0.0)
    }

    @Test fun aMemberRecordIsTakenOnlyFromThatMemberOrAnAdmin() {
        // M4/L4: a member can no longer rename others, mark them gone, or put a key of their own in their record
        val db = book()
        db.oo("family")["roster"] = arr(obj("id" to "mA", "role" to "admin"), obj("id" to "mB", "role" to "member"), obj("id" to "mC", "role" to "member"))
        merge(db, listOf(wire("member", """{"id":"mC","name":"C","updatedAt":10,"updatedBy":"mC"}""")))
        assertEquals("C", db.objs("members").first { it["id"] == "mC" }.s("name"))
        val out = merge(db, listOf(wire("member", """{"id":"mC","name":"Not C","left":true,"pub":"x","updatedAt":20,"updatedBy":"mB"}""")))
        assertEquals(1, out.ignored)
        assertEquals("C", db.objs("members").first { it["id"] == "mC" }.s("name"))
        merge(db, listOf(wire("member", """{"id":"mC","name":"C","left":true,"updatedAt":30,"updatedBy":"mA"}""")), Proved(setOf("mA")))
        assertTrue(db.objs("members").first { it["id"] == "mC" }.b("left"))
        // unproved: not taken at all
        assertEquals(1, merge(db, listOf(wire("member", """{"id":"mD","name":"D","updatedAt":40,"updatedBy":"mD"}""")), SyncModel.Trust.None).ignored)
    }

    @Test fun entriesFromBeforeJoiningWaitForTheAnswer() {
        // M2: joining shares nothing that was on the phone until the person says so
        val db = book()
        assertEquals(1, SyncModel.holdExisting(db))                       // l1 (l2 is private)
        listOf("l1", "p1", "f2").forEach { SyncModel.dirty(db)[it] = true }   // even edited meanwhile
        SyncModel.blobsUp(db)["f2"] = true
        assertTrue(SyncModel.collectPush(db).isEmpty())
        assertTrue(SyncModel.planBlobs(db).upload.isEmpty())
        // a new loan made after joining is shared as usual
        db.aa("loans").add(obj("id" to "l9", "name" to "new", "updatedAt" to 1, "updatedBy" to "mA")); SyncModel.dirty(db)["l9"] = true
        assertEquals(listOf("l9"), SyncModel.collectPush(db).map { it.id })
        // "Keep them on this phone only": the loan becomes private and nothing of it is ever sent
        val kept = db.copyObj()
        SyncModel.keepHeld(kept, "mA", NOW)
        assertTrue(kept.objs("loans").first { it["id"] == "l1" }.b("private"))
        assertTrue(SyncModel.collectPush(kept).filter { it.send }.none { it.id in setOf("l1", "p1", "f2") })
        // "Share all": everything goes
        SyncModel.shareHeld(db)
        assertTrue(SyncModel.collectPush(db).filter { it.send }.map { it.id }.containsAll(listOf("l1", "p1", "f2")))
        assertEquals(listOf("f2"), SyncModel.planBlobs(db).upload)
    }

    @Test fun dirtyLocalNewerIsKept() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        val out = merge(db, listOf(wire("loan", """{"id":"l1","name":"theirs","updatedAt":99,"updatedBy":"mZ"}""")))
        assertEquals(1, out.kept)
        assertEquals("SBI", loan(db, "l1").s("name"))
        assertTrue(SyncModel.dirty(db).b("l1"))
    }

    @Test fun dirtyLocalOlderLosesAndIsCleared() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        merge(db, listOf(wire("loan", """{"id":"l1","name":"theirs","updatedAt":101,"updatedBy":"mB"}""")))
        assertEquals("theirs", loan(db, "l1").s("name"))
        assertFalse(SyncModel.dirty(db).b("l1"))
    }

    @Test fun tieBreaksOnGreaterUpdatedBy() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        merge(db, listOf(wire("loan", """{"id":"l1","name":"lower","updatedAt":100,"updatedBy":"mA0"}""")))
        assertEquals("mA" < "mA0", true)
        assertEquals("lower", loan(db, "l1").s("name"))   // "mA0" > "mA": remote wins
        val db2 = book(); SyncModel.dirty(db2)["l1"] = true
        merge(db2, listOf(wire("loan", """{"id":"l1","name":"x","updatedAt":100,"updatedBy":"m"}""")))
        assertEquals("SBI", loan(db2, "l1").s("name"))   // "m" < "mA": local wins
        assertTrue(SyncModel.dirty(db2).b("l1"))
    }

    @Test fun tombstonesAndNewRecords() {
        val db = book()
        val out = merge(db, listOf(
            wire("file", """{"id":"f2","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("payment", """{"id":"p9","loanId":"l1","amount":5,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("member", """{"id":"mB","name":"B","updatedAt":300,"updatedBy":"mB"}"""),
        ), Proved(setOf("mB")))
        assertEquals(listOf("f2"), out.filesRemoved)
        val f2 = db.objs("files").first { it["id"] == "f2" }
        assertTrue(f2.b("deleted"))
        assertTrue("local-only enc is kept", f2.b("enc"))
        assertNotNull(db.objs("payments").firstOrNull { it["id"] == "p9" })
        assertEquals("B", db.objs("members").first()["name"])
    }

    @Test fun privateLocalIsNeverReplacedAndTombstoneIsReasserted() {
        val db = book()
        merge(db, listOf(wire("loan", """{"id":"l2","name":"shared again","private":false,"updatedAt":999,"updatedBy":"mB"}""")))
        assertEquals("Uncle", loan(db, "l2").s("name"))
        assertTrue(loan(db, "l2").b("private"))
        assertTrue(SyncModel.dirty(db).b("l2"))
        // our own tombstone echoing back changes nothing
        val db2 = book()
        merge(db2, listOf(wire("loan", """{"id":"l2","deleted":true,"updatedAt":100,"updatedBy":"mA"}""")))
        assertEquals("Uncle", loan(db2, "l2").s("name"))
        assertFalse(loan(db2, "l2").b("deleted"))
        assertFalse(SyncModel.dirty(db2).b("l2"))
    }

    @Test fun bareTombstonesOfOwnersDoNotShieldTheirFiles() {
        // l1 and p1 arrive as bare tombstones (no loanId / private), then the file under p1: it must still be taken
        val db = book()
        db.objs("files").first { it["id"] == "f2" }.apply { put("ownerType", "payment"); put("ownerId", "p1") }
        merge(db, listOf(
            wire("loan", """{"id":"l1","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("payment", """{"id":"p1","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("file", """{"id":"f2","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
        ), Proved(setOf("mB")))
        assertTrue(db.objs("files").first { it["id"] == "f2" }.b("deleted"))
        // but for pushing, an unresolvable owner still counts as private
        assertTrue(SyncModel.isPrivate(db, "payment", obj("id" to "p7", "loanId" to "nowhere")))
    }

    @Test fun kindMismatchIsIgnored() {
        val db = book()
        val out = merge(db, listOf(wire("payment", """{"id":"l1","amount":1,"updatedAt":999}""")))
        assertEquals(1, out.ignored)
        assertEquals("SBI", loan(db, "l1").s("name"))
    }

    @Test fun privateNeverPushedAndTombstoneForPrivateTheRelayHolds() {
        val db = book()
        listOf("l1", "l2", "p1", "p2", "p3", "f1", "f2").forEach { SyncModel.dirty(db)[it] = true }
        var items = SyncModel.collectPush(db).associateBy { it.id }
        assertTrue(items["l1"]!!.send); assertTrue(items["p1"]!!.send); assertTrue(items["f2"]!!.send)
        for (id in listOf("l2", "p2", "p3", "f1")) assertFalse("$id must not be sent", items[id]!!.send)
        // loans go first
        assertEquals("loan", SyncModel.collectPush(db).first().kind)
        // the file's local-only field does not travel; unknown fields do
        assertFalse(items["f2"]!!.json!!.contains("\"enc\""))
        assertTrue(items["l1"]!!.json!!.contains("\"future\":{\"x\":1}"))
        assertTrue(items["l1"]!!.json!!.startsWith("{\"k\":\"loan\",\"r\":{"))

        // the relay already holds p2 and l2 (they were shared once): now they go up as bare tombstones
        SyncModel.pushed(db)["l2"] = true; SyncModel.pushed(db)["p2"] = true
        items = SyncModel.collectPush(db).associateBy { it.id }
        assertEquals("""{"k":"payment","r":{"id":"p2","deleted":true,"updatedAt":100,"updatedBy":"mA"}}""", items["p2"]!!.json)
        assertEquals("""{"k":"loan","r":{"id":"l2","deleted":true,"updatedAt":100,"updatedBy":"mA"}}""", items["l2"]!!.json)
        for (it in items.values) assertFalse(it.json?.contains("Uncle") == true || it.json?.contains("\"amount\":200") == true)
    }

    @Test fun afterPushClearsOnlyUnchanged() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true; SyncModel.dirty(db)["p1"] = true; SyncModel.dirty(db)["l2"] = true
        val items = SyncModel.collectPush(db)
        loan(db, "l1")["name"] = "edited while pushing"; loan(db, "l1")["updatedAt"] = 101.0
        SyncModel.afterPush(db, items)
        assertTrue(SyncModel.dirty(db).b("l1"))
        assertFalse(SyncModel.dirty(db).b("p1"))
        assertFalse("a private record with nothing on the relay is simply done", SyncModel.dirty(db).b("l2"))
        assertTrue(SyncModel.pushed(db).b("l1")); assertTrue(SyncModel.pushed(db).b("p1")); assertFalse(SyncModel.pushed(db).b("l2"))
    }

    @Test fun madePrivateDuringPushStaysDirty() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        val items = SyncModel.collectPush(db)
        loan(db, "l1")["private"] = true
        SyncModel.afterPush(db, items)
        assertTrue(SyncModel.dirty(db).b("l1"))
        assertTrue(SyncModel.collectPush(db).single().json!!.contains("\"deleted\":true"))
        assertFalse(SyncModel.collectPush(db).single().json!!.contains("SBI"))
    }

    @Test fun blobPlanSkipsPrivateAndDropsGone() {
        val db = book()
        listOf("f1", "f2", "nope").forEach { SyncModel.blobsUp(db)[it] = true }
        val plan = SyncModel.planBlobs(db)
        assertEquals(listOf("f2"), plan.upload)
        assertEquals(listOf("nope"), plan.drop)
    }
}
