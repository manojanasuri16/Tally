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
 * The loan arithmetic (rules: docs/DESIGN.md "Loan rules"). Pure: a loan, its payments and a date in, figures out. Nothing here writes.
 *
 * Two shapes of loan:
 *  - instalment loans (`bank`): a fixed instalment every month / fortnight / week / day, reducing-balance or flat interest,
 *    with optional rate changes from a date. EMI payments cover the instalments in order, oldest first.
 *  - interest-only loans (`hand` with a rate, `gold`): interest every month or year on what is still owed (never flat);
 *    the principal is returned when it is returned. Interest payments cover the periods in order, oldest first.
 *
 * A "slot" is one instalment or one interest period that is not fully paid yet: its due date and what is still to pay on it.
 */

val INTEREST_METHODS = listOf("reducing", "flat")
val FREQUENCIES = listOf("month", "fortnight", "week", "day")
val PLEDGES = listOf("gold", "lic", "fd", "land", "none", "other")

/**
 * "reducing" (default: interest on what is still owed) or "flat" (interest on the original amount for the whole loan).
 * Flat is for bank loans only: an interest-only entry (from a person, against something pledged) always charges interest on
 * what is still owed, even if an old copy says "flat".
 */
fun interestMethod(loan: Obj): String = if (loan.s("kind") == "bank" && loan.s("interestMethod") == "flat") "flat" else "reducing"

/** How often a bank loan's instalment falls: "month" (default), "fortnight", "week" or "day". */
fun frequencyOf(loan: Obj): String = loan.s("frequency").takeIf { it in FREQUENCIES } ?: "month"

/** Instalments in a year: 12, 26, 52 or 365. The periodic rate is the yearly rate divided by this. */
fun perYear(frequency: String): Int = when (frequency) { "fortnight" -> 26; "week" -> 52; "day" -> 365; else -> 12 }

/** What a gold-kind loan is taken against: "gold" (default), "lic", "fd", "land", "none" or "other" (named by `pledgeText`). */
fun pledgeOf(loan: Obj): String = loan.s("pledge").takeIf { it in PLEDGES } ?: "gold"

/** The longest `pledgeText`. */
const val PLEDGE_TEXT_MAX = 40

/** What the person typed for a pledge of "other" (trimmed, at most [PLEDGE_TEXT_MAX]); empty for any other pledge. */
fun pledgeTextOf(loan: Obj): String = if (pledgeOf(loan) == "other") loan.s("pledgeText").trim().take(PLEDGE_TEXT_MAX) else ""

/** The number of instalments a bank loan was agreed for (`tenure`); 0 when not given. */
fun tenureOf(loan: Obj): Int = max(0, loan.num("tenure").toInt())

/**
 * A loan paid back in fixed instalments: every bank loan, and a loan from (or to) a person with `plan` "instalments" (a lender who
 * collects ₹1,000 every week for 12 weeks on ₹10,000). Such a loan has `emi`, `tenure` and `frequency`, and EMI payments cover the
 * instalments in order. Everything else is interest-only (or no interest).
 */
fun isInstalments(loan: Obj): Boolean = loan.s("kind") == "bank" || (loan.s("kind") == "hand" && loan.s("plan") == "instalments")

/** A person's instalment loan (not a bank loan): no rate, the instalments add up to what is paid back. */
fun isPersonInstalments(loan: Obj): Boolean = loan.s("kind") == "hand" && loan.s("plan") == "instalments"

/** What a person's instalment loan pays back in all: each instalment × how many (12 × ₹1,000 = ₹12,000); 0 when either is missing. */
fun totalToRepay(loan: Obj): Double = if (loan.num("emi") > 0 && tenureOf(loan) > 0) loan.num("emi") * tenureOf(loan) else 0.0

/**
 * What a person's instalment loan costs over what was handed over: the total paid back less `received` (the money actually given, e.g.
 * ₹9,500 of ₹10,000), or less the amount when `received` is not given. 0 when it cannot be worked out or is not more.
 */
