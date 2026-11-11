package com.tally.book

import com.tally.book.sync.SyncError
import com.tally.book.sync.SyncResult
import com.tally.book.sync.SyncState
import com.tally.book.ui.family.SyncText
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class SyncTextTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val now = 1_800_000_000_000L

    @Test fun agoReadsPlainly() {
        fun ago(back: Long) = SyncText.ago(now - back, now)
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59_999))
        assertEquals("1 min ago", ago(min))
        assertEquals("5 min ago", ago(5 * min + 20_000))
        assertEquals("59 min ago", ago(hour - 1))
        assertEquals("1 hour ago", ago(hour))
        assertEquals("3 hours ago", ago(3 * hour + 40 * min))
        assertEquals("23 hours ago", ago(day - 1))
        assertEquals("yesterday", ago(day))
        assertEquals("yesterday", ago(2 * day - 1))
        assertEquals("2 days ago", ago(2 * day))
        assertEquals("30 days ago", ago(30 * day))
        assertEquals("just now", SyncText.ago(now + 5 * min, now))      // a clock that moved back counts as now
    }

    @Test fun everyErrorIsOnePlainSentence() {
        assertEquals("No internet. Try again later.", SyncText.errorMessage(SyncError.Offline))
        assertEquals("This phone is no longer part of the family.", SyncText.errorMessage(SyncError.Unauthorised))
        assertEquals("This family code does not work. Ask for a new one.", SyncText.errorMessage(SyncError.Unauthorised, joining = true))
        val others = listOf(SyncError.Server(500, "x"), SyncError.BadData("y"), SyncError.NotConfigured, SyncError.Storage)
        others.forEach { e ->
            val m = SyncText.errorMessage(e)
            assertTrue(m, m.isNotBlank())
            // no technical words, and none of the error's own detail, reach the screen
            listOf("relay", "key", "encrypt", "500", "BadData", "Exception").forEach { w -> assertFalse("$m contains $w", m.contains(w, ignoreCase = true)) }
        }
        assertEquals(others.size, others.map { SyncText.errorMessage(it) }.toSet().size)
    }

    @Test fun resultsNeedingNoWordsAreSilent() {
        assertNull(SyncText.message(SyncResult.Done(1, 2, 3, 4, 0)))
        assertNull(SyncText.message(SyncResult.Done(0, 0, 0, 0, 3)))
        assertNull(SyncText.message(SyncResult.Busy))
        assertEquals("No internet. Try again later.", SyncText.message(SyncResult.Failed(SyncError.Offline)))
        assertEquals("You are not sharing with your family yet.", SyncText.message(SyncResult.NoFamily))
        assertEquals("This phone is already in a family.", SyncText.message(SyncResult.AlreadyInFamily))
        assertEquals("This is not a Tally family code.", SyncText.message(SyncResult.BadInvite))
    }

    @Test fun statusLineFollowsTheEngine() {
        val idle = SyncState()
        assertEquals("Not shared yet", SyncText.status(idle, false, 0, now).text)
        assertEquals(SyncText.Action.SET_UP, SyncText.status(idle, false, 0, now).action)
        assertEquals("Shared with family", SyncText.status(idle, true, 0, now).text)
        assertEquals("Shared with family · synced 5 min ago", SyncText.status(idle, true, now - 5 * min, now).text)
        assertEquals(SyncText.Action.NONE, SyncText.status(idle, true, now - 5 * min, now).action)
        assertEquals("Syncing…", SyncText.status(SyncState(running = true), true, now - 5 * min, now).text)
        val failed = SyncText.status(SyncState(last = SyncResult.Failed(SyncError.Offline), lastAt = now - hour), true, now - hour, now)
        assertEquals("Couldn't sync · tap to retry", failed.text)
        assertEquals(SyncText.Action.RETRY, failed.action)
        // a later success replaces the failure
        assertEquals("Shared with family · synced just now", SyncText.status(SyncState(last = SyncResult.Done(0, 0, 0, 0, 0)), true, now, now).text)
        // a sync that is running wins over an old failure; without a family nothing else matters
        assertEquals("Syncing…", SyncText.status(SyncState(running = true, last = SyncResult.Failed(SyncError.Offline)), true, 0, now).text)
        assertEquals("Not shared yet", SyncText.status(SyncState(running = true), false, 0, now).text)
    }

    @Test fun joinedDateIsDeviceLocal() {
        val ms = 1_794_355_200_000L     // 2026-11-11T00:00:00Z
        assertEquals("2026-11-11", SyncText.isoDate(ms, ZoneId.of("UTC")))
        assertEquals("2026-11-10", SyncText.isoDate(ms, ZoneId.of("America/New_York")))
        assertEquals("2026-11-11", SyncText.isoDate(ms, ZoneId.of("Asia/Kolkata")))
    }
}
