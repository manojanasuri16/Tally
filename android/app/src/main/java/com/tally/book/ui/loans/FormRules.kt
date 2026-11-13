package com.tally.book.ui.loans

import com.tally.book.core.*
import com.tally.book.model.INTEREST_EVERY
import com.tally.book.model.frequencyOf
import com.tally.book.model.hasRepayBy
import java.time.LocalDate

/*
 * Which parts of the loan form are on screen for the choices made so far (progressive disclosure), and the little sums the
 * loan sheets start from. No Compose, so they can be tested: LoanSheet only asks.
 */
object LoanFormRules {
    /** A rate is entered (every kind but a hand loan that says No interest): the Reducing / Flat choice goes with it. */
    fun hasInterest(kind: String, handInterest: String): Boolean = kind != "card" && (kind != "hand" || handInterest != "none")

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

/**
 * The loan form in Easy mode: only the essential questions (kind, name, who, amount, date, the rate, EMI or number of months, grams, repay
 * or renew by, Keep private). Reducing / Flat, how often, the pay-on day, "interest is paid every" and Paid up to stay at their defaults and
 * are not drawn. But a loan that already uses one of them is never edited blind: [extrasOf] says which ones it uses, and the form then draws
 * those fields as in the standard form. A new loan whose start date is in the past still gets Paid up to from [suggestPaidUpTo] (so an old loan
 * never shows years of pending payments), with one quiet line saying so ([paidUpToNote]).
 * Pure, so it is tested (EasyModeTest); LoanSheet only asks.
 */
object EasyForm {
    /** Which hidden options a loan already uses. */
    data class Extras(val method: Boolean = false, val frequency: Boolean = false, val dueDay: Boolean = false, val every: Boolean = false, val paidUpTo: Boolean = false) {
        val any: Boolean get() = method || frequency || dueDay || every || paidUpTo
    }

    val NONE = Extras()

    /** The advanced options [existing] uses (none for a new loan). */
    fun extrasOf(existing: Obj?): Extras {
        existing ?: return NONE
        val kind = existing.s("kind")
        val start = parseISO(existing.s("startDate"))
        val day = existing.num("dueDay").toInt()
        val every = existing.s("interestEvery")
        return Extras(
            method = kind == "bank" && existing.s("interestMethod") == "flat",
            frequency = kind == "bank" && frequencyOf(existing) != "month",
            dueDay = kind == "bank" && frequencyOf(existing) == "month" && day in 1..31 && start != null && day != start.dayOfMonth,
            every = kind != "bank" && every in INTEREST_EVERY && every != (if (kind == "gold") "year" else "month"),
            paidUpTo = existing.s("paidUpTo").isNotEmpty(),
        )
    }

    /** Reducing/Flat is a question for bank loans only. Interest-only entries (from a person, against something pledged) pay interest on what is still owed. */
    fun showMethod(easy: Boolean, kind: String, hasInterest: Boolean, x: Extras): Boolean =
        kind == "bank" && hasInterest && (!easy || x.method)
    fun showFrequency(easy: Boolean, kind: String, x: Extras): Boolean = LoanFormRules.showFrequency(kind) && (!easy || x.frequency)
    fun showDueDay(easy: Boolean, kind: String, frequency: String, x: Extras): Boolean = LoanFormRules.showDueDay(kind, frequency) && (!easy || x.dueDay)
    fun showEvery(easy: Boolean, kind: String, hasInterest: Boolean, x: Extras): Boolean = kind != "bank" && hasInterest && (!easy || x.every)

    /** The Paid up to field itself. Easy mode draws it only for a loan that already has one. */
    fun showPaidUpToField(easy: Boolean, paidShown: Boolean, x: Extras): Boolean = paidShown && (!easy || x.paidUpTo)

    /** The quiet line "Payments up to <date> are counted as already paid." instead of the field: Easy mode, a new loan, with a suggestion. */
    fun paidUpToNote(easy: Boolean, editing: Boolean, suggestion: String): Boolean = easy && !editing && suggestion.isNotEmpty()
}
