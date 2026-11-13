package com.tally.book.ui.loans

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.*
import com.tally.book.ui.AppNav
import com.tally.book.ui.ScreenPadding
import com.tally.book.ui.SubScreen
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.LocalSemantic
import com.tally.book.ui.theme.LocalEasy
import com.tally.book.ui.theme.stackGap
import kotlinx.coroutines.launch

/* A loan: what is left, what is next, the payments, the documents, and the few things you can do to it. */

private class Detail(
    val loan: Obj, val view: Obj, val payments: List<Obj>, val docs: List<Obj>, val attached: Set<String>,
)

private fun readDetail(id: String): Detail? {
    val l = loan(id) ?: return null
    val pays = paymentsOf(id)
    val withFiles = filesAll().filter { it.s("ownerType") == "payment" }.map { it.s("ownerId") }.toSet()
    return Detail(l, loanView(l), pays, filesOf("loan", id), pays.map { it.s("id") }.filter { it in withFiles }.toSet())
}

@Composable
fun LoanDetailScreen(id: String) {
    val rev = rememberLedger()
    val cur = remember(rev, id) { readDetail(id) }
    // keep showing the last state while the screen pops after a delete
    val last = remember { arrayOfNulls<Detail>(1) }
    if (cur != null) last[0] = cur
    val d = cur ?: last[0]
    if (d == null) {
        SubScreen(t("loan.title", "Entry")) { pad -> Column(Modifier.padding(pad).fillMaxSize()) { EmptyState(t("loan.gone", "This entry is no longer here.")) } }
        return
    }
    LoanBody(id, d)
    ShareIntake()
}

@Composable
private fun LoanBody(id: String, d: Detail) {
    val loan = d.loan; val v = d.view
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val closed = loan.b("closed")
    val kind = loan.s("kind")
    val neg = LocalSemantic.current.negative
    val words = loanWords(loan)
    val given = isGiven(loan)

    var logging by remember { mutableStateOf(false) }
    var editingLoan by remember { mutableStateOf(false) }
    var editingPayment by remember { mutableStateOf<Obj?>(null) }
    var payingPending by remember { mutableStateOf(false) }
    var changingRate by remember { mutableStateOf(false) }
    var renewing by remember { mutableStateOf(false) }
    val pickers = rememberAttachPickers { p -> storeAll("loan", id, listOf(p)) }

    fun closeIt() {
        scope.launch {
            val left = v.num("owed")
            if (left > 0 && !Dialogs.ask(t("loan.close.ask", "Close this entry?\n\nThere is still {amt} left to pay. You can open it again later.", "amt" to amtMask(left)), t("loan.close", "Close this entry"))) return@launch
            Ledger.edit { closeLoan(id, todayISO()) }
            Dialogs.snack(t("loan.closed.snack", "Entry closed"), t("dlg.undo", "Undo")) { Ledger.edit { reopenLoan(id) } }
        }
    }
    fun deleteIt() {
        scope.launch {
            val rec = DB.objs("loans").firstOrNull { it.s("id") == id }
            if (rec != null && !com.tally.book.sync.SyncModel.mayDeleteHere(DB, "loan", rec)) { Dialogs.tell(notYoursToDelete(rec)); return@launch }
            val ok = Dialogs.ask(
                t("loan.delete.ask", "Delete this entry?\n\nIts payments and documents are deleted too. This cannot be undone."),
                t("dlg.delete", "Delete"), danger = true,
            )
            if (!ok) return@launch
            val payIds = d.payments.map { it.s("id") }.toSet()
            val fileIds = filesAll().filter { (it.s("ownerType") == "loan" && it.s("ownerId") == id) || (it.s("ownerType") == "payment" && it.s("ownerId") in payIds) }.map { it.s("id") }
            Ledger.edit { deleteLoan(id); fileIds.forEach { DB.oo("sync").oo("blobsUp").remove(it) } }
            dropBytesNow(fileIds)
            AppNav.back()
        }
    }

    SubScreen(loan.s("name")) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(stackGap()),
        ) {
            val kindLine = listOf(loanKindLabel(loan), loan.s("lender")).filter { it.isNotBlank() }.joinToString(" · ")
            val subtitle = kindLine + "\n" + addedByLine(loan.s("by"), loan.num("at"), loan.s("updatedBy"), ::memberName) { fmtISODate(isoOfMillis(it)) }
            if (closed) BigFigure(t("loan.closed", "Closed"), null, sub = subtitle, text = fmtISODate(loan.s("closedDate")).ifEmpty { "—" })
            else BigFigure(words.left, v.num("owed"), sub = subtitle)

            if (!closed) PendingRow(loan, v) { payingPending = true }
            if (!closed) NextDue(loan, v)
            if (!closed) PrimaryButton(words.log, { logging = true }, Modifier.fillMaxWidth())

            Facts(loan, v)
            RateChanges(loan, closed)
            PaymentsGroup(d, given, onEdit = { editingPayment = it })

            AGroup(title = t("doc.title", "Documents"), footer = if (filesShared(loan)) FileAway.shareNote() else null) {
                d.docs.forEach { f ->
                    item(Space60) {
                        ARow(
                            f.s("name"), sub = storedFileLine(f),
                            icon = Icons.Rounded.Description, tint = Apple.colors.accent,
                            onClick = { scope.launch { openStoredFile(ctx, f) } },
                            trailing = {
                                BarIcon(Icons.Rounded.DeleteOutline, t("doc.deleteNamed", "Delete {name}", "name" to f.s("name")), {
                                    scope.launch {
                                        if (Dialogs.ask(t("doc.delete.ask", "Delete \"{name}\"?\n\nIt is removed from this phone.", "name" to f.s("name")), t("dlg.delete", "Delete"), danger = true))
                                            removeStored(listOf(f.s("id")))
                                    }
                                }, tint = Apple.colors.secondaryLabel)
                            },
                        )
                    }
                }
                item { AttachButtons(pickers) }
            }

            AGroup {
                if (!closed && kind == "bank" && interestMethod(loan) == "reducing") item { ButtonRow(t("loan.rateChange.title", "Rate changed")) { changingRate = true } }
                if (!closed && hasRepayBy(kind) && v.s("repayBy").isNotEmpty()) item { ButtonRow(t("loan.renew.title", "Renewed")) { renewing = true } }
                item { ButtonRow(t("loan.edit", "Edit entry")) { editingLoan = true } }
                if (closed) item { ButtonRow(t("loan.reopen", "Open this entry again")) { Ledger.edit { reopenLoan(id) } } }
                else item { ButtonRow(t("loan.close", "Close this entry")) { closeIt() } }
                item { ButtonRow(t("loan.delete", "Delete this entry"), neg) { deleteIt() } }
            }
        }
    }

    if (logging) PaymentSheet(id, null, onDismiss = { logging = false })
    if (payingPending) PaymentSheet(id, null, startAmount = v.num("pendingAmount"), onDismiss = { payingPending = false })
    if (changingRate) RateChangeSheet(id, onDismiss = { changingRate = false })
    if (renewing) RenewSheet(id, onDismiss = { renewing = false })
    editingPayment?.let { p -> PaymentSheet(id, p, onDismiss = { editingPayment = null }) }
    if (editingLoan) LoanSheet(loan, onDismiss = { editingLoan = false }, onSaved = { editingLoan = false })
}

