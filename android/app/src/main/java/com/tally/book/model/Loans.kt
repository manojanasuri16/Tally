package com.tally.book.model

import com.tally.book.core.*
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * The read side: pure functions of a loan, its payments and a date (fields: docs/DESIGN.md). Nothing here writes.
 * "owed" is the principal still outstanding, worked out from the payments actually logged, never from the calendar.
 * Interest that was due and not paid is not added on (the parents' question is "how much do I still owe": the principal).
 */

val LOAN_KINDS = listOf("bank", "hand", "gold")
val PAY_KINDS = listOf("emi", "interest", "principal")

fun kindLabel(kind: String?): String = when (kind) {
    "bank" -> t("loan.kind.bank", "Bank loan")
    "hand" -> t("loan.kind.hand", "From a person")
    "gold" -> t("loan.kind.gold", "Gold loan")
    else -> t("loan.kind.other", "Loan")
}

/** [kindLabel] for a loan record: money I gave reads "To a person". */
fun loanKindLabel(loan: Obj): String = if (isGiven(loan)) t("loan.kind.given", "To a person") else kindLabel(loan.s("kind"))

/**
 * What a payment was for, in the words of the loan it is on. [loanKind] "hand" says "Returned some of the amount" for a part repayment
 * (a bank or gold loan keeps "Principal"); [given] turns the hand loan around ("Interest received", "Got some of the amount back").
 */
fun payKindLabel(kind: String?, loanKind: String? = null, given: Boolean = false): String = when {
    kind == "emi" -> t("pay.kind.emi", "EMI")
    kind == "interest" && given -> t("pay.kind.interest.given", "Interest received")
    kind == "interest" -> t("pay.kind.interest", "Interest")
    kind == "principal" && given -> t("pay.kind.principal.given", "Got some of the amount back")
    kind == "principal" && loanKind == "hand" -> t("pay.kind.principal.hand", "Returned some of the amount")
    kind == "principal" -> t("pay.kind.principal", "Principal")
    else -> t("pay.kind.other", "Payment")
}

/** Money I gave to someone: only a `hand` loan can be given (direction "given"); everything else is money I took. */
fun isGiven(loan: Obj): Boolean = loan.s("kind") == "hand" && loan.s("direction") == "given"

/** A person-to-person loan that charges nothing (rate 0): never due, never overdue, simply still owed. */
fun isNoInterest(loan: Obj): Boolean = loan.s("kind") == "hand" && loan.num("rate") <= 0

// ---- the arithmetic ----

private fun monthKey(d: LocalDate): String = YearMonth.from(d).toString()
private fun clampDay(key: String, day: Int): LocalDate = LocalDate.parse("$key-01").withDayOfMonth(min(max(day, 1), daysIn(key)))

/** Monthly EMI for a reducing-balance loan, to the whole rupee (banks quote whole rupees). 0 when it cannot be worked out. */
fun emiFor(principal: Double, yearlyRatePct: Double, months: Int): Double {
    if (principal <= 0 || months <= 0) return 0.0
    val r = yearlyRatePct / 1200
    if (r <= 0) return jsRound(principal / months)
    val g = (1 + r).pow(months)
    return jsRound(principal * r * g / (g - 1))
}

/** Months needed to clear [balance] with [emi] at monthly rate [r]; -1 when the EMI does not even cover the interest. */
private fun monthsToClear(balance: Double, r: Double, emi: Double): Int {
    if (balance <= 0.005) return 0
    if (emi <= 0) return -1
    if (r <= 0) return ceil(balance / emi - 1e-9).toInt()
    if (emi <= balance * r) return -1
    return ceil(-ln(1 - balance * r / emi) / ln(1 + r) - 1e-9).toInt()
}

/** The period a hand or gold loan pays interest in: "month" or "year". */
fun interestEvery(loan: Obj): String {
    val e = loan.s("interestEvery")
    return if (e == "month" || e == "year") e else if (loan.s("kind") == "gold") "year" else "month"
}

/** Interest for one period on [outstanding], following rateUnit ("year" default: percent a year; "month": percent a month). */
fun periodInterest(loan: Obj, outstanding: Double): Double {
    val rate = loan.num("rate") / 100
    val perYear = if (loan.s("rateUnit") == "month") rate * 12 else rate
    return jsRound(outstanding * (if (interestEvery(loan) == "month") perYear / 12 else perYear))
}

private fun sortedLive(payments: List<Obj>, loanId: String): List<Obj> =
    payments.filter { !it.b("deleted") && it.s("loanId") == loanId }
        .sortedWith(compareBy<Obj> { it.s("date") }.thenBy { it.num("at") })

// ---- the view ----

/** loanView with the payments read from the book. */
fun loanView(loan: Obj, today: LocalDate = Clock.today()): Obj =
    loanView(loan, paymentsOf(loan.s("id")), today)

/**
 * Everything a screen shows for one loan: owed, paidTotal, paidPrincipal, paidInterest, nextDueDate ("" when none),
 * nextDueAmount, overdue, dueThisMonth, paidThisPeriod, and for bank loans emi, monthsLeft, endDate ("" when none).
 * For hand loans also: given, noInterest (rate 0: nextDueDate stays ""), repayBy ("" when none), repayDue (still owed and repayBy falls
 * in this month or earlier: the remaining amount is a principal item in the due list) and repayOverdue (repayBy has passed and
 * something is still owed). overdue/dueThisMonth/paidThisPeriod are about interest only.
 */
fun loanView(loan: Obj, payments: List<Obj>, today: LocalDate): Obj {
    val kind = loan.s("kind")
    val pays = sortedLive(payments, loan.s("id"))
    val principal = loan.num("principal")
    val closed = loan.b("closed")
    val start = parseISO(loan.s("startDate"))
    var balance = principal
    var paidPrincipal = 0.0; var paidInterest = 0.0; var paidTotal = 0.0

    var emi = 0.0; var months = 0
    val r = if (kind == "bank") loan.num("rate") / 1200 else 0.0
    if (kind == "bank") {
        months = loan.num("tenureMonths").toInt()
        emi = loan.num("emi")
        if (emi <= 0) emi = emiFor(principal, loan.num("rate"), months)
        else if (months <= 0) months = max(0, monthsToClear(principal, r, emi))
    }

    var lastEmiMonth: String? = null
    var lastInterest: LocalDate? = null
    for (p in pays) {
        val amt = p.num("amount"); val d = parseISO(p.s("date"))
        paidTotal += amt
        when (p.s("kind")) {
            "emi" -> {
                val interest = min(amt, balance * r)
                val prin = min(balance, amt - interest)
                paidInterest += interest; paidPrincipal += prin; balance -= prin
                if (d != null) { val k = monthKey(d); if (lastEmiMonth == null || k > lastEmiMonth) lastEmiMonth = k }
            }
            "principal" -> { val prin = min(balance, amt); paidPrincipal += prin; balance -= prin }
            "interest" -> {
                paidInterest += amt
                if (d != null && (lastInterest == null || d.isAfter(lastInterest))) lastInterest = d
            }
        }
    }
    if (balance < 0.005) balance = 0.0

    var nextDue = ""; var nextAmount = 0.0
    var monthsLeft = 0; var endDate = ""
    val every = interestEvery(loan)
    val open = !closed && balance > 0 && start != null

    if (kind == "bank") {
        monthsLeft = if (closed || balance <= 0) 0 else {
            val m = monthsToClear(balance, r, emi)
            if (m >= 0) m else max(0, months - pays.count { it.s("kind") == "emi" })
        }
        if (open) {
            val dueDay = loan.num("dueDay").toInt().let { if (it in 1..31) it else start!!.dayOfMonth }
            val sk = monthKey(start!!)
            val first = clampDay(sk, dueDay).let { if (it.isAfter(start)) it else clampDay(addMonths(sk, 1), dueDay) }
            val next = if (lastEmiMonth == null) first else clampDay(addMonths(lastEmiMonth, 1), dueDay).let { if (it.isBefore(first)) first else it }
            nextDue = next.toString()
            nextAmount = jsRound(min(emi, balance * (1 + r)))
            if (monthsLeft > 0) endDate = clampDay(addMonths(monthKey(next), monthsLeft - 1), dueDay).toString()
        }
    } else if (open && !isNoInterest(loan)) {
        val step = if (every == "month") 1 else 12
        val base = lastInterest ?: start!!
        nextDue = clampDay(addMonths(monthKey(base), step), start!!.dayOfMonth).toString()
        nextAmount = periodInterest(loan, balance)
    }

    val due = parseISO(nextDue)
    val overdue = due != null && due.isBefore(today)
    val dueThisMonth = due != null && monthKey(due) == monthKey(today)
    val hasPaid = if (kind == "bank") lastEmiMonth != null else lastInterest != null
    val periodEnd = if (kind == "bank" || every == "month") today.withDayOfMonth(today.lengthOfMonth()) else LocalDate.of(today.year, 12, 31)
    val paidThisPeriod = open && hasPaid && due != null && due.isAfter(periodEnd)

    val v = obj(
        "owed" to if (closed) 0.0 else balance,
        "paidTotal" to paidTotal, "paidPrincipal" to paidPrincipal, "paidInterest" to paidInterest,
        "nextDueDate" to nextDue, "nextDueAmount" to nextAmount,
        "overdue" to overdue, "dueThisMonth" to dueThisMonth, "paidThisPeriod" to paidThisPeriod,
    )
    if (kind == "bank") { v["emi"] = emi; v["monthsLeft"] = monthsLeft; v["endDate"] = endDate }
    else v["interestEvery"] = every
    if (kind == "hand") {
        val by = parseISO(loan.s("repayBy"))
        val owing = !closed && balance > 0 && by != null
        v["given"] = isGiven(loan); v["noInterest"] = isNoInterest(loan)
        v["repayBy"] = if (by != null) by.toString() else ""
        v["repayDue"] = owing && monthKey(by!!) <= monthKey(today)
        v["repayOverdue"] = owing && by!!.isBefore(today)
    }
    return v
}

// ---- lists and totals ----

/**
 * Open loans with something due this calendar month or overdue, soonest first:
 * { loan, date, amount, overdue, given, kind }. A loan can give two rows: its interest (or EMI), kind "emi"/"interest", and, for a hand
 * loan with a `repayBy` date in this month or earlier, the whole amount still owed, kind "principal", dated repayBy and overdue once
 * that date has passed. A no-interest loan gives only the second. [given] marks money I gave (rows to receive, not to pay).
 */
fun dueList(today: LocalDate = Clock.today(), sharedOnly: Boolean = false): List<Obj> =
    loans().filter { !it.b("closed") && !(sharedOnly && it.b("private")) }.flatMap { l ->
        val v = loanView(l, today)
        val given = isGiven(l)
        val rows = ArrayList<Obj>(2)
        if (v.num("owed") > 0 && v.s("nextDueDate").isNotEmpty() && (v.b("overdue") || v.b("dueThisMonth")))
            rows.add(obj("loan" to l, "date" to v.s("nextDueDate"), "amount" to v.num("nextDueAmount"), "overdue" to v.b("overdue"),
                "given" to given, "kind" to if (l.s("kind") == "bank") "emi" else "interest"))
        if (v.b("repayDue"))
            rows.add(obj("loan" to l, "date" to v.s("repayBy"), "amount" to v.num("owed"), "overdue" to v.b("repayOverdue"),
                "given" to given, "kind" to "principal"))
        rows
    }.sortedWith(compareBy<Obj> { it.s("date") }.thenBy { it.o("loan").s("name") })

/**
 * { owed, dueThisMonth, paidThisMonth, byKind: { bank, hand, gold }, count, toReceive, expectedThisMonth, receivedThisMonth, givenCount }.
 * Money never nets out: owed, dueThisMonth, paidThisMonth and byKind are about money I took only; toReceive (what is still to come
 * back on open loans I gave), expectedThisMonth and receivedThisMonth are the given side. count is every open loan, givenCount the
 * given ones among them. [sharedOnly] leaves private loans out; the default counts everything on this phone.
 */
fun totals(today: LocalDate = Clock.today(), sharedOnly: Boolean = false): Obj {
    val all = loans().filter { !(sharedOnly && it.b("private")) }
    val open = all.filter { !it.b("closed") }
    val by = obj("bank" to 0.0, "hand" to 0.0, "gold" to 0.0)
    var owed = 0.0; var toReceive = 0.0; var givenCount = 0
    open.forEach {
        val o = loanView(it, today).num("owed")
        if (isGiven(it)) { toReceive += o; givenCount++ } else { owed += o; by[it.s("kind")] = by.num(it.s("kind")) + o }
    }
    val rows = dueList(today, sharedOnly)
    val due = rows.filter { !it.b("given") }.sumOf { it.num("amount") }
    val expected = rows.filter { it.b("given") }.sumOf { it.num("amount") }
    val key = monthKey(today)
    val givenIds = all.filter { isGiven(it) }.map { it.s("id") }.toSet()
    val takenIds = all.map { it.s("id") }.toSet() - givenIds
    val inMonth = DB.objs("payments").filter { !it.b("deleted") && it.s("date").startsWith(key) }
    val paid = inMonth.filter { it.s("loanId") in takenIds }.sumOf { it.num("amount") }
    val received = inMonth.filter { it.s("loanId") in givenIds }.sumOf { it.num("amount") }
    return obj("owed" to owed, "dueThisMonth" to due, "paidThisMonth" to paid, "byKind" to by, "count" to open.size.toDouble(),
        "toReceive" to toReceive, "expectedThisMonth" to expected, "receivedThisMonth" to received, "givenCount" to givenCount.toDouble())
}

/** True when a payment took what is owed from something to nothing: the moment to offer closing the loan (once). */
fun reachedZero(owedBefore: Double, owedAfter: Double): Boolean = owedBefore > 0.005 && owedAfter < 0.005

/**
 * What the Log payment form starts with: { kind, amount }. EMI for a bank loan, the period's interest for hand and gold. A no-interest
 * loan has nothing to suggest but a part repayment, with amount 0 (the person types what was returned).
 */
fun suggestPayment(loan: Obj, today: LocalDate = Clock.today()): Obj {
    if (isNoInterest(loan)) return obj("kind" to "principal", "amount" to 0.0)
    val v = loanView(loan, today)
    return obj("kind" to if (loan.s("kind") == "bank") "emi" else "interest", "amount" to v.num("nextDueAmount"))
}

// ---- checks ----

/** The first problem with a loan form, in plain words, or null. */
fun validateLoan(draft: Obj): String? {
    val kind = draft.s("kind")
    if (draft.s("name").isBlank()) return t("loan.err.name", "Please give this loan a name.")
    if (kind !in LOAN_KINDS) return t("loan.err.kind", "Please choose what kind of loan this is.")
    if (draft.num("principal") <= 0) return t("loan.err.principal", "Please enter the loan amount.")
    if (draft.num("rate") < 0) return t("loan.err.rate", "The interest rate cannot be below zero.")
    if (parseISO(draft.s("startDate")) == null) return t("loan.err.date", "Please enter the date the loan started.")
    if (kind == "bank") {
        if (draft.num("emi") <= 0 && draft.num("tenureMonths") <= 0)
            return t("loan.err.emi", "Please enter the EMI or the number of months.")
        if (draft.num("dueDay") !in 1.0..31.0) return t("loan.err.dueDay", "The due day must be a day of the month, from 1 to 31.")
    } else {
        val e = draft.s("interestEvery")
        if (e.isNotEmpty() && e != "month" && e != "year") return t("loan.err.every", "Please choose whether interest is paid every month or every year.")
    }
    val dir = draft.s("direction")
    if (dir.isNotEmpty() && dir != "taken" && dir != "given") return t("loan.err.direction", "Please choose whether you took or gave this money.")
    if (dir == "given" && kind != "hand") return t("loan.err.direction", "Please choose whether you took or gave this money.")
    if (draft.s("repayBy").isNotEmpty() && parseISO(draft.s("repayBy")) == null) return t("loan.err.repayBy", "Please enter a date for when it is to be returned.")
    return null
}

/** The first problem with a payment form, in plain words, or null. */
fun validatePayment(draft: Obj, today: LocalDate = Clock.today()): String? {
    val l = loan(draft.s("loanId")) ?: return t("pay.err.loan", "This loan could not be found.")
    val isNew = DB.objs("payments").none { it.s("id") == draft.s("id") && it.s("id").isNotEmpty() && !it.b("deleted") }
    if (l.b("closed") && isNew) return t("pay.err.closed", "This loan is closed. Reopen it to add a payment.")
    if (draft.num("amount") <= 0) return t("pay.err.amount", "Please enter the amount paid.")
    val d = parseISO(draft.s("date")) ?: return t("pay.err.date", "Please enter the date of the payment.")
    if (d.isAfter(today)) return t("pay.err.future", "The payment date cannot be in the future.")
    val k = draft.s("kind")
    if (k !in PAY_KINDS || (k == "emi" && l.s("kind") != "bank")) return t("pay.err.kind", "Please choose what the payment was for.")
    return null
}
