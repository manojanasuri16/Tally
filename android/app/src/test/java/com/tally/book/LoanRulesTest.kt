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
 * The loan rules added for flat interest, instalment frequency, rate changes, pending interest and instalments, loans against
 * something, renewals and reminders (docs/DESIGN.md "Loan rules"). Today is pinned to Wednesday 11 Nov 2026.
 */
class LoanRulesTest {
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
    private fun bank(vararg extra: Pair<String, Any?>) = saveLoan(obj("kind" to "bank", "name" to "City Bank", "principal" to 100000, "rate" to 12,
        "tenure" to 12, "startDate" to "2026-01-10", "dueDay" to 5).also { o -> extra.forEach { (k, x) -> o[k] = norm(x) } })
    private fun hand(principal: Double, rate: Double, start: String, vararg extra: Pair<String, Any?>) = saveLoan(obj("kind" to "hand", "name" to "Mr Smith",
        "principal" to principal, "rate" to rate, "rateUnit" to "month", "interestEvery" to "month", "startDate" to start).also { o -> extra.forEach { (k, x) -> o[k] = norm(x) } })

    // ---- flat vs reducing ----

    // 1,00,000 at 12% a year for 12 months.
    // Reducing: r = 1%, EMI = 100000 × 0.01 × 1.01^12 / (1.01^12 − 1) = 8,884.88 → 8,885 (whole rupees).
    // Flat: interest = 100000 × 12% × 1 year = 12,000; instalment = 1,12,000 / 12 = 9,333.33 → 9,333; each has 1,000 interest
    // (100000 × 12% / 12) and 8,333 principal. After 11: owed 100000 − 11 × 8,333 = 8,337, so the 12th is 8,337 + 1,000 = 9,337
    // (the last one evens out the rounding). Total paid 11 × 9,333 + 9,337 = 1,12,000.
    @Test fun flatAgainstReducingEmi() {
        assertEquals(8885.0, emiFor(100000.0, 12.0, 12), 0.0)
        assertEquals(9333.0, flatEmiFor(100000.0, 12.0, 12), 0.0)
        val red = bank(); val flat = bank("interestMethod" to "flat")
        assertEquals(8885.0, v(red, "2026-01-20").num("emi"), 0.0)
        val f = v(flat, "2026-01-20")
        assertEquals(9333.0, f.num("emi"), 0.0); assertEquals(1000.0, f.num("flatInterestPart"), 1e-9)
        assertEquals("flat", f.s("interestMethod")); assertEquals(12.0, f.num("instalmentsLeft"), 0.0)
        assertEquals("2027-01-05", f.s("endDate"))
        pay(flat, "2026-02-05", 9333.0, "emi")
        val one = v(flat, "2026-02-06")
        assertEquals(91667.0, one.num("owed"), 1e-6); assertEquals(1000.0, one.num("paidInterest"), 1e-6); assertEquals(8333.0, one.num("paidPrincipal"), 1e-6)
        for (m in 3..12) pay(flat, "2026-%02d-05".format(m), 9333.0, "emi")
        val eleven = v(flat, "2026-12-06")
        assertEquals(8337.0, eleven.num("owed"), 1e-6)
        assertEquals("2027-01-05", eleven.s("nextDueDate")); assertEquals(9337.0, eleven.num("nextDueAmount"), 1e-6)
        assertEquals(1.0, eleven.num("instalmentsLeft"), 0.0)
        pay(flat, "2027-01-05", 9337.0, "emi")
        val done = v(flat, "2027-01-06")
        assertEquals(0.0, done.num("owed"), 1e-6); assertEquals(12000.0, done.num("paidInterest"), 1e-6); assertEquals(112000.0, done.num("paidTotal"), 0.0)
    }

