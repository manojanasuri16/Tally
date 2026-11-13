package com.tally.book.model

import com.tally.book.core.*
import java.time.LocalDate
import kotlin.math.max

/*
 * The read side: pure functions of a loan, its payments and a date (fields and rules: docs/DESIGN.md). Nothing here writes.
 * The arithmetic is in LoanMath.kt; labels and wording in LoanLabels.kt; reminders in Reminders.kt.
 * "owed" is the principal still outstanding, worked out from the payments actually logged, never from the calendar.
 * Interest or instalments that fell due and were not paid are shown separately as "pending", never added to what is owed.
 */

val LOAN_KINDS = listOf("bank", "hand", "gold", "card")

/**
 * A credit card: its statement says what is outstanding (`principal`, as of `startDate`, the statement date), the minimum to
 * pay (`minDue`, 0 = the whole bill) and by when (`dueDate`). Payments made since the statement lower both. No interest is
 * worked out here: the next statement brings the bank's own figures ("Update statement").
 */
fun isCard(loan: Obj): Boolean = loan.s("kind") == "card"
val PAY_KINDS = listOf("emi", "interest", "principal")

/** Money I gave to someone: only a `hand` loan can be given (direction "given"); everything else is money I took. */
fun isGiven(loan: Obj): Boolean = loan.s("kind") == "hand" && loan.s("direction") == "given"

/** An interest-only loan (hand or gold kind) that charges nothing (rate 0): never due, never overdue, simply still owed. */
fun isNoInterest(loan: Obj): Boolean = loan.s("kind") in setOf("hand", "gold") && loan.num("rate") <= 0 && !isPersonInstalments(loan)

/** A loan whose principal may carry a "repay by" date: person-to-person loans and loans against something. */
fun hasRepayBy(kind: String?): Boolean = kind == "hand" || kind == "gold"

// ---- the view ----

/** loanView with the payments read from the book. */
fun loanView(loan: Obj, today: LocalDate = Clock.today()): Obj =
    loanView(loan, paymentsOf(loan.s("id")), today)

internal fun calcOf(loan: Obj, payments: List<Obj>, today: LocalDate): LoanCalc? {
    val start = parseISO(loan.s("startDate")) ?: return null
    val pays = sortedLive(payments, loan.s("id"))
    return if (isCard(loan)) calcCard(loan, pays)
    else if (isInstalments(loan)) calcInstalments(instalmentBasis(loan), pays, start, today)
    else calcInterestOnly(loan, pays, start, today, isNoInterest(loan))
}

/**
 * Everything a screen shows for one loan (every key is always present for the kinds named):
 *
 * All kinds: owed (principal still outstanding; 0 when closed), paidTotal, paidPrincipal, paidInterest, interestMethod,
 * nextDueDate (the oldest instalment or period not fully paid, "" when none; in the past means overdue), nextDueAmount (what is
 * still to pay on it), overdue, dueThisMonth (nextDueDate is in this month), paidThisPeriod (something was paid and nothing is
 * left to pay up to the end of this month, or this year for yearly interest), pendingAmount / pendingCount (unpaid on instalments
 * or periods whose date is before today), monthDue / monthDueCount (unpaid on every instalment or period dated up to the end of
 * this month, the pending ones included).
 *
 * bank: emi (the instalment in force today), startEmi, currentRate (the yearly rate in force today), frequency, perYear,
 * tenure (instalments agreed, or worked out from the EMI), instalmentsLeft (= monthsLeft, kept for old callers: instalments still
 * to pay, pending ones included), endDate (date of the last one, "" when it cannot be worked out), emisPending, amountPending,
 * emiTooLow (the instalment never clears the loan), flatInterestPart (flat: interest in each instalment, else 0),
 * weekday (1 = Monday .. 7 = Sunday for weekly and fortnightly loans, else 0), dueDay (monthly loans, else 0).
 *
 * A person's instalment loan (`plan` "instalments", isInstalments) has the bank keys too, worked out on instalmentBasis, plus
 * totalToRepay (each instalment × how many) and extra (what that is over the money received); it keeps the hand keys below.
 *
 * hand and gold: interestEvery, periodInterest (one period's interest on what is owed now, or on the full amount when flat),
 * interestPending, periodsPending, given, noInterest, repayBy ("" when none), repayDue (still owed and repayBy falls in this month
 * or earlier: the remaining amount is a principal item in the due list), repayOverdue (repayBy has passed, something still owed),
 * renewals (how many times it was renewed), lastRenewal ("" or the ISO date of the newest renewal).
 * gold also: pledge, grams (0 unless the pledge is gold).
 */
