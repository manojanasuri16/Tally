package com.tally.book.ui.loans

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tally.book.core.*
import com.tally.book.data.Ledger
import com.tally.book.model.*
import com.tally.book.ui.components.*
import com.tally.book.ui.theme.Apple
import com.tally.book.ui.theme.AppleShapes
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Log a payment, or edit one, in a sheet. Amount, date and what it was for come first; "Add details" opens the optional rest:
 * a note, a pasted bank message (stored in `sms`), and photos or documents. The form works on copies held in remember; Save writes
 * the payment (savePayment inside Ledger.edit), then stores each new file and removes the ones taken off (Attachments.kt).
 * Nothing is stored or removed before Save.
 */

@Composable
fun PaymentSheet(loanId: String, existing: Obj?, sms: String? = null, startKind: String? = null, startAmount: Double? = null, onDismiss: () -> Unit) {
    val theLoan = remember(loanId) { loan(loanId) }
    if (theLoan == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val editing = existing != null
    val words = loanWords(theLoan)
    val given = isGiven(theLoan)
    /* a due row for the amount still owed starts the form as a part repayment of all of it */
    val suggestion = remember(loanId, startKind, startAmount) {
        when {
            startKind == "principal" -> obj("kind" to "principal", "amount" to loanView(theLoan).num("owed"))
            /* the pending row: the usual kind of payment for this loan, with all that is pending as the amount */
            startAmount != null -> suggestPayment(theLoan).also { it["amount"] = startAmount }
            else -> suggestPayment(theLoan)
        }
    }
    val options = remember(loanId) { payKindOptions(theLoan.s("kind"), existing?.s("kind")) }
    val saved = remember { existing?.let { filesOf("payment", it.s("id")) } ?: emptyList() }

    var amount by remember { mutableDoubleStateOf(existing?.num("amount") ?: suggestion.num("amount")) }
    var amountTouched by remember { mutableStateOf(editing) }
    var date by remember { mutableStateOf(existing?.s("date")?.ifEmpty { null } ?: todayISO()) }
    var kind by remember { mutableStateOf(existing?.s("kind")?.ifEmpty { null } ?: suggestion.s("kind").takeIf { it in options } ?: options.first()) }
    var note by remember { mutableStateOf(existing?.s("note").orEmpty()) }
    var message by remember { mutableStateOf(existing?.s("sms")?.ifEmpty { null } ?: sms.orEmpty()) }
    var showDetails by remember { mutableStateOf(editing && (existing?.s("note").orEmpty().isNotBlank() || existing?.s("sms").orEmpty().isNotBlank() || saved.isNotEmpty()) || !sms.isNullOrBlank()) }
    val pending = remember { mutableStateListOf<Picked>() }
    val removed = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var errorStamp by remember { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val pickers = rememberAttachPickers { p -> pending.add(p); showDetails = true }

    fun save() {
        val draft = existing?.copyObj() ?: obj()
        draft["loanId"] = loanId; draft["date"] = date; draft["amount"] = amount; draft["kind"] = kind
        draft["note"] = note.trim(); draft["sms"] = message.trim()
        val problem = validatePayment(draft)
        if (problem != null) { error = problem; errorStamp++; return }
        var id = ""
        val before = loanView(theLoan).num("owed")
        Ledger.edit { id = savePayment(draft).s("id") }
        removeStored(removed.toList())
        storeAll("payment", id, pending.toList())
        val after = loan(loanId)?.let { loanView(it).num("owed") } ?: before
        onDismiss()
        if (!theLoan.b("closed") && reachedZero(before, after)) offerClose(loanId, words.closeAsk)
    }

    EditSheet(
        if (editing) t("pay.edit", "Edit payment") else words.log, onDismiss = onDismiss,
        onSave = { save() }, saveLabel = t("dlg.save", "Save"),
    ) {
        Text(theLoan.s("name"), Modifier.padding(start = 4.dp), style = Apple.type.subheadline, color = Apple.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MoneyField(amount, { amount = it; amountTouched = true; error = null }, if (given) t("pay.amount.given", "Amount received") else t("pay.amount", "Amount paid"))
        DateField(date, { date = it; error = null }, t("pay.date", "Date"))
        Text(t("pay.for", "What is it for?"), Modifier.padding(start = 14.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
        ChoiceChips(options.map { it to payKindLabel(it, theLoan.s("kind"), given) }, kind, { k ->
            kind = k; error = null
            if (!amountTouched) amount = if (k == suggestion.s("kind")) suggestion.num("amount") else 0.0
        })

        if (!showDetails) {
            PlainButton(t("pay.addDetails", "Add details"), { showDetails = true }, Modifier.align(Alignment.Start), bold = true)
        } else {
            TextInput(note, { note = it }, t("pay.note", "Note"), singleLine = false, supporting = t("common.optional", "Optional"))
            TextInput(
                message, { message = it }, t("pay.message", "Bank message"), singleLine = false,
                supporting = t("pay.message.help", "Paste the message the bank sent you. Optional."),
            )
            PlainButton(t("pay.paste", "Paste bank message"), {
                val text = clipboard.getText()?.text.orEmpty()
                if (text.isBlank()) Dialogs.snack(t("pay.paste.empty", "There is nothing to paste."))
                else message = text
            }, Modifier.align(Alignment.Start), icon = Icons.Rounded.ContentPaste)

            AttachedList(saved, removed, pending)
            InsetSurface { AttachButtons(pickers) }
        }
        FormError(error, errorStamp)
    }
}

/** Outlives the sheet, which is gone by the time the question is asked. */
private val askScope = kotlinx.coroutines.MainScope()

/** Everything owed has been paid: offers once to close the loan. */
private fun offerClose(loanId: String, message: String) {
    askScope.launch {
        if (Dialogs.ask(message, t("loan.close", "Close this loan")))
            Ledger.edit { closeLoan(loanId, todayISO()) }
    }
}

/**
 * The files a form holds before Save: the ones already saved (minus those marked in [removed]; a tap opens one) and the [pending] ones
 * picked now. Taking either off only changes the form's lists; nothing is stored or deleted until Save.
 */
@Composable
fun AttachedList(saved: List<Obj>, removed: MutableList<String>, pending: MutableList<Picked>) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val shown = saved.filter { it.s("id") !in removed }
    if (shown.isEmpty() && pending.isEmpty()) return
    InsetSurface {
        shown.forEachIndexed { i, f ->
            if (i > 0) Hairline(start = 16.dp)
            // A saved file opens on a tap (fetching it first when a family member added it), so a receipt can be looked at again.
            val sub = if (FileFetch.isBusy(f.s("id"))) t("doc.getting", "Getting the file…") else fileSizeLabel(f.num("size"))
            AttachedRow(f.s("name"), sub, isImage = fileFamily(f) == "image", thumb = null, onRemove = { removed.add(f.s("id")) },
                onOpen = { scope.launch { openStoredFile(ctx, f) } })
        }
        pending.toList().forEachIndexed { i, p ->
            if (i > 0 || shown.isNotEmpty()) Hairline(start = 16.dp)
            var thumb by remember(p.key) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(p.key) { if (p.photo) thumb = withContext(Dispatchers.Default) { PhotoCodec.thumb(p.bytes) } }
            AttachedRow(p.name, fileSizeLabel(p.bytes.size.toDouble()), isImage = p.photo, thumb = thumb, onRemove = { pending.remove(p) })
        }
    }
}

/** A file waiting to be saved with the payment, or one it already has, with a button to take it off. */
@Composable
private fun AttachedRow(name: String, size: String, isImage: Boolean, thumb: ImageBitmap?, onRemove: () -> Unit, onOpen: (() -> Unit)? = null) {
    val c = Apple.colors
    Row(Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.pressDim(onClick = onOpen) else Modifier).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(AppleShapes.tile).background(c.fill), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(if (isImage) Icons.Rounded.Image else Icons.Rounded.Description, null, tint = c.secondaryLabel, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Apple.type.body, color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(size, style = Apple.type.footnote, color = c.secondaryLabel)
        }
        BarIcon(Icons.Rounded.Close, t("pay.removeFile", "Take this off"), onRemove, tint = c.secondaryLabel)
    }
}

/** The three ways to add a file: take a photo, choose a photo, add a document. */
@Composable
fun AttachButtons(pickers: AttachPickers) {
    Column {
        ARow(t("pay.takePhoto", "Take a photo"), icon = Icons.Rounded.PhotoCamera, tint = Apple.colors.accent, onClick = pickers.takePhoto)
        Hairline(start = 60.dp)
        ARow(t("pay.choosePhoto", "Choose a photo"), icon = Icons.Rounded.PhotoLibrary, tint = Apple.colors.accent, onClick = pickers.choosePhoto)
        Hairline(start = 60.dp)
        ARow(t("pay.addDocument", "Add a document"), icon = Icons.Rounded.NoteAdd, tint = Apple.colors.accent, onClick = pickers.chooseDocument)
    }
}
