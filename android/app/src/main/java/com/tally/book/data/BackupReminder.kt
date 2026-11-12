package com.tally.book.data

import androidx.compose.runtime.mutableIntStateOf
import com.tally.book.core.Clock
import com.tally.book.core.DevicePrefs

/**
 * The monthly "send a copy somewhere safe" reminder (docs/DESIGN.md, "Backups"). The daily backup sits in a folder on the
 * phone, so a lost phone loses it too; a copy that left the phone is the one that survives. This keeps, on the device only
 * (never in the book or a backup), when a backup was last sent off the phone, when the reminder was snoozed and when the
 * app was first used, and holds the pure rule for showing the card. Nothing here posts a notification.
 */
object BackupReminder {
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** A copy older than this (or never sent, once the app has been used for [NEW_INSTALL_DAYS]) is worth a reminder. */
    const val STALE_DAYS = 30
    const val SNOOZE_DAYS = 7
    const val NEW_INSTALL_DAYS = 7
    /** Without a chooser answer, a return to the app counts as sent only after the share sheet was away at least this long. */
    const val MIN_AWAY_MS = 8_000L

    private const val SENT = "tally-backup-sent"
    private const val SNOOZE = "tally-backup-snooze"
    private const val FIRST = "tally-first-use"
    private const val OPENED = "tally-backup-share-open"

    /** Read by the cards, so a change redraws them. */
    val tick = mutableIntStateOf(0)

    /**
     * Whether the card shows. [lastSentAt] 0 = never; [snoozedUntil] 0 = not snoozed; [firstUseAt] 0 = unknown (counts as new);
     * [hasEntries] = the book has at least one loan.
     */
    fun due(now: Long, lastSentAt: Long, snoozedUntil: Long, firstUseAt: Long, hasEntries: Boolean): Boolean {
        if (!hasEntries) return false
        if (snoozedUntil > now) return false
        if (lastSentAt > 0) return now - lastSentAt > STALE_DAYS * DAY_MS
        return firstUseAt in 1..now && now - firstUseAt >= NEW_INSTALL_DAYS * DAY_MS
    }

    /** Whether a return to the app after the share sheet was opened at [openedAt] counts as sent (no chooser answer came). */
    fun returnedCounts(openedAt: Long, now: Long): Boolean = openedAt > 0 && now - openedAt >= MIN_AWAY_MS

    private fun long(key: String) = DevicePrefs.get(key)?.toLongOrNull() ?: 0L
    private fun put(key: String, v: Long) { if (v <= 0) DevicePrefs.remove(key) else DevicePrefs.set(key, v.toString()); tick.intValue++ }

    fun lastSentAt(): Long = long(SENT)
    fun snoozedUntil(): Long = long(SNOOZE)
    fun firstUseAt(): Long = long(FIRST)

    /** Remembers the first day the app was used (kept; an update of an older install starts its week from here). */
    fun noteFirstUse() { if (long(FIRST) == 0L) put(FIRST, Clock.millis()) }

    fun isDue(hasEntries: Boolean): Boolean { tick.intValue; return due(Clock.millis(), lastSentAt(), snoozedUntil(), firstUseAt(), hasEntries) }

    fun snooze() = put(SNOOZE, Clock.millis() + SNOOZE_DAYS * DAY_MS)

    /** The share sheet was opened: if the person comes back without a chooser answer, [onReturn] may still count it. */
    fun opened() { put(OPENED, Clock.millis()) }

    /** The chooser said which app was picked: the backup is on its way off the phone. */
    fun sent() { put(OPENED, 0); put(SNOOZE, 0); put(SENT, Clock.millis()) }

    /** The app is back in front. Counts a share that was opened and not answered, when it was away long enough. */
    fun onReturn() {
        val at = long(OPENED)
        if (at == 0L) return
        if (returnedCounts(at, Clock.millis())) sent() else put(OPENED, 0)
    }
}
