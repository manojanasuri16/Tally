package com.tally.book.widget

import com.tally.book.core.*
import java.time.LocalDate

/*
 * What the "Next payment" widget says (docs/DESIGN.md "Widget"). Pure, so every state is unit tested.
 *
 * Two sources, and only one is ever used at a time:
 *  - the SNAPSHOT (name, date, amount, overdue count), kept on the device only while the app is open with no app lock and amounts
 *    shown: the full text;
 *  - otherwise only the DATES of the due-date cache (data/DueNotices.kt): a text that names nobody and shows no amount, the same
 *    privacy rule as the reminder notification.
 */
object WidgetText {
    /** The locked text counts payments in this many days ahead as "due soon". */
    const val SOON_DAYS = 14

    /** What the app wrote: the soonest payment on money I took (empty [date] when none), and how many loans are overdue. */
    data class Snap(val name: String, val date: String, val amount: Double, val overdue: Int, val late: Boolean)

    /** The widget's words. [single] is the one-line form: "Next payment · City Bank · 5 Dec · ₹21,696" or "Tally · 1 payment due soon". */
    data class Lines(val caption: String, val main: String, val detail: String?, val overdue: String?) {
        fun single(): String = listOfNotNull(caption, main, detail).joinToString(" · ")
    }

    /** [snap] when the app may show names and amounts, else null; [taken] the cached due dates of money I took (null: never saved). */
    fun choose(snap: Snap?, taken: List<String>?, today: LocalDate): Lines = when {
        snap != null -> full(snap, today)
        taken != null -> private(taken, today)
        else -> Lines(app(), t("widget.open", "Open Tally to see your payments"), null, null)
    }

    private fun app() = t("app.name", "Tally")

    fun full(s: Snap, today: LocalDate): Lines {
        val date = parseISO(s.date)
        // the snapshot is from an earlier day: its payment may have fallen due since, and that is one more overdue
        val overdue = s.overdue + if (date != null && date.isBefore(today) && !s.late) 1 else 0
        val overdueLine = if (overdue > 0) tp("widget.overdue", overdue, "{n} overdue", "{n} overdue") else null
        val caption = t("widget.next", "Next payment")
        if (date == null) return Lines(caption, t("widget.nothing", "Nothing due soon"), null, overdueLine)
        return Lines(caption, s.name.ifBlank { t("widget.aLoan", "A loan") }, whenText(date, today) + " · " + inr(s.amount), overdueLine)
    }

    /** Names nothing and shows no amount: overdue, how many dates are due in the next [SOON_DAYS] days, or nothing. */
    fun private(taken: List<String>, today: LocalDate): Lines {
        val dates = taken.mapNotNull { parseISO(it) }.distinct()
        val soon = dates.count { !it.isBefore(today) && !it.isAfter(today.plusDays(SOON_DAYS.toLong())) }
        val main = when {
            dates.any { it.isBefore(today) } -> t("notice.due.overdue", "Payments are overdue")
            soon > 0 -> tp("widget.soon", soon, "{n} payment due soon", "{n} payments due soon")
            else -> t("widget.nothing", "Nothing due soon")
        }
        return Lines(app(), main, null, null)
    }

    /** "Today", "Tomorrow" or a short date ("5 Dec"). */
    fun whenText(date: LocalDate, today: LocalDate): String = when (daysBetween(today, date)) {
        0L -> t("widget.today", "Today")
        1L -> t("widget.tomorrow", "Tomorrow")
        else -> fmtISODateNoYear(date.toString()).trimStart('0')
    }
}
