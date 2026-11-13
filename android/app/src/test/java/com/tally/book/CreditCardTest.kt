package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import com.tally.book.ui.loans.payKindOptions
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** The credit card kind: a statement's outstanding amount and minimum due, payments since the statement, new statements. */
class CreditCardTest {
    @Before fun setUp() { DB = freshDb(); ensureBookShape(DB); Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    private fun d(s: String) = LocalDate.parse(s)
    private val today get() = Clock.today()
    private fun v(l: Obj) = loanView(loan(l.s("id"))!!, today)

    private fun card(outstanding: Double = 40000.0, minDue: Double = 2000.0, statement: String = "2026-11-05", due: String = "2026-11-25") =
        saveLoan(obj("kind" to "card", "name" to "SBI card", "principal" to outstanding, "minDue" to minDue,
            "startDate" to statement, "dueDate" to due))

    private fun pay(l: Obj, amount: Double, date: String) =
        savePayment(obj("loanId" to l.s("id"), "kind" to "principal", "amount" to amount, "date" to date))

    private fun at(s: String) { Clock.fixed = LocalDateTime.parse(s) }

    @Test fun theStatementShowsOwedAndTheMinimumDue() {
        val l = card()
        assertTrue(isCard(loan(l.s("id"))!!))
        assertNull(validateLoan(loan(l.s("id"))!!))
        val w = v(l)
        assertEquals(40000.0, w.num("owed"), 0.0)
        assertEquals("2026-11-25", w.s("nextDueDate")); assertEquals(2000.0, w.num("nextDueAmount"), 0.0)
        assertEquals(2000.0, w.num("minDue"), 0.0); assertEquals("2026-11-25", w.s("dueDate"))
        assertEquals("2026-11-05", w.s("statementDate")); assertEquals(0.0, w.num("paidSinceStatement"), 0.0)
        assertFalse(w.b("overdue")); assertFalse(w.b("paidThisPeriod"))

        val rows = dueList(today)
        assertEquals(1, rows.size)
        assertEquals("card", rows[0].s("kind")); assertEquals(2000.0, rows[0].num("amount"), 0.0)
        assertEquals("2026-11-25", rows[0].s("date"))
        val t = totals(today)
        assertEquals(40000.0, t.num("owed"), 0.0); assertEquals(2000.0, t.num("dueThisMonth"), 0.0)

        val s = suggestPayment(loan(l.s("id"))!!, today)
        assertEquals("principal", s.s("kind")); assertEquals(2000.0, s.num("amount"), 0.0)
        assertEquals(listOf("principal"), payKindOptions(loan(l.s("id"))!!, null))
        assertEquals("Credit card", kindLabel("card"))
        assertEquals("Card payment", payKindLabel("principal", "card"))
    }

    @Test fun payingTheMinimumInPartsClearsTheDueButNotTheBalance() {
        val l = card()
        pay(l, 1500.0, "2026-11-08")
        val mid = v(l)
        assertEquals(38500.0, mid.num("owed"), 0.0); assertEquals(500.0, mid.num("nextDueAmount"), 0.0)
        pay(l, 500.0, "2026-11-09")
        val w = v(l)
        assertEquals(38000.0, w.num("owed"), 0.0)
        assertEquals("", w.s("nextDueDate"))
        assertTrue(w.b("paidThisPeriod")); assertFalse(w.b("overdue"))
        assertEquals(2000.0, w.num("paidSinceStatement"), 0.0)
        assertTrue(dueList(today).isEmpty())
        assertEquals(0.0, totals(today).num("dueThisMonth"), 0.0)
        assertEquals(38000.0, totals(today).num("owed"), 0.0)
    }

    @Test fun aPaymentBeforeTheStatementDoesNotCount() {
        val l = card()
        pay(l, 700.0, "2026-11-01")
        val w = v(l)
        assertEquals(40000.0, w.num("owed"), 0.0); assertEquals(2000.0, w.num("nextDueAmount"), 0.0)
        assertEquals(0.0, w.num("paidSinceStatement"), 0.0)
        assertEquals(700.0, w.num("paidTotal"), 0.0)
    }

    @Test fun noMinimumMeansTheWholeBillIsDue() {
        val l = card(outstanding = 40000.0, minDue = 0.0)
        val w = v(l)
        assertEquals(40000.0, w.num("nextDueAmount"), 0.0)
        assertEquals(40000.0, dueList(today).single().num("amount"), 0.0)
        assertEquals(40000.0, totals(today).num("dueThisMonth"), 0.0)
        assertEquals(40000.0, suggestPayment(loan(l.s("id"))!!, today).num("amount"), 0.0)
        pay(l, 40000.0, "2026-11-10")
        val z = v(l)
        assertEquals(0.0, z.num("owed"), 0.0); assertEquals("", z.s("nextDueDate"))
    }

    @Test fun overdueWhenThePayByDateHasPassed() {
        val l = card()
        at("2026-11-27T09:00")
        val w = v(l)
        assertTrue(w.b("overdue"))
        assertEquals(2000.0, w.num("pendingAmount"), 0.0)
        val rows = dueList(today)
        assertEquals(1, rows.size); assertTrue(rows[0].b("overdue")); assertEquals("card", rows[0].s("kind"))
        assertEquals("Card payment overdue: ${inr(2000.0)}", pendingInWords(loan(l.s("id"))!!, inr(2000.0), 1))
        val r = remindersFor(today)
        assertEquals(1.0, r.o("taken").num("overdue"), 0.0)
        assertEquals("card", r.objs("items").first().s("kind"))
        val next = nextPaymentFor(today)
        assertEquals(1, next.overdue); assertTrue(next.late)
        assertEquals(2000.0, next.amount, 0.0)
        // paying the minimum clears the overdue mark
        pay(l, 2000.0, "2026-11-27")
        assertFalse(v(l).b("overdue")); assertEquals(0, nextPaymentFor(today).overdue)
    }

    @Test fun aNewStatementStartsAgain() {
        val l = card()
        pay(l, 1500.0, "2026-11-08"); pay(l, 500.0, "2026-11-09")
        at("2026-12-05T09:00")
        assertNull(updateStatement(l.s("id"), 25000.0, 1250.0, "2026-12-25", "2026-12-05"))
        val w = v(l)
        assertEquals(25000.0, w.num("owed"), 0.0)
        assertEquals(1250.0, w.num("nextDueAmount"), 0.0); assertEquals("2026-12-25", w.s("nextDueDate"))
        assertEquals(0.0, w.num("paidSinceStatement"), 0.0); assertFalse(w.b("paidThisPeriod"))
        assertEquals(2000.0, w.num("paidTotal"), 0.0)
        val old = loan(l.s("id"))!!.objs("statements")
        assertEquals(1, old.size)
        assertEquals(40000.0, old[0].num("outstanding"), 0.0); assertEquals(2000.0, old[0].num("minDue"), 0.0)
        assertEquals("2026-11-25", old[0].s("dueDate")); assertEquals("2026-11-05", old[0].s("date"))

        // a payment dated on the statement day but entered after the update counts; one entered before it did not
        at("2026-12-05T09:01")
        pay(l, 1000.0, "2026-12-05")
        val after = v(l)
        assertEquals(24000.0, after.num("owed"), 0.0); assertEquals(250.0, after.num("nextDueAmount"), 0.0)
        assertEquals(1000.0, after.num("paidSinceStatement"), 0.0)
    }

    @Test fun aPaymentEnteredBeforeTheStatementOnItsDateDoesNotCount() {
        val l = card()
        at("2026-12-05T08:00")
        pay(l, 900.0, "2026-12-05")
        at("2026-12-05T09:00")
        assertNull(updateStatement(l.s("id"), 25000.0, 1250.0, "2026-12-25", "2026-12-05"))
        assertEquals(25000.0, v(l).num("owed"), 0.0)
        assertEquals(1250.0, v(l).num("nextDueAmount"), 0.0)
    }

    @Test fun updateStatementChecksTheFigures() {
        val l = card()
        assertNotNull(updateStatement(l.s("id"), 1000.0, 2000.0, "2026-12-25", "2026-12-05"))
        assertEquals(40000.0, v(l).num("owed"), 0.0)                       // nothing changed
        assertTrue(loan(l.s("id"))!!.objs("statements").isEmpty())
        assertNotNull(updateStatement("nope", 1000.0, 100.0, "2026-12-25", "2026-12-05"))
    }

    @Test fun validation() {
        fun draft(p: Double = 40000.0, min: Double = 2000.0, start: String = "2026-11-05", due: String = "2026-11-25") =
            obj("kind" to "card", "name" to "City Bank card", "principal" to p, "minDue" to min, "startDate" to start, "dueDate" to due)
        assertNull(validateLoan(draft()))
        assertEquals("The minimum due cannot be more than the outstanding amount.", validateLoan(draft(p = 1000.0, min = 2000.0)))
        assertEquals("Please enter the date to pay by.", validateLoan(draft(due = "")))
        assertNull(validateLoan(draft(due = "2026-11-04")))                // an already-past pay-by date is allowed
        assertNull(validateLoan(draft(due = "2026-11-05")))
        assertEquals("The outstanding amount cannot be below zero.", validateLoan(draft(p = -1.0, min = 0.0)))
        assertEquals("The minimum due cannot be below zero.", validateLoan(draft(min = -5.0)))
        assertEquals("Please enter the statement date.", validateLoan(draft(start = "")))
        assertNull(validateLoan(draft(p = 0.0, min = 0.0)))                // paid in full is fine
        assertEquals(validateCard(draft(due = "")), validateLoan(draft(due = "")))
        assertEquals("Please give this entry a name.", validateLoan(draft().also { it["name"] = "" }))
    }

    @Test fun aPayByDateBeforeTheStatementJustMakesItOverdue() {
        val l = card(due = "2026-11-04")
        assertTrue(v(l).b("overdue"))
        assertEquals(2000.0, dueList(today).single().num("amount"), 0.0)
    }

    @Test fun aCardPaidInFullHasNothingDue() {
        val l = card(outstanding = 0.0, minDue = 0.0)
        val w = v(l)
        assertEquals(0.0, w.num("owed"), 0.0); assertEquals("", w.s("nextDueDate"))
        assertTrue(dueList(today).isEmpty())
    }
}
