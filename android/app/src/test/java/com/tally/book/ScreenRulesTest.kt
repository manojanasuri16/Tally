package com.tally.book

import com.tally.book.core.*
import com.tally.book.data.DueNotices
import com.tally.book.data.DueNotices.Kind
import com.tally.book.model.*
import com.tally.book.sync.PollPacer
import com.tally.book.sync.SyncResult
import com.tally.book.ui.family.SyncText
import com.tally.book.ui.loans.LoanFormRules
import com.tally.book.ui.loans.dueRowNote
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** The pure rules behind the loan form, the due rows, the reminder notification and the join-check pace. */
class ScreenRulesTest {
    @Before fun setUp() { Clock.fixed = LocalDateTime.of(2026, 11, 12, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    // ---- which fields the form shows ----

    @Test fun reducingOrFlatShowsWithAnyRateButNotForNoInterest() {
        assertTrue(LoanFormRules.hasInterest("bank", "month"))
        assertTrue(LoanFormRules.hasInterest("gold", "month"))
        assertTrue(LoanFormRules.hasInterest("hand", "year"))
        assertTrue(LoanFormRules.hasInterest("hand", "month"))
        assertFalse(LoanFormRules.hasInterest("hand", "none"))
    }

    @Test fun frequencyDecidesDueDayAndWeekday() {
        assertTrue(LoanFormRules.showFrequency("bank")); assertFalse(LoanFormRules.showFrequency("hand"))
        assertTrue(LoanFormRules.showDueDay("bank", "month"))
        for (f in listOf("fortnight", "week", "day")) assertFalse(f, LoanFormRules.showDueDay("bank", f))
        assertTrue(LoanFormRules.showWeekday("bank", "week")); assertTrue(LoanFormRules.showWeekday("bank", "fortnight"))
        assertFalse(LoanFormRules.showWeekday("bank", "day")); assertFalse(LoanFormRules.showWeekday("bank", "month"))
        assertFalse(LoanFormRules.showDueDay("gold", "month"))
    }

    @Test fun pledgeGramsAndRepayByForLoansAgainstSomething() {
        assertTrue(LoanFormRules.showPledge("gold")); assertFalse(LoanFormRules.showPledge("bank"))
        assertTrue(LoanFormRules.showGrams("gold", "gold")); assertFalse(LoanFormRules.showGrams("gold", "lic"))
        assertTrue(LoanFormRules.repayByInMain("gold", "gold")); assertFalse(LoanFormRules.repayByInMain("gold", "fd"))
        assertFalse(LoanFormRules.repayByInMain("hand", "gold"))
        assertTrue(LoanFormRules.repayByApplies("hand")); assertTrue(LoanFormRules.repayByApplies("gold")); assertFalse(LoanFormRules.repayByApplies("bank"))
    }

    @Test fun paidUpToIsOfferedOnlyForNewLoansWithSomethingDue() {
        assertTrue(LoanFormRules.showPaidUpTo(false, "", "2026-10-05"))
        assertFalse(LoanFormRules.showPaidUpTo(false, "", ""))
        // editing never invents it, and keeps showing one that exists
        assertFalse(LoanFormRules.showPaidUpTo(true, "", "2026-10-05"))
        assertTrue(LoanFormRules.showPaidUpTo(true, "2026-09-05", ""))
        assertTrue(LoanFormRules.showAmountHint("hand", true)); assertTrue(LoanFormRules.showAmountHint("gold", true))
        assertFalse(LoanFormRules.showAmountHint("bank", true)); assertFalse(LoanFormRules.showAmountHint("hand", false))
    }

    @Test fun tenureLabelFollowsHowOftenYouPay() {
        assertEquals("Number of months", LoanFormRules.tenureLabel("month"))
        assertEquals("Number of fortnights", LoanFormRules.tenureLabel("fortnight"))
        assertEquals("Number of weeks", LoanFormRules.tenureLabel("week"))
        assertEquals("Number of days", LoanFormRules.tenureLabel("day"))
    }

    @Test fun renewalStartsAYearOnForGoldAndTheSameStretchOtherwise() {
        val gold = obj("kind" to "gold", "startDate" to "2026-01-10", "repayBy" to "2026-11-10")
        assertEquals("2027-11-12", LoanFormRules.suggestRenewal(gold, "2026-11-12"))
        val hand = obj("kind" to "hand", "startDate" to "2026-01-01", "repayBy" to "2026-03-02")      // 60 days
        assertEquals("2026-04-30", LoanFormRules.suggestRenewal(hand, "2026-03-01"))
        hand["renewals"] = arr(obj("date" to "2026-03-01"))                                           // next time: from the last renewal
        assertEquals("2026-03-03", LoanFormRules.suggestRenewal(hand, "2026-03-02"))
        assertEquals("", LoanFormRules.suggestRenewal(obj("kind" to "hand", "startDate" to "2026-01-01"), "2026-03-01"))
        assertEquals("", LoanFormRules.suggestRenewal(gold, "nonsense"))
    }

    // ---- the due row's extra line ----

    @Test fun dueRowNoteSaysPendingAndSeveralPayments() {
        val weekly = obj("kind" to "bank", "frequency" to "week")
        assertEquals("4 payments", dueRowNote(weekly, 0.0, 4, "₹0"))
        assertEquals("4 payments · includes ₹1,000 pending", dueRowNote(weekly, 1000.0, 4, "₹1,000"))
        assertNull(dueRowNote(weekly, 0.0, 1, "₹0"))
        val monthly = obj("kind" to "bank", "frequency" to "month")
        assertNull(dueRowNote(monthly, 0.0, 2, "₹0"))
        assertEquals("includes ₹3,600 pending", dueRowNote(monthly, 3600.0, 2, "₹3,600"))
        val hand = obj("kind" to "hand")
        assertEquals("includes ₹3,600 pending", dueRowNote(hand, 3600.0, 3, "₹3,600"))
        assertNull(dueRowNote(hand, 0.0, 1, "₹0"))
    }

    // ---- the reminder notification ----

    private fun d(s: String) = LocalDate.parse(s)

    @Test fun reminderMessagePrefersOverdueThenToday() {
        val today = d("2026-11-12")
        assertEquals(Kind.OVERDUE, DueNotices.pick(listOf("2026-11-10", "2026-11-12"), emptyList(), today))
        assertEquals(Kind.DUE_TODAY, DueNotices.pick(listOf("2026-11-12", "2026-11-13"), emptyList(), today))
        assertEquals(Kind.EXPECTED, DueNotices.pick(emptyList(), listOf("2026-11-12"), today))
        assertEquals(Kind.EXPECTED, DueNotices.pick(emptyList(), listOf("2026-11-01"), today))
        assertEquals(Kind.DUE_TODAY, DueNotices.pick(listOf("2026-11-12"), listOf("2026-11-12"), today))
        assertNull(DueNotices.pick(listOf("2026-11-13"), listOf("2026-11-14"), today))
        assertNull(DueNotices.pick(emptyList(), emptyList(), today))
    }

    @Test fun reminderWordsNameNothing() {
        assertEquals("Payments are overdue", DueNotices.words(Kind.OVERDUE).first)
        assertEquals("A payment is due today", DueNotices.words(Kind.DUE_TODAY).first)
        assertEquals("Money is expected today", DueNotices.words(Kind.EXPECTED).first)
        for (k in Kind.values()) {
            val (title, text) = DueNotices.words(k)
            assertFalse((title + text).contains("₹")); assertFalse((title + text).any { it.isDigit() })
        }
    }

    @Test fun cacheKeepsOnlyDatesSplitByDirection() {
        val text = DueNotices.cacheOf(listOf(d("2026-11-10") to false, d("2026-11-10") to false, d("2026-11-12") to true))
        assertFalse(text.contains("amount")); assertFalse(text.contains("l1"))
        val (taken, given) = DueNotices.readCache(text)
        assertEquals(listOf("2026-11-10"), taken)
        assertEquals(listOf("2026-11-12"), given)
        assertEquals(emptyList<String>() to emptyList<String>(), DueNotices.readCache(null))
        assertEquals(emptyList<String>() to emptyList<String>(), DueNotices.readCache("not json"))
    }

    private fun book(vararg loans: Obj) { resetModelState(); DB = freshDb(); ensureBookShape(DB); DB.putAll(obj("loans" to arr(*loans))) }
    private fun bank(id: String, freq: String, start: String = "2026-08-05", tenure: Double = 24.0, closed: Boolean = false) = obj(
        "id" to id, "kind" to "bank", "name" to "City Bank", "principal" to 120000.0, "rate" to 12.0, "tenure" to tenure, "frequency" to freq,
        "startDate" to start, "dueDay" to 5.0, "closed" to closed)
    private fun dates(l: List<Pair<LocalDate, Boolean>>, given: Boolean) = l.filter { it.second == given }.map { it.first.toString() }

    @Test fun monthlyEmiGivesOverdueThenTheNext60Days() {
        book(bank("l1", "month"))
        val all = dueDatesAhead(Clock.today(), 60)
        // 5 Sep, 5 Oct, 5 Nov are overdue (collapsed to the oldest); 5 Dec, 5 Jan are ahead; 5 Jan is within 60 days of 12 Nov? no (maybe 5 Jan = day 54)
        assertEquals(listOf("2026-09-05", "2026-12-05", "2027-01-05"), dates(all, false))
        assertTrue(dates(all, true).isEmpty())
        assertEquals(Kind.OVERDUE, DueNotices.pick(dates(all, false), emptyList(), Clock.today()))
    }

    @Test fun weeklyGivesEveryWeekAndDailyIsCapped() {
        book(bank("l1", "week", start = "2026-11-02", tenure = 52.0))
        val w = dates(dueDatesAhead(Clock.today(), 60), false)
        assertEquals("2026-11-09", w.first())                              // overdue, collapsed
        assertTrue(w.contains("2026-11-16")); assertTrue(w.contains("2027-01-11")); assertFalse(w.contains("2027-01-18"))
        book(bank("l2", "day", start = "2026-01-01", tenure = 1000.0))
        val daily = dueDatesAhead(Clock.today(), 60)
        assertTrue(daily.size <= 62)
    }

    @Test fun interestOnlyYearlyAndRepayByAndGivenVsTaken() {
        book(
            obj("id" to "g1", "kind" to "gold", "name" to "x", "principal" to 50000.0, "rate" to 12.0, "rateUnit" to "year", "interestEvery" to "year",
                "startDate" to "2025-12-20", "closed" to false),
            obj("id" to "h1", "kind" to "hand", "name" to "y", "principal" to 20000.0, "rate" to 0.0, "startDate" to "2026-01-01",
                "repayBy" to "2026-12-01", "closed" to false),
            obj("id" to "h2", "kind" to "hand", "name" to "z", "principal" to 20000.0, "rate" to 0.0, "startDate" to "2026-01-01",
                "repayBy" to "2026-11-20", "direction" to "given", "closed" to false),
        )
        val all = dueDatesAhead(Clock.today(), 60)
        assertEquals(listOf("2026-12-01", "2026-12-20"), dates(all, false))     // repayBy, and the yearly interest 20 Dec
        assertEquals(listOf("2026-11-20"), dates(all, true))
    }

    @Test fun closedAndNoInterestLoansWithoutRepayByGiveNothing() {
        book(
            bank("l1", "month", closed = true),
            obj("id" to "h1", "kind" to "hand", "name" to "y", "principal" to 20000.0, "rate" to 0.0, "startDate" to "2026-01-01", "closed" to false),
            obj("id" to "h3", "kind" to "hand", "name" to "w", "principal" to 20000.0, "rate" to 0.0, "startDate" to "2026-01-01",
                "repayBy" to "2026-12-01", "closed" to true),
        )
        assertTrue(dueDatesAhead(Clock.today(), 60).isEmpty())
    }

    @Test fun datesBeyondTheWindowAreLeftOut() {
        book(obj("id" to "h1", "kind" to "hand", "name" to "y", "principal" to 20000.0, "rate" to 0.0, "startDate" to "2026-01-01",
            "repayBy" to "2027-03-01", "closed" to false))
        assertTrue(dueDatesAhead(Clock.today(), 60).isEmpty())
        assertEquals(listOf("2027-03-01"), dates(dueDatesAhead(Clock.today(), 200), false))
    }

    @Test fun reminderComesAtNineLocalTime() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
        assertEquals(3 * 3_600_000L, DueNotices.delayToNine(ms("2026-11-12T06:00:00"), zone))
        assertEquals(24 * 3_600_000L, DueNotices.delayToNine(ms("2026-11-12T09:00:00"), zone))
        assertEquals(23 * 3_600_000L, DueNotices.delayToNine(ms("2026-11-12T10:00:00"), zone))
    }

    // ---- join checks while the app is open ----

    @Test fun joinChecksStartFastSlowDownAfterTwoQuietMinutesAndSpeedUpAgain() {
        val p = PollPacer()
        p.reset(0)
        assertEquals(5_000L, p.intervalAt(0)); assertEquals(5_000L, p.intervalAt(119_999))
        assertEquals(30_000L, p.intervalAt(120_000)); assertEquals(30_000L, p.intervalAt(600_000))
        p.activity(600_000)                       // a touch or a new request
        assertEquals(5_000L, p.intervalAt(600_000)); assertEquals(5_000L, p.intervalAt(719_999))
        assertEquals(30_000L, p.intervalAt(720_000))
    }

    // ---- renaming the family ----

    @Test fun renameSentences() {
        assertEquals("The family is now called Home.", SyncText.renameMessage(SyncResult.Done(0, 0, 0, 0, 0), "Home"))
        assertEquals("Only a family admin can do that.", SyncText.renameMessage(SyncResult.Failed(com.tally.book.sync.SyncError.NotAdmin), "Home"))
        assertEquals("Please give the family a name.", SyncText.renameMessage(SyncResult.BadInvite, ""))
        assertEquals("No internet. Try again later.", SyncText.renameMessage(SyncResult.Failed(com.tally.book.sync.SyncError.Offline), "Home"))
    }
}