    // Flat prepayment: after 2 instalments (owed 1,00,000 − 2 × 8,333 = 83,334) a part-payment of 20,000 leaves 63,334. The instalment
    // stays 9,333 with 1,000 interest in each, so ceil(63,334 / 8,333) = 8 more are needed (the 8th: 63,334 − 7 × 8,333 = 5,003, plus
    // 1,000 = 6,003) instead of 10; the interest of the 2 instalments no longer needed is not charged.
    @Test fun flatPrepaymentEndsTheLoanSooner() {
        val flat = bank("interestMethod" to "flat")
        pay(flat, "2026-02-05", 9333.0, "emi"); pay(flat, "2026-03-05", 9333.0, "emi")
        pay(flat, "2026-03-20", 20000.0, "principal")
        val w = v(flat, "2026-03-21")
        assertEquals(63334.0, w.num("owed"), 1e-6)
        assertEquals(8.0, w.num("instalmentsLeft"), 0.0); assertEquals("2026-11-05", w.s("endDate"))
        assertEquals(9333.0, w.num("nextDueAmount"), 1e-6)
    }

    // Interest-only, 1,00,000 at 2% a month from 10 Sep; 40,000 returned on 1 Oct.
    // Reducing: period to 10 Oct started on 1,00,000 → 2,000; period to 10 Nov started 10 Oct on 60,000 → 1,200; from now 1,200.
    // Flat: both 2,000 (on the full 1,00,000), and it stays 2,000 until the loan is closed.
    @Test fun flatInterestOnlyStaysOnTheOriginalAmount() {
        val red = hand(100000.0, 2.0, "2026-09-10")
        val flat = hand(100000.0, 2.0, "2026-09-10", "interestMethod" to "flat")
        for (l in listOf(red, flat)) pay(l, "2026-10-01", 40000.0, "principal")
        val r = v(red); val f = v(flat)
        assertEquals(60000.0, r.num("owed"), 0.0); assertEquals(60000.0, f.num("owed"), 0.0)
        assertEquals(3200.0, r.num("interestPending"), 0.0); assertEquals(4000.0, f.num("interestPending"), 0.0)
        assertEquals(1200.0, r.num("periodInterest"), 0.0); assertEquals(2000.0, f.num("periodInterest"), 0.0)
        assertEquals(2000.0, suggestPayment(loan(flat.s("id"))!!, today).num("amount"), 0.0)
        assertEquals("2% a month, on the full amount", rateWithMethodInWords(2.0, "month", "flat"))
        assertEquals("12% a year, on the amount still owed", rateWithMethodInWords(12.0, "year", "reducing"))
        assertEquals("On the amount still owed", interestMethodLabel("reducing")); assertEquals("On the full amount", interestMethodLabel("flat"))
    }

    // ---- frequency ----

    // Weekly microfinance loan: 50,000 at 20% flat for 52 weeks. Interest 50,000 × 20% × 1 year = 10,000; instalment
    // 60,000 / 52 = 1,153.85 → 1,154 (192.31 interest, 961.69 principal). Started Tuesday 27 Oct: due every Tuesday from 3 Nov.
    // November: 3, 10, 17, 24 Nov. Today 11 Nov with 3 Nov paid: 10 Nov is pending, 17 and 24 to come: 3 × 1,154 = 3,462.
    // December has five Tuesdays (1, 8, 15, 22, 29): 5 × 1,154 = 5,770.
    @Test fun weeklyLoanSumsEveryInstalmentOfTheMonth() {
        val l = bank("name" to "Group loan", "principal" to 50000, "rate" to 20, "tenure" to 52, "interestMethod" to "flat",
            "frequency" to "week", "startDate" to "2026-10-27", "dueDay" to 0)
        assertNull(validateLoan(loan(l.s("id"))!!))   // dueDay does not matter for a weekly loan
        val a = v(l, "2026-10-28")
        assertEquals(1154.0, a.num("emi"), 0.0); assertEquals("2026-11-03", a.s("nextDueDate")); assertEquals(2.0, a.num("weekday"), 0.0)
        assertEquals(52.0, a.num("instalmentsLeft"), 0.0); assertEquals("2027-10-26", a.s("endDate"))
        assertEquals("Every Tuesday", instalmentDayInWords(l)); assertEquals("52 weeks", instalmentsInWords(52, "week"))
        pay(l, "2026-11-03", 1154.0, "emi")
        val row = dueList(today).single()
        assertEquals("2026-11-10", row.s("date")); assertEquals(3462.0, row.num("amount"), 1e-6)
        assertEquals(1154.0, row.num("pending"), 1e-6); assertEquals(1.0, row.num("pendingCount"), 0.0); assertEquals(3.0, row.num("count"), 0.0)
        assertEquals(2308.0, row.num("upcoming"), 1e-6)
        assertEquals(3462.0, totals(today).num("dueThisMonth"), 1e-6)
        assertEquals(50000.0 - 961.6923, v(l).num("owed"), 0.001)
        for (day in listOf("10", "17", "24")) pay(l, "2026-11-$day", 1154.0, "emi")
        val dec = d("2026-12-01")
        assertEquals(5770.0, totals(dec).num("dueThisMonth"), 1e-6)
        assertEquals(5.0, dueList(dec).single().num("count"), 0.0)
        assertEquals(1154.0, suggestPayment(loan(l.s("id"))!!, dec).num("amount"), 1e-6)
        assertTrue(v(l, "2026-11-25").b("paidThisPeriod"))   // every November instalment paid
    }

