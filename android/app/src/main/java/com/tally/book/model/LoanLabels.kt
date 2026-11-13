package com.tally.book.model

import com.tally.book.core.*

/* Labels and plain-word helpers that read a loan's fields. Every word goes through t(); the keys are in assets/i18n/te.json and hi.json. */

/** The kind as a group name: "Bank loan", "From a person", "Loan against something" (the gold kind, whatever is pledged). */
fun kindLabel(kind: String?): String = when (kind) {
    "bank" -> t("loan.kind.bank", "Bank / EMI")
    "hand" -> t("loan.kind.hand", "From a person")
    "gold" -> t("loan.kind.secured", "Pledged")
    else -> t("loan.kind.other", "Entry")
}

/**
 * The kind of one loan as it reads on its row and detail: money I gave reads "To a person"; a loan against something names what
 * is pledged ([securedTitle]); everything else is [kindLabel].
 */
fun loanKindLabel(loan: Obj): String = when {
    isGiven(loan) -> t("loan.kind.given", "To a person")
    loan.s("kind") == "gold" -> securedTitle(pledgeOf(loan), pledgeTextOf(loan))
    else -> kindLabel(loan.s("kind"))
}

/** What is pledged, as a choice: "Gold", "LIC policy", "Fixed deposit", "Land papers", "Nothing pledged". */
fun pledgeLabel(pledge: String?, text: String? = null): String = when (pledge) {
    "other" -> text?.trim().orEmpty().ifEmpty { t("pledge.other", "Something else") }   // typed by the person: never translated
    "lic" -> t("pledge.lic", "LIC policy")
    "fd" -> t("pledge.fd", "Fixed deposit")
    "land" -> t("pledge.land", "Land papers")
    "none" -> t("pledge.none", "Nothing pledged")
    else -> t("pledge.gold", "Gold")
}

/** A loan against something, named the way people say it: "Gold loan", "Loan against LIC policy", "Loan against FD", "Loan against land papers", "Crop / other loan". */
fun securedTitle(pledge: String?, text: String? = null): String = when (pledge) {
    "other" -> text?.trim().orEmpty().let { if (it.isEmpty()) t("loan.title.other", "Loan against something else") else t("loan.title.pledgeText", "Loan against {what}", "what" to it) }
    "lic" -> t("loan.title.lic", "Loan against LIC policy")
    "fd" -> t("loan.title.fd", "Loan against FD")
    "land" -> t("loan.title.land", "Loan against land papers")
    "none" -> t("loan.title.none", "Crop / other loan")
    else -> t("loan.kind.gold", "Gold loan")
}

/**
 * What a payment was for, in the words of the loan it is on. [loanKind] "hand" says "Returned some of the amount" for a part repayment
 * (a bank or gold loan keeps "Principal"); [given] turns the hand loan around ("Interest received", "Got some of the amount back").
 */
fun payKindLabel(kind: String?, loanKind: String? = null, given: Boolean = false): String = when {
    kind == "emi" && loanKind == "hand" && given -> t("pay.kind.instalment.given", "Instalment received")
    kind == "emi" && loanKind == "hand" -> t("pay.kind.instalment", "Instalment")
    kind == "emi" -> t("pay.kind.emi", "EMI")
    kind == "interest" && given -> t("pay.kind.interest.given", "Interest received")
    kind == "interest" -> t("pay.kind.interest", "Interest")
    kind == "principal" && given -> t("pay.kind.principal.given", "Got some of the amount back")
    kind == "principal" && loanKind == "hand" -> t("pay.kind.principal.hand", "Returned some of the amount")
    kind == "principal" -> t("pay.kind.principal", "Principal")
    else -> t("pay.kind.other", "Payment")
}

/** The interest method as a choice: "On the amount still owed" (reducing) / "On the full amount" (flat). */
fun interestMethodLabel(method: String?): String =
    if (method == "flat") t("loan.method.flat", "On the full amount") else t("loan.method.reducing", "On the amount still owed")

/** A line under the choice: what the method means in practice. */
fun interestMethodHelp(method: String?): String =
    if (method == "flat") t("loan.method.flat.help", "Interest stays on the amount first taken, even after part of it is paid back.")
    else t("loan.method.reducing.help", "Interest goes down as the amount is paid back.")

