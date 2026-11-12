package com.tally.book

import com.tally.book.core.*
import com.tally.book.data.Crypto
import com.tally.book.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class BookTest {
    @Test fun freshBookIsJustAVersion() {
        assertEquals("""{"v":1}""", JsonCodec.stringify(freshDb()))
    }

    @Test fun unknownFieldsRoundTrip() {
        val text = """{"v":1,"loans":[{"id":"l1","principal":1500000,"future":{"x":[1,2.5,null]}}]}"""
        assertEquals(text, JsonCodec.stringify(JsonCodec.parse(text)))
    }

    @Test fun sealAndUnsealWithTheLock() {
        Crypto.clearLock()
        Crypto.setLock("2468", "pin")
        val env = Crypto.sealText("""{"v":1,"me":{"name":"Alex"}}""")
        assertTrue(env.b("enc"))
        Crypto.clearLock()
        assertFalse(Crypto.locked)
        val back = Crypto.unseal(env, "2468").asObj()
        assertEquals("Alex", back.o("me").s("name"))
        assertTrue(Crypto.locked)
        assertThrows(Exception::class.java) { Crypto.unseal(env, "0000") }
        Crypto.clearLock()
    }

    @Test fun clockIsPinnable() {
        Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0)
        try {
            assertEquals("2026-11-11", todayISO())
            assertEquals("2026-11", todayKey())
        } finally { Clock.fixed = null }
    }
}
