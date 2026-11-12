package com.tally.book

import com.tally.book.sync.Invite
import org.junit.Assert.*
import org.junit.Test

/** The invite format (docs/DESIGN.md, "Invite"): tally://join#t2.<familyId>.<secret>.<expires>.<name>[.<inviter>]. */
class SyncInviteTest {
    private val secret = ByteArray(32) { (it * 7).toByte() }
    private val exp = 1_800_000_000_000L          // a whole second, as the code carries seconds

    @Test fun roundTrip() {
        val id = Invite.newFamilyId()
        assertEquals(22, id.length)
        assertTrue(Invite.isFamilyId(id))
        val text = Invite.make(id, secret, exp, "Example family")
        assertTrue(text.startsWith("tally://join#t2.$id."))
        val inv = Invite.parse(text)!!
        assertEquals(id, inv.familyId)
        assertArrayEquals(secret, inv.secret)
        assertEquals(exp, inv.expiresAt)
        assertEquals("Example family", inv.name)
        assertEquals(inv, Invite.parse("  $text\n"))
        assertEquals("మా కుటుంబం", Invite.parse(Invite.make(id, secret, exp, "మా కుటుంబం"))!!.name)
    }

    @Test fun theCodeNeverCarriesAFamilyKeyOnlyItsOwnSecret() {
        val id = Invite.newFamilyId()
        val text = Invite.make(id, secret, exp, "Home", "Ravi")
        val parts = text.removePrefix(Invite.PREFIX).split('.')
        assertEquals(listOf("t2", id, Invite.b64url(secret), (exp / 1000).toString()), parts.take(4))
        assertEquals(6, parts.size)
        // the relay knows the invite by an id derived one way from the secret, so it never learns the secret
        val inv = Invite.parse(text)!!
        assertEquals(22, inv.inviteId.length)
        assertNotEquals(Invite.b64url(secret).take(22), inv.inviteId)
        assertEquals(Invite.idOf(secret), inv.inviteId)
        assertFalse(Invite.requestKey(secret).contentEquals(Invite.grantKey(secret)))
        // a fresh secret, a fresh invite id
        val ids = (1..100).map { Invite.idOf(Invite.newSecret()) }.toSet()
        assertEquals(100, ids.size)
    }

    @Test fun inviterTravelsInTheLastPart() {
        val id = Invite.newFamilyId()
        val text = Invite.make(id, secret, exp, "Example family", "Ravi")
        val inv = Invite.parse(text)!!
        assertEquals("Example family", inv.name)       // the family's name, never the inviter's
        assertEquals("Ravi", inv.inviter)
        assertEquals(inv, Invite.find("Join our family in Tally:\n$text"))
        val long = Invite.parse(Invite.make(id, secret, exp, "కు".repeat(40), "ర".repeat(200)))!!
        assertEquals("కు".repeat(40), long.name)
        assertEquals("ర".repeat(80), long.inviter)
        for (who in listOf(null, "", "  ", "\u0007")) {
            val t = Invite.make(id, secret, exp, "Home", who)
            assertEquals(5, t.removePrefix(Invite.PREFIX).split('.').size)
            assertNull(Invite.parse(t)!!.inviter)
        }
        assertEquals("Ravi Kumar", Invite.parse(Invite.make(id, secret, exp, "Home", "  Ravi\u0000 Kumar \n"))!!.inviter)
        assertEquals(inv, Invite.parse(Invite.make(inv)))
    }

    @Test fun expiry() {
        val inv = Invite.parse(Invite.make(Invite.newFamilyId(), secret, exp, "Home"))!!
        assertFalse(inv.expired(exp - 1))
        assertTrue(inv.expired(exp))
        assertTrue(inv.expired(exp + 86_400_000))
    }

    @Test fun oldInvitesAreRecognisedAndRefused() {
        // made by hand exactly as 0.1.0 (three parts) and 0.2.0 (four) did: id.key.name[.inviter], the key inside
        val id = Invite.newFamilyId()
        val key = ByteArray(32) { 1 }
        val three = Invite.PREFIX + id + "." + Invite.b64url(key) + "." + Invite.b64url("Home".toByteArray())
        val four = three + "." + Invite.b64url("Ravi".toByteArray())
        for (old in listOf(three, four, "Join our family in Tally:\n$four")) {
            assertNull(Invite.find(old))
            assertTrue(old, Invite.isOld(old))
        }
        assertFalse(Invite.isOld(Invite.make(id, secret, exp, "Home")))
        assertFalse(Invite.isOld("tally://join#nope"))
        assertFalse(Invite.isOld(null))
    }