private val Space60 = com.tally.book.ui.theme.Space.iconInset

@Composable
private fun NextDue(loan: Obj, v: Obj) {
    val s = LocalSemantic.current
    val owed = v.num("owed")
    if (owed <= 0) {
        AGroup { item { NoteRow(t("loan.paidOff", "Everything is paid. You can close this entry below.")) } }
        return
    }
    val date = v.s("nextDueDate")
    val w = loanWords(loan)
    val repayBy = v.s("repayBy")
    AGroup {
        if (v.b("noInterest")) {
            item { NoteRow(t("loan.noInterest", "No interest. Pay back when you can.")) }
        } else if (v.b("paidThisPeriod")) {
            val monthly = isInstalments(loan) || v.s("interestEvery") == "month"
            item(Space60) {
                ARow(
                    if (monthly) w.paidMonth else w.paidYear,
                    sub = if (date.isNotEmpty()) t("loan.nextOn", "Next payment: {date}", "date" to dateInWords(date)) else null,
                    icon = Icons.Rounded.CheckCircle, tint = s.positive,
                )
            }
        } else if (date.isNotEmpty()) {
            val words = dateInWords(date)
            item {
                ARow(
                    w.next, sub = if (v.b("overdue")) t("loan.overdue.on", "Overdue since {date}", "date" to words) else words,
                    subColor = if (v.b("overdue")) s.negative else androidx.compose.ui.graphics.Color.Unspecified, money = v.num("nextDueAmount"), moneyColor = Apple.colors.label,
                )
            }
        } else item { NoteRow(t("loan.noNext", "No payment is due.")) }
        if (repayBy.isNotEmpty()) item {
            ARow(
                t("loan.repayBy", "To be returned by {date}", "date" to fmtISODate(repayBy)),
                sub = if (v.b("repayOverdue")) t("loan.repayBy.over", "This date has passed") else null, subColor = s.negative,
                money = owed, moneyColor = Apple.colors.label,
            )
        }
    }
}

