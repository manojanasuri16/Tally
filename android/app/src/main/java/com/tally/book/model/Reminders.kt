package com.tally.book.model

import com.tally.book.core.*
import java.time.LocalDate

/*
 * What a due reminder needs (docs/DESIGN.md "Reminders"): pure, read from the book. The notification built on it names nothing
 * (no loan, person or amount), so it is safe on a locked phone; the amounts here are for the app's own screens.
 */

/**
 * What is due across all open loans on this phone (private ones too, since nothing leaves the phone), as
 * { horizon, any, taken: { overdue, today, soon }, given: { overdue, today, soon }, items: [...] }.
 *
 * [horizonDays] (clamped to 0..3) is how far "soon" looks: days 1..horizon after [today]. Counts are numbers of items.
 * Each item: { loanId, kind ("emi" | "interest" | "principal"), date, amount, count, given, state ("overdue" | "today" | "soon"),
 * days (from today to date; negative when overdue) }. Per loan: one "overdue" item for everything pending (dated before today: date
 * is the oldest, amount the sum, count how many), one "today" item for what falls today, one "soon" item per later day. A hand or
 * gold loan's repayBy date gives a "principal" item (the whole amount still owed) the same way. Given loans are what is expected back.
 * No-interest loans only ever give the principal item.
 */
fun remindersFor(today: LocalDate = Clock.today(), horizonDays: Int = 0): Obj {
    val horizon = horizonDays.coerceIn(0, 3)
    val until = today.plusDays(horizon.toLong())
    val items = ArrayList<Obj>()
    for (l in loans().filter { !it.b("closed") }) {
        val c = calcOf(l, paymentsOf(l.s("id")), today) ?: continue
        val given = isGiven(l)
        val kind = if (isCard(l)) "card" else if (isInstalments(l)) "emi" else "interest"
        fun item(k: String, date: LocalDate, amount: Double, count: Int, state: String) = obj(
            "loanId" to l.s("id"), "kind" to k, "date" to date.toString(), "amount" to amount, "count" to count.toDouble(),
            "given" to given, "state" to state, "days" to daysBetween(today, date).toDouble())
        val past = c.slots.filter { it.due.isBefore(today) }
        if (past.isNotEmpty()) items.add(item(kind, past.first().due, past.sumOf { it.left }, past.size, "overdue"))
        c.slots.filter { !it.due.isBefore(today) && !it.due.isAfter(until) }.groupBy { it.due }.forEach { (d, s) ->
            items.add(item(kind, d, s.sumOf { it.left }, s.size, if (d == today) "today" else "soon"))
        }
        val by = if (hasRepayBy(l.s("kind"))) parseISO(l.s("repayBy")) else null
        if (by != null && c.balance > 0 && !by.isAfter(until))
            items.add(item("principal", by, c.balance, 1, when { by.isBefore(today) -> "overdue"; by == today -> "today"; else -> "soon" }))
    }
    items.sortWith(compareBy<Obj> { it.s("date") }.thenBy { it.s("loanId") })
    fun counts(given: Boolean): Obj {
        val mine = items.filter { it.b("given") == given }
        return obj("overdue" to mine.count { it.s("state") == "overdue" }.toDouble(),
            "today" to mine.count { it.s("state") == "today" }.toDouble(), "soon" to mine.count { it.s("state") == "soon" }.toDouble())
    }
    val arr = arr(); items.forEach { arr.add(it) }
    return obj("horizon" to horizon.toDouble(), "any" to items.isNotEmpty(), "taken" to counts(false), "given" to counts(true), "items" to arr)
}

/**
 * Every date something falls due or is expected on, from today to [days] ahead (clamped to 0..400), as (date, given) pairs,
 * oldest first, with no names, ids or amounts: for the device copy the daily reminder reads while the book is sealed
 * (data/DueNotices.kt). Each open loan's own schedule gives them (instalments at their frequency, interest periods, the
 * repay-by date), worked from what has been paid so far; no-interest and closed loans give only their repay-by date (closed: none).
 * Everything already overdue collapses into the single oldest date per direction (all that matters is that it is overdue), so a
 * daily loan gives at most days + 2 dates per direction.
 */