    // Fortnightly: 1,00,000 at 13% a year, 26 instalments. r = 13% / 26 = 0.5%; EMI = 100000 × 0.005 × 1.005^26 / (1.005^26 − 1)
    // = 4,111.16 → 4,111. Started Friday 2 Oct: due 16 Oct, 30 Oct, 13 Nov, 27 Nov. Two paid: interest 500 then 0.5% of 96,389
    // = 481.945, so owed 1,00,000 − 3,611 − 3,629.055 = 92,759.945; November still has 13 and 27 Nov: 8,222.
    @Test fun fortnightlyLoan() {
        assertEquals(4111.0, emiFor(100000.0, 13.0, 26, 26), 0.0)
        val l = bank("rate" to 13, "tenure" to 26, "frequency" to "fortnight", "startDate" to "2026-10-02")
        pay(l, "2026-10-16", 4111.0, "emi"); pay(l, "2026-10-30", 4111.0, "emi")
        val w = v(l)
        assertEquals(92759.945, w.num("owed"), 0.001); assertEquals("2026-11-13", w.s("nextDueDate"))
        assertEquals(8222.0, w.num("monthDue"), 1e-6); assertEquals(0.0, w.num("emisPending"), 0.0)
        assertEquals(5.0, w.num("weekday"), 0.0); assertEquals("Every other Friday", instalmentDayInWords(l))
        assertEquals(24.0, w.num("instalmentsLeft"), 0.0)
        assertEquals("fortnight", w.s("frequency")); assertEquals(26.0, w.num("perYear"), 0.0)
    }

    // Daily collection: 10,000 at 36% flat for 100 days. Interest per day 10,000 × 36% / 365 = 9.863; instalment 100 + 9.863
    // = 109.86 → 110. Started 1 Nov, so due every day from 2 Nov. Ten paid (2..11 Nov): owed 10,000 − 10 × 100.137 = 8,998.63;
    // the rest of November (12..30) is 19 × 110 = 2,090; 90 instalments left.
    @Test fun dailyLoan() {
        val l = bank("principal" to 10000, "rate" to 36, "tenure" to 100, "interestMethod" to "flat", "frequency" to "day", "startDate" to "2026-11-01")
        for (day in 2..11) pay(l, "2026-11-%02d".format(day), 110.0, "emi")
        val w = v(l)
        assertEquals(110.0, w.num("emi"), 0.0)
        assertEquals(8998.63, w.num("owed"), 0.01)
        assertEquals("2026-11-12", w.s("nextDueDate")); assertEquals(2090.0, w.num("monthDue"), 1e-6); assertEquals(19.0, w.num("monthDueCount"), 0.0)
        assertEquals(90.0, w.num("instalmentsLeft"), 0.0); assertEquals("2027-02-09", w.s("endDate"))
        assertEquals("Every day", frequencyLabel("day")); assertEquals("Every day", instalmentDayInWords(l))
    }

    // ---- rate changes ----