/** The warning row: what fell due and was not paid, in plain words. A tap opens Log payment with that amount. */
@Composable
private fun PendingRow(loan: Obj, v: Obj, onPay: () -> Unit) {
    val count = v.num("pendingCount").toInt()
    if (count <= 0 || v.num("owed") <= 0) return
    val s = LocalSemantic.current
    val amount = v.num("pendingAmount")
    AGroup {
        item(Space60) {
            ARow(
                pendingInWords(loan, amtText(amount), count), icon = Icons.Rounded.Warning, tint = s.warning, titleColor = s.warning,
                sub = loanWords(loan).log, chevron = true, onClick = onPay,
            )
        }
    }
}

@Composable
private fun Facts(loan: Obj, v: Obj) {
    val kind = loan.s("kind")
    AGroup(title = t("loan.details", "Details")) {
        item { ARow(loanWords(loan).amount, money = loan.num("principal"), moneyColor = Apple.colors.label) }
        if (isPersonInstalments(loan)) {
            val given = isGiven(loan)
            if (loan.num("received") > 0) item {
                ARow(if (given) t("loan.received.given", "Actually handed over") else t("loan.received", "Actually received"), money = loan.num("received"), moneyColor = Apple.colors.label)
            }
            item { ARow(if (given) t("loan.total.given", "To get back in all") else t("loan.total", "To pay back in all"), money = v.num("totalToRepay"), moneyColor = Apple.colors.label) }
            if (v.num("extra") > 0) item {
                ARow(if (given) t("loan.extra.given", "Extra you get") else t("loan.extra", "Extra you pay"), money = v.num("extra"), moneyColor = Apple.colors.label)
            }
        }
        if (isInstalments(loan)) {
            val freq = v.s("frequency").ifEmpty { frequencyOf(loan) }
            item { ARow(if (freq == "month") t("loan.emi.short", "EMI") else t("loan.emi.each.short", "Each payment"), money = v.num("emi"), moneyColor = Apple.colors.label) }
            if (kind == "bank") item { ARow(t("loan.rate", "Interest rate"), value = rateWithMethodInWords(v.num("currentRate"), "year", v.s("interestMethod"))) }
            item {
                ARow(
                    t("loan.instalment", "Payments"),
                    value = if (freq == "month") dueDayInWords(v.num("dueDay").toInt()) else instalmentDayInWords(loan),
                )
            }
            item { ARow(t("loan.instalmentsLeft", "Payments left"), value = instalmentsInWords(v.num("instalmentsLeft").toInt(), freq)) }
            if (v.s("endDate").isNotEmpty()) item { ARow(t("loan.ends", "Last payment"), value = fmtISODate(v.s("endDate"))) }
        } else {
            if (v.b("noInterest")) item { ARow(t("loan.interest", "Interest"), value = t("loan.noInterest.choice", "No interest")) }
            else {
                item { ARow(t("loan.rate", "Interest rate"), value = rateInWords(loan.num("rate"), loan.s("rateUnit"))) }   // interest-only: always on what is still owed
                item { ARow(t("loan.interestPaid", "Interest is paid"), value = everyInWords(v.s("interestEvery").ifEmpty { interestEvery(loan) }).lowercase()) }
            }
            if (kind == "gold") item { ARow(t("loan.pledged", "Pledged"), value = pledgeLabel(v.s("pledge"), v.s("pledgeText"))) }
            if (kind == "gold" && v.num("grams") > 0) item { ARow(t("loan.gold", "Gold"), value = t("loan.grams.value", "{g} grams", "g" to gram(v.num("grams")))) }
            if (v.num("renewals") > 0) item {
                ARow(
                    t("loan.renewals", "Renewed"),
                    value = tp("loan.renewals.count", v.num("renewals").toInt(), "{n} time", "{n} times"),
                    sub = t("loan.renewals.last", "Last on {date}", "date" to fmtISODate(v.s("lastRenewal"))),
                )
            }
        }
        item { ARow(if (isGiven(loan)) t("loan.given", "Given on") else t("loan.taken", "Taken on"), value = fmtISODate(loan.s("startDate")).ifEmpty { "—" }) }
        if (v.s("paidUpTo").isNotEmpty()) item { ARow(t("loan.paidUpTo.row", "Paid up to"), value = fmtISODate(v.s("paidUpTo"))) }
        if (loan.b("private")) item {
            ARow(t("loan.private", "Private"), sub = t("loan.keepPrivate.help", "Only on this phone. Not shared with family."), trailing = { PrivateMark() })
        }
        if (loan.s("note").isNotBlank()) item { NoteRow(loan.s("note")) }
    }
}

