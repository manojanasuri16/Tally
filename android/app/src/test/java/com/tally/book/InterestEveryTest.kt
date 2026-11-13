package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import com.tally.book.ui.loans.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Interest paid every 3 or 6 months: ₹1,00,000 at 2% a month from 10 Jan, so ₹6,000 a quarter and ₹12,000 a half year. */
class InterestEveryTest {
    @Before fun setUp() { DB = freshDb(); ensureBookShape(DB); Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    private fun d(s: String) = LocalDate.parse(s)
    private fun hand(every: String) = saveLoan(obj("kind" to "hand", "name" to "P", "principal" to 100000.0, "rate" to 2.0,
        "rateUnit" to "month", "interestEvery" to every, "startDate" to "2026-01-10"))
    private fun v(l: Obj) = loanView(loan(l.s("id"))!!, d("2026-11-11"))

    @Test fun everyThreeMonths() {
        val l = hand("quarter")
        assertEquals("quarter", interestEvery(l)); assertEquals(6000.0, periodInterest(l, 100000.0), 0.0)
        val w = v(l)
        assertEquals(3.0, w.num("pendingCount"), 0.0)                 // 10 Apr, 10 Jul, 10 Oct
        assertEquals(18000.0, w.num("pendingAmount"), 0.0)
        assertEquals("2026-04-10", w.s("nextDueDate"))
        assertEquals("Interest pending: ₹18,000 (3 payments)", pendingInWords(loan(l.s("id"))!!, inr(18000.0), 3))
        assertEquals("2026-10-10", suggestPaidUpTo(l, d("2026-11-11")))
        assertEquals("Every 3 months", everyInWords("quarter"))
    }

    @Test fun everySixMonthsAndPaidSoFar() {
        val l = hand("half")
        assertEquals(12000.0, periodInterest(l, 100000.0), 0.0)
        assertEquals(1.0, v(l).num("pendingCount"), 0.0)               // 10 Jul
        savePayment(obj("loanId" to l.s("id"), "date" to "2026-11-11", "amount" to 12000.0, "kind" to "interest"))
        val w = v(l)
        assertEquals(0.0, w.num("pendingCount"), 0.0); assertEquals("2027-01-10", w.s("nextDueDate"))
        assertTrue(w.b("paidThisPeriod"))
        assertEquals("Every 6 months", everyInWords("half"))
    }

    @Test fun monthAndYearUnchanged() {
        assertEquals(2000.0, periodInterest(hand("month"), 100000.0), 0.0)
        assertEquals(24000.0, periodInterest(hand("year"), 100000.0), 0.0)
        assertNull(validateLoan(obj("kind" to "hand", "name" to "P", "principal" to 1000.0, "rate" to 2.0, "startDate" to "2026-01-10", "interestEvery" to "half")))
        assertNotNull(validateLoan(obj("kind" to "hand", "name" to "P", "principal" to 1000.0, "rate" to 2.0, "startDate" to "2026-01-10", "interestEvery" to "week")))
    }
}
