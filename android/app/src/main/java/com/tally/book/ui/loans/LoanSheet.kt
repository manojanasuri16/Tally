package com.tally.book.ui.loans

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.*
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.AppleShapes
import com.tally.book.ui.theme.LocalEasy

/*
 * Add or edit a loan, in a sheet. The kind comes first as three large choices; then only that kind's fields, in the order a person
 * would say them. The form edits copies of the values held in remember; nothing reaches the book until Save (saveLoan inside Ledger.edit).
 */

@Composable
fun LoanSheet(existing: Obj?, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val editing = existing != null
    val today = remember { Clock.today() }
    var kind by remember { mutableStateOf(existing?.s("kind").orEmpty()) }
    var given by remember { mutableStateOf(existing?.let { isGiven(it) } ?: false) }
    var name by remember { mutableStateOf(existing?.s("name").orEmpty()) }
    var lender by remember { mutableStateOf(existing?.s("lender").orEmpty()) }
    var principal by remember { mutableDoubleStateOf(existing?.num("principal") ?: 0.0) }
    var startDate by remember { mutableStateOf(existing?.s("startDate")?.ifEmpty { null } ?: todayISO()) }
    var rate by remember { mutableStateOf(existing?.let { editText(it.num("rate")) }.orEmpty()) }
    var rateUnit by remember { mutableStateOf(existing?.s("rateUnit")?.takeIf { it == "month" } ?: "year") }
    var emi by remember { mutableDoubleStateOf(existing?.num("emi") ?: 0.0) }
    var method by remember { mutableStateOf(existing?.let { interestMethod(it) } ?: "reducing") }
    var frequency by remember { mutableStateOf(existing?.let { frequencyOf(it) } ?: "month") }
    var frequencyTouched by remember { mutableStateOf(editing) }
    var pledge by remember { mutableStateOf(existing?.takeIf { it.s("kind") == "gold" }?.let { pledgeOf(it) } ?: "gold") }
    var pledgeText by remember { mutableStateOf(existing?.let { pledgeTextOf(it) }.orEmpty()) }
    val existingPaidUpTo = remember { existing?.s("paidUpTo").orEmpty() }
    var paidUpTo by remember { mutableStateOf(existingPaidUpTo) }
    var paidTouched by remember { mutableStateOf(editing) }
    var tenure by remember { mutableStateOf(existing?.let { editText(it.num("tenure")) }.orEmpty()) }
    var dueDay by remember { mutableStateOf(existing?.let { editText(it.num("dueDay")) } ?: today.dayOfMonth.toString()) }
    var dueDayTouched by remember { mutableStateOf(editing) }
    // The first payment date: shown filled with the usual date (one period after the date taken) until the user picks another.
    var firstDue by remember { mutableStateOf(existing?.s("firstDue").orEmpty()) }
    var firstDueTouched by remember { mutableStateOf(existing?.s("firstDue").orEmpty().isNotEmpty()) }
    // A person's entry is paid back one of three ways: interest only (the default: interest every month, the amount itself later, all
    // at once or in parts), no interest (back when they can, in parts or all at once), or fixed instalments (₹1,000 every week for 12 weeks).
    var plan by remember { mutableStateOf(existing?.takeIf { it.s("kind") == "hand" }?.let { personPlanOf(it) } ?: "interest") }
    var handUnit by remember { mutableStateOf(existing?.takeIf { it.s("kind") == "hand" && it.num("rate") > 0 && it.s("rateUnit") != "month" }?.let { "year" } ?: "month") }
    var received by remember { mutableDoubleStateOf(existing?.num("received") ?: 0.0) }
    val interest = if (plan == "interest") handUnit else "none"
    val personInstalments = kind == "hand" && plan == "instalments"
    var repayBy by remember { mutableStateOf(existing?.s("repayBy").orEmpty()) }
    val saved = remember { existing?.let { filesOf("loan", it.s("id")) } ?: emptyList() }
    var showDetails by remember { mutableStateOf(editing && (existing?.s("note").orEmpty().isNotBlank() || existing?.s("repayBy").orEmpty().isNotBlank() || saved.isNotEmpty())) }
    var every by remember { mutableStateOf(existing?.s("interestEvery")?.takeIf { it in INTEREST_EVERY }) }
    // a credit card: the statement's minimum due and pay-by date
    var minDue by remember { mutableDoubleStateOf(existing?.num("minDue") ?: 0.0) }
    var cardDue by remember { mutableStateOf(existing?.s("dueDate").orEmpty()) }
    // its credit limit, or the card whose limit it shares ("" = its own); the person says so, the bank is never assumed
    var limit by remember { mutableDoubleStateOf(existing?.num("limit") ?: 0.0) }
    var limitWith by remember { mutableStateOf(existing?.s("limitWith").orEmpty()) }
    val limitCards = remember { limitChoices(existing?.s("id").orEmpty()) }
    // the card's billing days (optional): the statement day and the day it must be paid by; Pay by follows them until typed
    var billDay by remember { mutableStateOf(existing?.num("billDay")?.toInt()?.takeIf { it in 1..31 }?.toString().orEmpty()) }
    var payDay by remember { mutableStateOf(existing?.num("payDay")?.toInt()?.takeIf { it in 1..31 }?.toString().orEmpty()) }
    var cardDueTouched by remember { mutableStateOf(existing != null) }
    var grams by remember { mutableStateOf(existing?.let { editText(it.num("grams")) }.orEmpty()) }
    var note by remember { mutableStateOf(existing?.s("note").orEmpty()) }
    var keepPrivate by remember { mutableStateOf(existing?.b("private") ?: false) }
    // Whose entry it is: one person often keeps the book for the family. The choice shows when the family has someone else
    // (or the entry already belongs to someone else); a new entry is mine.
    var owner by remember { mutableStateOf(existing?.let { ownerOf(it) } ?: myId()) }
    val people = remember {
        val m = com.tally.book.sync.Sync.membership(DB).members.map { it.id to it.name.ifBlank { memberName(it.id) } }
        val all = if (m.none { it.first == owner }) m + (owner to memberName(owner)) else m
        all.map { (id, name) -> id to if (id == myId()) t("loan.owner.me", "Me") else name }
            .sortedBy { if (it.first == myId()) "" else it.second.lowercase() }
    }
    val pending = remember { mutableStateListOf<Picked>() }
    val removed = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var errorStamp by remember { mutableIntStateOf(0) }

    val pickers = rememberAttachPickers { p -> pending.add(p); showDetails = true }
    val words = loanWords(given)
    // Easy mode: only the essential questions. What a loan being edited already uses stays on screen (EasyForm.extrasOf).
    val easy = LocalEasy.current
    val extras = remember { EasyForm.extrasOf(existing) }
    val effEvery = every ?: if (kind == "gold") "year" else "month"

    val hasInterest = LoanFormRules.hasInterest(kind, interest)

    /** The loan as the form stands, without Paid up to (which is worked out from it). */
    fun core(): Obj {
        val d = existing?.copyObj() ?: obj("closed" to false)
        d["kind"] = kind
        d["name"] = name.trim()
        d["lender"] = lender.trim()
        d["principal"] = principal
        d["startDate"] = startDate
        d["rate"] = if (!hasInterest) 0.0 else n(rate)
        if (hasInterest && kind == "bank") d["interestMethod"] = method else d.remove("interestMethod")
        d["note"] = note.trim()
        d["private"] = keepPrivate
        if (people.size > 1) d["owner"] = owner
        if (kind == "bank") {
            d["emi"] = emi
            d["tenure"] = n(tenure)
            d["frequency"] = frequency
            if (LoanFormRules.showDueDay(kind, frequency))
                d["dueDay"] = if (firstDueTouched) (parseISO(firstDue)?.dayOfMonth?.toDouble() ?: n(dueDay)) else n(dueDay)
            else d.remove("dueDay")
        } else if (personInstalments) {
            d["plan"] = "instalments"
            d["emi"] = emi
            d["tenure"] = n(tenure)
            d["frequency"] = frequency
            if (received > 0) d["received"] = received else d.remove("received")
            listOf("rateUnit", "interestEvery", "dueDay", "repayBy").forEach { d.remove(it) }
        } else if (kind == "card") {
            d["minDue"] = minDue
            if (n(billDay) in 1.0..31.0) d["billDay"] = n(billDay) else d.remove("billDay")
            if (n(payDay) in 1.0..31.0) d["payDay"] = n(payDay) else d.remove("payDay")
            // a new card's figures are from its latest statement (by the bill day), else from today
            if (!editing) d["startDate"] = lastBillDate(d, today)?.toString() ?: startDate
            d["dueDate"] = cardDue
            if (limitWith.isNotEmpty() && limitCards.any { it.s("id") == limitWith }) { d["limitWith"] = limitWith; d.remove("limit") }
            else { d.remove("limitWith"); if (limit > 0) d["limit"] = limit else d.remove("limit") }
            listOf("rateUnit", "interestEvery", "interestMethod", "repayBy", "emi", "tenure", "frequency", "dueDay").forEach { d.remove(it) }
        } else {
            if (kind == "hand") listOf("plan", "emi", "tenure", "frequency", "received").forEach { d.remove(it) }
            d["rateUnit"] = if (kind == "hand") (if (interest == "month") "month" else "year") else rateUnit
            d["interestEvery"] = effEvery
            if (kind == "gold") {
                d["pledge"] = pledge
                if (pledge == "gold") d["grams"] = n(grams) else d.remove("grams")
                if (pledge == "other") d["pledgeText"] = pledgeText.trim() else d.remove("pledgeText")
            }
        }
        if (LoanFormRules.repayByApplies(kind) && !personInstalments) {
            if (repayBy.isNotEmpty()) d["repayBy"] = repayBy else d.remove("repayBy")
        }
        if (kind == "hand" && !editing) { if (given) d["direction"] = "given" else d.remove("direction") }
        d.remove("firstDue")
        if (firstDueTouched && firstDue.isNotEmpty() && defaultFirstDue(d).isNotEmpty()) d["firstDue"] = firstDue
        return d
    }

    /** What Paid up to would start as: the latest date that has come due by the form's own schedule. */
    val suggestion = remember(kind, startDate, frequency, dueDay, tenure, rate, plan, handUnit, every, rateUnit, firstDue, firstDueTouched) {
        if (editing && existingPaidUpTo.isEmpty()) "" else suggestPaidUpTo(core(), today)
    }
    val paidShown = LoanFormRules.showPaidUpTo(editing, existingPaidUpTo, suggestion)
    val paidValue = if (!paidShown) "" else if (paidTouched) paidUpTo else suggestion
    // (in Easy mode the field is not drawn for a new loan, but the suggestion still applies: an old loan never shows years of pending payments)

    fun build(): Obj {
        val d = core()
        if (paidValue.isNotEmpty()) d["paidUpTo"] = paidValue else d.remove("paidUpTo")
        return d
    }

    fun save() {
        val draft = build()
        val problem = if (kind == "hand" && plan == "interest" && n(rate) <= 0)
            t("loan.err.rateNeeded", "Please enter the interest rate, or choose No interest.") else validateLoan(draft)
        if (problem != null) { error = problem; errorStamp++; return }
        var id = ""
        Ledger.edit { id = saveLoan(draft).s("id") }
        removeStored(removed.toList())
        storeAll("loan", id, pending.toList())
        onSaved(id)
    }

    EditSheet(
        if (editing) t("loan.edit", "Edit entry") else t("loan.add", "Add an entry"), onDismiss = onDismiss,
        onSave = { save() }, saveLabel = t("dlg.save", "Save"), saveEnabled = kind.isNotEmpty(),
    ) {
        if (!editing) {
            FieldTitle(t("loan.which", "What kind of entry is it?"))
            listOf("bank", "hand", "gold", "card", "given").forEach { k ->
                val isKind = if (k == "given") kind == "hand" && given else if (k == "hand") kind == "hand" && !given else kind == k
                KindChoice(kindIcon(k), choiceTitle(k), choiceHelp(k), selected = isKind) {
                    kind = if (k == "given") "hand" else k; given = k == "given"; error = null
                }
            }
        }
        if (kind.isNotEmpty()) {
            if (people.size > 1) {
                FieldTitle(t("loan.owner", "Whose entry is it?"))
                ChoiceChips(people, owner, { owner = it; error = null })
            }
            if (LoanFormRules.showPledge(kind)) {
                FieldTitle(t("loan.pledge", "What is pledged?"))
                ChoiceChips(PLEDGES.map { it to pledgeLabel(it) }, pledge, { pledge = it; error = null })
                if (pledge == "other") TextInput(
                    pledgeText, { pledgeText = it.take(PLEDGE_TEXT_MAX); error = null }, t("loan.pledgeText", "What is pledged?"),
                    placeholder = t("loan.pledgeText.ph", "e.g. Silver, vehicle papers"),
                )
            }
            TextInput(name, { name = it; error = null }, t("loan.name", "Name"), placeholder = when (kind) {
                "gold" -> pledgeNameExample(pledge)
                "hand" -> if (given) t("loan.name.eg.given", "e.g. Money for a friend") else t("loan.name.eg.hand", "e.g. From a friend")
                "card" -> t("card.name.eg", "e.g. SBI card")
                else -> t("loan.name.eg", "e.g. Home EMI")
            })
            TextInput(
                lender, { lender = it },
                if (kind == "bank" || kind == "card") t("loan.lender.bank", "Bank") else words.who,
                placeholder = when (kind) { "bank", "card" -> t("loan.lender.bank.eg", "e.g. State Bank"); "gold" -> pledgeLenderExample(pledge); else -> words.whoEg },
            )
            MoneyField(
                principal, { principal = it; error = null }, if (kind == "card") t("card.outstanding", "Outstanding (from the statement)") else words.amount,
                supporting = if (kind != "card" && LoanFormRules.showAmountHint(kind, paidShown)) t("loan.amount.stillOwed", "If part of the amount was already returned, enter what is still owed.") else null,
            )
            // a card is kept simple: its figures are as of the day they are entered, so no date is asked
            if (kind != "card") DateField(startDate, {
                startDate = it
                if (kind == "bank" && !dueDayTouched) parseISO(it)?.let { d -> dueDay = d.dayOfMonth.toString() }
                // a first payment date on or before the new date taken no longer fits: go back to the usual one
                if (firstDueTouched) { val f = parseISO(firstDue); val s = parseISO(it); if (f != null && s != null && !f.isAfter(s)) firstDueTouched = false }
            }, words.date)
            if (kind == "card") {
                MoneyField(minDue, { minDue = it; error = null }, t("card.minDue", "Minimum due"),
                    supporting = t("card.minDue.help", "Leave it empty if you pay the full amount every month."))
                // the billing days, side by side; Pay by fills itself from them until it is picked by hand
                fun refreshDue(b: String, p: String) {
                    if (cardDueTouched) return
                    val probe = obj("billDay" to n(b), "payDay" to n(p))
                    val st = lastBillDate(probe, today) ?: today
                    payDateAfter(probe, st)?.let { cardDue = it.toString() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextInput(billDay, { billDay = digitsOnly(it).take(2); error = null; refreshDue(billDay, payDay) }, t("card.billDay", "Statement day"),
                        keyboard = KeyboardType.Number, placeholder = t("card.billDay.eg", "e.g. 15"), modifier = Modifier.weight(1f))
                    TextInput(payDay, { payDay = digitsOnly(it).take(2); error = null; refreshDue(billDay, payDay) }, t("card.payDay", "Due day"),
                        keyboard = KeyboardType.Number, placeholder = t("card.payDay.eg", "e.g. 5"), modifier = Modifier.weight(1f))
                }
                Hint(t("card.days.help", "Days of the month. Optional: they fill the pay-by date for you."))
                DateField(cardDue, { cardDue = it; cardDueTouched = true; error = null }, t("card.dueDate", "Pay by"))
                if (limitCards.isNotEmpty()) {
                    FieldTitle(t("card.shares", "Shares its credit limit with"))
                    ChoiceChips(listOf("" to t("card.shares.none", "No, its own")) + limitCards.map { it.s("id") to loanTitle(it) }, limitWith, { limitWith = it; error = null })
                }
                val with = limitCards.firstOrNull { it.s("id") == limitWith }
                if (with == null) MoneyField(limit, { limit = it; error = null }, t("card.limit", "Credit limit (optional)"))
                else {
                    val l = limitRoot(with).num("limit")
                    Hint(if (l > 0) t("card.limit.uses", "Uses the limit of {card}: {amt}", "card" to loanTitle(limitRoot(with)), "amt" to amtMask(l))
                        else t("card.limit.usesNone", "Uses the limit of {card}. Add the limit on that card.", "card" to loanTitle(limitRoot(with))))
                }
            }

            if (kind == "hand") {
                FieldTitle(if (given) t("loan.plan.given", "How will it come back?") else t("loan.plan", "How is it paid back?"))
                SegmentedControl(
                    listOf(
                        "interest" to t("loan.plan.interest", "Interest only"),
                        "none" to t("loan.noInterest.choice", "No interest"),
                        "instalments" to t("loan.plan.instalments", "Instalments"),
                    ),
                    plan, { p ->
                        plan = p; error = null
                        if (p == "instalments" && !frequencyTouched) frequency = "week"
                    },
                )
                Hint(planHelp(plan, given))
                if (plan == "interest") SegmentedControl(
                    listOf("month" to t("loan.perMonth", "Per month"), "year" to t("loan.perYear", "Per year")), handUnit, { handUnit = it; error = null },
                )
            } else if (kind == "gold") {
                FieldTitle(t("loan.rateUnit", "Interest is quoted"))
                SegmentedControl(listOf("year" to t("loan.perYear", "Per year"), "month" to t("loan.perMonth", "Per month")), rateUnit, { rateUnit = it })
            }
            val perMonth = if (kind == "hand") interest == "month" else rateUnit == "month"
            if (hasInterest) {
                TextInput(
                    rate, { rate = decimalOnly(it); error = null },
                    if (kind == "bank" || !perMonth) t("loan.rate.year.label", "Interest rate (% a year)") else t("loan.rate.month.label", "Interest rate (% a month)"),
                    keyboard = KeyboardType.Decimal, placeholder = if (kind == "hand" && perMonth) "2" else "0",
                )
                if (kind != "bank") interestHint(core())?.let { Hint(it) }
                if (EasyForm.showMethod(easy, kind, hasInterest, extras)) {
                    SegmentedControl(
                        listOf("reducing" to t("loan.method.choice.reducing", "Reducing"), "flat" to t("loan.method.choice.flat", "Flat")),
                        method, { method = it; error = null },
                    )
                    Hint(interestMethodHelp(method))
                }
            }
            if (personInstalments) {
                MoneyField(
                    received, { received = it; error = null },
                    if (given) t("loan.received.given.field", "Amount actually handed over (optional)") else t("loan.received.field", "Amount actually received (optional)"),
                    supporting = t("loan.received.help", "If less was handed over, like ₹9,500 for ₹10,000."),
                )
                FieldTitle(if (given) t("loan.frequency.given", "How often is it paid back?") else t("loan.frequency", "How often do you pay?"))
                ChoiceChips(FREQUENCIES.map { it to frequencyLabel(it) }, frequency, { frequency = it; frequencyTouched = true; error = null })
                if (frequency == "week" || frequency == "fortnight") Hint(instalmentDayInWords(core()))
                MoneyField(emi, { emi = it; error = null }, t("loan.emi.each", "Amount of each payment"))
                TextInput(tenure, { tenure = digitsOnly(it); error = null }, LoanFormRules.tenureLabel(frequency), keyboard = KeyboardType.Number, placeholder = t("loan.count.eg", "e.g. 12"))
                instalmentTotalHint(core(), given)?.let { Hint(it) }
            } else if (kind == "bank") {
                if (EasyForm.showFrequency(easy, kind, extras)) {
                    FieldTitle(t("loan.frequency", "How often do you pay?"))
                    ChoiceChips(FREQUENCIES.map { it to frequencyLabel(it) }, frequency, { frequency = it; error = null })
                    if (LoanFormRules.showWeekday(kind, frequency)) Hint(instalmentDayInWords(core()))
                }
                val monthly = frequency == "month"
                MoneyField(
                    emi, { emi = it; error = null },
                    if (monthly) t("loan.emi", "EMI (the monthly payment)") else t("loan.emi.each", "Amount of each payment"),
                    supporting = if (monthly) t("loan.emi.help", "If you do not know it, leave it empty and give the number of months.")
                    else t("loan.emi.help.count", "If you do not know it, leave it empty and give the number of payments."),
                )
                TextInput(tenure, { tenure = digitsOnly(it); error = null }, LoanFormRules.tenureLabel(frequency), keyboard = KeyboardType.Number, placeholder = t("loan.tenure.eg", "e.g. 24"))
            } else {
                if (EasyForm.showEvery(easy, kind, hasInterest, extras)) {
                    FieldTitle(t("loan.every", "Interest is paid"))
                    ChoiceChips(INTEREST_EVERY.map { it to everyInWords(it) }, effEvery, { every = it; error = null })
                }
                if (LoanFormRules.showGrams(kind, pledge)) TextInput(grams, { grams = decimalOnly(it) }, t("loan.grams", "Gold pledged (grams)"), keyboard = KeyboardType.Decimal, placeholder = "0")
                if (LoanFormRules.repayByInMain(kind, pledge)) {
                    DateField(repayBy, { repayBy = it; error = null }, t("loan.repayBy.renew", "Repay or renew by"), clearable = true)
                    Hint(t("loan.repayBy.gold.hint", "Gold loans usually must be repaid or renewed within 12 months."))
                }
            }
            // Payments are often due on another day than the one the money came: every entry with a schedule asks for the first one.
            val usualFirst = defaultFirstDue(core())
            if (usualFirst.isNotEmpty()) {
                DateField(if (firstDueTouched) firstDue else usualFirst, { firstDue = it; firstDueTouched = true; error = null }, t("loan.firstDue", "First payment date"))
                Hint(t("loan.firstDue.help", "The rest follow from this date."))
            }
            if (EasyForm.showPaidUpToField(easy, paidShown, extras)) {
                DateField(paidValue, { paidUpTo = it; paidTouched = true; error = null }, t("loan.paidUpTo", "Paid up to"), clearable = true)
                Hint(t("loan.paidUpTo.help", "Everything due until this date counts as paid. Change it if some payments were missed."))
            } else if (EasyForm.paidUpToNote(easy, editing, suggestion)) {
                Hint(t("easy.paidUpTo.note", "Payments up to {date} are counted as already paid.", "date" to fmtISODate(paidValue)))
            }
            if (!showDetails) {
                PlainButton(t("pay.addDetails", "Add details"), { showDetails = true }, Modifier.align(Alignment.Start), bold = true)
            } else {
                if (LoanFormRules.repayByApplies(kind) && !LoanFormRules.repayByInMain(kind, pledge))
                    DateField(
                        repayBy, { repayBy = it; error = null },
                        if (kind == "hand") t("loan.repayBy.label", "To be returned by (optional)") else t("loan.repayBy.renew.optional", "Repay or renew by (optional)"),
                        clearable = true,
                    )
                TextInput(note, { note = it }, t("loan.note", "Note"), singleLine = false, supporting = t("common.optional", "Optional"))
                FieldTitle(t("doc.title", "Documents"))
                AttachedList(saved, removed, pending)
                InsetSurface { AttachButtons(pickers) }
            }
            InsetSurface {
                SwitchRow(
                    t("loan.keepPrivate", "Keep private"), keepPrivate, { keepPrivate = it },
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    subtitle = t("loan.keepPrivate.help", "Only on this phone. Not shared with family."),
                )
            }
            FormError(error, errorStamp)
        }
    }
}

/** The four big choices of a new loan: [k] is "bank", "hand", "gold", or "given" (stored as a hand loan with direction given). */
private fun choiceTitle(k: String): String = when (k) {
    "bank" -> t("loan.kind.bank", "Bank / EMI")
    "hand" -> t("loan.choice.hand", "Money I took from a person")
    "gold" -> kindLabel("gold")
    "card" -> kindLabel("card")
    else -> t("loan.choice.given", "Money I gave")
}

private fun choiceHelp(k: String): String = when (k) {
    "bank" -> t("loan.kind.bank.help", "A home, car, personal or group loan with a monthly EMI")
    "hand" -> loanWords(false).kindHelp
    "gold" -> t("loan.kind.secured.help", "Gold, LIC policy, FD, land papers, something else")
    "card" -> t("loan.kind.card.help", "The outstanding amount, minimum due and pay-by date from each statement")
    else -> loanWords(true).kindHelp
}

/** What each way of paying back a person's entry means, under the choice. */
private fun planHelp(plan: String, given: Boolean): String = when (plan) {
    "none" -> if (given) t("loan.plan.none.help.given", "They return it when they can, all at once or a little at a time. Log each part as it comes back.")
        else t("loan.plan.none.help", "Pay it back when you can, all at once or a little at a time. Log each part you pay and the amount goes down.")
    "instalments" -> if (given) t("loan.plan.instalments.help.given", "A fixed amount comes back every week or month until it is cleared.")
        else t("loan.plan.instalments.help", "A fixed amount every week or month until it is cleared, like ₹1,000 every week for 12 weeks.")
    else -> if (given) t("loan.plan.interest.help.given", "They pay only the interest. The amount itself comes back later, all at once or in parts.")
        else t("loan.plan.interest.help", "Pay only the interest. The amount itself is returned later, all at once or in parts.")
}

/** "Interest: ₹1,000 every month (₹2 on every ₹100)" for the rate typed so far; null until there is an amount and a rate. */
private fun interestHint(draft: Obj): String? {
    val principal = draft.num("principal"); val rate = draft.num("rate")
    if (principal <= 0 || rate <= 0) return null
    val each = periodInterest(draft, principal)
    if (each <= 0) return null
    val every = when (interestEvery(draft)) {
        "year" -> t("loan.interestHint.year", "every year")
        "quarter" -> t("loan.interestHint.quarter", "every 3 months")
        "half" -> t("loan.interestHint.half", "every 6 months")
        else -> t("loan.interestHint.month", "every month")
    }
    val line = t("loan.interestHint", "Interest: {amt} {every}", "amt" to amtMask(each), "every" to every)
    return if (draft.s("rateUnit") == "month")
        line + " " + t("loan.interestHint.rupees", "(₹{r} on every ₹100 a month)", "r" to fmtIN(rate, 2))
    else line
}

/** "You pay back ₹12,000 in all, ₹2,500 more than you received." once each payment and the count are in; null before. */
private fun instalmentTotalHint(draft: Obj, given: Boolean): String? {
    val total = totalToRepay(draft)
    if (total <= 0) return null
    val extra = instalmentExtra(draft)
    return when {
        given && extra > 0 -> t("loan.total.hint.extra.given", "You get back {total} in all, {extra} more than you gave.", "total" to amtMask(total), "extra" to amtMask(extra))
        given -> t("loan.total.hint.given", "You get back {total} in all.", "total" to amtMask(total))
        extra > 0 -> t("loan.total.hint.extra", "You pay back {total} in all, {extra} more than you received.", "total" to amtMask(total), "extra" to amtMask(extra))
        else -> t("loan.total.hint", "You pay back {total} in all.", "total" to amtMask(total))
    }
}

/** The name placeholder for what is pledged (the gold kind covers every pledge). */
private fun pledgeNameExample(pledge: String): String = when (pledge) {
    "lic" -> t("loan.name.eg.lic", "e.g. Loan on LIC policy")
    "fd" -> t("loan.name.eg.fd", "e.g. Loan on FD")
    "land" -> t("loan.name.eg.land", "e.g. Loan on land papers")
    "none" -> t("loan.name.eg.none", "e.g. Crop loan")
    "other" -> t("loan.name.eg.other", "e.g. Loan on vehicle papers")
    else -> t("loan.name.eg.gold", "e.g. Gold loan")
}

/** The lender placeholder for what is pledged. */
private fun pledgeLenderExample(pledge: String): String = when (pledge) {
    "lic" -> t("loan.lender.lic.eg", "e.g. LIC")
    "fd" -> t("loan.lender.fd.eg", "e.g. the bank that holds the FD")
    "land" -> t("loan.lender.land.eg", "e.g. a bank or a person")
    "none" -> t("loan.lender.none.eg", "e.g. bank or society")
    "other" -> t("loan.lender.other.eg", "e.g. a bank or a person")
    else -> t("loan.lender.gold.eg", "e.g. bank or finance company")
}

/** A quiet one-line explanation under a field. */
@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(start = 14.dp, end = 8.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
}

@Composable
private fun FieldTitle(text: String) {
    Text(text, Modifier.padding(start = 14.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
}

/** One of the three big choices of a new loan. */
@Composable
private fun KindChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, help: String, selected: Boolean, onClick: () -> Unit) {
    val c = Apple.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(AppleShapes.cell)
            .background(if (selected) c.accent.copy(alpha = 0.12f) else c.cell)
            .border(BorderStroke(if (selected) 2.dp else 0.dp, if (selected) c.accent else c.cell), AppleShapes.cell)
            .semantics { this.selected = selected }
            .pressable(scaleTo = 0.98f, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, c.accent, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Apple.type.headline, color = c.label)
            Text(help, style = Apple.type.footnote, color = c.secondaryLabel)
        }
        if (selected) { Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(24.dp)) }
    }
}
