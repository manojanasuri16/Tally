package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Membership end to end against [FakeRelay] (which keeps the SQL's role rules): joining by approval, admins, removing with
 * key rotation, leaving, concurrency, missed rotations, restore, and a hostile relay. docs/DESIGN.md, "Membership".
 */
class SyncMembershipTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }
    private fun <T> rb(block: suspend () -> T): T = runBlocking { block() }

    private fun famId(p: Phone) = p.db.o("family").s("id")
    private fun keysOf(p: Phone): Map<Int, ByteArray> =
        p.db.o("family")!!.o("keys")!!.entries.associate { (e, k) -> e.toInt() to Base64.getDecoder().decode(k as String) }
    private fun cred(p: Phone) = Cred(famId(p), p.me, p.db.o("family").o("me").s("secret"))

    /** A starts "Home" with a loan; the others join through A, in order. */
    private fun family(a: Phone, vararg others: Phone) {
        a.loan("l1", "SBI home loan")
        a.payment("p1", "l1", 23000)
        done(rb { a.engine.createFamily("Home") })
        others.forEach { done(it.joinVia(a)) }
        done(a.sync())
    }

    /** Opens a relay row with [keys] (what some phone holds), or null. */
    private fun openWith(keys: Map<Int, ByteArray>, fam: String, id: String): String? {
        val row = relay.records[fam]!![id] ?: return null
        return SyncCrypto.openE({ keys[it]?.let { k -> SyncCrypto.encKey(k) } }, row.blob, SyncCrypto.recordAad(fam, id))?.let { String(it) }
    }

    @Test fun createInviteRequestApproveAndBothSync() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        a.loan("l1", "SBI home loan")
        done(rb { a.engine.createFamily("Home") })
        assertTrue(a.view.iAmAdmin); assertTrue(a.view.iAmCreator)
        val invite = a.invite()
        assertEquals("A", Invite.parse(invite)!!.inviter)
        assertEquals(1, a.view.invites.size)

        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(invite) })
        assertNotNull(b.view.joining); assertEquals("Home", b.view.joining!!.familyName); assertEquals("A", b.view.joining!!.inviter)
        assertNull(b.db["family"])                                        // nothing of the family yet
        assertEquals(SyncResult.JoinPending, b.sync())
        // the background check sees it, with the notice credential only
        val n = a.notices.cred!!
        assertTrue(n.admin)
        assertEquals(RelayResult.Ok(1), rb { relay.pending(n.familyId, n.memberId, n.notice) })
        assertEquals(RelayResult.Err(SyncError.Unauthorised), rb { relay.pending(n.familyId, n.memberId, "wrong") })

        done(a.sync())
        val req = a.engine.requests.value.single()
        assertEquals("B", req.name); assertEquals("m-A", req.invitedById); assertEquals("A", req.invitedBy)
        assertEquals(1, a.notices.pending)
        done(rb { a.engine.approve(req.id) })
        assertTrue(a.engine.requests.value.isEmpty())

        done(b.sync())
        assertNull(b.db["joining"])
        assertEquals(famId(a), famId(b))
        assertEquals("SBI home loan", b.rec("loans", "l1")!!.s("name"))
        assertFalse(b.view.iAmAdmin)
        assertFalse(b.notices.cred!!.admin)
        b.payment("p2", "l1", 700)
        done(b.sync()); done(a.sync())
        assertEquals(a.shared(), b.shared())
        assertEquals(listOf("m-A", "m-B"), a.view.members.map { it.id })
        assertEquals(listOf(true, false), a.view.members.map { it.admin })
        // the key reached B sealed to B's own public key; both phones pinned each other's
        assertArrayEquals(keysOf(a)[1], keysOf(b)[1])
        assertEquals(a.db.o("family").o("pins")!!["m-B"], b.db.o("family").o("me").s("pub"))
        assertEquals(b.db.o("family").o("pins")!!["m-A"], a.db.o("family").o("me").s("pub"))

        // the invite worked once
        val c = Phone("C", relay)
        assertEquals(SyncResult.Failed(SyncError.InviteGone), rb { c.engine.requestJoin(invite) })
        // and a fresh one stops after 24 hours, on the phone itself
        val later = a.invite()
        TestClock.advance(25)
        assertEquals(SyncResult.Failed(SyncError.InviteGone), rb { c.engine.requestJoin(later) })
        assertNull(c.db["joining"])
    }

    @Test fun dontAllowSharesNothingAndTheAskingPhoneIsTold() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a)
        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(a.invite()) })
        done(a.sync())
        done(rb { a.engine.deny(a.engine.requests.value.single().id) })
        assertEquals(SyncResult.JoinDenied, b.sync())
        assertNull(b.db["family"]); assertNull(b.db["joining"])
        assertEquals("denied", b.view.gone!!.why)
        assertNull(relay.fams[famId(a)]!!.members["m-B"])
        rb { b.engine.clearGone() }
        assertNull(b.view.gone)
        // asking again with a new code works
        done(b.joinVia(a))
    }

    @Test fun aMemberCannotApproveRemoveOrPromote() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b)
        assertEquals(SyncResult.JoinPending, rb { c.engine.requestJoin(b.invite()) })       // any member may invite
        done(b.sync())
        assertTrue(b.engine.requests.value.isEmpty())                                       // only admins see requests
        assertEquals(SyncResult.Failed(SyncError.NotAdmin), rb { b.engine.setAdmin("m-B", true) })
        assertEquals(SyncResult.Failed(SyncError.NotAdmin), rb { b.engine.removeMember("m-A") })
        // and the relay itself refuses, whatever a modified app sends
        val inviteId = relay.fams[famId(a)]!!.requests.keys.single()
        assertEquals(RelayResult.Err(SyncError.NotAdmin), rb { relay.decide(cred(b), inviteId, true, 1, "x") })
        assertEquals(RelayResult.Err(SyncError.NotAdmin), rb { relay.setRole(cred(b), "m-B", true) })
        assertEquals(RelayResult.Err(SyncError.NotAdmin), rb { relay.remove(cred(b), "m-A", 2, listOf(BoxFor("m-B", "x"))) })
        assertEquals(RelayResult.Err(SyncError.NotAdmin), rb { relay.rotate(cred(b), 2, listOf(BoxFor("m-B", "x"))) })
        assertEquals(0, (rb { relay.pending(famId(b), "m-B", b.db.o("family").o("me").s("notice")) } as RelayResult.Ok).value)
        // the admin approves the member's invitee
        done(a.sync())
        done(rb { a.engine.approve(a.engine.requests.value.single().id) })
        done(c.sync())
        assertEquals(3, a.view.members.size)
    }

    @Test fun removingShutsTheMemberOutAndRotatesTheKey() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        c.loan("lc", "C's loan")
        done(c.sync()); done(a.sync()); done(b.sync())
        val cKeys = keysOf(c)
        val cCred = cred(c)

        done(rb { a.engine.removeMember("m-C") })
        // the relay rejects C at once, for everything
        assertEquals(RelayResult.Err(SyncError.Removed), rb { relay.pull(cCred, 0, 10) })
        assertEquals(RelayResult.Err(SyncError.Removed), rb { relay.push(cCred, 2, emptyList()) })
        assertEquals(RelayResult.Err(SyncError.Removed), rb { relay.state(cCred, 0) })
        assertEquals(2, relay.fams[famId(a)]!!.epoch)
        assertNull(relay.fams[famId(a)]!!.boxes["m-C"]?.get(2))            // no new key for C
        assertEquals(listOf("m-A", "m-B"), a.view.members.map { it.id })

        // B picks the new key up and writes with it; nothing C held opens it
        done(b.sync())
        assertEquals(setOf(1, 2), keysOf(b).keys)
        b.loan("l9", "After the removal")
        done(b.sync())
        assertEquals(2, SyncCrypto.epochOf(relay.records[famId(a)]!!["l9"]!!.blob))
        assertNull(openWith(cKeys, famId(a), "l9"))
        assertNotNull(openWith(keysOf(a), famId(a), "l9"))
        done(a.sync())
        assertEquals("After the removal", a.rec("loans", "l9")!!.s("name"))
        assertEquals(a.shared(), b.shared())

        // C's next sync says so, forgets the family and keeps every record
        c.payment("pc", "lc", 5)
        assertEquals(SyncResult.Removed, c.sync())
        assertNull(c.db["family"]); assertNull(c.db["sync"])
        assertEquals("removed", c.view.gone!!.why); assertEquals("Home", c.view.gone!!.familyName)
        assertNotNull(c.rec("loans", "lc")); assertNotNull(c.rec("payments", "pc")); assertNotNull(c.rec("loans", "l1"))
        assertNull(c.notices.cred)
        assertNull(relay.records[famId(a)]!!["pc"])
        // the others see C's record marked as gone
        assertTrue(b.rec("members", "m-C")!!.b("left"))
    }

    @Test fun whenTheLastAdminLeavesTheLongestStandingMemberTakesOver() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        assertTrue(rb { a.engine.leaveFamily() })
        assertNull(a.db["family"]); assertNotNull(a.rec("loans", "l1"))
        assertNull(a.notices.cred)
        val f = relay.fams[famId(b)]!!
        assertEquals("admin", f.members["m-B"]!!.role)                     // B joined before C
        assertEquals("member", f.members["m-C"]!!.role)
        assertNull(f.creator)
        assertTrue(f.rotate)
        done(b.sync())                                                      // B, now an admin, rotates (A held the key)
        assertTrue(b.view.iAmAdmin)
        assertEquals(2, f.epoch)
        done(c.sync())
        assertEquals(setOf(1, 2), keysOf(c).keys)
        c.payment("p3", "l1", 3)
        done(c.sync()); done(b.sync())
        assertEquals(b.shared(), c.shared())
        // the last one out deletes the family from the relay
        assertTrue(rb { c.engine.leaveFamily() })
        assertTrue(rb { b.engine.leaveFamily() })
        assertNull(relay.fams[famId(a).ifEmpty { f.id }])
        assertNull(relay.records[f.id])
    }

    @Test fun theCreatorCannotBeRemovedOrDemotedByOthers() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        done(rb { a.engine.setAdmin("m-B", true) })
        done(b.sync())
        assertTrue(b.view.iAmAdmin)
        assertEquals(SyncResult.Failed(SyncError.Refused), rb { b.engine.removeMember("m-A") })
        assertEquals(SyncResult.Failed(SyncError.Refused), rb { b.engine.setAdmin("m-A", false) })
        assertEquals(SyncResult.Failed(SyncError.Refused), rb { a.engine.removeMember("m-A") })      // leave instead
        // an admin can remove another admin, and an admin can step down while another remains
        done(rb { a.engine.setAdmin("m-C", true) })
        done(rb { b.engine.removeMember("m-C") })
        done(rb { b.engine.setAdmin("m-B", false) })
        // the last admin cannot step down
        assertEquals(SyncResult.Failed(SyncError.Refused), rb { a.engine.setAdmin("m-A", false) })
        assertEquals(listOf("m-A"), relay.fams[famId(a)]!!.members.values.filter { it.role == "admin" && it.status == "active" }.map { it.id })
    }

    @Test fun twoAdminsActingAtOnceEndConsistent() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay); val d = Phone("D", relay); val e = Phone("E", relay)
        family(a, b, c, d)
        done(rb { a.engine.setAdmin("m-B", true) })
        done(b.sync())

        // both remove at once: A's lands first, B's was made for the old epoch, is refused as stale and redone
        relay.before["remove"] = { done(a.engine.removeMember("m-C")) }
        done(rb { b.engine.removeMember("m-D") })
        val f = relay.fams[famId(a)]!!
        assertEquals(3, f.epoch)
        assertEquals(listOf("m-A", "m-B"), f.members.values.filter { it.status == "active" }.map { it.id })
        done(a.sync()); done(b.sync())
        assertEquals(setOf(1, 2, 3), keysOf(a).keys); assertEquals(setOf(1, 2, 3), keysOf(b).keys)
        assertArrayEquals(keysOf(a)[3], keysOf(b)[3])
        assertEquals(SyncResult.Removed, c.sync()); assertEquals(SyncResult.Removed, d.sync())

        // both allow the same request: one wins, the other is told it is gone, the joiner is in once
        assertEquals(SyncResult.JoinPending, rb { e.engine.requestJoin(a.invite()) })
        done(a.sync()); done(b.sync())
        val id = b.engine.requests.value.single().id
        relay.before["decide"] = { done(a.engine.approve(id)) }
        assertEquals(SyncResult.Failed(SyncError.InviteGone), rb { b.engine.approve(id) })
        done(e.sync())
        assertEquals(setOf(1, 2, 3), keysOf(e).keys)

        // one rotates (after someone left) while the other removes: still one epoch after the other, everyone converges
        val g = Phone("G", relay)
        done(g.joinVia(a))
        assertTrue(rb { g.engine.leaveFamily() })
        relay.before["rotate"] = { done(b.engine.removeMember("m-E")) }
        done(a.sync())                                                      // its rotation is stale: B's removal rotated already
        assertEquals(4, f.epoch)
        assertFalse(f.rotate)
        done(b.sync()); done(a.sync())
        a.loan("lz", "after all that"); done(a.sync()); done(b.sync())
        assertEquals(a.shared(), b.shared())
        assertEquals(keysOf(a).keys, keysOf(b).keys)
    }

    @Test fun aPhoneThatMissedRotationsCatchesUp() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay); val d = Phone("D", relay)
        family(a, b, c, d)
        // B is away while C and D are removed
        done(rb { a.engine.removeMember("m-C") })
        done(rb { a.engine.removeMember("m-D") })
        a.loan("la", "while B was away"); done(a.sync())
        assertEquals(setOf(1), keysOf(b).keys)
        val r = done(b.sync())
        assertFalse(r.waitingForKey)
        assertEquals(setOf(1, 2, 3), keysOf(b).keys)
        assertEquals("while B was away", b.rec("loans", "la")!!.s("name"))
        b.payment("pb", "la", 1); done(b.sync()); done(a.sync())
        assertEquals(a.shared(), b.shared())
    }

    @Test fun aMemberTheRotatingPhoneCouldNotSealToGetsTheKeyFromTheNextAdminSync() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val pinB = a.db.o("family")!!.o("pins")!!.remove("m-B")            // A cannot seal to B for now
        done(rb { a.engine.removeMember("m-C") })
        assertNull(relay.fams[famId(a)]!!.boxes["m-B"]?.get(2))
        // B can read but must not write under the old key
        b.payment("pw", "l1", 9)
        val waiting = done(b.sync())
        assertTrue(waiting.waitingForKey); assertEquals(0, waiting.pushed)
        assertTrue(b.view.waitingForKey)
        assertNull(relay.records[famId(a)]!!["pw"])
        // A learns B's key again (B's own record carries it) and repairs on its next sync
        a.db.o("family")!!.o("pins")!!["m-B"] = pinB
        done(a.sync())
        assertNotNull(relay.fams[famId(a)]!!.boxes["m-B"]!![2])
        val r = done(b.sync())
        assertFalse(r.waitingForKey)
        assertEquals(2, SyncCrypto.epochOf(relay.records[famId(a)]!!["pw"]!!.blob))
    }

    @Test fun aRestoredPhoneIsStillTheSameMember() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        val backupB = b.db.copyObj()
        val backupC = c.db.copyObj()
        done(rb { a.engine.removeMember("m-C") })                          // a rotation after the backups
        a.loan("ln", "new"); done(a.sync())

        val b2 = Phone("B", relay)                                         // B's new phone, restored from the old backup
        b2.book.db = backupB.copyObj(); SyncModel.markRestored(b2.db)
        assertEquals(setOf(1), keysOf(b2).keys)
        done(b2.sync())                                                    // same credentials, same key pair: the box for epoch 2 opens
        assertEquals("m-B", b2.me)
        assertEquals(setOf(1, 2), keysOf(b2).keys)
        assertEquals("new", b2.rec("loans", "ln")!!.s("name"))
        b2.payment("p2", "ln", 2); done(b2.sync()); done(a.sync())
        assertNotNull(a.rec("payments", "p2"))

        // C's backup restored after C was removed: told, and the family is forgotten
        val c2 = Phone("C", relay)
        c2.book.db = backupC.copyObj(); SyncModel.markRestored(c2.db)
        assertEquals(SyncResult.Removed, c2.sync())
        assertNull(c2.db["family"]); assertNotNull(c2.rec("loans", "l1"))
    }

    @Test fun aHostileRelayCannotSwapTheJoinersKeyOrForgeTheKeys() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a)
        val mallory = SyncCrypto.newIdentity()

        // 1. the relay swaps the public key in B's request for its own: it cannot seal a request without the invite's secret,
        //    so it puts in one made with a secret of its own -> A's phone refuses it and turns it down; nothing is shared
        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(a.invite()) })
        relay.tamperRequests = { rows -> rows.map { r ->
            r.copy(blob = Membership.sealRequest(Invite.newSecret(), famId(a), r.inviteId, r.memberId, "B", mallory.publicSpki))
        } }
        done(a.sync())
        assertTrue(a.engine.requests.value.isEmpty())
        assertNull(relay.fams[famId(a)]!!.members["m-B"])
        assertEquals(SyncResult.JoinDenied, b.sync())
        relay.tamperRequests = null

        // 2. the relay renames the member in the request: it no longer opens (the id is bound in), refused the same way
        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(a.invite()) })
        relay.tamperRequests = { rows -> rows.map { it.copy(memberId = "m-Mallory") } }
        done(a.sync())
        assertTrue(a.engine.requests.value.isEmpty())
        relay.tamperRequests = null
        assertEquals(SyncResult.JoinDenied, b.sync())

        // 3. the relay hands the joiner keys of its own, sealed to the joiner's public key: no MAC with the invite's secret, refused
        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(a.invite()) })
        done(a.sync())
        done(rb { a.engine.approve(a.engine.requests.value.single().id) })
        val bPub = Invite.unb64url(b.db.o("joining").o("me").s("pub"))!!
        relay.tamperBoxes = { member, boxes -> if (member != "m-B") boxes else boxes.map { row ->
            RelayBox(row.epoch, Membership.sealBox(Membership.Box(famId(a), "m-B", bPub, 0, mapOf(1 to ByteArray(32) { 6 }), by = "m-A",
                invite = b.db.o("joining").s("invite")), ByteArray(32) { 1 }))
        } }
        assertEquals(SyncResult.Failed(SyncError.Unverified), b.sync())
        assertNull(b.db["family"]); assertNotNull(b.db["joining"])          // still waiting; nothing entered
        relay.tamperBoxes = null
        done(b.sync())                                                     // the real grant
        assertArrayEquals(keysOf(a)[1], keysOf(b)[1])

        // 4. a forged rotation box for B (the relay picks the "new key"): B does not take it and writes nothing under it
        relay.fams[famId(a)]!!.epoch = 2                                   // the relay pretends there was a rotation
        relay.fams[famId(a)]!!.boxes.getOrPut("m-B") { HashMap() }[2] =
            Membership.sealBox(Membership.Box(famId(a), "m-B", bPub, 1, mapOf(2 to ByteArray(32) { 8 }), by = "m-A"), ByteArray(32) { 2 })
        b.payment("pz", "l1", 1)
        val r = done(b.sync())
        assertTrue(r.waitingForKey); assertEquals(0, r.pushed)
        assertEquals(setOf(1), keysOf(b).keys)
        assertNull(relay.records[famId(a)]!!["pz"])
    }

    @Test fun aReplayedOrLeakedInviteGetsNobodyIn() {
        val a = Phone("A", relay); val b = Phone("B", relay); val x = Phone("X", relay)
        family(a)
        val invite = a.invite()
        // a relay that ignores "used once": two requests with the same code; A's phone lets neither through
        relay.ignoreUsed = true
        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(invite) })
        assertEquals(SyncResult.JoinPending, rb { x.engine.requestJoin(invite) })
        done(a.sync())
        assertTrue(a.engine.requests.value.isEmpty())
        assertEquals(1, relay.fams[famId(a)]!!.members.size)

        // an invite A already used comes back (the relay keeps a copy of its row) and someone with the leaked code asks: refused
        relay.ignoreUsed = false
        val fresh = a.invite()
        val id = Invite.parse(fresh)!!.inviteId
        val f = relay.fams[famId(a)]!!
        val row = f.invites[id]!!
        val b2 = Phone("B2", relay)
        assertEquals(SyncResult.JoinPending, rb { b2.engine.requestJoin(fresh) })
        done(a.sync())
        done(rb { a.engine.approve(a.engine.requests.value.single().id) })
        done(b2.sync())
        f.invites[id] = FakeRelay.InviteRow(id, row.by, row.box, false, Clock.millis() + 3_600_000, Clock.millis())
        val y = Phone("Y", relay)
        assertEquals(SyncResult.JoinPending, rb { y.engine.requestJoin(fresh) })
        done(a.sync())
        assertTrue(a.engine.requests.value.isEmpty())
        assertNull(f.members["m-Y"])
        assertEquals(SyncResult.JoinDenied, y.sync())
        // and an admin who joined later knows it too, from the new member's record
        done(rb { a.engine.setAdmin("m-B2", true) })
        done(b2.sync())
        assertEquals(id, b2.rec("members", "m-B2")!!.s("invite"))
    }

    @Test fun anOldFamilyAsksToBeSetUpAgain() {
        val a = Phone("A", relay)
        a.loan("l1", "kept")
        a.db["family"] = obj("id" to Invite.newFamilyId(), "key" to Base64.getEncoder().encodeToString(ByteArray(32)), "name" to "Home")
        assertEquals(SyncResult.OldFamily, a.sync())
        assertTrue(a.view.oldFamily); assertFalse(a.view.inFamily)
        assertTrue(rb { a.engine.newInvite() } is InviteResult.Failed)
        assertTrue(rb { a.engine.leaveFamily() })
        assertNull(a.db["family"])
        done(rb { a.engine.createFamily("Home") })
        assertNotNull(relay.records[famId(a)]!!["l1"])                     // the records go up again under the new family
    }

    @Test fun limitsAndCancelling() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        val made = (1..5).map { (rb { a.engine.newInvite() } as InviteResult.Made) }
        assertEquals(InviteResult.Failed(SyncResult.Failed(SyncError.Limit)), rb { b.engine.newInvite() })
        // a member may cancel only its own; an admin any
        assertEquals(SyncResult.Failed(SyncError.NotAdmin), rb { b.engine.cancelInvite(made[0].inviteId) })
        done(rb { a.engine.cancelInvite(made[0].inviteId) })
        val c = Phone("C", relay)
        assertEquals(SyncResult.Failed(SyncError.InviteGone), rb { c.engine.requestJoin(made[0].text) })
        assertEquals(4, a.view.invites.size)
        // a joiner can withdraw its own request
        assertEquals(SyncResult.JoinPending, rb { c.engine.requestJoin(made[1].text) })
        assertTrue(rb { c.engine.cancelJoin() })
        assertNull(c.db["joining"])
        done(a.sync()); assertTrue(a.engine.requests.value.isEmpty())
    }
}
