package com.tally.book

import com.tally.book.core.*
import com.tally.book.ui.components.PullMath
import com.tally.book.ui.loans.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime

/** The non-UI logic of the loan screens: the pull-to-refresh arithmetic, plain-word dates, photo scaling and safe reading. */
class LoanUiLogicTest {
    @Before fun setUp() { Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    // ---- pull to refresh ----

    @Test fun rubberBandStartsAtRestAndNeverReachesTheRange() {
        val range = 480f
        assertEquals(0f, PullMath.rubber(0f, range), 0f)
        assertEquals(0f, PullMath.rubber(-20f, range), 0f)
        var last = 0f
        for (raw in listOf(10f, 50f, 100f, 300f, 1000f, 100000f)) {
            val shown = PullMath.rubber(raw, range)
            assertTrue("rises", shown > last); assertTrue("stays inside the range", shown < range)
            last = shown
        }
    }

    @Test fun rubberBandIsNearlyOneToOneAtTheStartAndResistsMore() {
        val range = 480f
        assertEquals(PullMath.RESIST.toDouble(), PullMath.rubber(1f, range).toDouble(), 0.01)
        val first = PullMath.rubber(100f, range) - PullMath.rubber(0f, range)
        val later = PullMath.rubber(500f, range) - PullMath.rubber(400f, range)
        assertTrue("the same finger travel shows less further out", later < first)
    }

    @Test fun rawIsTheInverseOfRubberSoADragCanResumeFromWhatIsShown() {
        val range = 480f
        for (raw in listOf(0f, 5f, 80f, 200f, 700f)) assertEquals(raw.toDouble(), PullMath.raw(PullMath.rubber(raw, range), range).toDouble(), raw * 0.001 + 0.01)
        assertEquals(0f, PullMath.raw(0f, range), 0f)
        assertTrue(PullMath.raw(range * 5, range).isFinite())   // an offset beyond the range cannot divide by zero
    }

    @Test fun slopeMatchesTheCurve() {
        val range = 480f; val h = 0.5f
        for (raw in listOf(0f, 100f, 400f)) {
            val numeric = (PullMath.rubber(raw + h, range) - PullMath.rubber(maxOf(0f, raw - h), range)) / (if (raw == 0f) h else 2 * h)
            assertEquals(numeric.toDouble(), PullMath.slope(raw, range).toDouble(), 0.01)
        }
    }

    @Test fun refreshesOnlyFromTheThreshold() {
        assertFalse(PullMath.releases(63.9f, 64f)); assertTrue(PullMath.releases(64f, 64f)); assertTrue(PullMath.releases(120f, 64f))
        assertEquals(0f, PullMath.progress(0f, 64f), 0f); assertEquals(0.5f, PullMath.progress(32f, 64f), 0f); assertEquals(1f, PullMath.progress(200f, 64f), 0f)
    }

    @Test fun releaseVelocityGoesOnlyTheWayTheContentIsHeading() {
        // heading back to rest: only an upward finger velocity carries over; heading to the hold: only a downward one
        assertEquals(0f, PullMath.handoff(2000f, 100f, 480f, 0f), 0f)
        assertTrue(PullMath.handoff(-2000f, 100f, 480f, 0f) < 0f)
        assertEquals(0f, PullMath.handoff(-2000f, 100f, 480f, 64f), 0f)
        assertTrue(PullMath.handoff(2000f, 100f, 480f, 64f) in 1f..2000f)
    }

    // ---- dates in words ----

    @Test fun datesAreSaidTheWayPeopleSayThem() {
        val today = LocalDate.of(2026, 11, 11)
        assertEquals("Today", dateInWords("2026-11-11", today))
        assertEquals("Tomorrow", dateInWords("2026-11-12", today))
        assertEquals("Yesterday", dateInWords("2026-11-10", today))
        assertEquals("15 Nov", dateInWords("2026-11-15", today))
        assertEquals("03 Jan", dateInWords("2026-01-03", today))
        assertEquals("05-Jan-2027", dateInWords("2027-01-05", today))
        assertEquals("", dateInWords("", today)); assertEquals("", dateInWords("soon", today))
        assertEquals("Tomorrow", dateInWords("2026-11-12"))   // the pinned clock
    }

    @Test fun ratesAndIntervalsCarryTheirUnit() {
        assertEquals("9.5% a year", rateInWords(9.5, "year")); assertEquals("2% a month", rateInWords(2.0, "month"))
        assertEquals("12% a year", rateInWords(12.0, "")); assertEquals("Every month", everyInWords("month")); assertEquals("Every year", everyInWords("year"))
    }

    @Test fun paymentKindsFollowTheLoanKind() {
        assertEquals(listOf("emi", "principal"), payKindsFor("bank"))
        assertEquals(listOf("interest", "principal"), payKindsFor("hand")); assertEquals(listOf("interest", "principal"), payKindsFor("gold"))
        assertEquals(listOf("interest", "principal", "emi"), payKindOptions("hand", "emi"))   // an old payment keeps its own kind
        assertEquals(listOf("emi", "principal"), payKindOptions("bank", "principal")); assertEquals(listOf("emi", "principal"), payKindOptions("bank", "bogus"))
    }

    @Test fun numberFieldsKeepOnlyWhatTheyCanUse() {
        assertEquals("12", digitsOnly("1a2-")); assertEquals("1.23", decimalOnly("1.2.3")); assertEquals("9.5", decimalOnly("9.5%")); assertEquals("", decimalOnly("abc"))
    }

    // ---- photos and files ----

    @Test fun photosAreScaledToTheLongEdgeAndNeverEnlarged() {
        assertEquals(1600 to 1200, PhotoMath.fit(4000, 3000)); assertEquals(1200 to 1600, PhotoMath.fit(3000, 4000))
        assertEquals(1600 to 900, PhotoMath.fit(3200, 1800)); assertEquals(800 to 600, PhotoMath.fit(800, 600)); assertEquals(1600 to 1600, PhotoMath.fit(1600, 1600))
        assertEquals(1600 to 1, PhotoMath.fit(16000, 1)); assertEquals(1 to 1, PhotoMath.fit(0, 0))
    }

    @Test fun theDecoderSamplesDownOnlyWhileTheLongEdgeStaysAtLeastTheTarget() {
        assertEquals(1, PhotoMath.sampleSize(1600, 1200)); assertEquals(1, PhotoMath.sampleSize(3199, 2000))
        assertEquals(2, PhotoMath.sampleSize(3200, 2400)); assertEquals(4, PhotoMath.sampleSize(4000 * 2, 3000 * 2)); assertEquals(1, PhotoMath.sampleSize(500, 400))
        for ((w, h) in listOf(4000 to 3000, 12000 to 9000, 6400 to 100)) {
            val s = PhotoMath.sampleSize(w, h); assertTrue(maxOf(w, h) / s >= 1600)
            assertTrue("one more halving would go below the target", maxOf(w, h) / (s * 2) < 1600)
        }
    }

    @Test fun readingStopsAtTheCapInsteadOfFillingMemory() {
        val data = ByteArray(1000) { it.toByte() }
        assertArrayEquals(data, AttachIo.readLimited(ByteArrayInputStream(data), 1000))
        assertNull(AttachIo.readLimited(ByteArrayInputStream(data), 999))
        assertArrayEquals(ByteArray(0), AttachIo.readLimited(ByteArrayInputStream(ByteArray(0)), 10))
        val big = ByteArray(100_000)
        assertNull(AttachIo.readLimited(ByteArrayInputStream(big), 4L * 1024))
    }

    @Test fun theStoreKeepsAtMostFourMegabytes() {
        assertEquals(4.0, com.tally.book.model.fileCapMB(), 0.0)
    }
}