/** The rate changes of a bank loan, newest last, each with a delete (it asks first). Nothing is drawn when there are none. */
@Composable
private fun RateChanges(loan: Obj, closed: Boolean) {
    val changes = rateChangesOf(loan)
    if (changes.isEmpty()) return
    val scope = rememberCoroutineScope()
    AGroup(title = t("loan.rateChange.list", "Rate changes")) {
        changes.forEach { c ->
            item {
                ARow(
                    t("loan.rateChange.row", "{rate}% a year", "rate" to fmtIN(c.num("rate"), 2)),
                    sub = listOfNotNull(
                        t("loan.rateChange.since", "From {date}", "date" to fmtISODate(c.s("from"))),
                        if (c.num("emi") > 0) t("loan.rateChange.emiRow", "New EMI {amt}", "amt" to amtMask(c.num("emi"))) else null,
                    ).joinToString(" · "),
                    trailing = if (closed) null else ({
                        BarIcon(Icons.Rounded.DeleteOutline, t("loan.rateChange.delete", "Delete this rate change"), {
                            scope.launch {
                                if (Dialogs.ask(t("loan.rateChange.delete.ask", "Delete this rate change?\n\nThe entry goes back to the earlier rate from that date."), t("dlg.delete", "Delete"), danger = true))
                                    Ledger.edit { removeRateChange(loan.s("id"), c.s("id")) }
                            }
                        }, tint = Apple.colors.secondaryLabel)
                    }),
                )
            }
        }
    }
}

/** "Day 5 of each month". */
private fun dueDayInWords(day: Int): String =
    if (day in 1..31) t("loan.dueDay.value", "Day {n} of each month", "n" to day) else frequencyLabel("month")

@Composable
private fun PaymentsGroup(d: Detail, given: Boolean, onEdit: (Obj) -> Unit) {
    AGroup(title = t("pay.title", "Payments")) {
        if (d.payments.isEmpty()) item { NoteRow(t("pay.none", "No payments yet.")) }
        d.payments.forEach { p ->
            item {
                // Easy mode has no swipe: a payment is deleted with the Delete button in its sheet
                SwipeRow(enabled = !LocalEasy.current, onDelete = { deletePaymentWithUndo(p) }) { _, _ ->
                    ARow(
                        payKindLabel(p.s("kind"), d.loan.s("kind"), given),
                        sub = t("pay.rowSub", "{date} · by {name}", "date" to fmtISODate(p.s("date")), "name" to memberName(p.s("by"))),
                        money = p.num("amount"), moneyColor = Apple.colors.label, onClick = { onEdit(p) }, chevron = LocalEasy.current,
                        trailing = if (p.s("id") in d.attached) ({ Icon(Icons.Rounded.AttachFile, t("pay.hasFiles", "Has attachments"), Modifier.size(18.dp), tint = Apple.colors.secondaryLabel) }) else null,
                    )
                }
            }
        }
    }
}

/** In a family only the one who added an entry (or its loan), or an admin, can delete it: the others' phones would keep it. */
internal fun notYoursToDelete(rec: Obj): String =
    t("delete.notYours", "Only {name}, who added this, or a family admin can delete it.", "name" to memberName(rec.s("by")))

/** Deletes a payment and offers Undo (the files' bytes are kept for a while so Undo can bring them back too). Always true: the row is gone. */
internal suspend fun deletePaymentWithUndo(p: Obj): Boolean {
    if (!com.tally.book.sync.SyncModel.mayDeleteHere(DB, "payment", p)) { Dialogs.tell(notYoursToDelete(p)); return false }
    val id = p.s("id")
    val snap = p.copyObj()
    val files = filesOf("payment", id).map { it.copyObj() }
    Ledger.edit {
        deletePayment(id)
        files.forEach { DB.oo("sync").oo("blobsUp").remove(it.s("id")) }
    }
    dropBytesLater(files.map { it.s("id") })
    Dialogs.snack(t("pay.deleted", "Payment deleted"), t("dlg.undo", "Undo")) { restorePayment(snap, files) }
    return true
}