fun loanView(loan: Obj, payments: List<Obj>, today: LocalDate): Obj {
    val kind = loan.s("kind")
    val closed = loan.b("closed")
    val c = calcOf(loan, payments, today)
    val slots = if (closed || c == null) emptyList() else c.slots
    val balance = c?.balance ?: loan.num("principal")
    val first = slots.firstOrNull()
    val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
    val pending = slots.filter { it.due.isBefore(today) }
    val month = slots.filter { !it.due.isAfter(monthEnd) }
    val overdue = first != null && first.due.isBefore(today)
    val instalments = isInstalments(loan)
    val yearly = !instalments && interestEvery(loan) == "year"
    val periodEnd = if (yearly) LocalDate.of(today.year, 12, 31) else monthEnd
    val hasPaid = c?.hasPaid ?: false
    val open = !closed && c != null && (balance > 0 || slots.isNotEmpty())
    val paidThisPeriod = open && hasPaid && (first == null || first.due.isAfter(periodEnd))

    val v = obj(
        "owed" to if (closed) 0.0 else balance,
        "paidTotal" to (c?.paidTotal ?: 0.0), "paidPrincipal" to (c?.paidPrincipal ?: 0.0), "paidInterest" to (c?.paidInterest ?: 0.0),
        "interestMethod" to interestMethod(loan),
        "nextDueDate" to (first?.due?.toString() ?: ""), "nextDueAmount" to (first?.left ?: 0.0),
        "overdue" to overdue, "dueThisMonth" to (first != null && monthKey(first.due) == monthKey(today)),
        "paidThisPeriod" to paidThisPeriod,
        "pendingAmount" to pending.sumOf { it.left }, "pendingCount" to pending.size.toDouble(),
        "monthDue" to month.sumOf { it.left }, "monthDueCount" to month.size.toDouble(),
        "paidUpTo" to (paidUpToOf(loan)?.toString() ?: ""), "paidUpToCount" to (c?.prePaid ?: 0).toDouble(),
    )
    if (instalments) {
        val freq = frequencyOf(loan)
        val start = parseISO(loan.s("startDate"))
        val n = c?.count ?: tenureOf(loan)
        val left = when {
            closed || balance <= 0 -> 0
            c != null && c.clears -> c.slots.size
            else -> max(0, n - (c?.covered ?: 0))
        }
        v["emi"] = c?.emiNow ?: loan.num("emi"); v["startEmi"] = c?.emiStart ?: loan.num("emi")
        v["currentRate"] = c?.rateNow ?: loan.num("rate")
        v["frequency"] = freq; v["perYear"] = perYear(freq).toDouble(); v["tenure"] = n.toDouble()
        v["instalmentsLeft"] = left.toDouble(); v["monthsLeft"] = left.toDouble()
        v["endDate"] = if (!closed && c != null && c.clears && c.slots.isNotEmpty()) c.slots.last().due.toString() else ""
        v["emisPending"] = pending.size.toDouble(); v["amountPending"] = pending.sumOf { it.left }
        v["emiTooLow"] = !closed && balance > 0 && c != null && !c.clears
        v["flatInterestPart"] = c?.flatInterestPart ?: 0.0
        v["weekday"] = if ((freq == "week" || freq == "fortnight") && start != null) (firstDueOf(loan) ?: start).dayOfWeek.value.toDouble() else 0.0
        v["totalToRepay"] = if (isPersonInstalments(loan)) totalToRepay(loan) else 0.0
        v["extra"] = if (isPersonInstalments(loan)) instalmentExtra(loan) else 0.0
        v["dueDay"] = if (freq == "month") (firstDueOf(loan)?.dayOfMonth?.toDouble() ?: instalmentBasis(loan).num("dueDay")).toInt().let { if (it in 1..31) it else start?.dayOfMonth ?: 0 }.toDouble() else 0.0
    } else {
        v["interestEvery"] = interestEvery(loan)
        v["periodInterest"] = if (closed) 0.0 else c?.periodInterestNow ?: 0.0
        v["interestPending"] = pending.sumOf { it.left }; v["periodsPending"] = pending.size.toDouble()
    }
    if (hasRepayBy(kind)) {
        val by = parseISO(loan.s("repayBy"))
        val owing = !closed && balance > 0 && by != null
        v["given"] = isGiven(loan); v["noInterest"] = isNoInterest(loan)
        v["repayBy"] = by?.toString() ?: ""
        v["repayDue"] = owing && monthKey(by!!) <= monthKey(today)
        v["repayOverdue"] = owing && by!!.isBefore(today)
        val rn = loan.objs("renewals").filter { parseISO(it.s("date")) != null }
        v["renewals"] = rn.size.toDouble(); v["lastRenewal"] = rn.maxOfOrNull { it.s("date") } ?: ""
    }
    if (isCard(loan)) {
        v["minDue"] = loan.num("minDue"); v["dueDate"] = loan.s("dueDate"); v["statementDate"] = loan.s("startDate")
        v["paidSinceStatement"] = c?.paidPrincipal ?: 0.0
    }
    if (kind == "gold") {
        v["pledge"] = pledgeOf(loan); v["pledgeText"] = pledgeTextOf(loan)
        v["grams"] = if (pledgeOf(loan) == "gold") loan.num("grams") else 0.0
    }
    return v
}

