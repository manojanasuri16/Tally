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
    var pledge by remember { mutableStateOf(existing?.takeIf { it.s("kind") == "gold" }?.let { pledgeOf(it) } ?: "gold") }
    val existingPaidUpTo = remember { existing?.s("paidUpTo").orEmpty() }
    var paidUpTo by remember { mutableStateOf(existingPaidUpTo) }
    var paidTouched by remember { mutableStateOf(editing) }
    var tenure by remember { mutableStateOf(existing?.let { editText(it.num("tenure")) }.orEmpty()) }
    var dueDay by remember { mutableStateOf(existing?.let { editText(it.num("dueDay")) } ?: today.dayOfMonth.toString()) }
    var dueDayTouched by remember { mutableStateOf(editing) }
    var interest by remember { mutableStateOf(existing?.takeIf { it.s("kind") == "hand" }?.let { interestChoice(it.num("rate"), it.s("rateUnit")) } ?: "month") }
    var repayBy by remember { mutableStateOf(existing?.s("repayBy").orEmpty()) }
    val saved = remember { existing?.let { filesOf("loan", it.s("id")) } ?: emptyList() }
    var showDetails by remember { mutableStateOf(editing && (existing?.s("note").orEmpty().isNotBlank() || existing?.s("repayBy").orEmpty().isNotBlank() || saved.isNotEmpty())) }
    var every by remember { mutableStateOf(existing?.s("interestEvery")?.takeIf { it == "month" || it == "year" }) }
    var grams by remember { mutableStateOf(existing?.let { editText(it.num("grams")) }.orEmpty()) }
    var note by remember { mutableStateOf(existing?.s("note").orEmpty()) }
    var keepPrivate by remember { mutableStateOf(existing?.b("private") ?: false) }
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
        if (hasInterest) d["interestMethod"] = method else d.remove("interestMethod")
        d["note"] = note.trim()
        d["private"] = keepPrivate
        if (kind == "bank") {
            d["emi"] = emi
            d["tenure"] = n(tenure)
            d["frequency"] = frequency
            if (LoanFormRules.showDueDay(kind, frequency)) d["dueDay"] = n(dueDay) else d.remove("dueDay")
        } else {
            d["rateUnit"] = if (kind == "hand") (if (interest == "month") "month" else "year") else rateUnit
            d["interestEvery"] = effEvery
            if (kind == "gold") {
                d["pledge"] = pledge
                if (pledge == "gold") d["grams"] = n(grams) else d.remove("grams")
            }
        }
        if (LoanFormRules.repayByApplies(kind)) {
            if (repayBy.isNotEmpty()) d["repayBy"] = repayBy else d.remove("repayBy")
        }
        if (kind == "hand" && !editing) { if (given) d["direction"] = "given" else d.remove("direction") }
        return d
    }

    /** What Paid up to would start as: the latest date that has come due by the form's own schedule. */
    val suggestion = remember(kind, startDate, frequency, dueDay, tenure, rate, interest, every, rateUnit) {
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
        val problem = if (kind == "hand" && hasInterest && n(rate) <= 0)
            t("loan.err.rateNeeded", "Please enter the interest rate, or choose No interest.") else validateLoan(draft)
        if (problem != null) { error = problem; errorStamp++; return }
        var id = ""
        Ledger.edit { id = saveLoan(draft).s("id") }
        removeStored(removed.toList())
        storeAll("loan", id, pending.toList())
        onSaved(id)
    }

    EditSheet(
        if (editing) t("loan.edit", "Edit loan") else t("loan.add", "Add a loan"), onDismiss = onDismiss,
        onSave = { save() }, saveLabel = t("dlg.save", "Save"), saveEnabled = kind.isNotEmpty(),
    ) {
        if (!editing) {
            FieldTitle(t("loan.which", "What kind of loan is it?"))
            listOf("bank", "hand", "gold", "given").forEach { k ->
                val isKind = if (k == "given") kind == "hand" && given else if (k == "hand") kind == "hand" && !given else kind == k
                KindChoice(kindIcon(k), choiceTitle(k), choiceHelp(k), selected = isKind) {
                    kind = if (k == "given") "hand" else k; given = k == "given"; error = null
                }
            }
        }
        if (kind.isNotEmpty()) {
            if (LoanFormRules.showPledge(kind)) {
                FieldTitle(t("loan.pledge", "What is pledged?"))
                ChoiceChips(PLEDGES.map { it to pledgeLabel(it) }, pledge, { pledge = it; error = null })
            }
            TextInput(name, { name = it; error = null }, t("loan.name", "Name"), placeholder = when (kind) {
                "gold" -> t("loan.name.eg.gold", "e.g. Gold loan")
                "hand" -> if (given) t("loan.name.eg.given", "e.g. Money for a friend") else t("loan.name.eg.hand", "e.g. Loan from a friend")
                else -> t("loan.name.eg", "e.g. Home loan")
            })
            TextInput(
                lender, { lender = it },
                if (kind == "bank") t("loan.lender.bank", "Bank") else words.who,
                placeholder = when (kind) { "bank" -> t("loan.lender.bank.eg", "e.g. State Bank"); "gold" -> t("loan.lender.gold.eg", "e.g. bank or finance company"); else -> words.whoEg },
            )
            MoneyField(
                principal, { principal = it; error = null }, words.amount,
                supporting = if (LoanFormRules.showAmountHint(kind, paidShown)) t("loan.amount.stillOwed", "If part of the amount was already returned, enter what is still owed.") else null,
            )
            DateField(startDate, {
                startDate = it
                if (kind == "bank" && !dueDayTouched) parseISO(it)?.let { d -> dueDay = d.dayOfMonth.toString() }
            }, words.date)

            if (kind == "hand") {
                FieldTitle(t("loan.interest", "Interest"))
                SegmentedControl(
                    listOf("none" to t("loan.noInterest.choice", "No interest"), "month" to t("loan.perMonth", "Per month"), "year" to t("loan.perYear", "Per year")),
                    interest, { interest = it; error = null },
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
                    keyboard = KeyboardType.Decimal, placeholder = "0",
                )
                if (EasyForm.showMethod(easy, hasInterest, extras)) {
                    SegmentedControl(
                        listOf("reducing" to t("loan.method.choice.reducing", "Reducing"), "flat" to t("loan.method.choice.flat", "Flat")),
                        method, { method = it; error = null },
                    )
                    Hint(interestMethodHelp(method))
                }
            }
            if (kind == "bank") {
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
                if (EasyForm.showDueDay(easy, kind, frequency, extras))
                    TextInput(dueDay, { dueDay = digitsOnly(it).take(2); dueDayTouched = true; error = null }, t("loan.dueDay", "Pay on this day of the month"), keyboard = KeyboardType.Number, placeholder = "5")
            } else {
                if (EasyForm.showEvery(easy, kind, hasInterest, extras)) {
                    FieldTitle(t("loan.every", "Interest is paid"))
                    SegmentedControl(listOf("month" to t("loan.everyMonth", "Every month"), "year" to t("loan.everyYear", "Every year")), effEvery, { every = it })
                }
                if (LoanFormRules.showGrams(kind, pledge)) TextInput(grams, { grams = decimalOnly(it) }, t("loan.grams", "Gold pledged (grams)"), keyboard = KeyboardType.Decimal, placeholder = "0")
                if (LoanFormRules.repayByInMain(kind, pledge)) {
                    DateField(repayBy, { repayBy = it; error = null }, t("loan.repayBy.renew", "Repay or renew by"), clearable = true)
                    Hint(t("loan.repayBy.gold.hint", "Gold loans usually must be repaid or renewed within 12 months."))
                }
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
    "bank" -> t("loan.kind.bank", "Bank loan")
    "hand" -> t("loan.choice.hand", "Money I took from a person")
    "gold" -> kindLabel("gold")
    else -> t("loan.choice.given", "Money I gave to someone")
}

private fun choiceHelp(k: String): String = when (k) {
    "bank" -> t("loan.kind.bank.help", "A home, car, personal or group loan with a monthly EMI")
    "hand" -> loanWords(false).kindHelp
    "gold" -> t("loan.kind.secured.help", "Gold, LIC policy, FD, land papers")
    else -> loanWords(true).kindHelp
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
