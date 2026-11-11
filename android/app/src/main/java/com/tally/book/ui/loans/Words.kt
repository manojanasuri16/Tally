package com.tally.book.ui.loans

import com.tally.book.core.*
import java.time.LocalDate

/* Plain-word helpers for the loan screens: no Compose, so they can be tested. */

/**
 * A date as a person would say it: "Today", "Tomorrow", "Yesterday", otherwise "15 Nov" (with the year when it is not this year).
 * Blank when [iso] is not a date.
 */
fun dateInWords(iso: String, today: LocalDate = Clock.today()): String {
    val d = parseISO(iso) ?: return ""
    return when (daysBetween(today, d)) {
        0L -> t("date.today", "Today")
        1L -> t("date.tomorrow", "Tomorrow")
        -1L -> t("date.yesterday", "Yesterday")
        else -> if (d.year == today.year) fmtISODateNoYear(iso) else fmtISODate(iso)
    }
}

/** "9.5% a year" / "2% a month": the rate with the unit it was agreed in. [unit] is the loan's rateUnit ("month", else year). */
fun rateInWords(rate: Double, unit: String): String {
    val r = fmtIN(rate, 2)
    return if (unit == "month") t("loan.rate.month", "{r}% a month", "r" to r) else t("loan.rate.year", "{r}% a year", "r" to r)
}

/** "every month" / "every year". */
fun everyInWords(every: String): String =
    if (every == "year") t("loan.every.year", "Every year") else t("loan.every.month", "Every month")

/** The kinds of payment that make sense for a loan kind: a bank loan has EMIs and part-payments; hand and gold loans pay interest and the principal. */
fun payKindsFor(loanKind: String): List<String> =
    if (loanKind == "bank") listOf("emi", "principal") else listOf("interest", "principal")

/** [payKindsFor] plus [keep] (a payment already saved with another kind keeps showing it). */
fun payKindOptions(loanKind: String, keep: String?): List<String> {
    val base = payKindsFor(loanKind)
    return if (!keep.isNullOrEmpty() && keep !in base && keep in com.tally.book.model.PAY_KINDS) base + keep else base
}
