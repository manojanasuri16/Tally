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

/**
 * The words that turn around for money I gave. One place, so the screens never branch on it: [loanWords] picks the set.
 * "Taken" is the original wording; "given" is the same screen seen from the other side.
 */
class LoanWords(
    val who: String, val whoEg: String, val amount: String, val date: String, val left: String, val log: String,
    val interest: String, val principal: String, val expected: String, val next: String,
    val paidMonth: String, val paidYear: String, val toReturn: String, val closeAsk: String, val kindHelp: String,
)

fun loanWords(given: Boolean): LoanWords = if (given) LoanWords(
    who = t("loan.lender.given", "Who took the money?"), whoEg = t("loan.lender.given.eg", "Their name"),
    amount = t("loan.principal.given", "Amount given"), date = t("loan.startDate.given", "Date given"),
    left = t("loan.left.given", "Still to come back"), log = t("pay.log.given", "Log money received"),
    interest = t("pay.kind.interest.given", "Interest received"), principal = t("pay.kind.principal.given", "Got some of the amount back"),
    expected = t("home.expected", "Expected this month"), next = t("loan.next.given", "Expected next"),
    paidMonth = t("loan.paid.month.given", "Received for this month"), paidYear = t("loan.paid.year.given", "Received for this year"),
    toReturn = t("home.toComeBack", "To come back"),
    closeAsk = t("pay.closeAsk.given", "Everything has come back. Close this loan now?"),
    kindHelp = t("loan.kind.given.help", "Money you lent to someone you know"),
) else LoanWords(
    who = t("loan.lender.person", "Who lent the money?"), whoEg = t("loan.lender.person.eg", "Their name"),
    amount = t("loan.principal", "Amount borrowed"), date = t("loan.startDate", "Date taken"),
    left = t("loan.left", "Still to pay"), log = t("pay.log", "Log payment"),
    interest = t("pay.kind.interest", "Interest"), principal = t("pay.kind.principal.hand", "Returned some of the amount"),
    expected = t("home.due", "Due this month"), next = t("loan.next", "Next payment"),
    paidMonth = t("loan.paid.month", "Paid for this month"), paidYear = t("loan.paid.year", "Paid for this year"),
    toReturn = t("home.toReturn", "To be returned"),
    closeAsk = t("pay.closeAsk", "Everything is paid. Close this loan now?"),
    kindHelp = t("loan.kind.hand.help", "Money borrowed from someone you know"),
)

/** The words for one loan (hand loans I gave are turned around; anything else is money I took). */
fun loanWords(loan: Obj): LoanWords = loanWords(com.tally.book.model.isGiven(loan))

/** A loan row's second line: "<lender> · added by <name>"; just the lender when [adder] is null (nobody else could have added it). */
fun loanRowSub(lender: String, adder: String?): String? = when {
    adder == null -> lender.ifBlank { null }
    lender.isBlank() -> t("loan.addedBy", "Added by {name}", "name" to adder)
    else -> t("loan.row.addedBy", "{lender} · added by {name}", "lender" to lender, "name" to adder)
}

/** The detail's line under the kind: "Added by Amma on 05-Nov-2026", then "Last changed by Nanna" when someone else edited it. */
fun addedByLine(by: String, atMillis: Double, updatedBy: String, myName: (String) -> String, dateOf: (Double) -> String): String {
    val who = myName(by)
    val on = if (atMillis > 0) dateOf(atMillis) else ""
    val added = if (on.isEmpty()) t("loan.addedBy", "Added by {name}", "name" to who) else t("loan.addedByOn", "Added by {name} on {date}", "name" to who, "date" to on)
    return if (updatedBy.isNotEmpty() && by.isNotEmpty() && updatedBy != by)
        added + "\n" + t("loan.changedBy", "Last changed by {name}", "name" to myName(updatedBy))
    else added
}

/** What the interest choice of a hand loan form is, from the stored fields: "none" (rate 0), "month" or "year". */
fun interestChoice(rate: Double, rateUnit: String): String = if (rate <= 0) "none" else if (rateUnit == "month") "month" else "year"

/** The kinds of payment that make sense for a loan kind: a bank loan has EMIs and part-payments; hand and gold loans pay interest and the principal. */
fun payKindsFor(loanKind: String): List<String> =
    if (loanKind == "bank") listOf("emi", "principal") else listOf("interest", "principal")

/** [payKindsFor] plus [keep] (a payment already saved with another kind keeps showing it). */
fun payKindOptions(loanKind: String, keep: String?): List<String> {
    val base = payKindsFor(loanKind)
    return if (!keep.isNullOrEmpty() && keep !in base && keep in com.tally.book.model.PAY_KINDS) base + keep else base
}
