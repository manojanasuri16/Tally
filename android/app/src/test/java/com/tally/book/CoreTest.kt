package com.tally.book

import com.tally.book.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreTest {
    @Test fun parseFloatLikeJs() {
        assertEquals(12.5, n("12.5abc"), 0.0); assertEquals(0.0, n("abc"), 0.0); assertEquals(0.0, n(null), 0.0)
        assertEquals(-3.0, n(" -3"), 0.0); assertEquals(0.5, n(".5"), 0.0); assertEquals(0.0, n(true), 0.0); assertEquals(1e3, n("1e3"), 0.0)
    }
    @Test fun inrLikeJs() {
        assertEquals("₹12,34,567", inr(1234567.4)); assertEquals("-₹500", inr(-499.6)); assertEquals("₹0", inr(-0.3)); assertEquals("₹999", inr(999.0))
        assertEquals("₹1,000", inr(999.5)); assertEquals("₹1,00,000", inr(100000.0))
    }
    @Test fun numbersPrintLikeJs() {
        assertEquals("3", jsNumber(3.0)); assertEquals("0.1", jsNumber(0.1)); assertEquals("1e-7", jsNumber(1e-7)); assertEquals("1e+21", jsNumber(1e21))
        assertEquals("""{"a":1,"b":[0.5,"x\n"],"c":null}""", JsonCodec.stringify(JsonCodec.parse("""{"a":1,"b":[0.5,"x\n"],"c":null}""")))
    }
    @Test fun monthKeys() {
        assertEquals("2026-12", prevKey("2027-01")); assertEquals("2027-01", nextKey("2026-12")); assertEquals("2025-11", addMonths("2026-01", -2)); assertEquals(28, daysIn("2026-02"))
    }
}