// ---- lists and totals ----

/**
 * Open loans with something to pay up to the end of this calendar month (or overdue), soonest first:
 * { loan, date, amount, overdue, given, kind, pending, pendingCount, upcoming, count }.
 *
 * A loan can give two rows. Its instalments or interest, kind "emi"/"interest": [date] is the oldest one not fully paid,
 * [amount] everything unpaid dated up to the end of this month (every weekly or daily instalment of the month is in it),
 * split into [pending] (dated before today; [pendingCount] of them) and [upcoming] (today to the month's end), [count] how many
 * instalments or periods that is, [overdue] when anything is pending. And, for a hand or gold loan with a `repayBy` date in this
 * month or earlier, the whole amount still owed, kind "principal", dated repayBy, overdue once that date has passed (pending 0,
 * count 1). A no-interest loan gives only the second. [given] marks money I gave (rows to receive, not to pay).
 */
fun dueList(today: LocalDate = Clock.today(), sharedOnly: Boolean = false, only: (Obj) -> Boolean = { true }): List<Obj> =
    loans().filter { !it.b("closed") && !(sharedOnly && it.b("private")) && only(it) }.flatMap { l ->
        val v = loanView(l, today)
        val given = isGiven(l)
        val rows = ArrayList<Obj>(2)
        if (v.num("monthDueCount") > 0) {
            val pend = v.num("pendingAmount")
            rows.add(obj("loan" to l, "date" to v.s("nextDueDate"), "amount" to v.num("monthDue"), "overdue" to v.b("overdue"),
                "given" to given, "kind" to if (isCard(l)) "card" else if (isInstalments(l)) "emi" else "interest",
                "pending" to pend, "pendingCount" to v.num("pendingCount"), "upcoming" to v.num("monthDue") - pend, "count" to v.num("monthDueCount")))
        }
        if (v.b("repayDue"))
            rows.add(obj("loan" to l, "date" to v.s("repayBy"), "amount" to v.num("owed"), "overdue" to v.b("repayOverdue"),
                "given" to given, "kind" to "principal", "pending" to 0.0, "pendingCount" to 0.0, "upcoming" to v.num("owed"), "count" to 1.0))
        rows
    }.sortedWith(compareBy<Obj> { it.s("date") }.thenBy { it.o("loan").s("name") })

/**
 * { owed, dueThisMonth, paidThisMonth, byKind: { bank, hand, gold }, count, toReceive, expectedThisMonth, receivedThisMonth, givenCount,
 *   pendingThisMonth, pendingToReceive }.
 * Money never nets out: owed, dueThisMonth, paidThisMonth and byKind are about money I took only; toReceive (what is still to come
 * back on open loans I gave), expectedThisMonth and receivedThisMonth are the given side. dueThisMonth / expectedThisMonth are the
 * sums of the due list's amounts (so they include what is pending); pendingThisMonth / pendingToReceive are the pending parts.
 * count is every open loan, givenCount the given ones among them. [sharedOnly] leaves private loans out; [only] keeps just the
 * loans it accepts (the home screen's Mine: `::isMine`).
 */
