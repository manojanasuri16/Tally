package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * `paidUpTo`: a loan entered today that started long ago. Every instalment or interest period dated on or before it counts as
 * paid as agreed, without logged payments (docs/DESIGN.md "Loan rules"). Today is Wednesday 11 Nov 2026.
 */
class LoanPaidUpToTest {
    @Before fun setUp() {
        resetModelState()
        DB = freshDb(); ensureBookShape(DB)
        Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0)
        setMyName("Alex")
    }
    @After fun tearDown() { Clock.fixed = null }

    private val today get() = Clock.today()
    private fun d(s: String) = LocalDate.parse(s)
    private fun pay(l: Obj, date: String, amount: Double, kind: String) = savePayment(obj("loanId" to l.s("id"), "date" to date, "amount" to amount, "kind" to kind))
    private fun v(l: Obj, on: String = "2026-11-11") = loanView(loan(l.s("id"))!!, d(on))
    private fun save(vararg f: Pair<String, Any?>) = saveLoan(obj("name" to "City Bank", *f))

    // Home loan 5,00,000 at 9% for 60 months from 1 Nov 2024, due on the 5th: r = 0.75%, EMI = 10,379.18 → 10,379.
    // Paid up to 5 Oct 2026: 24 instalments (5 Nov 2024 .. 5 Oct 2026). Owed after them, by the schedule:
    // 5,00,000 × 1.0075^24 − 10,379 × (1.0075^24 − 1) / 0.0075 = 3,26,396.63. Only 5 Nov 2026 is pending (10,379).
    // Then 10,379 logged on 8 Nov: interest 3,26,396.63 × 0.75% = 2,447.97, principal 7,931.03, owed 3,18,465.60; nothing pending.
    // A payment logged on 5 Oct (on the paid-up-to date) is counted in paidTotal only and covers nothing.
    @Test fun reducingEmiTwoYearsIn() {
        val l = save("kind" to "bank", "principal" to 500000, "rate" to 9, "tenure" to 60, "startDate" to "2024-11-01", "dueDay" to 5,
            "paidUpTo" to "2026-10-05")
        val w = v(l)
        assertEquals(10379.0, w.num("emi"), 0.0)
        assertEquals(326396.63, w.num("owed"), 0.01)
        assertEquals("2026-10-05", w.s("paidUpTo")); assertEquals(24.0, w.num("paidUpToCount"), 0.0)
        assertEquals(1.0, w.num("emisPending"), 0.0); assertEquals(10379.0, w.num("amountPending"), 1e-6)
        assertEquals("2026-11-05", w.s("nextDueDate")); assertEquals(36.0, w.num("instalmentsLeft"), 0.0)
        assertEquals(0.0, w.num("paidTotal"), 0.0); assertEquals(0.0, totals(today).num("paidThisMonth"), 0.0)   // nothing invented
        pay(l, "2026-10-05", 10379.0, "emi")
        assertEquals(326396.63, v(l).num("owed"), 0.01); assertEquals(1.0, v(l).num("emisPending"), 0.0)
        assertEquals(10379.0, v(l).num("paidTotal"), 0.0)
        pay(l, "2026-11-08", 10379.0, "emi")
        val x = v(l)
        assertEquals(318465.60, x.num("owed"), 0.01); assertEquals(0.0, x.num("emisPending"), 0.0)
        assertEquals("2026-12-05", x.s("nextDueDate")); assertEquals(2447.97, x.num("paidInterest"), 0.01)
        assertEquals(10379.0, totals(today).num("paidThisMonth"), 0.0)
        assertEquals(35.0, x.num("instalmentsLeft"), 0.0)
    }

    // Flat 1,00,000 at 12% for 12 months from 10 Jan 2026: 9,333 a month, 8,333 of it principal. Paid up to 5 Nov: 10 instalments,
    // owed 1,00,000 − 10 × 8,333 = 16,670; next 5 Dec (9,333) and the last 5 Jan 2027 (8,337 + 1,000 = 9,337).
    @Test fun flatPaidUpTo() {
        val l = save("kind" to "bank", "principal" to 100000, "rate" to 12, "tenure" to 12, "interestMethod" to "flat",
            "startDate" to "2026-01-10", "dueDay" to 5, "paidUpTo" to "2026-11-05")
        val w = v(l)
        assertEquals(16670.0, w.num("owed"), 1e-6); assertEquals(10.0, w.num("paidUpToCount"), 0.0)
        assertEquals(0.0, w.num("emisPending"), 0.0); assertEquals("2026-12-05", w.s("nextDueDate"))
        assertEquals(2.0, w.num("instalmentsLeft"), 0.0); assertEquals("2027-01-05", w.s("endDate"))
        assertTrue(w.b("paidThisPeriod")); assertTrue(dueList(today).isEmpty())
    }

    // Weekly 50,000 at 20% flat for 52 weeks from Tuesday 4 Aug: 1,154 a week (961.69 principal). Paid up to 3 Nov: 13 weeks
    // (91 days), owed 50,000 − 13 × 961.6923 = 37,498.00. November still has 10 Nov (pending), 17 and 24 Nov: 3 × 1,154 = 3,462.
    @Test fun weeklyPaidUpTo() {
        val l = save("kind" to "bank", "principal" to 50000, "rate" to 20, "tenure" to 52, "interestMethod" to "flat", "frequency" to "week",
            "startDate" to "2026-08-04", "paidUpTo" to "2026-11-03")
        val w = v(l)
        assertEquals(13.0, w.num("paidUpToCount"), 0.0); assertEquals(37498.0, w.num("owed"), 0.01)
        assertEquals(3462.0, dueList(today).single().num("amount"), 1e-6); assertEquals(1.0, w.num("emisPending"), 0.0)
        assertEquals(39.0, w.num("instalmentsLeft"), 0.0)
    }

    // Interest only: 1,00,000 at 2% a month from 15 Mar 2025. Without paidUpTo, the 19 months to 15 Oct 2026 would all be pending
    // (19 × 2,000 = 38,000). Paid up to 15 Oct 2026: nothing pending, next 2,000 on 15 Nov; the principal stays 1,00,000.
    // Yearly: 50,000 gold at 9% a year from 15 Dec 2023, paid up to 15 Dec 2025 (2 years): nothing pending, next 4,500 on 15 Dec 2026.
    @Test fun interestOnlyMonthlyAndYearly() {
        val plain = save("kind" to "hand", "principal" to 100000, "rate" to 2, "rateUnit" to "month", "interestEvery" to "month", "startDate" to "2025-03-15")
        assertEquals(38000.0, v(plain).num("interestPending"), 0.0)
        val l = save("kind" to "hand", "principal" to 100000, "rate" to 2, "rateUnit" to "month", "interestEvery" to "month", "startDate" to "2025-03-15",
            "paidUpTo" to "2026-10-15")
        val w = v(l)
        assertEquals(19.0, w.num("paidUpToCount"), 0.0); assertEquals(0.0, w.num("interestPending"), 0.0)
        assertEquals("2026-11-15", w.s("nextDueDate")); assertEquals(2000.0, w.num("nextDueAmount"), 0.0); assertEquals(100000.0, w.num("owed"), 0.0)
        assertEquals(0.0, w.num("paidInterest"), 0.0)
        val g = save("kind" to "gold", "principal" to 50000, "rate" to 9, "startDate" to "2023-12-15", "paidUpTo" to "2025-12-15")
        val y = v(g)
        assertEquals(2.0, y.num("paidUpToCount"), 0.0); assertEquals(0.0, y.num("interestPending"), 0.0)
        assertEquals("2026-12-15", y.s("nextDueDate")); assertEquals(4500.0, y.num("nextDueAmount"), 0.0)
        // a payment logged after paidUpTo works as before; one on or before it covers nothing
        pay(l, "2026-10-01", 2000.0, "interest")
        assertEquals("2026-11-15", v(l).s("nextDueDate")); assertEquals(2000.0, v(l).num("paidTotal"), 0.0)
        pay(l, "2026-11-11", 2000.0, "interest")
        assertEquals("2026-12-15", v(l).s("nextDueDate")); assertTrue(v(l).b("paidThisPeriod"))
    }

    // 1,00,000 at 12%, EMI 8,885, from 10 Jan 2026; the rate went to 18% from 10 Apr; paid up to 5 Jul (6 instalments).
    // 5 Feb, 5 Mar, 5 Apr at 1%: owed 76,107.66. 5 May, 5 Jun, 5 Jul at 1.5%: owed 52,527.31. The 7th (5 Aug) carries
    // 52,527.31 × 1.5% = 787.91 interest.
    @Test fun rateChangeBeforePaidUpTo() {
        val l = save("kind" to "bank", "principal" to 100000, "rate" to 12, "tenure" to 12, "emi" to 8885, "startDate" to "2026-01-10", "dueDay" to 5,
            "paidUpTo" to "2026-07-05")
        assertNull(addRateChange(l.s("id"), "2026-04-10", 18.0))
        val w = v(l, "2026-07-20")
        assertEquals(52527.31, w.num("owed"), 0.01); assertEquals(6.0, w.num("paidUpToCount"), 0.0)
        pay(l, "2026-08-05", 8885.0, "emi")
        assertEquals(787.91, v(l, "2026-08-06").num("paidInterest"), 0.01)
        assertEquals(52527.31 - (8885 - 787.91), v(l, "2026-08-06").num("owed"), 0.01)
    }

    @Test fun suggestingThePaidUpToDate() {
        assertEquals("2026-11-05", suggestPaidUpTo(obj("kind" to "bank", "startDate" to "2024-11-01", "dueDay" to 5, "tenure" to 60), today))
        assertEquals("2026-11-10", suggestPaidUpTo(obj("kind" to "bank", "frequency" to "week", "startDate" to "2026-08-04", "tenure" to 52), today))
        assertEquals("2021-01-05", suggestPaidUpTo(obj("kind" to "bank", "startDate" to "2020-01-10", "dueDay" to 5, "tenure" to 12), today))   // the 12th, the last
        assertEquals("2026-10-15", suggestPaidUpTo(obj("kind" to "hand", "rate" to 2, "interestEvery" to "month", "startDate" to "2025-03-15"), today))
        assertEquals("2025-12-15", suggestPaidUpTo(obj("kind" to "gold", "rate" to 9, "startDate" to "2023-12-15"), today))
        assertEquals("", suggestPaidUpTo(obj("kind" to "hand", "rate" to 0, "startDate" to "2025-03-15"), today))
        assertEquals("", suggestPaidUpTo(obj("kind" to "bank", "startDate" to "2026-11-01", "dueDay" to 20, "tenure" to 12), today))
        assertEquals("", suggestPaidUpTo(obj("kind" to "bank"), today))
    }

    @Test fun validation() {
        fun b(up: String) = obj("kind" to "bank", "name" to "x", "principal" to 100000, "rate" to 12, "tenure" to 12, "startDate" to "2026-01-10",
            "dueDay" to 5, "paidUpTo" to up)
        assertNull(validateLoan(b("2026-11-05"))); assertNull(validateLoan(b("2026-11-11")))
        assertEquals("The paid-up-to date must be after the entry started.", validateLoan(b("2026-01-10")))
        assertEquals("The paid-up-to date cannot be in the future.", validateLoan(b("2026-11-12")))
        assertEquals("Please enter the date it is paid up to.", validateLoan(b("soon")))
    }
}
