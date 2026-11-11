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
            "tally://join#$id.$k", "tally://join#$id.$k.$nm.extra", "tally://join#$id..$nm",
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
}