fun totals(today: LocalDate = Clock.today(), sharedOnly: Boolean = false, only: (Obj) -> Boolean = { true }): Obj {
    val all = loans().filter { !(sharedOnly && it.b("private")) && only(it) }
    val open = all.filter { !it.b("closed") }
    val by = obj("bank" to 0.0, "hand" to 0.0, "gold" to 0.0)
    var owed = 0.0; var toReceive = 0.0; var givenCount = 0
    open.forEach {
        val o = loanView(it, today).num("owed")
        if (isGiven(it)) { toReceive += o; givenCount++ } else { owed += o; by[it.s("kind")] = by.num(it.s("kind")) + o }
    }
    val rows = dueList(today, sharedOnly, only)
    val taken = rows.filter { !it.b("given") }; val given = rows.filter { it.b("given") }
    val key = monthKey(today)
    val givenIds = all.filter { isGiven(it) }.map { it.s("id") }.toSet()
    val takenIds = all.map { it.s("id") }.toSet() - givenIds
    val inMonth = DB.objs("payments").filter { !it.b("deleted") && it.s("date").startsWith(key) }
    val paid = inMonth.filter { it.s("loanId") in takenIds }.sumOf { it.num("amount") }
    val received = inMonth.filter { it.s("loanId") in givenIds }.sumOf { it.num("amount") }
    return obj("owed" to owed, "dueThisMonth" to taken.sumOf { it.num("amount") }, "paidThisMonth" to paid, "byKind" to by,
        "count" to open.size.toDouble(), "toReceive" to toReceive, "expectedThisMonth" to given.sumOf { it.num("amount") },
        "receivedThisMonth" to received, "givenCount" to givenCount.toDouble(),
        "pendingThisMonth" to taken.sumOf { it.num("pending") }, "pendingToReceive" to given.sumOf { it.num("pending") })
}

/** True when a payment took what is owed from something to nothing: the moment to offer closing the loan (once). */
fun reachedZero(owedBefore: Double, owedAfter: Double): Boolean = owedBefore > 0.005 && owedAfter < 0.005

/**
 * What the Log payment form starts with: { kind, amount }. A bank loan: the instalment (what is still to pay on the oldest unpaid
 * one, at the loan's frequency). Hand and gold: one period's interest on what is owed now (pending interest is shown separately;
 * the person decides how much to pay), or, when the principal is all returned, what is left on the oldest unpaid period.
 * A no-interest loan has nothing to suggest but a part repayment, with amount 0 (the person types what was returned).
 */
fun suggestPayment(loan: Obj, today: LocalDate = Clock.today()): Obj {
    if (isCard(loan)) return obj("kind" to "principal", "amount" to loanView(loan, today).num("nextDueAmount"))
    if (isNoInterest(loan)) return obj("kind" to "principal", "amount" to 0.0)
    val v = loanView(loan, today)
    if (isInstalments(loan)) return obj("kind" to "emi", "amount" to v.num("nextDueAmount"))
    val amount = if (v.num("owed") > 0) v.num("periodInterest") else v.num("nextDueAmount")
    return obj("kind" to "interest", "amount" to amount)
}

// ---- checks ----

