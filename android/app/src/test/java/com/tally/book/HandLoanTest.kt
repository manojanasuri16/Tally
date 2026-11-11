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

/** Person-to-person money: the owner's real cases, no-interest loans, a "to be returned by" date, and money given to others. */
class HandLoanTest {
    @Before fun setUp() {
        DB = freshDb(); ensureBookShape(DB)
        Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0)
    }
    @After fun tearDown() { Clock.fixed = null }

    private val today get() = Clock.today()
    private fun d(s: String) = LocalDate.parse(s)
    private fun hand(name: String, principal: Double, rate: Double, unit: String = "month", given: Boolean = false, repayBy: String? = null, start: String = "2026-09-10") =
        saveLoan(obj("kind" to "hand", "name" to name, "principal" to principal, "rate" to rate, "rateUnit" to unit, "interestEvery" to "month", "startDate" to start).also {
            if (given) it["direction"] = "given"
            if (repayBy != null) it["repayBy"] = repayBy
        })
    private fun pay(l: Obj, date: String, amount: Double, kind: String) = savePayment(obj("loanId" to l.s("id"), "date" to date, "amount" to amount, "kind" to kind))
    private fun v(l: Obj, on: String = "2026-11-11") = loanView(loan(l.s("id"))!!, d(on))

    // Suryarao: 2% a month on 50,000 = 1,000 a month, interest only; the principal stays 50,000 until it is returned in full.
    @Test fun interestOnlyLoanKeepsFullPrincipal() {
        val l = hand("Suryarao", 50000.0, 2.0)
        assertEquals(1000.0, v(l).num("nextDueAmount"), 0.0)
        pay(l, "2026-10-10", 1000.0, "interest"); pay(l, "2026-11-10", 1000.0, "interest")
        val w = v(l, "2026-11-12")
        assertEquals(50000.0, w.num("owed"), 0.0); assertEquals(2000.0, w.num("paidInterest"), 0.0)
        assertEquals("2026-12-10", w.s("nextDueDate")); assertEquals(1000.0, w.num("nextDueAmount"), 0.0)
        pay(l, "2026-11-12", 50000.0, "principal")          // repaid in full
        assertEquals(0.0, v(l, "2026-11-13").num("owed"), 0.0); assertEquals("", v(l, "2026-11-13").s("nextDueDate"))
    }

    // 2,00,000 at 2% = 4,000 a month. Return 50,000: 1,50,000 left, 2% = 3,000 a month.
    @Test fun partRepaymentLowersTheInterest() {
        val l = hand("Other", 200000.0, 2.0)
        assertEquals(4000.0, v(l).num("nextDueAmount"), 0.0)
        pay(l, "2026-11-01", 50000.0, "principal")
        val w = v(l)
        assertEquals(150000.0, w.num("owed"), 0.0); assertEquals(3000.0, w.num("nextDueAmount"), 0.0)
        assertEquals(3000.0, suggestPayment(loan(l.s("id"))!!, today).num("amount"), 0.0)
    }

    // 1.5% a month: 1,00,000 -> 1,500; 2,00,000 -> 3,000. Said as "1.5% a month".
    @Test fun fractionalRate() {
        assertEquals(1500.0, v(hand("a", 100000.0, 1.5)).num("nextDueAmount"), 0.0)
        assertEquals(3000.0, v(hand("b", 200000.0, 1.5)).num("nextDueAmount"), 0.0)
        assertEquals("1.5% a month", rateInWords(1.5, "month"))
        assertEquals("month", interestChoice(1.5, "month")); assertEquals("year", interestChoice(12.0, "")); assertEquals("none", interestChoice(0.0, "month"))
    }

    // Cousin: 1,00,000, rate 0, no date. Never due, never overdue, still owed; the suggestion is a part repayment of 0.
    @Test fun noInterestNoDateIsNeverDue() {
        val l = hand("Cousin", 100000.0, 0.0, start = "2025-01-01")
        val w = v(l)
        assertTrue(w.b("noInterest")); assertEquals("", w.s("nextDueDate")); assertFalse(w.b("overdue")); assertFalse(w.b("dueThisMonth"))
        assertEquals(100000.0, w.num("owed"), 0.0)
        assertTrue(dueList(today).isEmpty())
        assertEquals(100000.0, totals(today).num("owed"), 0.0); assertEquals(0.0, totals(today).num("dueThisMonth"), 0.0)
        val s = suggestPayment(loan(l.s("id"))!!, today)
        assertEquals("principal", s.s("kind")); assertEquals(0.0, s.num("amount"), 0.0)
        assertNull(validateLoan(loan(l.s("id"))!!))
    }

    // repayBy 20 Nov (this month): the whole 1,00,000 is one principal row, not overdue. 5 Dec: not yet. 1 Oct: overdue. Paid in full: gone.
    @Test fun repayByShowsInItsMonthAndAfter() {
        val inMonth = hand("A", 100000.0, 0.0, repayBy = "2026-11-20")
        var rows = dueList(today)
        assertEquals(1, rows.size)
        assertEquals("principal", rows[0].s("kind")); assertEquals(100000.0, rows[0].num("amount"), 0.0); assertEquals("2026-11-20", rows[0].s("date")); assertFalse(rows[0].b("overdue"))
        assertEquals(100000.0, totals(today).num("dueThisMonth"), 0.0)
        assertFalse(v(inMonth).b("repayOverdue"))
        val later = hand("B", 5000.0, 0.0, repayBy = "2026-12-05")
        assertEquals(1, dueList(today).size); assertFalse(v(later).b("repayDue"))
        val past = hand("C", 30000.0, 0.0, repayBy = "2026-10-01")
        rows = dueList(today)
        assertEquals(listOf("2026-10-01", "2026-11-20"), rows.map { it.s("date") })
        assertTrue(rows[0].b("overdue")); assertTrue(v(past).b("repayOverdue")); assertFalse(v(past).b("overdue"))   // overdue is interest only
        pay(past, "2026-11-11", 30000.0, "principal")
        assertEquals(1, dueList(today).size)
        assertEquals("2026-12-05", v(later).s("repayBy"))
    }

    // An interest loan with repayBy this month gives two rows: interest 1,000 (10 Nov? start 10 Sep, nothing paid: due 10 Oct, overdue) and principal 50,000.
    @Test fun interestAndPrincipalRowsAreSeparate() {
        val l = hand("Both", 50000.0, 2.0, repayBy = "2026-11-25")
        pay(l, "2026-10-10", 1000.0, "interest")       // next interest due 10 Nov (overdue by one day)
        val rows = dueList(today).filter { it.o("loan").s("id") == l.s("id") }
        assertEquals(listOf("interest", "principal"), rows.map { it.s("kind") })
        assertEquals(listOf(1000.0, 50000.0), rows.map { it.num("amount") })
        assertEquals(listOf(true, false), rows.map { it.b("overdue") })
        assertEquals(51000.0, totals(today).num("dueThisMonth"), 0.0)
    }

    // Given: 1,00,000 given at 2% (2,000 a month), 40,000 given free. Taken: 50,000. Nothing nets out.
    @Test fun givenLoansNeverNetWithTaken() {
        val taken = hand("Taken", 50000.0, 2.0, start = "2026-10-15")
        val g1 = hand("Gave1", 100000.0, 2.0, given = true, start = "2026-10-12")
        val g2 = hand("Gave2", 40000.0, 0.0, given = true, repayBy = "2026-11-15")
        pay(taken, "2026-11-02", 1000.0, "interest")     // paid on a taken loan
        pay(g1, "2026-11-03", 2000.0, "interest")        // received on a given loan
        pay(g2, "2026-11-04", 5000.0, "principal")       // received 5,000 back: 35,000 left
        val t = totals(today)
        assertEquals(50000.0, t.num("owed"), 0.0); assertEquals(50000.0, t.o("byKind").num("hand"), 0.0)
        assertEquals(135000.0, t.num("toReceive"), 0.0)   // 100000 + 35000
        assertEquals(1000.0, t.num("paidThisMonth"), 0.0); assertEquals(7000.0, t.num("receivedThisMonth"), 0.0)
        assertEquals(3.0, t.num("count"), 0.0); assertEquals(2.0, t.num("givenCount"), 0.0)
        assertTrue(isGiven(loan(g1.s("id"))!!)); assertFalse(isGiven(taken))
        // dues: both interests were paid in Nov (next in Dec), so only g2's principal 35,000 on 15 Nov remains, and it is expected, not due
        val rows = dueList(today)
        assertEquals(listOf(true), rows.map { it.b("given") })
        assertEquals(listOf(35000.0), rows.map { it.num("amount") })
        assertEquals(0.0, t.num("dueThisMonth"), 0.0); assertEquals(35000.0, t.num("expectedThisMonth"), 0.0)
    }

    @Test fun takenAndGivenDueRowsSplit() {
        hand("T", 50000.0, 2.0, start = "2026-10-15")                 // interest 1,000 due 15 Nov
        hand("G", 100000.0, 1.0, given = true, start = "2026-10-20")  // interest 1,000 expected 20 Nov
        val rows = dueList(today)
        assertEquals(listOf(false, true), rows.map { it.b("given") })
        val t = totals(today)
        assertEquals(1000.0, t.num("dueThisMonth"), 0.0); assertEquals(1000.0, t.num("expectedThisMonth"), 0.0)
    }

    @Test fun onlyHandLoansCanBeGiven() {
        assertNotNull(validateLoan(obj("kind" to "bank", "name" to "x", "principal" to 5, "emi" to 1, "startDate" to "2026-01-01", "dueDay" to 5, "direction" to "given")))
        assertNull(validateLoan(obj("kind" to "hand", "name" to "x", "principal" to 5, "startDate" to "2026-01-01", "direction" to "given")))
        assertNotNull(validateLoan(obj("kind" to "hand", "name" to "x", "principal" to 5, "startDate" to "2026-01-01", "direction" to "sideways")))
        assertNotNull(validateLoan(obj("kind" to "hand", "name" to "x", "principal" to 5, "startDate" to "2026-01-01", "repayBy" to "soon")))
        assertFalse(isGiven(obj("kind" to "bank", "direction" to "given")))
        assertEquals("To a person", loanKindLabel(hand("g", 1.0, 0.0, given = true)))
    }

    // Closing at zero: 50,000 owed, 20,000 returned (30,000 left, no offer), then 30,000 (zero: offer).
    @Test fun offerToCloseOnlyWhenItReachesZero() {
        val l = hand("Z", 50000.0, 2.0)
        pay(l, "2026-11-05", 20000.0, "principal")
        val mid = v(l).num("owed")
        assertEquals(30000.0, mid, 0.0); assertFalse(reachedZero(50000.0, mid))
        pay(l, "2026-11-06", 30000.0, "principal")
        assertTrue(reachedZero(mid, v(l).num("owed")))
        assertFalse(reachedZero(0.0, 0.0))
    }

    // The words turn around in one place.
    @Test fun wordsTurnAround() {
        val taken = loanWords(false); val given = loanWords(true)
        assertEquals("Who lent the money?", taken.who); assertEquals("Who took the money?", given.who)
        assertEquals("Amount borrowed", taken.amount); assertEquals("Amount given", given.amount)
        assertEquals("Date taken", taken.date); assertEquals("Date given", given.date)
        assertEquals("Still to pay", taken.left); assertEquals("Still to come back", given.left)
        assertEquals("Log payment", taken.log); assertEquals("Log money received", given.log)
        assertEquals("Expected this month", given.expected); assertEquals("Due this month", taken.expected)
        assertEquals("Returned some of the amount", payKindLabel("principal", "hand", false))
        assertEquals("Principal", payKindLabel("principal", "bank", false)); assertEquals("Principal", payKindLabel("principal"))
        assertEquals("Interest received", payKindLabel("interest", "hand", true)); assertEquals("Got some of the amount back", payKindLabel("principal", "hand", true))
        assertEquals("Interest", payKindLabel("interest", "hand", false))
        assertEquals("Got some of the amount back", loanWords(hand("g", 1.0, 0.0, given = true)).principal)
    }

    @Test fun whoAddedIt() {
        assertEquals("Ravi · added by Amma", loanRowSub("Ravi", "Amma")); assertEquals("Ravi", loanRowSub("Ravi", null))
        assertEquals("Added by Amma", loanRowSub("", "Amma")); assertNull(loanRowSub("", null))
        val line = addedByLine("m1", 1.0, "m1", { if (it == "m1") "Amma" else "Nanna" }) { "05-Nov-2026" }
        assertEquals("Added by Amma on 05-Nov-2026", line)
        assertEquals("Added by Amma on 05-Nov-2026\nLast changed by Nanna", addedByLine("m1", 1.0, "m2", { if (it == "m1") "Amma" else "Nanna" }) { "05-Nov-2026" })
        assertEquals("Added by Amma", addedByLine("m1", 0.0, "", { "Amma" }) { "" })
        // alone on the phone: nobody else could have added it
        me(); setMyName("Amma"); hand("x", 1.0, 0.0)
        assertFalse(bookHasOthers())
        DB["family"] = obj("id" to "f", "key" to "k", "name" to "Home")
        assertTrue(bookHasOthers())
        DB.remove("family"); DB.objs("loans").single()["by"] = "someone-else"
        assertTrue(bookHasOthers())
    }
}
