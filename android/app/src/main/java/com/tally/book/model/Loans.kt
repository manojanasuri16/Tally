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

fun payKindLabel(kind: String?): String = when (kind) {
    "emi" -> t("pay.kind.emi", "EMI")
    "interest" -> t("pay.kind.interest", "Interest")
    "principal" -> t("pay.kind.principal", "Principal")
    else -> t("pay.kind.other", "Payment")
}

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
    } else if (open) {
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
    return v
}

// ---- lists and totals ----

/** Open loans with something due this calendar month or overdue, soonest first: { loan, date, amount, overdue }. */
fun dueList(today: LocalDate = Clock.today(), sharedOnly: Boolean = false): List<Obj> =
    loans().filter { !it.b("closed") && !(sharedOnly && it.b("private")) }.mapNotNull { l ->
        val v = loanView(l, today)
        if (v.num("owed") > 0 && v.s("nextDueDate").isNotEmpty() && (v.b("overdue") || v.b("dueThisMonth")))
            obj("loan" to l, "date" to v.s("nextDueDate"), "amount" to v.num("nextDueAmount"), "overdue" to v.b("overdue"))
        else null
    }.sortedWith(compareBy<Obj> { it.s("date") }.thenBy { it.o("loan").s("name") })

/**
 * { owed, dueThisMonth, paidThisMonth, byKind: { bank, hand, gold } (owed per kind), count (open loans) }.
 * [sharedOnly] leaves private loans out; the default counts everything on this phone.
 */
fun totals(today: LocalDate = Clock.today(), sharedOnly: Boolean = false): Obj {
    val all = loans().filter { !(sharedOnly && it.b("private")) }
    val open = all.filter { !it.b("closed") }
    val by = obj("bank" to 0.0, "hand" to 0.0, "gold" to 0.0)
    var owed = 0.0
    open.forEach { val o = loanView(it, today).num("owed"); owed += o; by[it.s("kind")] = by.num(it.s("kind")) + o }
    val due = dueList(today, sharedOnly).sumOf { it.num("amount") }
    val key = monthKey(today)
    val ids = all.map { it.s("id") }.toSet()
    val paid = DB.objs("payments").filter { !it.b("deleted") && it.s("loanId") in ids && it.s("date").startsWith(key) }.sumOf { it.num("amount") }
    return obj("owed" to owed, "dueThisMonth" to due, "paidThisMonth" to paid, "byKind" to by, "count" to open.size.toDouble())
}

/** What the Log payment form starts with: { kind, amount }. EMI for a bank loan, the period's interest for hand and gold. */
fun suggestPayment(loan: Obj, today: LocalDate = Clock.today()): Obj {
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