/** The first problem with a loan form, in plain words, or null. */
fun validateLoan(draft: Obj): String? {
    val kind = draft.s("kind")
    if (draft.s("name").isBlank()) return t("loan.err.name", "Please give this entry a name.")
    if (kind !in LOAN_KINDS) return t("loan.err.kind", "Please choose what kind of entry this is.")
    if (kind == "card") return validateCard(draft)
    if (draft.num("principal") <= 0) return t("loan.err.principal", "Please enter the amount.")
    if (draft.num("rate") < 0) return t("loan.err.rate", "The interest rate cannot be below zero.")
    val start = parseISO(draft.s("startDate")) ?: return t("loan.err.date", "Please enter the date the entry started.")
    if (draft.s("paidUpTo").isNotEmpty()) {
        val up = parseISO(draft.s("paidUpTo")) ?: return t("loan.err.paidUpTo", "Please enter the date it is paid up to.")
        if (!up.isAfter(start)) return t("loan.err.paidUpTo.early", "The paid-up-to date must be after the entry started.")
        if (up.isAfter(Clock.today())) return t("loan.err.paidUpTo.future", "The paid-up-to date cannot be in the future.")
    }
    if (draft.s("firstDue").isNotEmpty()) {
        val f = parseISO(draft.s("firstDue")) ?: return t("loan.err.firstDue", "Please enter the first payment date.")
        if (!f.isAfter(start)) return t("loan.err.firstDue.early", "The first payment date must be after the date taken.")
    }
    val m = draft.s("interestMethod")
    if (m.isNotEmpty() && m !in INTEREST_METHODS) return t("loan.err.method", "Please choose how the interest is worked out.")
    if (kind == "bank") {
        val f = draft.s("frequency")
        if (f.isNotEmpty() && f !in FREQUENCIES) return t("loan.err.frequency", "Please choose how often the instalment is paid.")
        val tenure = draft.num("tenure")
        if (tenure < 0 || tenure != kotlin.math.floor(tenure)) return t("loan.err.tenure", "The number of instalments must be a whole number.")
        if (draft.num("emi") <= 0 && tenure <= 0) return t("loan.err.emi", "Please enter the EMI or the number of instalments.")
        if (frequencyOf(draft) == "month" && draft.num("dueDay") !in 1.0..31.0)
            return t("loan.err.dueDay", "The due day must be a day of the month, from 1 to 31.")
        val emi = draft.num("emi")
        if (emi > 0) {
            val r = draft.num("rate") / 100 / perYear(frequencyOf(draft))
            if (emi <= draft.num("principal") * r) return t("loan.err.emiLow", "This EMI is too small to ever pay this off.")
        }
        val changes = draft.objs("rateChanges")
        if (changes.isNotEmpty() && interestMethod(draft) == "flat")
            return t("loan.err.rateChange.flat", "A new rate can only be set on an entry whose interest is on the amount still owed.")
        for (c in changes) validateRateChangeAt(start, c.s("from"), c.num("rate"), c["emi"]?.let { n(it) })?.let { return it }
        if (changes.map { it.s("from") }.toSet().size != changes.size) return t("loan.err.rateChange.twice", "There are two rate changes on the same date.")
    } else if (isPersonInstalments(draft)) {
        val f = draft.s("frequency")
        if (f.isNotEmpty() && f !in FREQUENCIES) return t("loan.err.frequency", "Please choose how often the instalment is paid.")
        if (draft.num("emi") <= 0) return t("loan.err.instalment", "Please enter the amount of each payment.")
        val tenure = draft.num("tenure")
        if (tenure <= 0 || tenure != kotlin.math.floor(tenure)) return t("loan.err.count", "Please enter how many payments there are, as a whole number.")
        if (draft.num("received") < 0) return t("loan.err.received", "The amount received cannot be below zero.")
        if (draft.num("received") > draft.num("principal")) return t("loan.err.received.more", "The amount received cannot be more than the amount itself.")
    } else {
        val e = draft.s("interestEvery")
        if (e.isNotEmpty() && e !in INTEREST_EVERY) return t("loan.err.every", "Please choose whether interest is paid every month or every year.")
    }
    if (kind == "gold") {
        val p = draft.s("pledge")
        if (p.isNotEmpty() && p !in PLEDGES) return t("loan.err.pledge", "Please choose what this is pledged against.")
        if (p == "other") {
            val what = draft.s("pledgeText").trim()
            if (what.isEmpty()) return t("loan.err.pledgeText", "Please say what is pledged.")
            if (what.length > PLEDGE_TEXT_MAX) return t("loan.err.pledgeText.long", "Please keep it to {n} letters or fewer.", "n" to PLEDGE_TEXT_MAX)
        }
        if (draft.num("grams") < 0 || (draft.num("grams") > 0 && pledgeOf(draft) != "gold")) return t("loan.err.grams", "Grams are only for a pledged gold entry.")
    }
    val dir = draft.s("direction")
    if (dir.isNotEmpty() && dir != "taken" && dir != "given") return t("loan.err.direction", "Please choose whether you took or gave this money.")
    if (dir == "given" && kind != "hand") return t("loan.err.direction", "Please choose whether you took or gave this money.")
    if (hasRepayBy(kind) && draft.s("repayBy").isNotEmpty()) {
        val by = parseISO(draft.s("repayBy")) ?: return t("loan.err.repayBy", "Please enter a date for when it is to be returned.")
        if (!by.isAfter(start)) return t("loan.err.repayBy.early", "The date to repay by must be after the entry started.")
    }
    return null
}