fun dueDatesAhead(today: LocalDate = Clock.today(), days: Int = 60): List<Pair<LocalDate, Boolean>> {
    val until = today.plusDays(days.coerceIn(0, 400).toLong())
    val out = HashSet<Pair<LocalDate, Boolean>>()
    for (l in loans().filter { !it.b("closed") }) {
        val c = calcOf(l, paymentsOf(l.s("id")), until) ?: continue
        val given = isGiven(l)
        c.slots.forEach { if (!it.due.isAfter(until)) out.add(it.due to given) }
        val by = if (hasRepayBy(l.s("kind"))) parseISO(l.s("repayBy")) else null
        if (by != null && c.balance > 0 && !by.isAfter(until)) out.add(by to given)
    }
    val past = out.filter { it.first.isBefore(today) }
    val keep = out - past.toSet() + listOf(false, true).mapNotNull { g -> past.filter { it.second == g }.minByOrNull { it.first } }
    return keep.sortedWith(compareBy({ it.first }, { it.second }))
}

/**
 * The next payment for the home-screen widget (widget/, docs/DESIGN.md "Widget"): the loan [name], the [date] and the [amount] of
 * the soonest payment on money I took, and how many open loans are [overdue]. [date] is null when nothing falls due in the next days.
 * [late] marks that the payment shown is itself already overdue (nothing later was found), so it is already in [overdue].
 */
data class NextPayment(val name: String, val date: LocalDate?, val amount: Double, val overdue: Int, val late: Boolean)

/**
 * Looks at every open loan I took (money I gave is never in the widget): the earliest instalment, interest period or repay-by date
 * from [today] to [days] ahead, with everything unpaid on that date added up; the number of loans with something overdue; and, when
 * nothing is coming up but something is overdue, the oldest overdue one as the payment shown ([NextPayment.late]).
 */
fun nextPaymentFor(today: LocalDate = Clock.today(), days: Int = 60): NextPayment {
    val until = today.plusDays(days.coerceIn(0, 400).toLong())
    class Cand(val name: String, val date: LocalDate, val amount: Double)
    val upcoming = ArrayList<Cand>(); val past = ArrayList<Cand>()
    var overdue = 0
    for (l in loans().filter { !it.b("closed") && !isGiven(it) }) {
        val c = calcOf(l, paymentsOf(l.s("id")), until) ?: continue
        val name = loanTitle(l)
        val by = if (hasRepayBy(l.s("kind"))) parseISO(l.s("repayBy")) else null
        val late = c.slots.filter { it.due.isBefore(today) }
        val principalLate = by != null && c.balance > 0 && by.isBefore(today)
        if (late.isNotEmpty() || principalLate) {
            overdue++
            val oldest = listOfNotNull(late.firstOrNull()?.due, if (principalLate) by else null).min()
            past.add(Cand(name, oldest, if (principalLate) c.balance else late.sumOf { it.left }))
        }
        c.slots.filter { !it.due.isBefore(today) && !it.due.isAfter(until) }.groupBy { it.due }.minByOrNull { it.key }
            ?.let { (d, s) -> upcoming.add(Cand(name, d, s.sumOf { it.left })) }
        if (by != null && c.balance > 0 && !by.isBefore(today) && !by.isAfter(until)) upcoming.add(Cand(name, by, c.balance))
    }
    val order = compareBy<Cand> { it.date }.thenBy { it.name }
    upcoming.minWithOrNull(order)?.let { return NextPayment(it.name, it.date, it.amount, overdue, false) }
    past.minWithOrNull(order)?.let { return NextPayment(it.name, it.date, it.amount, overdue, true) }
    return NextPayment("", null, 0.0, 0, false)
}