    // 1,00,000 at 12%, EMI 8,885, six EMIs paid (5 Feb .. 5 Jul): owed 51,491.36, six left, the last on 5 Jan 2027.
    // From 10 Jul the rate is 18% (1.5% a month). EMI kept: the 7th instalment's interest is 51,491.36 × 1.5% = 772.37 (it would have
    // been 514.91), and 7 instalments are needed (six of 8,885 and a last of 967.62), so the loan ends 5 Feb 2027.
    // EMI changed to 9,050: six instalments (the last 8,975.45), ending 5 Jan 2027 as before.
    // The six instalments before 10 Jul are not recomputed: owed is 51,491.36 either way.
    @Test fun rateChangeKeepingOrChangingTheEmi() {
        val l = bank("emi" to 8885)
        for (m in 2..7) pay(l, "2026-%02d-05".format(m), 8885.0, "emi")
        val before = v(l, "2026-07-20")
        assertEquals(51491.36, before.num("owed"), 0.01); assertEquals(6.0, before.num("instalmentsLeft"), 0.0); assertEquals("2027-01-05", before.s("endDate"))
        assertNull(addRateChange(l.s("id"), "2026-07-10", 18.0))
        val kept = v(l, "2026-07-20")
        assertEquals(51491.36, kept.num("owed"), 0.01)
        assertEquals(18.0, kept.num("currentRate"), 0.0); assertEquals(8885.0, kept.num("emi"), 0.0)
        assertEquals(7.0, kept.num("instalmentsLeft"), 0.0); assertEquals("2027-02-05", kept.s("endDate"))
        assertTrue(DB.oo("sync").oo("dirty").b(l.s("id")))
        val seventh = pay(l, "2026-08-05", 8885.0, "emi")
        assertEquals(51491.36 - (8885 - 772.37), v(l, "2026-08-06").num("owed"), 0.01)
        assertEquals(12.0, v(l, "2026-07-01").num("currentRate"), 0.0)   // the rate in force on a day before the change
        deletePayment(seventh.s("id"))

        assertNull(addRateChange(l.s("id"), "2026-07-10", 18.0, 9050.0))   // same date: replaces the first
        assertEquals(1, loan(l.s("id"))!!.objs("rateChanges").size)
        val changed = v(l, "2026-07-20")
        assertEquals(9050.0, changed.num("emi"), 0.0); assertEquals(8885.0, changed.num("startEmi"), 0.0)
        assertEquals(6.0, changed.num("instalmentsLeft"), 0.0); assertEquals("2027-01-05", changed.s("endDate"))
        assertEquals(9050.0, changed.num("nextDueAmount"), 0.0)

        val id = loan(l.s("id"))!!.objs("rateChanges").single().s("id")
        assertTrue(removeRateChange(l.s("id"), id)); assertFalse(removeRateChange(l.s("id"), id))
        assertEquals(12.0, v(l, "2026-08-06").num("currentRate"), 0.0)
    }

    @Test fun rateChangeChecks() {
        val l = bank("emi" to 8885)
        assertEquals("The new rate must start after the loan started.", addRateChange(l.s("id"), "2026-01-10", 10.0))
        assertEquals("Please enter the date the new rate starts.", addRateChange(l.s("id"), "", 10.0))
        assertEquals("The interest rate cannot be below zero.", addRateChange(l.s("id"), "2026-05-01", -1.0))
        assertEquals("Please enter the new EMI, or leave it empty to keep the EMI.", addRateChange(l.s("id"), "2026-05-01", 10.0, 0.0))
        val flat = bank("interestMethod" to "flat")
        assertEquals("A new rate can only be set on a loan whose interest is on the amount still owed.", addRateChange(flat.s("id"), "2026-05-01", 10.0))
        assertEquals("Only a bank loan can have a new rate.", addRateChange(hand(1000.0, 2.0, "2026-01-01").s("id"), "2026-05-01", 10.0))
        assertTrue(loan(l.s("id"))!!.objs("rateChanges").isEmpty())
    }

    // ---- pending ----

