package com.tally.book.ui.loans

import com.tally.book.core.t
import com.tally.book.sync.FileAsk
import com.tally.book.sync.SyncError

/*
 * The words for a file whose bytes are not on this phone (docs/DESIGN.md "Files"): Tally's server holds a file only until
 * everyone in the family has it, or 30 days, so a phone that joined later, was away a long time or was restored without its
 * files knows the file only by its record. It is shown as being on the phone of whoever added it, never as an error.
 * Pure (no Compose) so it can be unit-tested. No "relay", "key" or "encrypt" on screen.
 */
object FileAway {
    /** The quiet line under the attachments of a shared entry. */
    fun shareNote(): String = t("doc.shareNote", "Your family gets a copy. Each phone keeps its own; Tally's server holds it only until everyone has it.")

    /** "On <name>'s phone", or "On your other phone" when this member added it (from another phone). */
    fun where(by: String, myId: String, name: String): String =
        if (by.isNotEmpty() && by == myId) t("doc.away.mine", "On your other phone")
        else t("doc.away.on", "On {name}'s phone", "name" to name)

    /** The grey line under the file's name: where it is, and the date it was added. */
    fun line(where: String, date: String): String = if (date.isEmpty()) where else "$where · $date"

    /** What a tap says. [canAsk]: in a family and the file is shared, so "Ask for this file" is offered. */
    fun explain(by: String, myId: String, name: String, canAsk: Boolean): String {
        val first = if (by.isNotEmpty() && by == myId)
            t("doc.away.mine.why", "This file is kept on your other phone and in its backups.")
        else t("doc.away.why", "This file is kept on {name}'s phone and in their backups.", "name" to name)
        val second = t("doc.away.server", "Tally's server holds a file only until everyone in the family has it.")
        val third = if (canAsk) "\n\n" + t("doc.away.ask.hint", "You can ask for it. It comes the next time a phone that has it opens Tally.") else ""
        return "$first $second$third"
    }

    /** The one sentence after "Ask for this file" (null when the file is here now and simply opens). */
    fun afterAsk(r: FileAsk): String? = when (r) {
        FileAsk.Here -> null
        FileAsk.Asked -> t("doc.away.asked", "Asked. It comes the next time a phone that has it opens Tally.")
        FileAsk.NoFamily -> t("doc.away.noFamily", "This phone is not sharing with a family now.")
        is FileAsk.Failed -> if (r.error == SyncError.Offline) t("doc.away.offline", "Could not ask now. Check the internet and try again.")
            else t("doc.away.failed", "Could not ask for this file now. Try again later.")
    }
}