/**
 * The rate with its unit and its method, for the loan detail: "2% a month, on the amount still owed" / "12% a year, on the full amount".
 * [unit] is the loan's rateUnit ("month", else year).
 */
fun rateWithMethodInWords(rate: Double, unit: String, method: String?): String {
    val r = fmtIN(rate, 2)
    val ratePart = if (unit == "month") t("loan.rate.month", "{r}% a month", "r" to r) else t("loan.rate.year", "{r}% a year", "r" to r)
    return if (method == "flat") t("loan.rateOn.flat", "{rate}, on the full amount", "rate" to ratePart)
    else t("loan.rateOn.reducing", "{rate}, on the amount still owed", "rate" to ratePart)
}

/** How often a bank loan's instalment falls: "Every month", "Every two weeks", "Every week", "Every day". */
fun frequencyLabel(frequency: String?): String = when (frequency) {
    "fortnight" -> t("loan.every.fortnight", "Every two weeks")
    "week" -> t("loan.every.week", "Every week")
    "day" -> t("loan.every.day", "Every day")
    else -> t("loan.every.month", "Every month")
}

/** The instalment day of a weekly or fortnightly loan, from its start date: "Every Tuesday" / "Every other Tuesday"; otherwise [frequencyLabel]. */
fun instalmentDayInWords(loan: Obj): String {
    val f = frequencyOf(loan)
    val start = parseISO(loan.s("startDate"))
    if (start == null || (f != "week" && f != "fortnight")) return frequencyLabel(f)
    val day = weekdayNameL(firstDueOf(loan) ?: start)
    return if (f == "week") t("loan.everyWeekday", "Every {day}", "day" to day) else t("loan.everyOtherWeekday", "Every other {day}", "day" to day)
}

/** A count of instalments in the loan's own unit: "12 months", "26 fortnights", "52 weeks", "100 days". */
fun instalmentsInWords(count: Int, frequency: String?): String = when (frequency) {
    "fortnight" -> tp("loan.count.fortnight", count, "{n} fortnight", "{n} fortnights")
    "week" -> tp("loan.count.week", count, "{n} week", "{n} weeks")
    "day" -> tp("loan.count.day", count, "{n} day", "{n} days")
    else -> tp("loan.count.month", count, "{n} month", "{n} months")
}

/**
 * The pending line, with the amount already formatted by the screen ([amountText], through amtText so the eye hides it):
 * interest-only loans "Interest pending: ₹3,000 (3 months)" (or years), bank loans "Instalments pending: ₹17,770 (2)".
 */
fun pendingInWords(loan: Obj, amountText: String, count: Int): String =
    if (isInstalments(loan)) t("loan.pending.emi", "Instalments pending: {amount} ({count})", "amount" to amountText, "count" to count)
    else {
        val periods = when (interestEvery(loan)) {
            "year" -> tp("loan.count.year", count, "{n} year", "{n} years")
            "month" -> tp("loan.count.month", count, "{n} month", "{n} months")
            else -> tp("loan.count.payment", count, "{n} payment", "{n} payments")   // every 3 or 6 months
        }
        t("loan.pending.interest", "Interest pending: {amount} ({periods})", "amount" to amountText, "periods" to periods)
    }

/**
 * What an entry is called on screen. A person's entry is known by the person: their name leads and the entry's own name
 * ("Borrowed") follows. A bank or pledged entry leads with its own name ("Home EMI"), the lender after.
 */
fun loanTitle(loan: Obj): String {
    val who = loan.s("lender").trim(); val name = loan.s("name").trim()
    return if (loan.s("kind") == "hand" && who.isNotEmpty()) who else name.ifEmpty { who }
}

/** The second line under [loanTitle]: the entry's name under a person, the lender under anything else; "" when there is nothing more to say. */
fun loanSecond(loan: Obj): String {
    val who = loan.s("lender").trim(); val name = loan.s("name").trim()
    return if (loan.s("kind") == "hand" && who.isNotEmpty()) (if (name.equals(who, ignoreCase = true)) "" else name)
    else if (name.isEmpty()) "" else who
}
