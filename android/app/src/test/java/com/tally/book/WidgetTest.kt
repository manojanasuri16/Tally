package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import com.tally.book.ui.theme.ThemeMode
import com.tally.book.widget.NextPaymentData
import com.tally.book.widget.WidgetText
import com.tally.book.widget.WidgetText.Snap
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The "Next payment" widget's words, its snapshot rules, the next-payment lookup, and the Appearance setting. */
class WidgetTest {
    @Before fun setUp() {
        resetModelState()
        DB = freshDb(); ensureBookShape(DB)
        Clock.fixed = LocalDateTime.of(2026, 11, 12, 9, 0)
        DevicePrefs.clearForTests()
        setMyName("Alex")
    }
    @After fun tearDown() { Clock.fixed = null; DevicePrefs.clearForTests() }

    private val today = LocalDate.of(2026, 11, 12)
    private fun snap(date: String = "2026-12-05", overdue: Int = 0, late: Boolean = false) = Snap("City Bank", date, 21696.0, overdue, late)

    // ---- widget text: unlocked and amounts shown (the snapshot) ----

    @Test fun fullTextNamesTheLoanDateAndAmount() {
        val l = WidgetText.choose(snap(), listOf("2026-12-05"), today)
        assertEquals("Next payment · City Bank · 5 Dec · ₹21,696", l.single())
        assertNull(l.overdue)
    }

    @Test fun fullTextShowsOverdueOnASecondLine() {
        assertEquals("2 overdue", WidgetText.choose(snap(overdue = 2), null, today).overdue)
    }

    @Test fun fullTextSaysTodayAndTomorrow() {
        assertEquals("Next payment · City Bank · Today · ₹21,696", WidgetText.choose(snap("2026-11-12"), null, today).single())
        assertEquals("Next payment · City Bank · Tomorrow · ₹21,696", WidgetText.choose(snap("2026-11-13"), null, today).single())
    }

    @Test fun nothingDueInSixtyDays() {
        val l = WidgetText.choose(Snap("", "", 0.0, 0, false), null, today)
        assertEquals("Next payment · Nothing due soon", l.single())
        assertNull(l.overdue)
    }

    @Test fun aSnapshotFromAnEarlierDayCountsItsPassedDateAsOverdue() {
        // written yesterday for 11 Nov: today that payment is one more overdue, unless it was already counted
        assertEquals("1 overdue", WidgetText.choose(snap("2026-11-11"), null, today).overdue)
        assertEquals("3 overdue", WidgetText.choose(snap("2026-11-11", overdue = 2), null, today).overdue)
        assertEquals("2 overdue", WidgetText.choose(snap("2026-11-01", overdue = 2, late = true), null, today).overdue)
    }

    // ---- widget text: locked or amounts hidden (dates only: no snapshot) ----

    @Test fun lockedTextNamesNothingAndShowsNoAmount() {
        assertEquals("Tally · 1 payment due soon", WidgetText.choose(null, listOf("2026-11-20"), today).single())
        assertEquals("Tally · 3 payments due soon", WidgetText.choose(null, listOf("2026-11-12", "2026-11-20", "2026-11-26"), today).single())
        assertEquals("Tally · Payments are overdue", WidgetText.choose(null, listOf("2026-11-10", "2026-11-20"), today).single())
        assertEquals("Tally · Nothing due soon", WidgetText.choose(null, emptyList(), today).single())
        // a date beyond the "soon" window is not counted
        assertEquals("Tally · Nothing due soon", WidgetText.choose(null, listOf("2026-12-20"), today).single())
        assertNull(WidgetText.choose(null, listOf("2026-11-10"), today).overdue)
    }

    @Test fun lockedTextNeverHasADigitOrNameFromTheSnapshot() {
        val line = WidgetText.choose(null, listOf("2026-11-20"), today).single()
        assertFalse(line.contains("₹")); assertFalse(line.contains("City"))
    }

    @Test fun noCopyYetAsksToOpenTheApp() {
        assertEquals("Tally · Open Tally to see your payments", WidgetText.choose(null, null, today).single())
    }

    // ---- the snapshot: when it may exist ----

    private val next = NextPayment("City Bank", LocalDate.of(2026, 12, 5), 21696.0, 2, false)

    @Test fun snapshotOnlyWhileAWidgetIsPlacedNoLockAndAmountsShown() {
        assertNotNull(NextPaymentData.snapshotText(placed = true, lockOn = false, hidden = false, next = next))
        assertNull("a lock is set", NextPaymentData.snapshotText(true, true, false, next))
        assertNull("amounts hidden", NextPaymentData.snapshotText(true, false, true, next))
        assertNull("no widget", NextPaymentData.snapshotText(false, false, false, next))
        assertNull(NextPaymentData.snapshotText(true, true, true, next))
    }

