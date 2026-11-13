package com.tally.book.ui.loans

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.*
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple

/* The two things a loan can have happen to it after it was made: its rate changed (a bank loan), or it was renewed (hand and gold). */

/**
 * "Rate changed": from which date, the new rate, and whether the payment stays the same (the loan then runs for more months, or
 * fewer) or the bank gave a new one. Saves with addRateChange; a second change on the same date replaces the first.
 */
@Composable
fun RateChangeSheet(loanId: String, onDismiss: () -> Unit) {
    val theLoan = remember(loanId) { loan(loanId) }
    if (theLoan == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var from by remember { mutableStateOf(todayISO()) }
    var rate by remember { mutableStateOf("") }
    var newEmi by remember { mutableStateOf(false) }
    var emi by remember { mutableDoubleStateOf(0.0) }
    var error by remember { mutableStateOf<String?>(null) }
    var stamp by remember { mutableIntStateOf(0) }

    fun save() {
        val newRate = n(rate)
        val newPayment = if (newEmi) emi else null
        val problem = if (rate.isBlank()) t("loan.err.rateChange.rate", "Please enter the new interest rate.")
        else validateRateChange(theLoan, from, newRate, newPayment)
        if (problem != null) { error = problem; stamp++; return }
        var again: String? = null
        Ledger.edit { again = addRateChange(loanId, from, newRate, newPayment) }
        if (again != null) { error = again; stamp++; return }
        onDismiss()
    }

    EditSheet(t("loan.rateChange.title", "Rate changed"), onDismiss, onSave = { save() }, saveEnabled = rate.isNotBlank()) {
        Text(loanTitle(theLoan), Modifier.padding(start = 4.dp), style = Apple.type.subheadline, color = Apple.colors.secondaryLabel)
        DateField(from, { from = it; error = null }, t("loan.rateChange.from", "New rate starts on"))
        TextInput(rate, { rate = decimalOnly(it); error = null }, t("loan.rateChange.rate", "New interest rate (% a year)"), keyboard = KeyboardType.Decimal, placeholder = "0")
        Text(t("loan.rateChange.emiAsk", "What happens to the payment?"), Modifier.padding(start = 14.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
        ChoiceChips(
            listOf("keep" to t("loan.rateChange.keep", "Keep the same EMI (more months)"), "new" to t("loan.rateChange.new", "New EMI")),
            if (newEmi) "new" else "keep", { newEmi = it == "new"; error = null },
        )
        if (newEmi) MoneyField(emi, { emi = it; error = null }, t("loan.rateChange.emi", "New EMI"))
        FormError(error, stamp)
    }
}

/**
 * "Renewed": the date it was renewed and the new date to repay or renew by (a year on for a gold loan, the same stretch again for
 * a loan from a person). Interest that is pending stays pending; it is paid as interest.
 */
@Composable
fun RenewSheet(loanId: String, onDismiss: () -> Unit) {
    val theLoan = remember(loanId) { loan(loanId) }
    if (theLoan == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    var on by remember { mutableStateOf(todayISO()) }
    var until by remember { mutableStateOf(LoanFormRules.suggestRenewal(theLoan, todayISO())) }
    var untilTouched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stamp by remember { mutableIntStateOf(0) }

    fun save() {
        var problem = validateRenewal(theLoan, until, on)
        if (problem == null) Ledger.edit { problem = renewLoan(loanId, until, on) }
        if (problem != null) { error = problem; stamp++; return }
        onDismiss()
    }

    EditSheet(t("loan.renew.title", "Renewed"), onDismiss, onSave = { save() }, saveEnabled = until.isNotEmpty()) {
        Text(loanTitle(theLoan), Modifier.padding(start = 4.dp), style = Apple.type.subheadline, color = Apple.colors.secondaryLabel)
        DateField(on, {
            on = it; error = null
            if (!untilTouched) until = LoanFormRules.suggestRenewal(theLoan, it)
        }, t("loan.renew.on", "Renewed on"))
        DateField(until, { until = it; untilTouched = true; error = null }, t("loan.renew.until", "Repay or renew again by"))
        Text(
            t("loan.renew.help", "Interest that is still pending stays pending. Log it as a payment when it is paid."),
            Modifier.padding(start = 14.dp, end = 8.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel,
        )
        FormError(error, stamp)
    }
}
