package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import com.tally.book.ui.family.MemberText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Coming back on a new phone as the same person (docs/DESIGN.md, "Back on a new phone"), against [FakeRelay], which keeps the
 * rules of tally_member_reclaim and tally_join_check: an admin picks the member, the old phone is told it was replaced, the key
 * is rotated, and a hostile relay can neither move the place to another member nor replay a grant.
 */
class SyncRejoinTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }
    private fun <T> rb(block: suspend () -> T): T = runBlocking { block() }
    private fun famId(p: Phone) = p.db.o("family").s("id")
    private fun keysOf(p: Phone): Map<Int, ByteArray> =
        p.db.o("family")!!.o("keys")!!.entries.associate { (e, k) -> e.toInt() to Base64.getDecoder().decode(k as String) }
    private fun cred(p: Phone) = Cred(famId(p), p.me, p.db.o("family").o("me").s("secret"))
    private fun openWith(keys: Map<Int, ByteArray>, fam: String, id: String): String? {
        val row = relay.records[fam]!![id] ?: return null
        return SyncCrypto.openE({ keys[it]?.let { k -> SyncCrypto.encKey(k) } }, row.blob, SyncCrypto.recordAad(fam, id))?.let { String(it) }
    }
    private fun named(p: Phone, name: String): Phone { p.db.o("me")!!["name"] = name; return p }
    private fun nameOf(p: Phone, id: String) = p.db.objs("members").firstOrNull { it.s("id") == id }?.s("name")

    /** A (creator, admin) with a loan; B joins, is made admin and adds a loan, a payment and a private loan; C joins. */
    private fun family(a: Phone, b: Phone, c: Phone) {
        a.loan("l1", "Home loan")
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a)); done(c.joinVia(a))
        done(rb { a.engine.setAdmin("m-B", true) })
        b.loan("lb", "B's bank loan"); b.payment("pb", "lb", 900)
        b.loan("lp", "B's private loan", private = true); b.payment("pp", "lp", 50)
        done(b.sync()); done(a.sync()); done(c.sync())
        TestClock.advance(1)
    }

    @Test fun aMemberComesBackOnANewPhoneAsTheSamePerson() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val since = relay.fams[famId(a)]!!.members["m-B"]!!.joined
        val oldKeys = keysOf(b)
        val b2 = named(Phone("B2", relay), "B")
        b2.loan("ln", "Entered on the new phone first")                     // written before it was in any family

        assertEquals(SyncResult.JoinPending, rb { b2.engine.requestJoin(a.invite(), back = true) })
        assertTrue(b2.view.joining!!.back)
        done(a.sync())
        val req = a.engine.requests.value.single()
        assertTrue(req.back); assertEquals("B", req.name)
        assertEquals("B says they are back on a new phone. You invited them.", MemberText.requestLine(req, "Home", a.me))
        assertEquals("m-B", MemberText.likelyMember(req, a.view.members)?.id)   // picked by the name
        done(rb { a.engine.approveAs(req.id, "m-B") })
        done(b2.sync())

        // the new phone is B: same member id, so everything B wrote still shows B's name; the admin role and joined date stay
        assertEquals("m-B", b2.me)
        assertNull(b2.db["joining"])
        assertEquals("B", nameOf(b2, b2.rec("loans", "lb")!!.s("by")))
        assertEquals("m-B", b2.rec("loans", "ln")!!.s("by"))
        assertTrue(b2.view.iAmAdmin)
        assertEquals(since, relay.fams[famId(a)]!!.members["m-B"]!!.joined)
        assertEquals(listOf("m-A", "m-B", "m-C"), relay.fams[famId(a)]!!.members.values.filter { it.status == "active" }.map { it.id })
        assertNull(b2.db.objs("members").firstOrNull { it.s("id") == "m-B2" })

        // the key moved on; the old phone holds none of the new one
        val f = relay.fams[famId(a)]!!
        assertEquals(2, f.epoch)
        assertEquals(setOf(1, 2), keysOf(b2).keys)
        b2.payment("p2", "lb", 10); done(b2.sync())
        assertEquals(2, SyncCrypto.epochOf(relay.records[f.id]!!["p2"]!!.blob))
        assertNull(openWith(oldKeys, f.id, "p2"))
        done(a.sync()); done(c.sync())
        assertEquals("Entered on the new phone first", c.rec("loans", "ln")!!.s("name"))
        assertEquals("B", nameOf(c, c.rec("loans", "ln")!!.s("by")))

        // the old phone is told once, keeps its records, and leaves the family cleanly
        val bCred = cred(b)
        assertEquals(RelayResult.Err(SyncError.Replaced), rb { relay.pull(bCred, 0, 1) })
        assertEquals(SyncResult.Replaced, b.sync())
        assertNull(b.db["family"]); assertEquals("replaced", b.view.gone!!.why)
        assertNotNull(b.rec("loans", "lp")); assertNotNull(b.rec("loans", "lb"))
        assertNull(b.notices.cred)

        // everyone pinned the new phone's key: a rotation by another admin (C, made admin, never saw the approval) reaches it
        val b2Pub = b2.db.o("family").o("me").s("pub")
        assertEquals(b2Pub, a.db.o("family").o("pins")!!["m-B"])
        assertEquals(b2Pub, c.db.o("family").o("pins")!!["m-B"])           // from the re-pin inside its rotation box
        done(rb { a.engine.setAdmin("m-C", true) }); done(c.sync())
        val d = Phone("D", relay); done(d.joinVia(a))
        done(c.sync())
        done(rb { c.engine.removeMember("m-D") })
        val r = done(b2.sync())
        assertFalse(r.waitingForKey)
        assertEquals(setOf(1, 2, 3), keysOf(b2).keys)
        done(a.sync()); done(c.sync()); done(b2.sync())
        assertEquals(a.shared(), b2.shared())
    }

    @Test fun allowAsSomeoneNewAndDontAllow() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val b2 = named(Phone("B2", relay), "B")
        assertEquals(SyncResult.JoinPending, rb { b2.engine.requestJoin(a.invite(), back = true) })
        done(a.sync())
        done(rb { a.engine.approve(a.engine.requests.value.single().id) })   // "Allow as someone new"
        done(b2.sync())
        assertEquals("m-B2", b2.me)
        assertEquals(1, relay.fams[famId(a)]!!.epoch)                         // nothing replaced, no new key
        done(b.sync())                                                        // the old phone is untouched

        val b3 = named(Phone("B3", relay), "B")
        assertEquals(SyncResult.JoinPending, rb { b3.engine.requestJoin(a.invite(), back = true) })
        done(a.sync())
        done(rb { a.engine.deny(a.engine.requests.value.single().id) })
        assertEquals(SyncResult.JoinDenied, b3.sync())
        assertEquals("denied", b3.view.gone!!.why)
        done(b.sync())
    }

    @Test fun onlyAnAdminCanLetSomeoneBackAndOnlyAsTheyAsked() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        // an ordinary request cannot be turned into a takeover
        val x = named(Phone("X", relay), "B")
        assertEquals(SyncResult.JoinPending, rb { x.engine.requestJoin(a.invite()) })
        done(a.sync())
        val plain = a.engine.requests.value.single()
        assertFalse(plain.back)
        assertEquals(SyncResult.Failed(SyncError.Refused), rb { a.engine.approveAs(plain.id, "m-B") })
        done(rb { a.engine.deny(plain.id) })

        val b2 = named(Phone("B2", relay), "B")
        assertEquals(SyncResult.JoinPending, rb { b2.engine.requestJoin(c.invite(), back = true) })
        done(c.sync())
        assertTrue(c.engine.requests.value.isEmpty())                        // a member sees no requests
        assertEquals(SyncResult.Failed(SyncError.InviteGone), rb { c.engine.approveAs("nope", "m-B") })
        val inviteId = relay.fams[famId(a)]!!.requests.values.single { it.status == "pending" }.invite
        // and the relay itself refuses, whatever a modified app sends
        assertEquals(RelayResult.Err(SyncError.NotAdmin), rb { relay.reclaim(cred(c), inviteId, "m-B", 2, listOf(BoxFor("m-B", "x"), BoxFor("m-C", "x"))) })
        // not someone who is not in the family; not without a box for the member
        assertEquals(RelayResult.Err(SyncError.Refused), rb { relay.reclaim(cred(a), inviteId, "m-Z", 2, listOf(BoxFor("m-A", "x"))) })
        assertTrue(rb { relay.reclaim(cred(a), inviteId, "m-B", 2, listOf(BoxFor("m-A", "x"))) } is RelayResult.Err)
        assertEquals(1, relay.fams[famId(a)]!!.epoch)
        done(b.sync())                                                        // nothing changed for B
    }

    @Test fun aHostileRelayCannotMoveThePlaceToAnotherMemberOrReplayAGrant() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val b2 = named(Phone("B2", relay), "B")
        assertEquals(SyncResult.JoinPending, rb { b2.engine.requestJoin(a.invite(), back = true) })
        done(a.sync())
        done(rb { a.engine.approveAs(a.engine.requests.value.single().id, "m-B") })

        // the relay tells the new phone it is A (the creator): nothing is entered, the phone keeps waiting
        relay.tamperJoinCheck = { it.copy(member = "m-A") }
        assertTrue(b2.sync() is SyncResult.Failed)
        assertNull(b2.db["family"]); assertNotNull(b2.db["joining"])
        // and even a relay that served it A's place could not make it take it: the grant opens only for the member it was sealed to
        val fid = famId(a)
        val grantRow = relay.fams[fid]!!.boxes["m-B"]!!.entries.single()
        val j = b2.db.o("joining")!!
        val id = SyncCrypto.Identity(Invite.unb64url(j.o("me").s("pub"))!!, Invite.unb64url(j.o("me").s("priv"))!!)
        val grantKey = Invite.grantKey(Invite.unb64url(j.s("s"))!!)
        val row = RelayBox(grantRow.key, grantRow.value)
        assertNull(Membership.openBox(fid, "m-A", id, row) { _, _ -> grantKey })
        assertNull(Membership.openBox(fid, "m-C", id, row) { _, _ -> grantKey })
        assertNotNull(Membership.openBox(fid, "m-B", id, row) { from, inv -> if (from == 0 && inv == j.s("invite")) grantKey else null })
        relay.tamperJoinCheck = null
        done(b2.sync())
        assertEquals("m-B", b2.me)

        // a second phone comes back as C; the relay hands it B2's grant instead of its own: refused (another invite, another key)
        val c2 = named(Phone("C2", relay), "C")
        assertEquals(SyncResult.JoinPending, rb { c2.engine.requestJoin(a.invite(), back = true) })
        done(a.sync())
        done(rb { a.engine.approveAs(a.engine.requests.value.single().id, "m-C") })
        relay.tamperBoxes = { member, boxes -> if (member == "m-C") listOf(RelayBox(grantRow.key, grantRow.value)) else boxes }
        assertEquals(SyncResult.Failed(SyncError.Unverified), c2.sync())
        assertNull(c2.db["family"])
        relay.tamperBoxes = null
        done(c2.sync())
        assertEquals("m-C", c2.me)
        assertEquals(SyncResult.Replaced, c.sync())
    }

    @Test fun theOnlyAdminMovingToANewPhoneApprovesItFromTheOldOne() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        a.loan("l1", "Home loan")
        done(rb { a.engine.createFamily("Home") })
        done(b.joinVia(a))
        TestClock.advance(1)
        // the sole admin (and creator) lost the phone: a member cannot let anyone back in, so nobody takes the place
        val lost = named(Phone("A2", relay), "A")
        assertEquals(SyncResult.JoinPending, rb { lost.engine.requestJoin(b.invite(), back = true) })
        done(b.sync())
        assertTrue(b.engine.requests.value.isEmpty())
        assertTrue(rb { lost.engine.cancelJoin() })

        // a phone upgrade: the old phone is still there, so the admin allows the new phone as themselves on it
        val a2 = named(Phone("A2", relay), "A")
        done(a2.comeBackVia(a, "m-A"))
        assertEquals("m-A", a2.me)
        assertTrue(a2.view.iAmAdmin); assertTrue(a2.view.iAmCreator)
        assertEquals("m-A", relay.fams[famId(a2)]!!.creator)
        assertNull(a.db["family"]); assertEquals("replaced", a.view.gone!!.why)
        done(b.sync())
        assertEquals(setOf(1, 2), keysOf(b).keys)
        b.payment("pb", "l1", 5); done(b.sync()); done(a2.sync())
        assertNotNull(a2.rec("payments", "pb"))
    }

    @Test fun theAdminHintIsForTheOnlyAdminOnceSomeoneElseIsIn() {
        val me = FamilyMember("m-A", "A", admin = true, creator = true, me = true, since = 1)
        val other = FamilyMember("m-B", "B", admin = false, creator = false, me = false, since = 2)
        assertFalse(MemberText.needsSecondAdmin(true, listOf(me)))
        assertTrue(MemberText.needsSecondAdmin(true, listOf(me, other)))
        assertFalse(MemberText.needsSecondAdmin(true, listOf(me, other.copy(admin = true))))
        assertFalse(MemberText.needsSecondAdmin(false, listOf(me.copy(admin = false, me = false), other.copy(me = true))))
    }

    @Test fun restoreFirstThenComeBack() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val backup = b.db.copyObj()                                            // the old phone's backup
        TestClock.advance(1)

        // the new phone restores it: it is B again at once (same member), private loans and all
        val b2 = Phone("B", relay)
        b2.book.db = backup.copyObj(); SyncModel.markRestored(b2.db)
        done(b2.sync())
        assertEquals("m-B", b2.me)
        assertNotNull(b2.rec("loans", "lp"))
        // then it comes back as itself, so the old phone (maybe in someone else's hands) stops working
        assertEquals(SyncResult.JoinPending, rb { b2.engine.requestJoin(a.invite(), back = true) })
        assertNotEquals("m-B", b2.db.o("joining").s("as"))                    // asked under a fresh id: B is an active member
        done(b2.sync())                                                        // still syncing as B meanwhile
        done(a.sync())
        done(rb { a.engine.approveAs(a.engine.requests.value.single().id, "m-B") })
        done(b2.sync())
        assertEquals("m-B", b2.me)
        assertNull(b2.db["joining"])
        assertEquals(setOf(1, 2), keysOf(b2).keys)
        assertEquals("B's private loan", b2.rec("loans", "lp")!!.s("name"))
        assertTrue(b2.view.iAmAdmin)
        assertEquals(SyncResult.Replaced, b.sync())
        b2.payment("p3", "lb", 3); done(b2.sync()); done(a.sync())
        assertEquals("m-B", a.rec("payments", "p3")!!.s("by"))
        assertNull(relay.records[famId(a)]!!["lp"])                           // the private loan never left
    }

    @Test fun comeBackFirstThenRestoreTheOldBackup() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val backup = b.db.copyObj()
        TestClock.advance(1)
        val b2 = named(Phone("B2", relay), "B")
        done(b2.comeBackVia(a, "m-B"))
        a.loan("la", "After"); done(a.sync())

        // restoring the old phone's backup as it is would bring back the replaced credentials: the family says so
        val plain = backup.copyObj()
        val wrong = Phone("B", relay); wrong.book.db = plain; SyncModel.markRestored(wrong.db)
        assertEquals(SyncResult.Replaced, wrong.sync())

        // the restore keeps this phone's newer place in the family, with the backup's loans and private entries
        val next = backup.copyObj()
        assertTrue(Membership.keepNewerSeat(next, b2.db))
        assertFalse(Membership.keepNewerSeat(backup.copyObj(), backup.copyObj()))
        b2.book.db = next; SyncModel.markRestored(b2.db); b2.engine.resetState()
        done(b2.sync())
        assertEquals("m-B", b2.me)
        assertEquals("B's private loan", b2.rec("loans", "lp")!!.s("name"))
        assertEquals("After", b2.rec("loans", "la")!!.s("name"))
        assertEquals(setOf(1, 2), keysOf(b2).keys)
        b2.payment("p4", "la", 4); done(b2.sync()); done(a.sync())
        assertNotNull(a.rec("payments", "p4"))
    }
}