    // 60,000 at 2% a month from 5 Aug: 1,200 due 5 Sep, 5 Oct, 5 Nov. Nothing paid by 11 Nov: 3,600 pending (3 months), oldest 5 Sep.
    // 1,500 paid: covers 5 Sep (1,200) and 300 of 5 Oct, so 900 + 1,200 = 2,100 is pending over 2 months; next is 5 Oct, 900 left on it.
    // 2,100 more: caught up, next 5 Dec, paid for this month. What is owed stays 60,000 throughout.
    @Test fun missedInterestPilesUpAndIsPaidOldestFirst() {
        val l = hand(60000.0, 2.0, "2026-08-05")
        val a = v(l)
        assertEquals(3600.0, a.num("interestPending"), 0.0); assertEquals(3.0, a.num("periodsPending"), 0.0)
        assertEquals("2026-09-05", a.s("nextDueDate")); assertTrue(a.b("overdue")); assertEquals(60000.0, a.num("owed"), 0.0)
        assertEquals("Interest pending: ₹3,600 (3 months)", pendingInWords(l, inr(a.num("interestPending")), a.num("periodsPending").toInt()))
        assertEquals(1200.0, suggestPayment(loan(l.s("id"))!!, today).num("amount"), 0.0)   // one period; the pending part is the person's call
        pay(l, "2026-11-11", 1500.0, "interest")
        val b = v(l)
        assertEquals(2100.0, b.num("interestPending"), 0.0); assertEquals(2.0, b.num("periodsPending"), 0.0)
        assertEquals("2026-10-05", b.s("nextDueDate")); assertEquals(900.0, b.num("nextDueAmount"), 0.0)
        val row = dueList(today).single()
        assertEquals(2100.0, row.num("amount"), 0.0); assertEquals(2100.0, row.num("pending"), 0.0); assertEquals(2.0, row.num("pendingCount"), 0.0)
        pay(l, "2026-11-11", 2100.0, "interest")
        val c = v(l)
        assertEquals(0.0, c.num("interestPending"), 0.0); assertEquals("2026-12-05", c.s("nextDueDate")); assertFalse(c.b("overdue"))
        assertTrue(c.b("paidThisPeriod")); assertTrue(dueList(today).isEmpty()); assertEquals(60000.0, c.num("owed"), 0.0)
    }

    // Yearly interest: 50,000 gold at 9% a year from 15 Dec 2024 → 4,500 due 15 Dec 2025, missed; the next falls 15 Dec 2026.
    @Test fun yearlyPendingCountsYears() {
        val g = saveLoan(obj("kind" to "gold", "name" to "Gold", "principal" to 50000, "rate" to 9, "startDate" to "2024-12-15"))
        val w = v(g)
        assertEquals(4500.0, w.num("interestPending"), 0.0); assertEquals(1.0, w.num("periodsPending"), 0.0)
        assertEquals("Interest pending: ₹4,500 (1 year)", pendingInWords(g, inr(4500.0), 1))
    }

    // Instalments: 8,885 paid 5 Feb (covers the first), then 4,000 on 5 Mar: 4,885 is still to pay on the 5 Mar instalment.
    // On 10 Apr the 5 Mar (4,885) and 5 Apr (8,885) instalments are pending: 13,770 over 2. Paying the 4,885 completes the 5 Mar
    // instalment, and interest is charged once for it: owed is then 84,151.15, as after two whole EMIs.
    @Test fun emiPendingAndSplitInstalments() {
        val l = bank("emi" to 8885)
        pay(l, "2026-02-05", 8885.0, "emi"); pay(l, "2026-03-05", 4000.0, "emi")
        val w = v(l, "2026-04-10")
        assertEquals("2026-03-05", w.s("nextDueDate")); assertEquals(4885.0, w.num("nextDueAmount"), 1e-6)
        assertEquals(2.0, w.num("emisPending"), 0.0); assertEquals(13770.0, w.num("amountPending"), 1e-6)
        assertEquals("Instalments pending: ₹13,770 (2)", pendingInWords(l, inr(w.num("amountPending")), 2))
        assertEquals(4885.0, suggestPayment(loan(l.s("id"))!!, d("2026-04-10")).num("amount"), 1e-6)
        pay(l, "2026-04-10", 4885.0, "emi")
        assertEquals(84151.15, v(l, "2026-04-11").num("owed"), 0.001)
        assertEquals(1.0, v(l, "2026-04-11").num("emisPending"), 0.0)
    }

