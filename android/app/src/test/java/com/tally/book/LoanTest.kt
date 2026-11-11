package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LoanTest {
    @Before fun setUp() {
        resetModelState()
        DB = freshDb(); ensureBookShape(DB)
        Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0)
        setMyName("Amma")
    }
    @After fun tearDown() { Clock.fixed = null }

    private val today get() = Clock.today()
    private fun d(s: String) = LocalDate.parse(s)

    private fun bank(emi: Double = 8885.0, start: String = "2026-01-10", dueDay: Int = 5) = saveLoan(obj(
        "kind" to "bank", "name" to "SBI", "principal" to 100000, "rate" to 12, "tenureMonths" to 12, "emi" to emi,
        "startDate" to start, "dueDay" to dueDay))
    private fun hand(rate: Double, unit: String, every: String, start: String = "2026-01-15", principal: Double = 100000.0) = saveLoan(obj(
        "kind" to "hand", "name" to "Ravi", "principal" to principal, "rate" to rate, "rateUnit" to unit,
        "interestEvery" to every, "startDate" to start))
    private fun pay(loan: Obj, date: String, amount: Double, kind: String) = savePayment(obj(
        "loanId" to loan.s("id"), "date" to date, "amount" to amount, "kind" to kind))
    private fun v(l: Obj, on: String = "2026-11-11") = loanView(l, d(on))

    // 100000 at 12%/yr for 12 months: r = 0.01, 1.01^12 = 1.126825, EMI = 100000*0.01*1.126825/0.126825 = 8884.88 -> 8885
    @Test fun emiFormula() {
        assertEquals(8885.0, emiFor(100000.0, 12.0, 12), 0.0)
        assertEquals(1000.0, emiFor(12000.0, 0.0, 12), 0.0)
        val l = saveLoan(obj("kind" to "bank", "name" to "x", "principal" to 100000, "rate" to 12, "tenureMonths" to 12, "emi" to 0,
            "startDate" to "2026-01-10", "dueDay" to 5))
        assertEquals(8885.0, v(l).num("emi"), 0.0)
    }

    // emi given, tenure 0: months to clear 100000 at 1%/month with 8885 = ceil(-ln(1-1000/8885)/ln(1.01)) = ceil(11.99) = 12
    @Test fun tenureDerivedFromEmi() {
        val l = saveLoan(obj("kind" to "bank", "name" to "x", "principal" to 100000, "rate" to 12, "tenureMonths" to 0, "emi" to 8885,
            "startDate" to "2026-01-10", "dueDay" to 5))
        assertEquals(12.0, v(l).num("monthsLeft"), 0.0)
    }

    // After EMI 1: interest 100000*0.01 = 1000, principal 7885, balance 92115.
    // After EMI 2: interest 921.15, principal 7963.85, balance 84151.15.
    @Test fun outstandingAfterEmis() {
        val l = bank()
        assertEquals(100000.0, v(l, "2026-01-20").num("owed"), 0.001)
        pay(l, "2026-02-05", 8885.0, "emi")
        assertEquals(92115.0, v(l).num("owed"), 0.001)
        pay(l, "2026-03-05", 8885.0, "emi")
        val w = v(l)
        assertEquals(84151.15, w.num("owed"), 0.001)
        assertEquals(15848.85, w.num("paidPrincipal"), 0.001)
        assertEquals(1921.15, w.num("paidInterest"), 0.001)
        assertEquals(17770.0, w.num("paidTotal"), 0.001)
    }

    // Prepayment of 10000 after 2 EMIs: 84151.15 - 10000 = 74151.15; interest-only payment changes nothing.
    // Next EMI interest = 741.5115, so 8885 -> principal 8143.4885, balance 66007.6615.
    @Test fun prepaymentAndInterestOnly() {
        val l = bank()
        pay(l, "2026-02-05", 8885.0, "emi"); pay(l, "2026-03-05", 8885.0, "emi")
        pay(l, "2026-03-20", 10000.0, "principal")
        assertEquals(74151.15, v(l).num("owed"), 0.001)
        pay(l, "2026-03-21", 500.0, "interest")
        assertEquals(74151.15, v(l).num("owed"), 0.001)
        pay(l, "2026-04-05", 8885.0, "emi")
        assertEquals(66007.6615, v(l).num("owed"), 0.001)
    }

    @Test fun neverBelowZero() {
        val l = bank()
        pay(l, "2026-02-05", 500000.0, "principal")
        assertEquals(0.0, v(l).num("owed"), 0.0)
        assertEquals("", v(l).s("nextDueDate"))
    }

    // Start 10 Jan, due day 5: first due 5 Feb. After EMI paid in Feb, next is 5 Mar. Today 11 Nov, nothing paid since Feb: due 5 Mar, overdue.
    @Test fun bankNextDueAndOverdue() {
        val l = bank()
        assertEquals("2026-02-05", v(l, "2026-01-20").s("nextDueDate"))
        pay(l, "2026-02-05", 8885.0, "emi")
        val w = v(l, "2026-02-20")
        assertEquals("2026-03-05", w.s("nextDueDate"))
        assertFalse(w.b("overdue")); assertTrue(w.b("paidThisPeriod")); assertFalse(w.b("dueThisMonth"))
        assertEquals(8885.0, w.num("nextDueAmount"), 0.0)
        val late = v(l)
        assertTrue(late.b("overdue")); assertFalse(late.b("paidThisPeriod"))
    }

    // After 1 EMI the balance is 92115 and the EMI 8885, so months left = ceil(-ln(1-921.15/8885)/ln(1.01)) = 11; end = 11 months from the Mar due date minus one: 5 Jan 2027.
    @Test fun monthsLeftAndEndDate() {
        val l = bank()
        pay(l, "2026-02-05", 8885.0, "emi")
        val w = v(l, "2026-02-20")
        assertEquals(11.0, w.num("monthsLeft"), 0.0)
        assertEquals("2027-01-05", w.s("endDate"))
    }

    // Due day 31: February has 28 days in 2027 -> 28 Feb; then back to 31 Mar.
    @Test fun dueDay31InFebruary() {
        val l = bank(start = "2027-01-01", dueDay = 31)
        pay(l, "2027-01-31", 8885.0, "emi")
        assertEquals("2027-02-28", v(l, "2027-02-01").s("nextDueDate"))
        pay(l, "2027-02-28", 8885.0, "emi")
        assertEquals("2027-03-31", v(l, "2027-03-01").s("nextDueDate"))
    }

    // Monthly quoting at 2% a month on 100000 = 2000 a month.
    @Test fun handMonthlyPerMonthQuote() {
        val l = hand(2.0, "month", "month")
        val a = v(l, "2026-01-20")
        assertEquals("2026-02-15", a.s("nextDueDate")); assertEquals(2000.0, a.num("nextDueAmount"), 0.0)
        pay(l, "2026-02-15", 2000.0, "interest")
        assertEquals("2026-03-15", v(l, "2026-02-16").s("nextDueDate"))
        assertEquals(100000.0, v(l).num("owed"), 0.0)
        assertEquals(2000.0, v(l).num("paidInterest"), 0.0)
    }

    // 24% a year, paid monthly = 2% a month = 2000; paid yearly = 24000. Same thing quoted per month: 2% a month yearly = 24000.
    @Test fun handYearQuoteAndYearlyPeriod() {
        assertEquals(2000.0, v(hand(24.0, "year", "month")).num("nextDueAmount"), 0.0)
        assertEquals(24000.0, v(hand(24.0, "year", "year")).num("nextDueAmount"), 0.0)
        assertEquals(24000.0, v(hand(2.0, "month", "year")).num("nextDueAmount"), 0.0)
        assertEquals(2000.0, v(hand(24.0, "", "month")).num("nextDueAmount"), 0.0) // rateUnit defaults to year
    }

    // Principal payment of 40000 leaves 60000; 2% a month = 1200.
    @Test fun handPrincipalReducesInterest() {
        val l = hand(2.0, "month", "month")
        pay(l, "2026-02-15", 40000.0, "principal")
        val w = v(l, "2026-02-16")
        assertEquals(60000.0, w.num("owed"), 0.0)
        assertEquals(1200.0, w.num("nextDueAmount"), 0.0)
        assertEquals(40000.0, w.num("paidPrincipal"), 0.0)
    }

    // Gold loan, yearly interest, started 15 Dec 2025. First due 15 Dec 2026 (year boundary); 9% of 50000 = 4500. Paid on 15 Dec 2026 -> next 15 Dec 2027.
    @Test fun goldAcrossYearBoundary() {
        val l = saveLoan(obj("kind" to "gold", "name" to "Muthoot", "principal" to 50000, "rate" to 9, "grams" to 20, "startDate" to "2025-12-15"))
        val a = v(l, "2026-11-11")
        assertEquals("2026-12-15", a.s("nextDueDate")); assertEquals(4500.0, a.num("nextDueAmount"), 0.0)
        assertFalse(a.b("overdue")); assertFalse(a.b("dueThisMonth"))
        assertEquals(0, dueList(d("2026-11-11")).size)
        assertEquals(1, dueList(d("2026-12-01")).size)
        pay(l, "2026-12-15", 4500.0, "interest")
        val b = v(l, "2026-12-20")
        assertEquals("2027-12-15", b.s("nextDueDate")); assertTrue(b.b("paidThisPeriod"))
        assertEquals("year", b.s("interestEvery"))
    }

    // Hand loan started 31 Jan, monthly: next due is 28 Feb 2027 (day kept at 31, clamped), then 31 Mar.
    @Test fun handDayClamped() {
        val l = hand(2.0, "month", "month", start = "2027-01-31")
        assertEquals("2027-02-28", v(l, "2027-02-01").s("nextDueDate"))
        pay(l, "2027-02-28", 2000.0, "interest")
        assertEquals("2027-03-31", v(l, "2027-03-01").s("nextDueDate"))
    }

    @Test fun closedLoan() {
        val l = hand(2.0, "month", "month")
        closeLoan(l.s("id"), "2026-11-01")
        val w = v(loan(l.s("id"))!!)
        assertEquals(0.0, w.num("owed"), 0.0); assertEquals("", w.s("nextDueDate")); assertFalse(w.b("overdue"))
        assertEquals(0, dueList(today).size)
        assertEquals(0.0, totals(today).num("owed"), 0.0)
        assertEquals("This loan is closed. Reopen it to add a payment.",
            validatePayment(obj("loanId" to l.s("id"), "date" to "2026-11-01", "amount" to 5, "kind" to "interest")))
        reopenLoan(l.s("id"))
        assertEquals(100000.0, v(loan(l.s("id"))!!).num("owed"), 0.0)
    }

    // Today 11 Nov: loan A due 15 Nov (this month), B due 5 Oct (overdue), C paid Nov so due 15 Dec (not listed).
    @Test fun dueListAndTotals() {
        val a = hand(2.0, "month", "month", start = "2026-10-15")
        pay(a, "2026-10-15", 2000.0, "interest")
        val b = bank(start = "2026-08-01", dueDay = 5)
        pay(b, "2026-09-05", 8885.0, "emi")
        val c = hand(1.0, "month", "month", start = "2026-05-20", principal = 50000.0)
        pay(c, "2026-11-01", 500.0, "interest")
        val list = dueList(today)
        assertEquals(listOf("2026-10-05", "2026-11-15"), list.map { it.s("date") })
        assertTrue(list[0].b("overdue")); assertFalse(list[1].b("overdue"))
        assertEquals(1, list.count { it.b("overdue") })
        assertTrue(v(c).b("paidThisPeriod"))
        val t = totals(today)
        assertEquals(8885.0 + 2000.0, t.num("dueThisMonth"), 0.0)
        /* only the 1 Nov payment is in November */
        assertEquals(500.0, t.num("paidThisMonth"), 0.0)
        assertEquals(100000.0 + 50000.0, t.o("byKind").num("hand"), 0.0)
        assertEquals(3.0, t.num("count"), 0.0)
        assertEquals(suggest(a), 2000.0, 0.0)
        assertEquals("emi", suggestPayment(b, today).s("kind"))
        assertEquals(8885.0, suggestPayment(b, today).num("amount"), 0.0)
    }
    private fun suggest(l: Obj) = suggestPayment(l, today).num("amount")

    @Test fun sharedOnlyTotals() {
        val a = hand(2.0, "month", "month"); hand(2.0, "month", "month")
        setLoanPrivate(a.s("id"), true)
        assertEquals(200000.0, totals(today).num("owed"), 0.0)
        assertEquals(100000.0, totals(today, sharedOnly = true).num("owed"), 0.0)
    }

    // ---- records ----

    @Test fun tombstonesAreHiddenAndCascade() {
        val l = hand(2.0, "month", "month")
        val p = pay(l, "2026-02-15", 2000.0, "interest")
        fileAdded(obj("id" to "f1", "ownerType" to "loan", "ownerId" to l.s("id"), "name" to "note.pdf", "size" to 10))
        fileAdded(obj("id" to "f2", "ownerType" to "payment", "ownerId" to p.s("id"), "name" to "slip.jpg", "size" to 10))
        assertEquals(1, filesOf("loan", l.s("id")).size)
        DB.oo("sync").oo("dirty").clear()
        deleteLoan(l.s("id"))
        assertNull(loan(l.s("id"))); assertTrue(paymentsOf(l.s("id")).isEmpty())
        assertTrue(filesOf("loan", l.s("id")).isEmpty()); assertTrue(filesOf("payment", p.s("id")).isEmpty())
        assertEquals(setOf(l.s("id"), p.s("id"), "f1", "f2"), DB.oo("sync").oo("dirty").keys)
        val tp = DB.objs("payments").single()
        assertEquals(setOf("id", "deleted", "updatedAt", "updatedBy", "loanId"), tp.keys)
        val tf = DB.objs("files").first { it.s("id") == "f2" }
        assertEquals(setOf("id", "deleted", "updatedAt", "updatedBy", "ownerType", "ownerId"), tf.keys)
        assertEquals(setOf("id", "deleted", "updatedAt", "updatedBy"), DB.objs("loans").single().keys)
        assertEquals(0.0, totals(today).num("owed"), 0.0)
    }

    @Test fun deleteOnePayment() {
        val l = hand(2.0, "month", "month")
        val p1 = pay(l, "2026-02-15", 2000.0, "interest"); pay(l, "2026-03-15", 2000.0, "interest")
        deletePayment(p1.s("id"))
        assertEquals(1, paymentsOf(l.s("id")).size)
        assertEquals(2000.0, v(l).num("paidInterest"), 0.0)
        assertEquals(2, DB.objs("payments").size)
    }

    @Test fun paymentsNewestFirst() {
        val l = hand(2.0, "month", "month")
        pay(l, "2026-03-15", 1.0, "interest"); pay(l, "2026-05-15", 3.0, "interest"); pay(l, "2026-04-15", 2.0, "interest")
        assertEquals(listOf(3.0, 2.0, 1.0), paymentsOf(l.s("id")).map { it.num("amount") })
    }

    @Test fun privacyResolvesThroughTheLoan() {
        val l = hand(2.0, "month", "month")
        val p = pay(l, "2026-02-15", 2000.0, "interest")
        fileAdded(obj("id" to "f2", "ownerType" to "payment", "ownerId" to p.s("id"), "name" to "slip"))
        fileAdded(obj("id" to "f1", "ownerType" to "loan", "ownerId" to l.s("id"), "name" to "doc"))
        val f2 = DB.objs("files").first { it.s("id") == "f2" }; val f1 = DB.objs("files").first { it.s("id") == "f1" }
        assertFalse(isPrivate(DB, l)); assertFalse(isPrivate(DB, p)); assertFalse(isPrivate(DB, f2))
        setLoanPrivate(l.s("id"), true)
        assertTrue(isPrivate(DB, loan(l.s("id"))!!)); assertTrue(isPrivate(DB, p)); assertTrue(isPrivate(DB, f1)); assertTrue(isPrivate(DB, f2))
        deleteLoan(l.s("id")) // tombstones still resolve as private
        assertTrue(isPrivate(DB, DB.objs("payments").single()))
        assertTrue(isPrivate(DB, DB.objs("files").first { it.s("id") == "f2" }))
    }

    @Test fun setLoanPrivateMarksEverythingDirty() {
        val l = hand(2.0, "month", "month")
        val p = pay(l, "2026-02-15", 2000.0, "interest")
        fileAdded(obj("id" to "f1", "ownerType" to "payment", "ownerId" to p.s("id"), "name" to "slip"))
        fileAdded(obj("id" to "f0", "ownerType" to "loan", "ownerId" to l.s("id"), "name" to "doc"))
        val other = hand(1.0, "month", "month")
        DB.oo("sync").oo("dirty").clear()
        setLoanPrivate(l.s("id"), true)
        assertEquals(setOf(l.s("id"), p.s("id"), "f1", "f0"), DB.oo("sync").oo("dirty").keys)
        assertFalse(DB.oo("sync").oo("dirty").containsKey(other.s("id")))
        DB.oo("sync").oo("dirty").clear()
        setLoanPrivate(l.s("id"), false)
        assertEquals(4, DB.oo("sync").oo("dirty").size)
        assertFalse(loan(l.s("id"))!!.b("private"))
    }

    @Test fun savingStampsAndReplaces() {
        val l = hand(2.0, "month", "month")
        assertEquals(myId(), l.s("by")); assertEquals(myId(), l.s("updatedBy"))
        assertTrue(l.num("at") > 0); assertTrue(DB.oo("sync").oo("dirty").b(l.s("id")))
        val edited = l.copyObj(); edited["name"] = "Ravi Kumar"
        Clock.fixed = LocalDateTime.of(2026, 11, 12, 9, 0)
        val saved = saveLoan(edited)
        assertEquals(1, loans().size)
        assertEquals("Ravi Kumar", loan(l.s("id"))!!.s("name"))
        assertEquals(l.num("at"), saved.num("at"), 0.0)
        assertTrue(saved.num("updatedAt") > l.num("updatedAt"))
    }

    @Test fun namesAndMembers() {
        assertEquals("Amma", memberName(myId()))
        assertEquals("Someone", memberName("nobody"))
        assertEquals("Someone", memberName(null))
        assertTrue(DB.objs("members").isEmpty()) // no family yet
        DB["family"] = obj("id" to "fam", "key" to "k", "name" to "Home")
        setMyName("Amma K")
        assertEquals("Amma K", DB.objs("members").single().s("name"))
        assertTrue(DB.oo("sync").oo("dirty").b(myId()))
        setMyName("Amma Kumari")
        assertEquals(1, DB.objs("members").size)
        assertEquals("Amma Kumari", memberName(myId()))
        DB.objs("members").single()["name"] = "Nanna"; DB.objs("members").single()["id"] = "m2"
        assertEquals("Nanna", memberName("m2"))
    }

    @Test fun ensureShape() {
        val db = obj("v" to 1, "extra" to arr(1, 2))
        ensureBookShape(db)
        for (k in listOf("loans", "payments", "files", "members")) assertTrue(db.a(k)!!.isEmpty())
        assertEquals(0.0, db.o("sync").num("cursor"), 0.0)
        assertTrue(db.o("sync").o("dirty")!!.isEmpty()); assertTrue(db.o("sync").o("blobsUp")!!.isEmpty())
        assertEquals(2, db.a("extra")!!.size)
    }

    @Test fun validation() {
        assertNotNull(validateLoan(obj("kind" to "hand", "principal" to 5, "startDate" to "2026-01-01")))
        assertNotNull(validateLoan(obj("kind" to "hand", "name" to "x", "principal" to 0, "startDate" to "2026-01-01")))
        assertNotNull(validateLoan(obj("kind" to "bank", "name" to "x", "principal" to 5, "startDate" to "2026-01-01", "dueDay" to 5)))
        assertNotNull(validateLoan(obj("kind" to "bank", "name" to "x", "principal" to 5, "emi" to 1, "startDate" to "2026-01-01", "dueDay" to 40)))
        assertNull(validateLoan(obj("kind" to "bank", "name" to "x", "principal" to 5, "emi" to 1, "startDate" to "2026-01-01", "dueDay" to 5)))
        assertNull(validateLoan(obj("kind" to "gold", "name" to "x", "principal" to 5, "rate" to 9, "startDate" to "2026-01-01")))
        val l = hand(2.0, "month", "month")
        val ok = obj("loanId" to l.s("id"), "date" to "2026-11-11", "amount" to 2000, "kind" to "interest")
        assertNull(validatePayment(ok))
        assertNotNull(validatePayment(ok.copyObj().also { it["date"] = "2026-11-12" }))
        assertNotNull(validatePayment(ok.copyObj().also { it["amount"] = 0 }))
        assertNotNull(validatePayment(ok.copyObj().also { it["kind"] = "emi" })) // not a bank loan
        assertNotNull(validatePayment(ok.copyObj().also { it["loanId"] = "nope" }))
    }

    @Test fun labels() {
        assertEquals("Bank loan", kindLabel("bank")); assertEquals("From a person", kindLabel("hand")); assertEquals("Gold loan", kindLabel("gold"))
        assertEquals("EMI", payKindLabel("emi")); assertEquals("Interest", payKindLabel("interest")); assertEquals("Principal", payKindLabel("principal"))
    }
}
