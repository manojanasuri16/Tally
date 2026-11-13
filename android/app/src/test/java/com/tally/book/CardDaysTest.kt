package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** A card's statement day and due day: the latest statement, the pay-by date, and when a new statement has come. */
class CardDaysTest {
    @Before fun setUp() { DB = freshDb(); ensureBookShape(DB); Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    private fun d(s: String) = LocalDate.parse(s)
    private val days = obj("billDay" to 15.0, "payDay" to 5.0)

    @Test fun latestStatementAndPayBy() {
        assertEquals(d("2026-10-15"), lastBillDate(days, d("2026-11-11")))   // the 15th has not come yet this month
        assertEquals(d("2026-11-15"), lastBillDate(days, d("2026-11-15")))
        assertEquals(d("2026-11-05"), payDateAfter(days, d("2026-10-15")))   // due on the 5th after the statement
        assertEquals(d("2026-12-05"), payDateAfter(obj("payDay" to 5.0), d("2026-11-15")))
        assertEquals(d("2026-11-25"), payDateAfter(obj("payDay" to 25.0), d("2026-11-05")))   // same month when it is later
        assertEquals(d("2026-02-28"), lastBillDate(obj("billDay" to 31.0), d("2026-03-15")))  // clamped to the month's end
        assertNull(lastBillDate(obj(), d("2026-11-11"))); assertNull(payDateAfter(obj(), d("2026-11-11")))
    }

    @Test fun newStatementIsNoticed() {
        val card = saveLoan(obj("kind" to "card", "name" to "SBI card", "principal" to 40000.0, "minDue" to 2000.0,
            "dueDate" to "2026-11-05", "startDate" to "2026-10-15", "billDay" to 15.0, "payDay" to 5.0))
        assertNull(newStatementSince(card, d("2026-11-11")))
        assertEquals(d("2026-11-15"), newStatementSince(card, d("2026-11-16")))
        Clock.fixed = LocalDateTime.of(2026, 11, 16, 9, 0)
        assertNull(updateStatement(card.s("id"), 30000.0, 1500.0, "2026-12-05", "2026-11-15"))
        assertNull(newStatementSince(loan(card.s("id"))!!, d("2026-11-16")))
        assertNull(newStatementSince(obj("kind" to "card", "startDate" to "2026-10-15"), d("2026-11-16")))   // no bill day: never asks
    }

    @Test fun daysAreChecked() {
        val base = obj("kind" to "card", "name" to "SBI card", "principal" to 1000.0, "dueDate" to "2026-11-25", "startDate" to "2026-11-11")
        assertEquals("A day of the month is from 1 to 31.", validateLoan(base.copyObj().also { it["billDay"] = 32.0 }))
        assertNull(validateLoan(base.copyObj().also { it["billDay"] = 15.0; it["payDay"] = 5.0 }))
    }
}