    @Test fun noInterestLoansAreNeverPendingOrOverdue() {
        val l = hand(10000.0, 0.0, "2025-01-01")
        val w = v(l)
        assertEquals(0.0, w.num("interestPending"), 0.0); assertFalse(w.b("overdue")); assertEquals("", w.s("nextDueDate"))
        val crop = saveLoan(obj("kind" to "gold", "pledge" to "none", "name" to "Crop loan", "principal" to 50000, "rate" to 0, "startDate" to "2025-06-01"))
        assertTrue(isNoInterest(crop)); assertTrue(v(crop).b("noInterest")); assertTrue(dueList(today).isEmpty())
    }

    // ---- loans against something ----

    @Test fun pledgeLabelsAndTitles() {
        val titles = PLEDGES.associateWith { securedTitle(it) }
        assertEquals(mapOf("gold" to "Gold loan", "lic" to "Loan against LIC policy", "fd" to "Loan against FD", "land" to "Loan against land papers",
            "none" to "Crop / other loan", "other" to "Loan against something else"), titles)
        assertEquals(listOf("Gold", "LIC policy", "Fixed deposit", "Land papers", "Nothing pledged", "Something else"), PLEDGES.map { pledgeLabel(it) })
        assertEquals("Loan against something", kindLabel("gold"))
        val lic = saveLoan(obj("kind" to "gold", "pledge" to "lic", "name" to "Policy", "principal" to 20000, "rate" to 10, "startDate" to "2026-01-01"))
        assertEquals("Loan against LIC policy", loanKindLabel(lic))
        assertEquals("Gold loan", loanKindLabel(obj("kind" to "gold")))   // pledge defaults to gold
        assertEquals("lic", v(lic).s("pledge")); assertEquals(0.0, v(lic).num("grams"), 0.0)
    }

    @Test fun somethingElseIsNamedByThePerson() {
        assertEquals("Silver", pledgeLabel("other", " Silver "))
        assertEquals("Loan against Silver", securedTitle("other", "Silver"))
        val o = saveLoan(obj("kind" to "gold", "pledge" to "other", "pledgeText" to "  Silver  ", "name" to "Bangles", "principal" to 20000, "rate" to 10, "startDate" to "2026-01-01"))
        assertEquals("Loan against Silver", loanKindLabel(o))
        assertEquals("Loan against Silver", loanKindLabel(JsonCodec.parseObj(JsonCodec.stringify(o))))   // survives a save and reload
        assertEquals("other", v(o).s("pledge")); assertEquals("Silver", v(o).s("pledgeText")); assertEquals(0.0, v(o).num("grams"), 0.0)
        assertEquals("Silver", pledgeLabel(v(o).s("pledge"), v(o).s("pledgeText")))
        assertEquals("", pledgeTextOf(obj("kind" to "gold", "pledge" to "lic", "pledgeText" to "Silver")))
    }

