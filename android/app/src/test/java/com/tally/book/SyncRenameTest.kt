package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Renaming the family (docs/DESIGN.md "The family's name"): admins only, sealed on the relay, picked up by every phone. */
class SyncRenameTest {
    private lateinit var relay: FakeRelay

    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun done(r: SyncResult): SyncResult.Done { assertTrue("expected Done, got $r", r is SyncResult.Done); return r as SyncResult.Done }
    private fun <T> rb(block: suspend () -> T): T = runBlocking { block() }
    private fun famId(p: Phone) = p.db.o("family").s("id")
    private fun cred(p: Phone) = Cred(famId(p), p.me, p.db.o("family").o("me").s("secret"))

    private fun family(a: Phone, vararg others: Phone) {
        done(rb { a.engine.createFamily("Home") })
        others.forEach { done(it.joinVia(a)) }
        done(a.sync())
    }

    @Test fun aRenameReachesEveryPhoneAndIsSealedOnTheRelay() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        done(rb { a.engine.renameFamily("  The Smiths  ") })
        assertEquals("The Smiths", a.view.familyName)
        val sealed = relay.fams[famId(a)]!!.meta!!
        assertFalse(sealed.contains("Smith")); assertFalse(String(java.util.Base64.getDecoder().decode(sealed), Charsets.ISO_8859_1).contains("Smith"))
        assertEquals("Home", b.view.familyName)
        done(b.sync())
        assertEquals("The Smiths", b.view.familyName)
        assertEquals(1.0, b.db.o("family").num("nameV"), 0.0)
        done(rb { a.engine.renameFamily("Smith house") })
        done(b.sync())
        assertEquals("Smith house", b.view.familyName); assertEquals(2.0, b.db.o("family").num("nameV"), 0.0)
    }

    @Test fun aMemberWhoIsNotAnAdminCannotRename() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        assertEquals(SyncResult.Failed(SyncError.NotAdmin), rb { b.engine.renameFamily("Mine now") })
        // and the relay refuses it too, whatever the phone says
        val fam = Membership.famOf(b.db)!!
        val blob = Membership.sealMeta(fam, "Mine now", 9, 0)
        assertEquals(RelayResult.Err(SyncError.NotAdmin), rb { relay.setMeta(cred(b), fam.relayEpoch, blob) })
        assertNull(relay.fams[famId(a)]!!.meta)
        done(a.sync()); assertEquals("Home", a.view.familyName)
        // once made an admin, the same member can
        done(rb { a.engine.setAdmin(b.me, true) })
        done(b.sync())
        done(rb { b.engine.renameFamily("B's family") })
        done(a.sync()); assertEquals("B's family", a.view.familyName)
    }

    @Test fun someoneLetInAfterARenameSeesTheNewName() {
        val a = Phone("A", relay); val c = Phone("C", relay)
        family(a)
        val oldInvite = a.invite()                         // made while the family was still "Home"
        assertEquals("Home", Invite.parse(oldInvite)!!.name)
        done(rb { a.engine.renameFamily("The Smiths") })
        done(c.joinVia(a, oldInvite))
        assertEquals("The Smiths", c.view.familyName)
    }

    @Test fun invitesMadeAfterARenameCarryTheNewName() {
        val a = Phone("A", relay)
        family(a)
        done(rb { a.engine.renameFamily("The Smiths") })
        assertEquals("The Smiths", Invite.parse(a.invite())!!.name)
    }

    @Test fun anOlderNameReplayedByTheRelayIsIgnored() {
        val a = Phone("A", relay); val b = Phone("B", relay)
        family(a, b)
        done(rb { a.engine.renameFamily("One") })
        val first = relay.fams[famId(a)]!!.meta!!
        done(rb { a.engine.renameFamily("Two") })
        done(b.sync()); assertEquals("Two", b.view.familyName)
        relay.replayMeta = first
        done(b.sync()); assertEquals("Two", b.view.familyName)
        relay.replayMeta = "not a sealed name"
        done(b.sync()); assertEquals("Two", b.view.familyName)
    }

    @Test fun aRenameAfterARemovalUsesTheNewKey() {
        val a = Phone("A", relay); val b = Phone("B", relay); val c = Phone("C", relay)
        family(a, b, c)
        done(rb { a.engine.removeMember(c.me) })
        done(rb { a.engine.renameFamily("Just us") })
        val sealed = relay.fams[famId(a)]!!.meta!!
        assertEquals(2, SyncCrypto.epochOf(sealed))                 // the key C never had
        done(b.sync()); assertEquals("Just us", b.view.familyName)
    }

    @Test fun aBadNameOrNoFamilyIsRefusedBeforeAnythingIsSent() {
        val a = Phone("A", relay)
        assertEquals(SyncResult.NoFamily, rb { a.engine.renameFamily("Home") })
        family(a)
        assertEquals(SyncResult.BadInvite, rb { a.engine.renameFamily("   ") })
        assertEquals(SyncResult.BadInvite, rb { a.engine.renameFamily("x".repeat(81)) })
        assertNull(relay.fams[famId(a)]!!.meta)
    }
}
