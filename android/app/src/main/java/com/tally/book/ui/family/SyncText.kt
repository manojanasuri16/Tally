package com.tally.book.ui.family

import com.tally.book.core.t
import com.tally.book.core.tp
import com.tally.book.sync.SyncError
import com.tally.book.sync.SyncResult
import com.tally.book.sync.SyncState

/*
 * The plain words for sync: how long ago, what a failed sync means, the quiet status line.
 * Pure (no Compose, no network) so they can be unit-tested. No technical words reach the screen: never "relay", "key" or "encrypt".
 */
object SyncText {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    /** "just now", "5 min ago", "2 hours ago", "yesterday", "3 days ago". A time in the future counts as now. */
    fun ago(thenMs: Long, nowMs: Long): String {
        val d = maxOf(0L, nowMs - thenMs)
        return when {
            d < MIN -> t("ago.now", "just now")
            d < HOUR -> t("ago.min", "{n} min ago", "n" to (d / MIN).toInt())
            d < DAY -> tp("ago.hour", (d / HOUR).toInt(), "{n} hour ago", "{n} hours ago")
            d < 2 * DAY -> t("ago.yesterday", "yesterday")
            else -> tp("ago.day", (d / DAY).toInt(), "{n} day ago", "{n} days ago")
        }
    }

    /** One sentence for a failed sync. [joining] words the "not allowed" case for a code that was just tried. */
    fun errorMessage(e: SyncError, joining: Boolean = false): String = when (e) {
        SyncError.Offline -> t("sync.err.offline", "No internet. Try again later.")
        SyncError.Unauthorised ->
            if (joining) t("sync.err.badCode", "This family code does not work. Ask for a new one.")
            else t("sync.err.left", "This phone is no longer part of the family.")
        SyncError.Removed -> t("sync.removed", "You were removed from the family. Your loans stay on this phone.")
        SyncError.NotAdmin -> t("sync.err.notAdmin", "Only a family admin can do that.")
        SyncError.Stale -> t("sync.err.stale", "The family changed just now. Try again.")
        SyncError.Limit -> t("sync.err.limit", "The family has reached a limit. Try again later.")
        SyncError.Refused -> t("sync.err.refused", "That can't be done in this family.")
        SyncError.InviteGone -> t("sync.err.badCode", "This family code does not work. Ask for a new one.")
        SyncError.Unverified -> t("sync.err.unverified", "Something from the family could not be checked, so nothing was changed.")
        is SyncError.Server -> t("sync.err.busy", "Sharing is busy right now. Try again later.")
        is SyncError.BadData -> t("sync.err.data", "Something went wrong while sharing. Try again later.")
        SyncError.NotConfigured -> t("sync.err.off", "Sharing is not available in this version of the app.")
        SyncError.Storage -> t("sync.err.storage", "This phone could not save. Free some space and try again.")
    }

    /** The sentence to tell after a sync, or null when nothing needs saying (it worked, or another sync is already running). */
    fun message(r: SyncResult, joining: Boolean = false): String? = when (r) {
        is SyncResult.Done, SyncResult.Busy -> null
        is SyncResult.Failed -> errorMessage(r.error, joining)
        SyncResult.NoFamily -> t("sync.noFamily", "You are not sharing with your family yet.")
        SyncResult.AlreadyInFamily -> t("sync.already", "This phone is already in a family.")
        SyncResult.BadInvite -> t("sync.notCode", "This is not a Tally family code.")
        SyncResult.OldInvite -> oldCode()
        SyncResult.JoinPending -> t("join.pending", "Asked to join. Someone in your family needs to allow it; this phone joins on its own once they do.")
        SyncResult.JoinDenied -> t("join.denied", "You were not let into the family.")
        SyncResult.Removed -> t("sync.removed", "You were removed from the family. Your loans stay on this phone.")
        SyncResult.OldFamily -> oldFamilyText()
    }

    /** A family made by 0.1.0/0.2.0: sharing has to be set up again. */
    fun oldFamilyText(): String = t("sync.oldFamily", "Sharing needs to be set up again after this update. Your loans stay on this phone.")

    /** An invite made by 0.1.0/0.2.0. */
    fun oldCode(): String = t("join.oldCode", "This code is from an older Tally. Ask for a new one.")

    /** What the quiet line under the figure says, and whether tapping it does something. */
    class Line(val text: String, val action: Action)
    enum class Action { NONE, SET_UP, RETRY }

    fun status(state: SyncState, hasFamily: Boolean, lastAt: Long, nowMs: Long, joining: Boolean = false, oldFamily: Boolean = false): Line = when {
        oldFamily -> Line(oldFamilyText(), Action.SET_UP)
        joining && !hasFamily -> Line(t("sync.line.joining", "Waiting for an admin to let you in"), Action.RETRY)
        !hasFamily -> Line(t("sync.line.none", "Not shared yet"), Action.SET_UP)
        state.running -> Line(t("sync.line.running", "Syncing…"), Action.NONE)
        state.last is SyncResult.Failed -> Line(t("sync.line.failed", "Couldn't sync · tap to retry"), Action.RETRY)
        lastAt > 0 -> Line(t("sync.line.done", "Shared with family · synced {when}", "when" to ago(lastAt, nowMs)), Action.NONE)
        else -> Line(t("sync.line.new", "Shared with family"), Action.NONE)
    }

    /** The device-local date (ISO, yyyy-mm-dd) of an epoch-millisecond time, for "joined 12-Nov-2026". */
    fun isoDate(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}
