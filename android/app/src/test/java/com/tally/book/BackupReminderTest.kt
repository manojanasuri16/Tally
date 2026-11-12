package com.tally.book

import com.tally.book.data.BackupReminder
import com.tally.book.data.BackupReminder.DAY_MS
import org.junit.Assert.*
import org.junit.Test

class BackupReminderTest {
    private val now = 1_800_000_000_000L
    private fun due(sent: Long = 0, snooze: Long = 0, first: Long = now - 100 * DAY_MS, entries: Boolean = true) =
        BackupReminder.due(now, sent, snooze, first, entries)

    @Test fun neverSentShowsOnceInUseForAWeekWithAnEntry() = assertTrue(due())
    @Test fun neverSentOnANewInstallWaitsAWeek() {
        assertFalse(due(first = now - 6 * DAY_MS))
        assertTrue(due(first = now - 7 * DAY_MS))
    }
    @Test fun unknownFirstUseIsTreatedAsNew() = assertFalse(due(first = 0))
    @Test fun emptyBookNeverShows() { assertFalse(due(entries = false)); assertFalse(due(sent = now - 90 * DAY_MS, entries = false)) }
    @Test fun sentRecentlyHidesIt() = assertFalse(due(sent = now - 29 * DAY_MS))
    @Test fun sentMoreThanThirtyDaysAgoShowsIt() {
        assertFalse(due(sent = now - 30 * DAY_MS))
        assertTrue(due(sent = now - 30 * DAY_MS - 1))
    }
    @Test fun sentRecentlyIgnoresANewInstall() = assertFalse(due(sent = now - DAY_MS, first = now - DAY_MS))
    @Test fun snoozeHidesItUntilItEnds() {
        assertFalse(due(snooze = now + 1))
        assertTrue(due(snooze = now))
        assertTrue(due(snooze = now - DAY_MS))
    }
    @Test fun shareWithoutAnswerCountsOnlyAfterBeingAwayAWhile() {
        assertFalse(BackupReminder.returnedCounts(0, now))
        assertFalse(BackupReminder.returnedCounts(now - 2_000, now))
        assertTrue(BackupReminder.returnedCounts(now - 9_000, now))
    }
}
