package com.tally.book

import com.tally.book.ui.family.BackupText
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class BackupTextTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(utc).toInstant().toEpochMilli()

    @Test fun lastBackupLine() {
        val now = ms(2026, 11, 11, 18, 0)
        assertEquals("Never", BackupText.last(0, now, utc))
        assertEquals("today 9:05 am", BackupText.last(ms(2026, 11, 11, 9, 5), now, utc))
        assertEquals("yesterday 9:05 pm", BackupText.last(ms(2026, 11, 10, 21, 5), now, utc))
        assertEquals("08-Nov-2026 9:05 am", BackupText.last(ms(2026, 11, 8, 9, 5), now, utc))
    }

    @Test fun restoreQuestionSaysWhatIsReplacedTheDateTheLoansAndTheLimit() {
        val q = BackupText.restoreQuestion("2026-11-08", 3, phoneHasData = true)
        assertTrue(q.startsWith("Replace everything on this phone with this backup?\n\n"))
        assertTrue(q.contains("08-Nov-2026")); assertTrue(q.contains("3 entries"))
        assertTrue(q.contains("private entries")); assertTrue(q.contains("stay deleted"))
        assertTrue(BackupText.restoreQuestion("2026-11-08", 1, false).contains("1 entry."))
        assertFalse(BackupText.restoreQuestion("2026-11-08", null, false).contains("entry."))
    }
}
