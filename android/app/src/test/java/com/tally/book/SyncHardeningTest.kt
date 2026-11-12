package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The fixes of the security review of 2026-11-12 (docs/SECURITY_REVIEW.md, "Fixes") that live in the relay and the merge, end to
 * end against [FakeRelay] (which mirrors the tally_hardening migration): H1 (limits), M2 (joining asks before sharing), M3 (no
 * rollback, no permanent future dates), M4 (pins only from an admin's certificate), L4 (who may delete, private flips), L5
 * (members see only their own invites), and the relay Info items (response cap, grants on future objects).
 */
class SyncHardeningTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }
    private fun <T> rb(block: suspend () -> T): T = runBlocking { block() }
    private fun famId(p: Phone) = p.db.o("family").s("id")
    private fun cred(p: Phone) = Cred(famId(p), p.me, p.db.o("family").o("me").s("secret"))
    private fun pin(p: Phone, id: String) = p.db.o("family").o("pins")?.get(id) as? String
    private fun pubOf(p: Phone) = p.db.o("family").o("me").s("pub")

    /** What [by] (a phone in the family) would push for a record: sealed, signed by it. */
    private fun forged(by: Phone, kind: String, rec: Obj): RelayRow {
        val fam = Membership.famOf(by.db)!!
        val env = obj("k" to kind, "r" to rec)
        Membership.signRecord(fam, kind, rec)?.let { env["sig"] = it }
        val blob = SyncCrypto.sealE(fam.encKey(fam.relayEpoch)!!, fam.relayEpoch, JsonCodec.stringify(env).toByteArray(), SyncCrypto.recordAad(fam.id, rec.s("id")))
        return RelayRow(rec.s("id"), kind, blob)
    }

    // ---------- M4: key pins ----------

    @Test fun aMemberCannotPoisonAnotherAdminsPinForSomeone() {
        val a = Phone("A", relay); val b = Phone("B", relay); val x = Phone("X", relay); val m = Phone("M", relay)
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a)); done(rb { a.engine.setAdmin("m-B", true) }); done(b.sync())
        done(x.joinVia(a)); done(m.joinVia(a))
        // M pushes a record for X carrying M's own key, dated far in the future
        val bad = obj("id" to "m-X", "name" to "X", "pub" to pubOf(m), "at" to 1.0, "updatedAt" to 9.9e15, "updatedBy" to "m-M")
        assertTrue(rb { relay.push(cred(m), 1, listOf(forged(m, "member", bad))) } is RelayResult.Ok)
        // B never saw X joining: it pins X's key only from A's certificate, never from M's record. M's row replaced A's on the
        // relay; a phone holding the proved copy (X's own, A's) refuses M's and sends the proved one back up
        done(b.sync())
        assertNull(pin(b, "m-X"))
        done(x.sync()); done(b.sync())
        assertEquals(pubOf(x), pin(b, "m-X"))
        assertEquals("X", b.rec("members", "m-X")!!.s("name"))
        assertNotEquals(pubOf(m), b.rec("members", "m-X")!!.s("pub"))
        // B removes M: X gets the new key (sealed to its real key) and keeps writing
        done(rb { b.engine.removeMember("m-M") })
        val r = done(x.sync())
        assertFalse(r.waitingForKey)
        x.loan("lx", "X after"); done(x.sync()); done(b.sync())
        assertEquals("X after", b.rec("loans", "lx")!!.s("name"))
    }

    @Test fun pinsComeOnlyFromAnAdminsCertificate() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a)); done(c.joinVia(a)); done(b.sync())
        // C's record carries A's certificate for C's key; B pinned C from it
        val cert = b.rec("members", "m-C")!!.o("cert")!!
        assertEquals("m-A", cert.s("by"))
        assertEquals(pubOf(c), pin(b, "m-C"))
        // a record with a key and no (or someone else's) certificate pins nothing
        val db = b.db.copyObj()
        db.o("family")!!.o("pins")!!.remove("m-C")
        db.objs("members").first { it.s("id") == "m-C" }.remove("cert")
        Membership.pinFromCerts(db); assertNull(db.o("family").o("pins")!!["m-C"])
        db.objs("members").first { it.s("id") == "m-C" }["cert"] = Membership.cert(Membership.famOf(b.db)!!, "m-C", pubOf(c))  // B is no admin
        Membership.pinFromCerts(db); assertNull(db.o("family").o("pins")!!["m-C"])
    }

    // ---------- L4: who wrote what ----------

    @Test fun aMemberCannotDeleteOrHideSomeoneElsesEntry() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        a.loan("l1", "A's loan"); a.payment("p1", "l1", 100); a.loan("l2", "A's other loan"); a.payment("p2", "l2", 7)
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a)); done(c.joinVia(a))
        // B flips "private" on A's loan: its phone sends a bare tombstone, which A and C refuse
        b.edit("loans", "l1") { it["private"] = true }
        done(b.sync()); done(a.sync()); done(c.sync())
        assertFalse(a.rec("loans", "l1")!!.b("deleted")); assertFalse(c.rec("loans", "l1")!!.b("deleted"))
        // a forged record saying private: true is never taken either
        val flip = a.rec("loans", "l1")!!.copyObj().also { it["private"] = true; it["updatedAt"] = Clock.millis() + 5000.0; it["updatedBy"] = "m-B"; it.remove("sig") }
        assertTrue(rb { relay.push(cred(b), 1, listOf(forged(b, "loan", flip))) } is RelayResult.Ok)
        done(c.sync()); assertFalse(c.rec("loans", "l1")!!.b("private"))
        // B tombstones A's payment: refused; the screens say so before it happens
        assertFalse(SyncModel.mayDeleteHere(b.db, "payment", b.rec("payments", "p2")!!))
        b.edit("payments", "p2") { it["deleted"] = true }; done(b.sync()); done(c.sync())
        assertFalse(c.rec("payments", "p2")!!.b("deleted"))
        // a record that claims another writer but carries B's signature proves only B
        val claim = obj("id" to "p2", "deleted" to true, "updatedAt" to Clock.millis() + 9000.0, "updatedBy" to "m-A")
        assertTrue(rb { relay.push(cred(b), 1, listOf(forged(b, "payment", claim))) } is RelayResult.Ok)
        done(c.sync()); assertFalse(c.rec("payments", "p2")!!.b("deleted"))
        // B cannot rename C either
        val rename = c.rec("members", "m-C")!!.copyObj().also { it["name"] = "Mallory"; it["updatedAt"] = Clock.millis() + 9000.0; it["updatedBy"] = "m-B"; it.remove("sig") }
        assertTrue(rb { relay.push(cred(b), 1, listOf(forged(b, "member", rename))) } is RelayResult.Ok)
        done(a.sync()); assertEquals("C", a.rec("members", "m-C")!!.s("name"))
        // the maker deletes their own; an admin may delete anything
        assertTrue(SyncModel.mayDeleteHere(a.db, "payment", a.rec("payments", "p2")!!))
        TestClock.tick(); a.edit("payments", "p2") { it["deleted"] = true }; done(a.sync()); done(c.sync())
        assertTrue(c.rec("payments", "p2")!!.b("deleted"))
    }

    // ---------- M3: rollback ----------

    @Test fun aRelayReplayingAnOldCopyCannotRollAPhoneBack() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        a.loan("l1", "first")
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a))
        val fid = famId(a)
        val old = relay.records[fid]!!["l1"]!!
        a.edit("loans", "l1") { it["name"] = "second" }
        done(a.sync()); done(b.sync())
        assertEquals("second", b.rec("loans", "l1")!!.s("name"))
        // the relay serves the old ciphertext again under a fresh seq
        relay.records[fid]!!["l1"] = FakeRelay.Row(old.kind, old.blob, 999_999)
        done(b.sync())
        assertEquals("second", b.rec("loans", "l1")!!.s("name"))
        // and B sent its newer copy back, so the relay holds it again
        done(a.sync())
        assertEquals("second", a.rec("loans", "l1")!!.s("name"))
    }

    // ---------- M2: joining asks before sharing ----------

    @Test fun joiningSharesNothingOfTheBookUntilThePersonSays() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        done(rb { a.engine.createFamily("Home") })
        b.loan("lb", "B's own loan"); b.payment("pb", "lb", 5)
        done(b.joinVia(a))
        val fid = famId(a)
        assertEquals(1, b.view.shareAsk)
        assertNull(relay.records[fid]!!["lb"]); assertNull(relay.records[fid]!!["pb"])
        b.loan("ln", "after joining"); done(b.sync())
        assertNotNull(relay.records[fid]!!["ln"]); assertNull(relay.records[fid]!!["lb"])
        // "Keep them on this phone only"
        done(b.share(false))
        assertEquals(0, b.view.shareAsk)
        assertTrue(b.rec("loans", "lb")!!.b("private"))
        done(b.sync())
        assertNull(relay.records[fid]!!["lb"]); assertNull(relay.records[fid]!!["pb"])
        // "Share all", on another phone
        c.loan("lc", "C's own loan")
        done(c.joinVia(a))
        assertNull(relay.records[fid]!!["lc"])
        done(c.share(true)); done(a.sync())
        assertEquals("C's own loan", a.rec("loans", "lc")!!.s("name"))
        // a phone with nothing on it is not asked
        val d = Phone("D", relay); done(d.joinVia(a)); assertEquals(0, d.view.shareAsk)
    }

    // ---------- L5: invites ----------

    @Test fun aMemberSeesOnlyTheirOwnInvites() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a))
        a.invite(); done(b.sync())
        assertTrue(b.view.invites.isEmpty())
        b.invite(); done(b.sync()); done(a.sync())
        assertEquals(1, b.view.invites.size)
        assertEquals(2, a.view.invites.size)
    }

    // ---------- H1: limits ----------

    @Test fun startingFamiliesNeedsWorkAndIsLimitedPerSource() {
        fun create(): RelayResult<Unit> { val f = Invite.newFamilyId(); return rb { relay.createFamily(f, "m-x", SyncCrypto.tokenHash("s"), null, SyncCrypto.pow(f, "m-x", relay.powBits)) } }
        val f0 = Invite.newFamilyId()
        assertEquals(RelayResult.Err(SyncError.BadData("pow")), rb { relay.createFamily(f0, "m-x", SyncCrypto.tokenHash("s"), null, "x") })
        repeat(5) { assertEquals(RelayResult.Ok(Unit), create()) }
        assertEquals(RelayResult.Err(SyncError.Limit), create())             // 5 an hour from one address
        relay.source = "elsewhere"; assertEquals(RelayResult.Ok(Unit), create())
        TestClock.advance(2); relay.source = "phone"; assertEquals(RelayResult.Ok(Unit), create())
        assertTrue(SyncCrypto.powOk("f", "m", SyncCrypto.pow("f", "m", 12), 12))
        assertFalse(SyncCrypto.powOk("f", "m", "nope-not-a-nonce", 30))
    }

    @Test fun aFamilyHasAByteBudgetAndAKeyLimit() {
        val a = Phone("A", relay)
        done(rb { a.engine.createFamily("Home") })
        relay.budgetOne = 300_000
        val big = "x".repeat(40_000)
        repeat(4) { a.put("loans", obj("id" to "big$it", "name" to big)) }
        done(a.sync())                                                         // about 160 kB: fits
        repeat(3) { a.put("loans", obj("id" to "more$it", "name" to big)) }
        val r = a.sync()
        assertEquals(SyncResult.Failed(SyncError.Limit), r)                   // a family of one is kept small
        // the relay as a whole: nothing new once it is at its ceiling
        relay.ceiling = 1
        assertEquals(SyncResult.Failed(SyncError.Limit), rb { Phone("Z", relay).engine.createFamily("Z") })
        // at most 300 key epochs
        relay.ceiling = Long.MAX_VALUE
        relay.fams[famId(a)]!!.epoch = 300
        assertEquals(RelayResult.Err(SyncError.Limit), rb { relay.rotate(cred(a), 301, listOf(BoxFor(a.me, "x"))) })
    }

    @Test fun shortPagesDoNotEndThePullAndDownloadsAreCapped() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        repeat(6) { a.loan("l$it", "loan $it") }
        done(rb { a.engine.createFamily("Home") })
        relay.pageBytes = 1                                                    // one row per page
        done(b.joinVia(a))
        assertEquals(6, b.db.objs("loans").size)
        relay.egressCap = 10
        b.db.oo("sync")["cursor"] = 0
        assertEquals(SyncResult.Failed(SyncError.Limit), b.sync())
    }

    @Test fun aFamilyOfOneTheRelayDeletedIsForgottenWithItsEntriesKept() {
        val a = Phone("A", relay)
        a.loan("l1", "mine")
        done(rb { a.engine.createFamily("Home") })
        relay.fams.remove(famId(a))                                            // tally_ping: one member, 60 days without a pull
        assertEquals(SyncResult.Ended, a.sync())
        assertNull(a.db["family"]); assertEquals("ended", a.view.gone!!.why); assertNotNull(a.rec("loans", "l1"))
        // a family with others in it is never forgotten on a bare "unauthorised"
        val b = Phone("B", relay); val c = Phone("C", relay)
        done(rb { b.engine.createFamily("Two") }); done(c.joinVia(b)); done(b.sync())
        relay.fams.remove(famId(b))
        assertEquals(SyncResult.Failed(SyncError.Unauthorised), b.sync())
        assertNotNull(b.db["family"])
    }

    // ---------- Info ----------

    @Test fun aHugeAnswerFromTheRelayIsRefused() {
        val ok = HttpRelay.readCapped(ByteArray(1000).inputStream(), 2000)
        assertEquals(1000, ok.size)
        assertThrows(HttpRelay.BadResponse::class.java) { HttpRelay.readCapped(ByteArray(3000).inputStream(), 2000) }
    }

    /** Every table, sequence and public function a migration creates has its grants revoked in the same migration. */
    @Test fun everyNewRelayObjectRevokesTheDefaultGrants() {
        val dir = listOf(File("../../supabase/migrations"), File("../supabase/migrations"), File("supabase/migrations")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".sql") }!!.sortedBy { it.name }
        assertTrue(files.size >= 8)
        for (f in files) {
            val sql = f.readText().lowercase().lines().filterNot { it.trim().startsWith("--") }.joinToString("\n")
            val revoked = Regex("""revoke all on (?:table|sequence|function)\s+([^;]+?)\s+from[^;]*\banon\b""").findAll(sql)
                .flatMap { m -> m.groupValues[1].split(',').map { it.trim().substringBefore('(').trim() } }.toSet()
            val allPrivate = sql.contains("revoke all on all functions in schema tally_private")
            for (m in Regex("""create (table|sequence) ([a-z_]+\.[a-z_]+)""").findAll(sql)) {
                assertTrue("${f.name}: ${m.groupValues[2]} keeps its default grants", m.groupValues[2] in revoked)
            }
            for (m in Regex("""create function ([a-z_]+\.[a-z_]+)\s*\(""").findAll(sql)) {
                val name = m.groupValues[1]
                assertTrue("${f.name}: $name keeps its default grants", name in revoked || (name.startsWith("tally_private.") && allPrivate))
            }
        }
        // and since the hardening migration, postgres's default privileges give anon and authenticated nothing at all
        val last = files.first { it.name.contains("hardening") }.readText()
        assertTrue(last.contains("alter default privileges in schema public revoke all on tables    from anon, authenticated"))
        assertTrue(last.contains("alter default privileges revoke execute on functions from public"))
    }
}