fun instalmentExtra(loan: Obj): Double {
    val total = totalToRepay(loan)
    val got = loan.num("received").takeIf { it > 0 } ?: loan.num("principal")
    return if (total <= 0 || got <= 0) 0.0 else max(0.0, total - got)
}

/**
 * The loan the instalment arithmetic sees for a person's instalment loan: the instalments are all that is paid back, so the amount
 * is their total and there is no rate (the lender's profit is in the instalments). A bank loan is itself.
 */
internal fun instalmentBasis(loan: Obj): Obj {
    if (!isPersonInstalments(loan)) return loan
    val b = loan.copyObj()
    val total = totalToRepay(loan)
    if (total > 0) b["principal"] = total
    b["rate"] = 0.0; b.remove("interestMethod"); b.remove("rateChanges"); b.remove("dueDay")
    return b
}

/** The period a hand or gold loan pays interest in: "month" or "year". */
fun interestEvery(loan: Obj): String {
    val e = loan.s("interestEvery")
    return if (e == "month" || e == "year") e else if (loan.s("kind") == "gold") "year" else "month"
}

/** Interest for one period on [base], following rateUnit ("year" default: percent a year; "month": percent a month); whole rupees. */
fun periodInterest(loan: Obj, base: Double): Double {
    val rate = loan.num("rate") / 100
    val perYear = if (loan.s("rateUnit") == "month") rate * 12 else rate
    return jsRound(base * (if (interestEvery(loan) == "month") perYear / 12 else perYear))
}

/**
 * The instalment of a reducing-balance loan, to the whole rupee (banks quote whole rupees): P·r·(1+r)^n / ((1+r)^n − 1) with
 * r = yearly rate / [perYear]. 0 when it cannot be worked out.
 */
fun emiFor(principal: Double, yearlyRatePct: Double, count: Int, perYear: Int = 12): Double {
    if (principal <= 0 || count <= 0) return 0.0
    val r = yearlyRatePct / 100 / perYear
    if (r <= 0) return jsRound(principal / count)
    val g = (1 + r).pow(count)
    return jsRound(principal * r * g / (g - 1))
}

/**
 * The instalment of a flat-rate loan, to the whole rupee: (P + P × yearly rate × years) / n, years = n / [perYear].
 * Each instalment's interest part is P × yearly rate / perYear, whatever n is.
 */
fun flatEmiFor(principal: Double, yearlyRatePct: Double, count: Int, perYear: Int = 12): Double {
    if (principal <= 0 || count <= 0) return 0.0
    return jsRound(principal / count + principal * yearlyRatePct / 100 / perYear)
}

/** Instalments needed to clear [balance] with [emi] at periodic rate [r] (reducing); -1 when the EMI does not even cover the interest. */
internal fun countToClear(balance: Double, r: Double, emi: Double): Int {
    if (balance <= 0.005) return 0
    if (emi <= 0) return -1
    if (r <= 0) return ceil(balance / emi - 1e-9).toInt()
    if (emi <= balance * r) return -1
    return ceil(-ln(1 - balance * r / emi) / ln(1 + r) - 1e-9).toInt()
}

/** Rate changes of a bank loan with a readable date, oldest first. */
fun rateChangesOf(loan: Obj): List<Obj> =
    loan.objs("rateChanges").filter { parseISO(it.s("from")) != null }.sortedBy { it.s("from") }

internal fun monthKey(d: LocalDate): String = YearMonth.from(d).toString()
internal fun clampDay(key: String, day: Int): LocalDate = LocalDate.parse("$key-01").withDayOfMonth(min(max(day, 1), daysIn(key)))

internal fun sortedLive(payments: List<Obj>, loanId: String): List<Obj> =
    payments.filter { !it.b("deleted") && it.s("loanId") == loanId }
        .sortedWith(compareBy<Obj> { it.s("date") }.thenBy { it.num("at") })

/** One instalment or interest period not fully paid: [left] is what is still to pay on it, [full] its whole amount. */
internal class Slot(val due: LocalDate, val left: Double, val full: Double)