/** The first problem with a rate change for [loan], or null: a bank loan with reducing interest, a date after the start, a rate, an EMI above 0 when given. */
fun validateRateChange(loan: Obj, from: String, rate: Double, emi: Double?): String? {
    if (loan.s("kind") != "bank") return t("loan.err.rateChange.kind", "Only a Bank / EMI entry can have a new rate.")
    if (interestMethod(loan) == "flat") return t("loan.err.rateChange.flat", "A new rate can only be set on an entry whose interest is on the amount still owed.")
    val start = parseISO(loan.s("startDate")) ?: return t("loan.err.date", "Please enter the date the entry started.")
    return validateRateChangeAt(start, from, rate, emi)
}

private fun validateRateChangeAt(start: LocalDate, from: String, rate: Double, emi: Double?): String? {
    val d = parseISO(from) ?: return t("loan.err.rateChange.date", "Please enter the date the new rate starts.")
    if (!d.isAfter(start)) return t("loan.err.rateChange.early", "The new rate must start after the entry started.")
    if (rate < 0) return t("loan.err.rate", "The interest rate cannot be below zero.")
    if (emi != null && emi <= 0) return t("loan.err.rateChange.emi", "Please enter the new EMI, or leave it empty to keep the EMI.")
    return null
}

/** The first problem with renewing [loan] on [date] until [newRepayBy], or null. */
fun validateRenewal(loan: Obj, newRepayBy: String, date: String): String? {
    if (!hasRepayBy(loan.s("kind")) || loan.b("closed")) return t("loan.err.renew.kind", "Only an open pledged entry or one from a person can be renewed.")
    val on = parseISO(date) ?: return t("loan.err.renew.when", "Please enter the date it was renewed.")
    val by = parseISO(newRepayBy) ?: return t("loan.err.renew.date", "Please enter the new date to repay or renew by.")
    if (!by.isAfter(on)) return t("loan.err.renew.early", "The new date must be after the day it was renewed.")
    val start = parseISO(loan.s("startDate"))
    if (start != null && !by.isAfter(start)) return t("loan.err.repayBy.early", "The date to repay by must be after the entry started.")
    return null
}

/** The checks for a credit card or a new statement: an outstanding amount (0 is fine: paid in full), a minimum within it, a pay-by date. */
fun validateCard(draft: Obj): String? {
    if (draft.num("principal") < 0) return t("card.err.outstanding", "The outstanding amount cannot be below zero.")
    if (draft.num("minDue") < 0) return t("card.err.min", "The minimum due cannot be below zero.")
    if (draft.num("minDue") > draft.num("principal")) return t("card.err.minMore", "The minimum due cannot be more than the outstanding amount.")
    parseISO(draft.s("startDate")) ?: return t("card.err.statement", "Please enter the statement date.")
    parseISO(draft.s("dueDate")) ?: return t("card.err.due", "Please enter the date to pay by.")
    return null   // a pay-by date already past is fine: the bill is simply overdue
}

/** The first problem with a payment form, in plain words, or null. */
fun validatePayment(draft: Obj, today: LocalDate = Clock.today()): String? {
    val l = loan(draft.s("loanId")) ?: return t("pay.err.loan", "This entry could not be found.")
    val isNew = DB.objs("payments").none { it.s("id") == draft.s("id") && it.s("id").isNotEmpty() && !it.b("deleted") }
    if (l.b("closed") && isNew) return t("pay.err.closed", "This entry is closed. Reopen it to add a payment.")
    if (draft.num("amount") <= 0) return t("pay.err.amount", "Please enter the amount paid.")
    val d = parseISO(draft.s("date")) ?: return t("pay.err.date", "Please enter the date of the payment.")
    if (d.isAfter(today)) return t("pay.err.future", "The payment date cannot be in the future.")
    val k = draft.s("kind")
    if (k !in PAY_KINDS || (k == "emi" && !isInstalments(l))) return t("pay.err.kind", "Please choose what the payment was for.")
    return null
}
