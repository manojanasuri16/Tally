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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Log a payment, or edit one, in a sheet. Amount, date and what it was for come first; "Add details" opens the optional rest:
 * a note, a pasted bank message (stored in `sms`), and photos or documents. The form works on copies held in remember; Save writes
 * the payment (savePayment inside Ledger.edit), then stores each new file and removes the ones taken off (Attachments.kt).
 * Nothing is stored or removed before Save.
 */

@Composable
fun PaymentSheet(loanId: String, existing: Obj?, sms: String? = null, onDismiss: () -> Unit) {
    val theLoan = remember(loanId) { loan(loanId) }
    if (theLoan == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val editing = existing != null
    val suggestion = remember(loanId) { suggestPayment(theLoan) }
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
        Ledger.edit { id = savePayment(draft).s("id") }
        removeStored(removed.toList())
        storeAll("payment", id, pending.toList())
        onDismiss()
    }

    EditSheet(
        if (editing) t("pay.edit", "Edit payment") else t("pay.log", "Log payment"), onDismiss = onDismiss,
        onSave = { save() }, saveLabel = t("dlg.save", "Save"),
    ) {
        Text(theLoan.s("name"), Modifier.padding(start = 4.dp), style = Apple.type.subheadline, color = Apple.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MoneyField(amount, { amount = it; amountTouched = true; error = null }, t("pay.amount", "Amount paid"))
        DateField(date, { date = it; error = null }, t("pay.date", "Date"))
        Text(t("pay.for", "What is it for?"), Modifier.padding(start = 14.dp), style = Apple.type.footnote, color = Apple.colors.secondaryLabel)
        ChoiceChips(options.map { it to payKindLabel(it) }, kind, { k ->
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

            val shown = saved.filter { it.s("id") !in removed }
            if (shown.isNotEmpty() || pending.isNotEmpty()) {
                InsetSurface {
                    shown.forEachIndexed { i, f ->
                        if (i > 0) Hairline(start = 16.dp)
                        AttachedRow(f.s("name"), fileSizeLabel(f.num("size")), isImage = fileFamily(f) == "image", thumb = null, onRemove = { removed.add(f.s("id")) })
                    }
                    pending.toList().forEachIndexed { i, p ->
                        if (i > 0 || shown.isNotEmpty()) Hairline(start = 16.dp)
                        var thumb by remember(p.key) { mutableStateOf<ImageBitmap?>(null) }
                        LaunchedEffect(p.key) { if (p.photo) thumb = withContext(Dispatchers.Default) { PhotoCodec.thumb(p.bytes) } }
                        AttachedRow(p.name, fileSizeLabel(p.bytes.size.toDouble()), isImage = p.photo, thumb = thumb, onRemove = { pending.remove(p) })
                    }
                }
            }
            InsetSurface { AttachButtons(pickers) }
        }
        FormError(error, errorStamp)
    }
}

/** A file waiting to be saved with the payment, or one it already has, with a button to take it off. */
@Composable
private fun AttachedRow(name: String, size: String, isImage: Boolean, thumb: ImageBitmap?, onRemove: () -> Unit) {
    val c = Apple.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