    @Test fun snapshotRoundTrips() {
        val back = NextPaymentData.readSnap(NextPaymentData.snapshotText(true, false, false, next))!!
        assertEquals(Snap("City Bank", "2026-12-05", 21696.0, 2, false), back)
        assertNull(NextPaymentData.readSnap(null)); assertNull(NextPaymentData.readSnap("not json"))
    }

    @Test fun delayToNextDayIsJustAfterMidnight() {
        val zone = ZoneId.of("Asia/Kolkata")
        val at = LocalDateTime.of(2026, 11, 12, 23, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(65 * 60_000L, NextPaymentData.delayToNextDay(at, zone))
    }

    // ---- the next payment from the book ----

    private fun bank(name: String, start: String, dueDay: Int, emi: Double = 8885.0) = saveLoan(obj(
        "kind" to "bank", "name" to name, "principal" to 100000, "rate" to 12, "tenure" to 12, "emi" to emi, "startDate" to start, "dueDay" to dueDay))

    @Test fun nextPaymentIsTheSoonestOnMoneyITook() {
        bank("City Bank", "2026-11-01", 5)         // paid nothing: Nov 5 is overdue, then Dec 5
        saveLoan(obj("kind" to "hand", "name" to "Mr Smith", "principal" to 5000, "rate" to 0, "startDate" to "2026-10-01", "repayBy" to "2026-11-20"))
        val n = nextPaymentFor(today)
        assertEquals("Mr Smith", n.name); assertEquals(LocalDate.of(2026, 11, 20), n.date); assertEquals(5000.0, n.amount, 0.001)
        assertEquals(1, n.overdue); assertFalse(n.late)
    }

    @Test fun moneyIGaveIsNeverInTheWidget() {
        saveLoan(obj("kind" to "hand", "direction" to "given", "name" to "Alex Jr", "principal" to 5000, "rate" to 0, "startDate" to "2026-10-01", "repayBy" to "2026-11-20"))
        val n = nextPaymentFor(today)
        assertNull(n.date); assertEquals(0, n.overdue)
    }

    @Test fun onlyOverdueShowsTheOldestOverdueAsLate() {
        saveLoan(obj("kind" to "hand", "name" to "Mr Smith", "principal" to 5000, "rate" to 0, "startDate" to "2026-09-01", "repayBy" to "2026-10-20"))
        val n = nextPaymentFor(today)
        assertEquals("Mr Smith", n.name); assertEquals(LocalDate.of(2026, 10, 20), n.date); assertTrue(n.late); assertEquals(1, n.overdue)
    }

    @Test fun nothingWhenThereAreNoLoans() {
        val n = nextPaymentFor(today)
        assertNull(n.date); assertEquals(0, n.overdue)
    }

    // ---- Appearance ----

    @Test fun appearanceModeResolution() {
        assertEquals("system", ThemeMode.normalize(null)); assertEquals("system", ThemeMode.normalize("blue"))
        assertEquals("light", ThemeMode.normalize("normal")); assertEquals("dark", ThemeMode.normalize("dark"))
        assertFalse(ThemeMode.isDark("light", systemDark = true)); assertTrue(ThemeMode.isDark("dark", systemDark = false))
        assertTrue(ThemeMode.isDark("system", true)); assertFalse(ThemeMode.isDark(null, false))
        assertTrue(ThemeMode.isDark("black", false))
        assertEquals("dark", ThemeMode.choiceOf("black")); assertEquals("system", ThemeMode.choiceOf("whatever"))
    }

    @Test fun windowColourMatchesThePageBackground() {
        assertEquals(0xFF111113.toInt(), ThemeMode.windowColor("dark", false))
        assertEquals(0xFFF2F2F7.toInt(), ThemeMode.windowColor("light", true))
        assertEquals(0xFF111113.toInt(), ThemeMode.windowColor("system", true))
        assertEquals(0xFFF2F2F7.toInt(), ThemeMode.windowColor("system", false))
        assertEquals(0xFF000000.toInt(), ThemeMode.windowColor("black", false))
    }

    @Test fun appearanceIsADeviceSetting() {
        assertEquals("system", ThemeMode.stored())
        ThemeMode.set("dark")
        assertEquals("dark", DevicePrefs.get("tally-theme-mode")); assertEquals("dark", ThemeMode.stored())
        assertNull("not in the book", DB.o("themeMode"))
    }
}
