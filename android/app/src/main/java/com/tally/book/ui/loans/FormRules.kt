package com.tally.book.ui.loans

import com.tally.book.core.*
import com.tally.book.model.hasRepayBy
import java.time.LocalDate

/*
 * Which parts of the loan form are on screen for the choices made so far (progressive disclosure), and the little sums the
 * loan sheets start from. No Compose, so they can be tested: LoanSheet only asks.
 */
object LoanFormRules {
    /** A rate is entered (every kind but a hand loan that says No interest): the Reducing / Flat choice goes with it. */
    fun hasInterest(kind: String, handInterest: String): Boolean = kind != "hand" || handInterest != "none"

    /** "How often do you pay?": bank loans only. */
    fun showFrequency(kind: String): Boolean = kind == "bank"

    /** "Pay on this day of the month": a monthly bank loan only. */
    fun showDueDay(kind: String, frequency: String): Boolean = kind == "bank" && frequency == "month"

    /** The quiet "Every Tuesday" line from the start date: weekly and fortnightly bank loans. */
    fun showWeekday(kind: String, frequency: String): Boolean = kind == "bank" && (frequency == "week" || frequency == "fortnight")

    /** What is pledged comes first for a loan against something. */
    fun showPledge(kind: String): Boolean = kind == "gold"

    /** Grams only for gold. */
    fun showGrams(kind: String, pledge: String): Boolean = kind == "gold" && pledge == "gold"

    /** The repay-or-renew date is among the main fields for a gold loan; everything else keeps it under "Add details". */
    fun repayByInMain(kind: String, pledge: String): Boolean = kind == "gold" && pledge == "gold"

    /** The repay-by date exists for these kinds at all. */
    fun repayByApplies(kind: String): Boolean = hasRepayBy(kind)

    /**
     * "Paid up to": offered for a new loan when something has already come due ([suggestion] not empty), and for a loan being edited
     * only when it already has one (an edit never invents it: payments may be logged already).
     */
    fun showPaidUpTo(editing: Boolean, existing: String, suggestion: String): Boolean =
        if (editing) existing.isNotEmpty() else suggestion.isNotEmpty()

    /** The "enter what is still owed" hint sits next to the amount of a hand or gold loan once Paid up to is shown. */
    fun showAmountHint(kind: String, paidUpToShown: Boolean): Boolean = paidUpToShown && (kind == "hand" || kind == "gold")

    /** The number-of-instalments label follows how often the loan is paid. */
    fun tenureLabel(frequency: String): String = when (frequency) {
        "fortnight" -> t("loan.tenure.fortnight", "Number of fortnights")
        "week" -> t("loan.tenure.week", "Number of weeks")
        "day" -> t("loan.tenure.day", "Number of days")
        else -> t("loan.tenure", "Number of months")
    }

    /**
     * The new repay-by date a renewal starts with: a gold loan, twelve months after the renewal; anything else, the same span the
     * loan was running for (from the start, or the last renewal, to the current date) after the renewal. "" when it cannot be worked out.
     */
    fun suggestRenewal(loan: Obj, renewedOn: String): String {
        val on = parseISO(renewedOn) ?: return ""
        if (loan.s("kind") == "gold") return on.plusMonths(12).toString()
        val by = parseISO(loan.s("repayBy")) ?: return ""
        val last = loan.objs("renewals").mapNotNull { parseISO(it.s("date")) }.maxOrNull()
        val from: LocalDate = last ?: parseISO(loan.s("startDate")) ?: return ""
        val span = maxOf(1L, daysBetween(from, by))
        return on.plusDays(span).toString()
    }
}