/** Everything worked out for one loan; [slots] are the unpaid instalments/periods, oldest first, at least up to [limit]. */
internal class LoanCalc(
    val balance: Double, val paidTotal: Double, val paidPrincipal: Double, val paidInterest: Double,
    val slots: List<Slot>, val hasPaid: Boolean,
    // instalment loans
    val emiNow: Double = 0.0, val emiStart: Double = 0.0, val rateNow: Double = 0.0, val count: Int = 0,
    val covered: Int = 0, val clears: Boolean = true, val flatInterestPart: Double = 0.0,
    // interest-only loans
    val periodInterestNow: Double = 0.0,
    // both: instalments or periods counted as paid by `paidUpTo`
    val prePaid: Int = 0,
)

/** The loan's `paidUpTo` date (everything due on or before it was paid as agreed before the loan was entered), or null. */
fun paidUpToOf(loan: Obj): LocalDate? = parseISO(loan.s("paidUpTo"))

/** A logged payment dated on or before `paidUpTo`: counted in paidTotal only, it covers nothing (the date already covers it). */
private fun beforePaidUpTo(p: Obj, upTo: LocalDate?): Boolean {
    if (upTo == null) return false
    val d = parseISO(p.s("date")) ?: return false
    return !d.isAfter(upTo)
}

/** How far ahead slots are worked out: the end of this month, or three days on when that is later (reminders look 3 days ahead). */
internal fun slotLimit(today: LocalDate): LocalDate {
    val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
    val ahead = today.plusDays(3)
    return if (ahead.isAfter(monthEnd)) ahead else monthEnd
}

// ---- instalment loans ----

/** The due date of instalment [k] (1 = the first). */
internal class Schedule(private val start: LocalDate, private val frequency: String, dueDay: Int) {
    private val day = if (dueDay in 1..31) dueDay else start.dayOfMonth
    private val firstKey: String = monthKey(start).let { sk -> if (clampDay(sk, day).isAfter(start)) sk else addMonths(sk, 1) }
    fun due(k: Int): LocalDate = when (frequency) {
        "fortnight" -> start.plusDays(14L * k)
        "week" -> start.plusDays(7L * k)
        "day" -> start.plusDays(k.toLong())
        else -> clampDay(addMonths(firstKey, k - 1), day)
    }
}

/** The yearly rate in force on [d]: the newest rate change dated on or before it, else the loan's own rate. */
fun rateOn(loan: Obj, d: LocalDate): Double {
    var rate = loan.num("rate")
    for (c in rateChangesOf(loan)) if (!parseISO(c.s("from"))!!.isAfter(d)) rate = c.num("rate")
    return rate
}

private fun emiOn(base: Double, changes: List<Obj>, d: LocalDate): Double {
    var emi = base
    for (c in changes) if (!parseISO(c.s("from"))!!.isAfter(d) && c.num("emi") > 0) emi = c.num("emi")
    return emi
}

