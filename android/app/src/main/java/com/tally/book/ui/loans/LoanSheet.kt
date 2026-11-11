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
    var tenure by remember { mutableStateOf(existing?.let { editText(it.num("tenureMonths")) }.orEmpty()) }
    var dueDay by remember { mutableStateOf(existing?.let { editText(it.num("dueDay")) } ?: today.dayOfMonth.toString()) }
    var dueDayTouched by remember { mutableStateOf(editing) }
    var interest by remember { mutableStateOf(existing?.takeIf { it.s("kind") == "hand" }?.let { interestChoice(it.num("rate"), it.s("rateUnit")) } ?: "month") }
    var repayBy by remember { mutableStateOf(existing?.s("repayBy").orEmpty()) }
    var showDetails by remember { mutableStateOf(editing && (existing?.s("note").orEmpty().isNotBlank() || existing?.s("repayBy").orEmpty().isNotBlank())) }
    var every by remember { mutableStateOf(existing?.s("interestEvery")?.takeIf { it == "month" || it == "year" }) }
    var grams by remember { mutableStateOf(existing?.let { editText(it.num("grams")) }.orEmpty()) }
    var note by remember { mutableStateOf(existing?.s("note").orEmpty()) }
    var keepPrivate by remember { mutableStateOf(existing?.b("private") ?: false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorStamp by remember { mutableIntStateOf(0) }

    val words = loanWords(given)
    val effEvery = every ?: if (kind == "gold") "year" else "month"

    fun build(): Obj {
        val d = existing?.copyObj() ?: obj("closed" to false)
        d["kind"] = kind
        d["name"] = name.trim()
        d["lender"] = lender.trim()
        d["principal"] = principal
        d["startDate"] = startDate
        d["rate"] = if (kind == "hand" && interest == "none") 0.0 else n(rate)
        d["note"] = note.trim()
        d["private"] = keepPrivate
        if (kind == "bank") {
            d["emi"] = emi
            d["tenureMonths"] = n(tenure)
            d["dueDay"] = n(dueDay)
        } else {
            d["rateUnit"] = if (kind == "hand") (if (interest == "month") "month" else "year") else rateUnit
            d["interestEvery"] = effEvery
            if (kind == "gold") d["grams"] = n(grams)
        }
        if (kind == "hand") {
            if (repayBy.isNotEmpty()) d["repayBy"] = repayBy else d.remove("repayBy")
            if (!editing) { if (given) d["direction"] = "given" else d.remove("direction") }
        }
        return d
    }

    fun save() {
        val draft = build()
        val problem = if (kind == "hand" && interest != "none" && n(rate) <= 0)
            t("loan.err.rateNeeded", "Please enter the interest rate, or choose No interest.") else validateLoan(draft)
        if (problem != null) { error = problem; errorStamp++; return }
        var id = ""
        Ledger.edit { id = saveLoan(draft).s("id") }
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
            TextInput(name, { name = it; error = null }, t("loan.name", "Name"), placeholder = t("loan.name.eg", "e.g. Home loan"))
            TextInput(
                lender, { lender = it },
                if (kind == "bank") t("loan.lender.bank", "Bank") else words.who,
                placeholder = if (kind == "bank") t("loan.lender.bank.eg", "e.g. State Bank") else words.whoEg,
            )
            MoneyField(principal, { principal = it; error = null }, words.amount)
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
            } else if (kind != "bank") {
                FieldTitle(t("loan.rateUnit", "Interest is quoted"))
                SegmentedControl(listOf("year" to t("loan.perYear", "Per year"), "month" to t("loan.perMonth", "Per month")), rateUnit, { rateUnit = it })
            }
            val perMonth = if (kind == "hand") interest == "month" else rateUnit == "month"
            val hasInterest = kind != "hand" || interest != "none"
            if (hasInterest) TextInput(
                rate, { rate = decimalOnly(it); error = null },
                if (kind == "bank" || !perMonth) t("loan.rate.year.label", "Interest rate (% a year)") else t("loan.rate.month.label", "Interest rate (% a month)"),
                keyboard = KeyboardType.Decimal, placeholder = "0",
            )
            if (kind == "bank") {
                MoneyField(emi, { emi = it; error = null }, t("loan.emi", "EMI (the monthly payment)"), supporting = t("loan.emi.help", "If you do not know it, leave it empty and give the number of months."))
                TextInput(tenure, { tenure = digitsOnly(it); error = null }, t("loan.tenure", "Number of months"), keyboard = KeyboardType.Number, placeholder = "12")
                TextInput(dueDay, { dueDay = digitsOnly(it).take(2); dueDayTouched = true; error = null }, t("loan.dueDay", "Pay on this day of the month"), keyboard = KeyboardType.Number, placeholder = "5")
            } else {
                if (hasInterest) {
                    FieldTitle(t("loan.every", "Interest is paid"))
                    SegmentedControl(listOf("month" to t("loan.everyMonth", "Every month"), "year" to t("loan.everyYear", "Every year")), effEvery, { every = it })
                }
                if (kind == "gold") TextInput(grams, { grams = decimalOnly(it) }, t("loan.grams", "Gold pledged (grams)"), keyboard = KeyboardType.Decimal, placeholder = "0")
            }
            if (!showDetails) {
                PlainButton(t("pay.addDetails", "Add details"), { showDetails = true }, Modifier.align(Alignment.Start), bold = true)
            } else {
                if (kind == "hand") DateField(repayBy, { repayBy = it; error = null }, t("loan.repayBy.label", "To be returned by (optional)"), clearable = true)
                TextInput(note, { note = it }, t("loan.note", "Note"), singleLine = false, supporting = t("common.optional", "Optional"))
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
    "gold" -> t("loan.kind.gold", "Gold loan")
    else -> t("loan.choice.given", "Money I gave to someone")
}

private fun choiceHelp(k: String): String = when (k) {
    "bank" -> t("loan.kind.bank.help", "A home, car, personal or DWCRA loan with a monthly EMI")
    "hand" -> loanWords(false).kindHelp
    "gold" -> t("loan.kind.gold.help", "A loan taken against gold")
    else -> loanWords(true).kindHelp
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
