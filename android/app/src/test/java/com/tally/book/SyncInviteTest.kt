package com.tally.book

import com.tally.book.sync.Invite
import org.junit.Assert.*
import org.junit.Test

class SyncInviteTest {
    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test fun roundTrip() {
        val id = Invite.newFamilyId()
        assertEquals(22, id.length)
        assertTrue(Invite.isFamilyId(id))
        val text = Invite.make(id, key, "Example family")
        assertTrue(text.startsWith("tally://join#$id."))
        val inv = Invite.parse(text)!!
        assertEquals(id, inv.familyId)
        assertArrayEquals(key, inv.key)
        assertEquals("Example family", inv.name)
        assertEquals(inv, Invite.parse("  $text\n"))
        val telugu = Invite.parse(Invite.make(id, key, "మా కుటుంబం"))!!
        assertEquals("మా కుటుంబం", telugu.name)
    }

    @Test fun inviterTravelsInAFourthPart() {
        val id = Invite.newFamilyId()
        val text = Invite.make(id, key, "Example family", "Ravi")
        assertEquals(4, text.removePrefix(Invite.PREFIX).split('.').size)
        val inv = Invite.parse(text)!!
        assertEquals("Example family", inv.name)       // the family's name, never the inviter's
        assertEquals("Ravi", inv.inviter)
        assertEquals(inv, Invite.find("Join our family in Tally:\n$text"))
        // two long Telugu names still make an invite that parses
        val long = Invite.parse(Invite.make(id, key, "కు".repeat(40), "ర".repeat(200)))!!
        assertEquals("కు".repeat(40), long.name)
        assertEquals("ర".repeat(80), long.inviter)
        // a blank or missing inviter gives the three-part invite of 0.1.0
        for (who in listOf(null, "", "  ", "\u0007")) {
            val t = Invite.make(id, key, "Home", who)
            assertEquals(3, t.removePrefix(Invite.PREFIX).split('.').size)
            assertNull(Invite.parse(t)!!.inviter)
        }
        assertEquals("Ravi Kumar", Invite.parse(Invite.make(id, key, "Home", "  Ravi\u0000 Kumar \n"))!!.inviter)
        assertEquals(Invite.parse(text), Invite.parse(Invite.make(inv)))
    }

    @Test fun threePartInvitesFrom010StillParse() {
        // made by hand exactly as 0.1.0 did: id.key.name
        val id = Invite.newFamilyId()
        val old = Invite.PREFIX + id + "." + Invite.b64url(key) + "." + Invite.b64url("Home".toByteArray())
        val inv = Invite.parse(old)!!
        assertEquals("Home", inv.name)
        assertNull(inv.inviter)
    }

    @Test fun familyIdsAreRandom() {
        val ids = (1..200).map { Invite.newFamilyId() }.toSet()
        assertEquals(200, ids.size)
    }

    @Test fun malformedIsNull() {
        val id = Invite.newFamilyId()
        val good = Invite.make(id, key, "Home")
        val (_, k, nm) = good.removePrefix(Invite.PREFIX).split('.')
        val bad = listOf(
            null, "", "   ", "tally://join#", "tally://join", "https://join#$id.$k.$nm",
            "TALLY://join#$id.$k.$nm", "tally://join?$id.$k.$nm",
            "tally://join#$id.$k", "tally://join#$id..$nm",
            "tally://join#$id.$k.$nm.extra",                         // inviter not base64url of a name
            "tally://join#$id.$k.$nm.${Invite.b64url("Ravi".toByteArray())}.${Invite.b64url("x".toByteArray())}",   // five parts
            "tally://join#$id.$k.$nm.",                              // empty inviter
            "tally://join#$id.$k.$nm.${Invite.b64url("  ".toByteArray())}",
            "tally://join#$id.$k.$nm.${Invite.b64url(byteArrayOf(0xff.toByte()))}",           // inviter not UTF-8
            "tally://join#$id.$k.$nm.${Invite.b64url("a\u0001b".toByteArray())}",              // inviter control char
            "tally://join#$id.$k.$nm.${Invite.b64url("y".repeat(81).toByteArray())}",          // inviter too long
            "tally://join#$id.$k.$nm.${Invite.b64url("Ravi".toByteArray())}=",                 // inviter padding
            "tally://join#${id.drop(1)}.$k.$nm",                     // short id
            "tally://join#${id}A.$k.$nm",                            // long id
            "tally://join#$id.${k.drop(2)}.$nm",                     // short key
            "tally://join#$id.$k=.$nm",                              // padding
            "tally://join#$id.${k.replace(k[0], '+')}.$nm",          // standard alphabet
            "tally://join#$id.$k.",                                  // empty name
            "tally://join#$id.$k.${Invite.b64url("  ".toByteArray())}",
            "tally://join#$id.$k.${Invite.b64url(byteArrayOf(0xff.toByte(), 0xfe.toByte()))}",   // not UTF-8
            "tally://join#$id.$k.${Invite.b64url("a\u0000b".toByteArray())}",                   // control char
            "tally://join#$id.$k.${Invite.b64url("x".repeat(81).toByteArray())}",               // too long
            "join me: $good",
            "$good junk",
        )
        for (b in bad) assertNull("should reject: $b", Invite.parse(b))
        assertNotNull(Invite.parse(good))
    }

    @Test fun makeRefusesBadInput() {
        assertThrows(IllegalArgumentException::class.java) { Invite.make("short", key, "x") }
        assertThrows(IllegalArgumentException::class.java) { Invite.make(Invite.newFamilyId(), ByteArray(16), "x") }
        assertThrows(IllegalArgumentException::class.java) { Invite.make(Invite.newFamilyId(), key, " ") }
    }

    @Test fun findPicksTheInviteOutOfAMessage() {
        val text = Invite.make(Invite.newFamilyId(), key, "Our family")
        val inv = Invite.parse(text)!!
        assertEquals(inv, Invite.find("Join our family in Tally:\n$text"))
        assertEquals(inv, Invite.find("$text"))
        assertEquals(inv, Invite.find("hello  $text  thanks!"))
        assertNull(Invite.find("Join our family in Tally"))
        assertNull(Invite.find(null))
        assertNull(Invite.find("tally://join#nope"))
        assertNull(Invite.find("https://example.com/#" + text.removePrefix(Invite.PREFIX)))
        // still strict about the invite itself
        assertNull(Invite.find("look: " + text + "x."))
    }
}
