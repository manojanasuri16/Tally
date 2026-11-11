package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.SyncModel
import com.tally.book.sync.SyncModel.WireRecord
import org.junit.Assert.*
import org.junit.Test

class SyncModelTest {
    private fun book() = JsonCodec.parseObj("""
        {"v":1,"me":{"id":"mA","name":"A"},
         "loans":[{"id":"l1","kind":"bank","name":"SBI","private":false,"updatedAt":100,"updatedBy":"mA","future":{"x":1}},
                  {"id":"l2","kind":"hand","name":"Uncle","private":true,"updatedAt":100,"updatedBy":"mA"}],
         "payments":[{"id":"p1","loanId":"l1","amount":100,"kind":"emi","updatedAt":100,"updatedBy":"mA"},
                     {"id":"p2","loanId":"l2","amount":200,"kind":"interest","updatedAt":100,"updatedBy":"mA"},
                     {"id":"p3","loanId":"gone","amount":1,"updatedAt":100,"updatedBy":"mA"}],
         "files":[{"id":"f1","ownerType":"payment","ownerId":"p2","name":"x.jpg","enc":true,"updatedAt":100,"updatedBy":"mA"},
                  {"id":"f2","ownerType":"loan","ownerId":"l1","name":"y.pdf","enc":true,"updatedAt":100,"updatedBy":"mA"}],
         "sync":{"cursor":0,"dirty":{},"pushed":{},"blobsUp":{}}}""")

    private fun wire(kind: String, json: String) = WireRecord(kind, JsonCodec.parseObj(json))
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
        val out = SyncModel.mergeRemote(db, listOf(wire("loan", """{"id":"l1","kind":"bank","name":"SBI home","updatedAt":200,"updatedBy":"mB","newField":[1,2]}""")))
        assertEquals(1, out.taken)
        assertEquals("SBI home", loan(db, "l1").s("name"))
        assertEquals("[1,2]", JsonCodec.stringify(loan(db, "l1")["newField"]))
        assertTrue(SyncModel.pushed(db).b("l1"))
    }

    @Test fun cleanRecordTakesRemoteEvenIfOlder() {
        // DESIGN.md: not dirty -> take the remote record (the relay is the truth for clean records).
        val db = book()
        SyncModel.mergeRemote(db, listOf(wire("loan", """{"id":"l1","name":"old","updatedAt":50,"updatedBy":"mB"}""")))
        assertEquals("old", loan(db, "l1").s("name"))
    }

    @Test fun dirtyLocalNewerIsKept() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        val out = SyncModel.mergeRemote(db, listOf(wire("loan", """{"id":"l1","name":"theirs","updatedAt":99,"updatedBy":"mZ"}""")))
        assertEquals(1, out.kept)
        assertEquals("SBI", loan(db, "l1").s("name"))
        assertTrue(SyncModel.dirty(db).b("l1"))
    }

    @Test fun dirtyLocalOlderLosesAndIsCleared() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        SyncModel.mergeRemote(db, listOf(wire("loan", """{"id":"l1","name":"theirs","updatedAt":101,"updatedBy":"mB"}""")))
        assertEquals("theirs", loan(db, "l1").s("name"))
        assertFalse(SyncModel.dirty(db).b("l1"))
    }

    @Test fun tieBreaksOnGreaterUpdatedBy() {
        val db = book()
        SyncModel.dirty(db)["l1"] = true
        SyncModel.mergeRemote(db, listOf(wire("loan", """{"id":"l1","name":"lower","updatedAt":100,"updatedBy":"mA0"}""")))
        assertEquals("mA" < "mA0", true)
        assertEquals("lower", loan(db, "l1").s("name"))   // "mA0" > "mA": remote wins
        val db2 = book(); SyncModel.dirty(db2)["l1"] = true
        SyncModel.mergeRemote(db2, listOf(wire("loan", """{"id":"l1","name":"x","updatedAt":100,"updatedBy":"m"}""")))
        assertEquals("SBI", loan(db2, "l1").s("name"))   // "m" < "mA": local wins
        assertTrue(SyncModel.dirty(db2).b("l1"))
    }

    @Test fun tombstonesAndNewRecords() {
        val db = book()
        val out = SyncModel.mergeRemote(db, listOf(
            wire("file", """{"id":"f2","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("payment", """{"id":"p9","loanId":"l1","amount":5,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("member", """{"id":"mB","name":"B","updatedAt":300,"updatedBy":"mB"}"""),
        ))
        assertEquals(listOf("f2"), out.filesRemoved)
        val f2 = db.objs("files").first { it["id"] == "f2" }
        assertTrue(f2.b("deleted"))
        assertTrue("local-only enc is kept", f2.b("enc"))
        assertNotNull(db.objs("payments").firstOrNull { it["id"] == "p9" })
        assertEquals("B", db.objs("members").first()["name"])
    }

    @Test fun privateLocalIsNeverReplacedAndTombstoneIsReasserted() {
        val db = book()
        SyncModel.mergeRemote(db, listOf(wire("loan", """{"id":"l2","name":"shared again","private":false,"updatedAt":999,"updatedBy":"mB"}""")))
        assertEquals("Uncle", loan(db, "l2").s("name"))
        assertTrue(loan(db, "l2").b("private"))
        assertTrue(SyncModel.dirty(db).b("l2"))
        // our own tombstone echoing back changes nothing
        val db2 = book()
        SyncModel.mergeRemote(db2, listOf(wire("loan", """{"id":"l2","deleted":true,"updatedAt":100,"updatedBy":"mA"}""")))
        assertEquals("Uncle", loan(db2, "l2").s("name"))
        assertFalse(loan(db2, "l2").b("deleted"))
        assertFalse(SyncModel.dirty(db2).b("l2"))
    }

    @Test fun bareTombstonesOfOwnersDoNotShieldTheirFiles() {
        // l1 and p1 arrive as bare tombstones (no loanId / private), then the file under p1: it must still be taken
        val db = book()
        db.objs("files").first { it["id"] == "f2" }.apply { put("ownerType", "payment"); put("ownerId", "p1") }
        SyncModel.mergeRemote(db, listOf(
            wire("loan", """{"id":"l1","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("payment", """{"id":"p1","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
            wire("file", """{"id":"f2","deleted":true,"updatedAt":300,"updatedBy":"mB"}"""),
        ))
        assertTrue(db.objs("files").first { it["id"] == "f2" }.b("deleted"))
        // but for pushing, an unresolvable owner still counts as private
        assertTrue(SyncModel.isPrivate(db, "payment", obj("id" to "p7", "loanId" to "nowhere")))
    }

    @Test fun kindMismatchIsIgnored() {
        val db = book()
        val out = SyncModel.mergeRemote(db, listOf(wire("payment", """{"id":"l1","amount":1,"updatedAt":999}""")))
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
