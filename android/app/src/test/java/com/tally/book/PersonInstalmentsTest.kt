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

/**
 * Money from a person paid back in fixed instalments: ₹10,000 on paper, ₹9,500 handed over, ₹1,000 collected every week for 12 weeks.
 * What is still owed is what is left of the ₹12,000 to pay back.
 */
class PersonInstalmentsTest {
    @Before fun setUp() {
        DB = freshDb(); ensureBookShape(DB)
        Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0)
    }
    @After fun tearDown() { Clock.fixed = null }

    private fun d(s: String) = LocalDate.parse(s)
    private fun weekly(start: String = "2026-11-04", given: Boolean = false, extra: Map<String, Any> = emptyMap()) = saveLoan(obj(
        "kind" to "hand", "plan" to "instalments", "name" to "Weekly", "principal" to 10000.0, "received" to 9500.0,
        "emi" to 1000.0, "tenure" to 12.0, "frequency" to "week", "rate" to 0.0, "startDate" to start,
    ).also { o -> if (given) o["direction"] = "given"; extra.forEach { (k, v) -> o[k] = v } })
    private fun pay(l: Obj, date: String, amount: Double, kind: String = "emi") = savePayment(obj("loanId" to l.s("id"), "date" to date, "amount" to amount, "kind" to kind))
    private fun v(l: Obj, on: String = "2026-11-11") = loanView(loan(l.s("id"))!!, d(on))

    @Test fun weeklyCollectionCountsDownTheTotal() {
        val l = weekly()
        assertTrue(isInstalments(l)); assertFalse(isNoInterest(l))
        val w = v(l)
        assertEquals(12000.0, w.num("owed"), 0.0)
        assertEquals(12000.0, w.num("totalToRepay"), 0.0)
        assertEquals(2500.0, w.num("extra"), 0.0)                       // 12,000 back for 9,500 received
        assertEquals("2026-11-11", w.s("nextDueDate")); assertEquals(1000.0, w.num("nextDueAmount"), 0.0)
        assertEquals(12.0, w.num("instalmentsLeft"), 0.0)
        assertEquals("2027-01-27", w.s("endDate"))                      // the 12th Wednesday after the start
        pay(l, "2026-11-11", 1000.0)
        val after = v(l)
        assertEquals(11000.0, after.num("owed"), 0.0); assertEquals(11.0, after.num("instalmentsLeft"), 0.0)
        assertEquals("2026-11-18", after.s("nextDueDate"))
        assertEquals("emi", suggestPayment(loan(l.s("id"))!!).s("kind"))
        assertEquals(1000.0, suggestPayment(loan(l.s("id"))!!).num("amount"), 0.0)
    }

    @Test fun missedWeeksArePendingAndAllTwelveClearIt() {
        val l = weekly(start = "2026-10-14")
        val w = v(l)
        assertEquals(3.0, w.num("pendingCount"), 0.0)                   // 21 Oct, 28 Oct, 4 Nov; today's is due, not pending
        assertEquals(3000.0, w.num("pendingAmount"), 0.0)
        assertEquals("Instalments pending: ₹3,000 (3)", pendingInWords(loan(l.s("id"))!!, inr(3000.0), 3))
        assertEquals(1, dueList(d("2026-11-11")).size)
        assertEquals("emi", dueList(d("2026-11-11"))[0].s("kind"))
        repeat(12) { pay(l, "2026-11-11", 1000.0) }
        assertEquals(0.0, v(l).num("owed"), 0.0); assertEquals("", v(l).s("nextDueDate"))
    }

    @Test fun paymentKindsAndLabels() {
        val l = weekly()
        assertEquals(listOf("emi", "principal"), payKindOptions(l, null))
        assertEquals("Instalment", payKindLabel("emi", "hand", false)); assertEquals("Instalment received", payKindLabel("emi", "hand", true))
        assertNull(validatePayment(obj("loanId" to l.s("id"), "date" to "2026-11-11", "amount" to 1000.0, "kind" to "emi")))
        val none = saveLoan(obj("kind" to "hand", "name" to "Brother", "principal" to 50000.0, "rate" to 0.0, "startDate" to "2026-01-01"))
        assertEquals(listOf("principal"), payKindOptions(none, null))
        assertEquals("none", personPlanOf(none)); assertEquals("instalments", personPlanOf(l))
        assertEquals("interest", personPlanOf(obj("kind" to "hand", "rate" to 2.0)))
    }

    @Test fun givenWeeklyIsToComeBack() {
        val l = weekly(given = true)
        val t = totals(d("2026-11-11"))
        assertEquals(12000.0, t.num("toReceive"), 0.0); assertEquals(0.0, t.num("owed"), 0.0)
    }

    @Test fun validation() {
        val base = obj("kind" to "hand", "plan" to "instalments", "name" to "W", "principal" to 10000.0, "startDate" to "2026-11-04", "frequency" to "week")
        assertEquals("Please enter the amount of each payment.", validateLoan(base.copyObj().also { it["tenure"] = 12.0 }))
        assertEquals("Please enter how many payments there are, as a whole number.", validateLoan(base.copyObj().also { it["emi"] = 1000.0 }))
        assertNull(validateLoan(base.copyObj().also { it["emi"] = 1000.0; it["tenure"] = 12.0 }))
        assertEquals("The amount received cannot be more than the amount itself.", validateLoan(base.copyObj().also { it["emi"] = 1000.0; it["tenure"] = 12.0; it["received"] = 10500.0 }))
    }

    @Test fun paidUpToSkipsWeeksAlreadyPaid() {
        val draft = obj("kind" to "hand", "plan" to "instalments", "emi" to 1000.0, "tenure" to 12.0, "frequency" to "week", "startDate" to "2026-10-14")
        assertEquals("2026-11-04", suggestPaidUpTo(draft, d("2026-11-11")))
        val l = weekly(start = "2026-10-14", extra = mapOf("paidUpTo" to "2026-11-04"))
        val w = v(l)
        assertEquals(9000.0, w.num("owed"), 0.0); assertEquals(0.0, w.num("pendingCount"), 0.0)
    }
}
