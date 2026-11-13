package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The first payment date: payments can fall on another day than the one the money came. */
class FirstDueTest {
    @Before fun setUp() { DB = freshDb(); ensureBookShape(DB); Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    private fun d(s: String) = LocalDate.parse(s)
    private fun v(l: Obj) = loanView(loan(l.s("id"))!!, d("2026-11-11"))

    @Test fun bankEmiFromItsFirstDate() {
        val base = obj("kind" to "bank", "name" to "Car", "principal" to 120000.0, "rate" to 12.0, "tenure" to 12.0,
            "frequency" to "month", "dueDay" to 3.0, "startDate" to "2026-10-03")
        assertEquals("2026-11-03", defaultFirstDue(base))
        val l = saveLoan(base.copyObj().also { it["firstDue"] = "2026-11-20"; it["dueDay"] = 20.0 })
        val w = v(l)
        assertEquals("2026-11-20", w.s("nextDueDate")); assertEquals(0.0, w.num("emisPending"), 0.0)
        assertEquals("2027-10-20", w.s("endDate")); assertEquals(20.0, w.num("dueDay"), 0.0)
    }

    @Test fun weeklyCollectionOnAnotherWeekday() {
        val l = saveLoan(obj("kind" to "hand", "plan" to "instalments", "name" to "Weekly", "principal" to 10000.0, "emi" to 1000.0,
            "tenure" to 12.0, "frequency" to "week", "rate" to 0.0, "startDate" to "2026-11-04", "firstDue" to "2026-11-09"))
        val w = v(l)
        assertEquals(1.0, w.num("weekday"), 0.0)                               // Monday, not the Wednesday it was taken
        assertEquals("Every Monday", instalmentDayInWords(loan(l.s("id"))!!))
        assertEquals(1.0, w.num("pendingCount"), 0.0)                          // 9 Nov
        assertEquals("2027-01-25", w.s("endDate"))                             // 12 Mondays from 9 Nov
    }

    @Test fun interestOnlyOnTheTenth() {
        val base = obj("kind" to "hand", "name" to "P", "principal" to 100000.0, "rate" to 2.0, "rateUnit" to "month", "startDate" to "2026-09-03")
        assertEquals("2026-10-03", defaultFirstDue(base))
        val l = saveLoan(base.copyObj().also { it["firstDue"] = "2026-09-10" })
        val w = v(l)
        assertEquals(3.0, w.num("pendingCount"), 0.0)                          // 10 Sep, 10 Oct, 10 Nov
        assertEquals("2026-09-10", w.s("nextDueDate"))
        assertEquals("2026-11-10", suggestPaidUpTo(l, d("2026-11-11")))
        val q = saveLoan(base.copyObj().also { it["firstDue"] = "2026-12-01"; it["interestEvery"] = "quarter" })
        assertEquals(0.0, v(q).num("pendingCount"), 0.0); assertEquals("2026-12-01", v(q).s("nextDueDate"))
    }

    @Test fun firstDateMustBeAfterTheDateTaken() {
        val base = obj("kind" to "hand", "name" to "P", "principal" to 1000.0, "rate" to 2.0, "startDate" to "2026-09-03")
        assertEquals("The first payment date must be after the date taken.", validateLoan(base.copyObj().also { it["firstDue"] = "2026-09-03" }))
        assertNull(validateLoan(base.copyObj().also { it["firstDue"] = "2026-09-04" }))
        assertNull(firstDueOf(base.copyObj().also { it["firstDue"] = "2026-09-01" }))   // an old date before the start is ignored
        assertEquals("", defaultFirstDue(obj("kind" to "hand", "principal" to 1000.0, "rate" to 0.0, "startDate" to "2026-09-03")))
    }
}