internal fun calcInstalments(loan: Obj, pays: List<Obj>, start: LocalDate, today: LocalDate): LoanCalc {
    val p0 = loan.num("principal")
    val flat = interestMethod(loan) == "flat"
    val freq = frequencyOf(loan)
    val ppy = perYear(freq)
    val baseRate = loan.num("rate")
    /* flat loans keep their one rate: rate changes are for reducing-balance loans (validateLoan refuses them on flat ones) */
    val changes = if (flat) emptyList() else rateChangesOf(loan)
    val fi = if (flat) p0 * baseRate / 100 / ppy else 0.0
    var n = tenureOf(loan)
    var emi0 = loan.num("emi")
    if (emi0 <= 0) emi0 = if (flat) flatEmiFor(p0, baseRate, n, ppy) else emiFor(p0, baseRate, n, ppy)
    else if (n <= 0) n = max(0, if (flat) (if (emi0 > fi) ceil(p0 / (emi0 - fi) - 1e-9).toInt() else -1) else countToClear(p0, baseRate / 100 / ppy, emi0))
    if (emi0 <= 0) {   /* neither an EMI nor a count: nothing can be scheduled (validateLoan refuses this) */
        var bal = p0; var pp = 0.0; var pi = 0.0; var pt = 0.0
        for (p in pays) { val a = p.num("amount"); pt += a; if (p.s("kind") == "interest") pi += a else { val x = min(bal, a); bal -= x; pp += x } }
        return LoanCalc(if (bal < 0.005) 0.0 else bal, pt, pp, pi, emptyList(), false, rateNow = baseRate, count = n, clears = false)
    }
    val sched = Schedule(start, freq, loan.num("dueDay").toInt())
    fun rAt(d: LocalDate) = if (flat) 0.0 else rateOn(loan, d) / 100 / ppy
    fun emiAt(d: LocalDate) = if (flat) emi0 else emiOn(emi0, changes, d)
    val lastChange = changes.lastOrNull()?.let { parseISO(it.s("from")) }

    var bal = p0
    var paidPrincipal = 0.0; var paidInterest = 0.0; var paidTotal = 0.0
    var k = 1; var started = false; var left = 0.0; var intLeft = 0.0; var full = 0.0; var hasPaid = false
    fun open(b: Double) {
        val d = sched.due(k)
        val i = if (flat) fi else b * rAt(d)
        val e = emiAt(d)
        /* the last agreed instalment evens out the rupees left over from rounding the EMI to the rupee */
        full = if (n > 0 && k >= n && b + i - e <= n + 1.0) b + i else min(e, b + i)
        left = full; intLeft = min(i, full); started = true
    }
    /* paidUpTo: every instalment dated on or before it was paid as agreed, so the balance follows the schedule to that date */
    val upTo = paidUpToOf(loan)
    var prePaid = 0
    if (upTo != null) while (bal > 0.005 && !sched.due(k).isAfter(upTo) && prePaid < 100000) {
        open(bal)
        bal -= max(0.0, min(bal, left - intLeft))
        k++; started = false; prePaid++
    }
    for (p in pays) {
        val amt = p.num("amount")
        paidTotal += amt
        if (beforePaidUpTo(p, upTo)) continue
        when (p.s("kind")) {
            "principal" -> { val x = min(bal, amt); bal -= x; paidPrincipal += x }
            "interest" -> paidInterest += amt
            "emi" -> {
                hasPaid = true
                var m = amt; var guard = 0
                while (m > 0.005 && bal > 0.005 && guard++ < 100000) {
                    if (!started) open(bal)
                    val pay = min(m, left)
                    val ip = min(pay, intLeft)
                    val pp = min(bal, pay - ip)
                    intLeft -= ip; bal -= pp; paidInterest += ip; paidPrincipal += pp
                    left -= pay; m -= pay
                    if (left <= 0.005) { k++; started = false }
                }
            }
        }
    }
    if (bal < 0.005) bal = 0.0
    val covered = k - 1

    /* the instalments still to pay, worked forward from what is owed now */
    val limit = slotLimit(today)
    val slots = ArrayList<Slot>()
    var pb = bal; var clears = true; var guard = 0
    while (pb > 0.005) {
        if (guard++ > 40000) { clears = false; break }
        val d = sched.due(k)
        if (!started) open(pb)
        val principalPart = max(0.0, min(pb, left - intLeft))
        if (!clears && d.isAfter(limit)) break
        slots.add(Slot(d, left, full))
        if (principalPart <= 1e-9 && (lastChange == null || !lastChange.isAfter(d))) clears = false
        pb -= principalPart; k++; started = false
        if (!clears && d.isAfter(limit)) break
    }
    return LoanCalc(bal, paidTotal, paidPrincipal, paidInterest, slots, hasPaid || prePaid > 0,
        emiNow = emiAt(today), emiStart = emi0, rateNow = if (flat) baseRate else rateOn(loan, today), count = n,
        covered = covered, clears = clears, flatInterestPart = fi, prePaid = prePaid)
}

// ---- interest-only loans ----