    @Test fun malformedIsNull() {
        val id = Invite.newFamilyId()
        val good = Invite.make(id, secret, exp, "Home")
        val (_, _, s, e, nm) = good.removePrefix(Invite.PREFIX).split('.')
        val p = "tally://join#t2"
        val b = { x: ByteArray -> Invite.b64url(x) }
        val bad = listOf(
            null, "", "   ", "tally://join#", "tally://join", "https://join#t2.$id.$s.$e.$nm",
            "TALLY://join#t2.$id.$s.$e.$nm", "tally://join?t2.$id.$s.$e.$nm",
            "tally://join#t3.$id.$s.$e.$nm",                         // another version
            "tally://join#$id.$s.$e.$nm",                            // no tag
            "$p.$id.$s.$e", "$p.$id..$e.$nm", "$p.$id.$s..$nm",
            "$p.$id.$s.$e.$nm.extra",                                // inviter not base64url of a name
            "$p.$id.$s.$e.$nm.${b("Ravi".toByteArray())}.${b("x".toByteArray())}",   // seven parts
            "$p.$id.$s.$e.$nm.",                                     // empty inviter
            "$p.$id.$s.$e.$nm.${b("  ".toByteArray())}",
            "$p.$id.$s.$e.$nm.${b(byteArrayOf(0xff.toByte()))}",     // inviter not UTF-8
            "$p.$id.$s.$e.$nm.${b("y".repeat(81).toByteArray())}",   // inviter too long
            "$p.${id.drop(1)}.$s.$e.$nm",                            // short id
            "$p.${id}A.$s.$e.$nm",                                   // long id
            "$p.$id.${s.drop(2)}.$e.$nm",                            // short secret
            "$p.$id.$s=.$e.$nm",                                     // padding
            "$p.$id.${s.replace(s[0], '+')}.$e.$nm",                 // standard alphabet
            "$p.$id.$s.0$e.$nm",                                     // leading zero
            "$p.$id.$s.-$e.$nm", "$p.$id.$s.${e}x.$nm", "$p.$id.$s.1234567890123.$nm",   // not a time
            "$p.$id.$s.$e.",                                         // empty name
            "$p.$id.$s.$e.${b(byteArrayOf(0xff.toByte(), 0xfe.toByte()))}",   // not UTF-8
            "$p.$id.$s.$e.${b("a\u0000b".toByteArray())}",           // control char
            "$p.$id.$s.$e.${b("x".repeat(81).toByteArray())}",       // too long
            "join me: $good",
            "$good junk",
        )
        for (x in bad) assertNull("should reject: $x", Invite.parse(x))
        assertNotNull(Invite.parse(good))
    }

    @Test fun makeRefusesBadInput() {
        assertThrows(IllegalArgumentException::class.java) { Invite.make("short", secret, exp, "x") }
        assertThrows(IllegalArgumentException::class.java) { Invite.make(Invite.newFamilyId(), ByteArray(16), exp, "x") }
        assertThrows(IllegalArgumentException::class.java) { Invite.make(Invite.newFamilyId(), secret, exp, " ") }
        assertThrows(IllegalArgumentException::class.java) { Invite.make(Invite.newFamilyId(), secret, 0, "x") }
    }

    @Test fun findPicksTheInviteOutOfAMessage() {
        val text = Invite.make(Invite.newFamilyId(), secret, exp, "Our family")
        val inv = Invite.parse(text)!!
        assertEquals(inv, Invite.find("Join our family in Tally:\n$text"))
        assertEquals(inv, Invite.find(text))
        assertEquals(inv, Invite.find("hello  $text  thanks!"))
        assertNull(Invite.find("Join our family in Tally"))
        assertNull(Invite.find(null))
        assertNull(Invite.find("tally://join#nope"))
        assertNull(Invite.find("https://example.com/#" + text.removePrefix(Invite.PREFIX)))
        assertNull(Invite.find("look: " + text + "x."))
    }
}