    // Gold loan, 50,000 at 12% a year, interest monthly (500), from 15 Dec 2025, repay or renew by 30 Nov 2026. All interest paid
    // to 15 Nov (11 × 500). On 11 Nov the 50,000 shows as a principal row on 30 Nov, not overdue. Renewed on 11 Nov to 30 Nov 2027:
    // the row goes, the history keeps the old and new dates. A loan whose date has passed is overdue while anything is owed.
    @Test fun goldRepayByAndRenewal() {
        val g = saveLoan(obj("kind" to "gold", "name" to "Gold", "principal" to 50000, "rate" to 12, "interestEvery" to "month",
            "startDate" to "2025-12-15", "repayBy" to "2026-11-30", "grams" to 20))
        pay(g, "2026-11-10", 5500.0, "interest")
        val rows = dueList(today)
        assertEquals(listOf("principal"), rows.map { it.s("kind") })
        assertEquals(50000.0, rows[0].num("amount"), 0.0); assertEquals("2026-11-30", rows[0].s("date")); assertFalse(rows[0].b("overdue"))
        assertTrue(v(g).b("repayDue")); assertFalse(v(g).b("repayOverdue")); assertEquals(20.0, v(g).num("grams"), 0.0)
        DB.oo("sync").oo("dirty").clear()
        assertEquals("The new date must be after the day it was renewed.", renewLoan(g.s("id"), "2026-11-11", "2026-11-11"))
        assertNull(renewLoan(g.s("id"), "2027-11-30", "2026-11-11"))
        assertTrue(dueList(today).isEmpty())
        val w = v(g)
        assertEquals("2027-11-30", w.s("repayBy")); assertEquals(1.0, w.num("renewals"), 0.0); assertEquals("2026-11-11", w.s("lastRenewal"))
        val r = loan(g.s("id"))!!.objs("renewals").single()
        assertEquals("2026-11-30", r.s("from")); assertEquals("2027-11-30", r.s("to")); assertEquals(myId(), r.s("by"))
        assertTrue(DB.oo("sync").oo("dirty").b(g.s("id")))
        val late = saveLoan(obj("kind" to "gold", "name" to "Old", "principal" to 30000, "rate" to 0, "startDate" to "2025-10-01", "repayBy" to "2026-10-01"))
        assertTrue(v(late).b("repayOverdue")); assertTrue(dueList(today).single().b("overdue"))
        assertEquals("Only an open gold or hand loan can be renewed.", renewLoan(bank().s("id"), "2027-01-01", "2026-11-11"))
    }

    // Renewing does not touch interest: pending interest stays pending.
    @Test fun renewalCarriesPendingInterest() {
        val g = saveLoan(obj("kind" to "gold", "name" to "Gold", "principal" to 10000, "rate" to 24, "interestEvery" to "month",
            "startDate" to "2026-08-11", "repayBy" to "2026-11-11"))
        assertEquals(400.0, v(g).num("interestPending"), 0.0)   // 200 due 11 Sep and 11 Oct; 11 Nov is today, not yet pending
        assertNull(renewLoan(g.s("id"), "2027-11-11", "2026-11-11"))
        assertEquals(400.0, v(g).num("interestPending"), 0.0)
        assertEquals(600.0, dueList(today).single().num("amount"), 0.0)   // 400 pending + 200 due today
    }

    // ---- reminders ----

    @Test fun remindersForTodayTomorrowOverdueTakenAndGiven() {
        hand(50000.0, 2.0, "2026-10-11")                                   // taken: 1,000 due today
        hand(30000.0, 2.0, "2026-10-12")                                   // taken: 600 due tomorrow
        hand(10000.0, 2.0, "2026-09-01")                                   // taken: 200 on 1 Oct and 1 Nov, both overdue
        hand(20000.0, 2.0, "2026-10-12", "direction" to "given")          // given: 400 expected tomorrow
        hand(5000.0, 0.0, "2026-10-01", "direction" to "given", "repayBy" to "2026-11-11")   // given: 5,000 back today
        val closed = hand(9000.0, 2.0, "2026-10-11"); closeLoan(closed.s("id"), "2026-11-01")
        val r0 = remindersFor(today, 0)
        assertEquals(obj("overdue" to 1, "today" to 1, "soon" to 0), r0.o("taken"))
        assertEquals(obj("overdue" to 0, "today" to 1, "soon" to 0), r0.o("given"))
        assertTrue(r0.b("any"))
        val overdue = r0.objs("items").single { it.s("state") == "overdue" }
        assertEquals(400.0, overdue.num("amount"), 0.0); assertEquals(2.0, overdue.num("count"), 0.0); assertEquals("2026-10-01", overdue.s("date"))
        assertEquals(-41.0, overdue.num("days"), 0.0)
        val back = r0.objs("items").single { it.b("given") }
        assertEquals("principal", back.s("kind")); assertEquals(5000.0, back.num("amount"), 0.0)
        val r1 = remindersFor(today, 1)
        assertEquals(1.0, r1.o("taken").num("soon"), 0.0); assertEquals(1.0, r1.o("given").num("soon"), 0.0)
        assertEquals(1.0, r1.objs("items").single { it.s("state") == "soon" && !it.b("given") }.num("days"), 0.0)
        assertEquals(3.0, remindersFor(today, 9).num("horizon"), 0.0)
        assertFalse(remindersFor(d("2026-01-01"), 0).b("any"))   // before any of them started
    }

