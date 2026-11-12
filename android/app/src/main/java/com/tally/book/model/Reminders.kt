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
        val kind = if (l.s("kind") == "bank") "emi" else "interest"
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