internal fun calcInterestOnly(loan: Obj, pays: List<Obj>, start: LocalDate, today: LocalDate, noInterest: Boolean): LoanCalc {
    val p0 = loan.num("principal")
    val flat = interestMethod(loan) == "flat"
    var bal = p0
    var paidPrincipal = 0.0; var paidInterest = 0.0; var paidTotal = 0.0
    var repaidOn: LocalDate? = null
    val returns = ArrayList<Pair<LocalDate?, Double>>()
    /* paidUpTo: the principal is what was still owed on that date, and every period ending on or before it was paid */
    val upTo = paidUpToOf(loan)
    for (p in pays) {
        val amt = p.num("amount"); paidTotal += amt
        if (beforePaidUpTo(p, upTo)) continue
        when (p.s("kind")) {
            "principal", "emi" -> {
                val x = min(bal, amt); bal -= x; paidPrincipal += x
                val d = parseISO(p.s("date")); returns.add(d to x)
                if (bal < 0.005 && repaidOn == null && x > 0) repaidOn = d ?: start
            }
            "interest" -> paidInterest += amt
        }
    }
    if (bal < 0.005) bal = 0.0
    if (noInterest) return LoanCalc(bal, paidTotal, paidPrincipal, paidInterest, emptyList(), false)

    fun owedOn(d: LocalDate): Double = max(0.0, p0 - returns.filter { it.first == null || !it.first!!.isAfter(d) }.sumOf { it.second })
    val step = if (interestEvery(loan) == "month") 1 else 12
    val sk = monthKey(start)
    val limit = slotLimit(today)
    val slots = ArrayList<Slot>()
    var t = paidInterest
    var prev = start; var k = 1; var prePaid = 0
    while (k < 5000) {
        val due = clampDay(addMonths(sk, k * step), start.dayOfMonth)
        /* only whole periods that ended while money was still owed */
        if (repaidOn != null && due.isAfter(repaidOn)) break
        if (upTo != null && !due.isAfter(upTo)) { prePaid++; prev = due; k++; continue }
        val whole = periodInterest(loan, if (flat) p0 else owedOn(prev))
        val cover = min(t, whole); t -= cover
        val unpaid = whole - cover
        if (unpaid > 0.005) slots.add(Slot(due, unpaid, whole))
        if (t <= 0.005 && due.isAfter(limit) && (slots.isNotEmpty() || whole <= 0.005)) break
        prev = due; k++
    }
    val now = if (bal <= 0) 0.0 else periodInterest(loan, if (flat) p0 else bal)
    return LoanCalc(bal, paidTotal, paidPrincipal, paidInterest, slots, paidInterest > 0 || prePaid > 0, periodInterestNow = now, prePaid = prePaid)
}

/**
 * For the loan form: the most recent instalment or interest date before [today] by the draft's own schedule (a bank loan: at most its
 * agreed number of instalments), so "Paid up to" can be pre-filled when the start date is in the past. "" when none has come due
 * yet, for a no-interest loan, or when the draft cannot be scheduled.
 */
fun suggestPaidUpTo(draft: Obj, today: LocalDate = Clock.today()): String {
    val start = parseISO(draft.s("startDate")) ?: return ""
    var last: LocalDate? = null
    if (isInstalments(draft)) {
        val sched = Schedule(start, frequencyOf(draft), if (isPersonInstalments(draft)) 0 else draft.num("dueDay").toInt())
        val n = tenureOf(draft)
        var k = 1
        while (k < 100000 && (n <= 0 || k <= n)) { val d = sched.due(k); if (!d.isBefore(today)) break; last = d; k++ }
    } else if (draft.s("kind") in setOf("hand", "gold") && draft.num("rate") > 0) {
        val step = if (interestEvery(draft) == "month") 1 else 12
        var k = 1
        while (k < 5000) { val d = clampDay(addMonths(monthKey(start), k * step), start.dayOfMonth); if (!d.isBefore(today)) break; last = d; k++ }
    }
    return last?.toString() ?: ""
}