    // ---- validation ----

    @Test fun validationOfTheNewFields() {
        fun b(vararg extra: Pair<String, Any?>) = obj("kind" to "bank", "name" to "x", "principal" to 100000, "rate" to 12, "tenure" to 12,
            "startDate" to "2026-01-10", "dueDay" to 5).also { o -> extra.forEach { (k, x) -> o[k] = norm(x) } }
        assertNull(validateLoan(b()))
        assertEquals("Please choose how often the instalment is paid.", validateLoan(b("frequency" to "hourly")))
        assertEquals("Please choose how the interest is worked out.", validateLoan(b("interestMethod" to "simple")))
        assertEquals("The number of instalments must be a whole number.", validateLoan(b("tenure" to 12.5)))
        assertEquals("Please enter the EMI or the number of instalments.", validateLoan(b("tenure" to 0)))
        assertEquals("The due day must be a day of the month, from 1 to 31.", validateLoan(b("dueDay" to 0)))
        assertNull(validateLoan(b("dueDay" to 0, "frequency" to "week")))
        assertEquals("This EMI is too small to ever pay off the loan.", validateLoan(b("emi" to 1000)))   // interest alone is 1,000 a month
        assertEquals("The new rate must start after the loan started.", validateLoan(b("rateChanges" to arr(obj("from" to "2025-12-01", "rate" to 10)))))
        assertEquals("A new rate can only be set on a loan whose interest is on the amount still owed.",
            validateLoan(b("interestMethod" to "flat", "rateChanges" to arr(obj("from" to "2026-05-01", "rate" to 10)))))
        assertEquals("There are two rate changes on the same date.",
            validateLoan(b("rateChanges" to arr(obj("from" to "2026-05-01", "rate" to 10), obj("from" to "2026-05-01", "rate" to 11)))))
        assertNull(validateLoan(b("rateChanges" to arr(obj("from" to "2026-05-01", "rate" to 10, "emi" to 9000)))))
        fun g(vararg extra: Pair<String, Any?>) = obj("kind" to "gold", "name" to "x", "principal" to 5000, "rate" to 9, "startDate" to "2026-01-01")
            .also { o -> extra.forEach { (k, x) -> o[k] = norm(x) } }
        assertNull(validateLoan(g("pledge" to "land")))
        assertEquals("Please choose what the loan is taken against.", validateLoan(g("pledge" to "car")))
        assertEquals("Grams are only for a gold loan.", validateLoan(g("pledge" to "fd", "grams" to 10)))
        assertNull(validateLoan(g("grams" to 10)))
        assertEquals("Please say what is pledged.", validateLoan(g("pledge" to "other")))
        assertEquals("Please say what is pledged.", validateLoan(g("pledge" to "other", "pledgeText" to "   ")))
        assertNull(validateLoan(g("pledge" to "other", "pledgeText" to "Silver")))
        assertNull(validateLoan(g("pledge" to "other", "pledgeText" to "x".repeat(40))))
        assertEquals("Please keep it to 40 letters or fewer.", validateLoan(g("pledge" to "other", "pledgeText" to "x".repeat(41))))
        assertEquals("The date to repay by must be after the loan started.", validateLoan(g("repayBy" to "2026-01-01")))
        assertEquals("The date to repay by must be after the loan started.",
            validateLoan(obj("kind" to "hand", "name" to "x", "principal" to 5, "startDate" to "2026-03-01", "repayBy" to "2026-02-01")))
        assertNull(validateLoan(g("repayBy" to "2026-12-31")))
    }
}
