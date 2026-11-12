package com.tally.book

import androidx.compose.ui.graphics.Color
import com.tally.book.core.*
import com.tally.book.ui.family.SyncText
import com.tally.book.ui.loans.EasyForm
import com.tally.book.ui.loans.LoanFormRules
import com.tally.book.ui.theme.EasyRules
import com.tally.book.ui.theme.appleColors
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/** Easy mode's pure rules: the combined font scale and its cap, the contrast colours, and which loan-form fields show. */
class EasyModeTest {
    // ---- the combined scale ----

    @Test fun easyModeAddsThirtyPercentToThePhonesOwnFontSize() {
        assertEquals(1.3f, EasyRules.scale(1.0f, EasyRules.EXTRA_SCALE), 1e-4f)
        assertEquals(1.495f, EasyRules.scale(1.15f, EasyRules.EXTRA_SCALE), 1e-3f)
        assertEquals(1.69f, EasyRules.scale(1.3f, EasyRules.EXTRA_SCALE), 1e-3f)
    }

    @Test fun theTotalNeverGoesPastTwo() {
        assertEquals(1.95f, EasyRules.scale(1.5f, EasyRules.EXTRA_SCALE), 1e-3f)
        for (system in listOf(1.54f, 1.6f, 1.8f, 2.0f)) assertEquals("system $system", 2.0f, EasyRules.scale(system, EasyRules.EXTRA_SCALE), 1e-4f)
        for (system in 50..200 step 5) assertTrue(EasyRules.scale(system / 100f, EasyRules.EXTRA_SCALE) <= 2.0f)
    }

    @Test fun aPhoneAlreadyAboveTheCapIsLeftAsItIsAndNeverShrunk() {
        assertEquals(2.4f, EasyRules.scale(2.4f, EasyRules.EXTRA_SCALE), 1e-4f)
        for (system in listOf(0.85f, 1.0f, 1.3f, 2.0f, 2.4f)) assertTrue(EasyRules.scale(system, EasyRules.EXTRA_SCALE) >= system)
    }

    @Test fun withEasyModeOffTheScaleIsExactlyThePhones() {
        for (system in listOf(0.85f, 1.0f, 1.15f, 1.5f, 2.0f)) assertEquals(system, EasyRules.scale(system, 1f), 0f)
        assertEquals(1.0f, EasyRules.scale(0f, 1f), 0f)   // a missing scale reads as normal
    }

    @Test fun pairsStackFromOnePointThree() {
        assertFalse(EasyRules.stacks(1.0f)); assertFalse(EasyRules.stacks(1.15f))
        assertTrue(EasyRules.stacks(1.3f)); assertTrue(EasyRules.stacks(2.0f))
        assertTrue(EasyRules.stacks(EasyRules.scale(1.0f, EasyRules.EXTRA_SCALE)))   // Easy mode alone always stacks
    }

    @Test fun capFactorHoldsATitleToItsCap() {
        assertEquals(1f, EasyRules.capFactor(1.3f, 1.5f), 0f)
        assertEquals(0.75f, EasyRules.capFactor(2.0f, 1.5f), 1e-4f)
        assertEquals(1.5f, 2.0f * EasyRules.capFactor(2.0f, 1.5f), 1e-4f)
    }

    @Test fun theSwitchIsStoredAsOne() {
        assertTrue(EasyRules.parse("1")); assertFalse(EasyRules.parse("0")); assertFalse(EasyRules.parse(null)); assertFalse(EasyRules.parse("true"))
    }

    // ---- contrast ----

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = v.toDouble().let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: Color, b: Color): Double = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)

    private val accent = Color(0xFF0071E3)

    @Test fun lightModeGetsDarkerLabelsAndStrongerLines() {
        val base = appleColors(false, false, accent)
        val easy = EasyRules.contrast(base)
        assertEquals(Color.Black, easy.label)
        assertTrue(ratio(easy.secondaryLabel, easy.cell) > ratio(base.secondaryLabel, base.cell))
        assertTrue("secondary text stays readable (7:1)", ratio(easy.secondaryLabel, easy.cell) >= 7.0)
        assertTrue(ratio(easy.separator, easy.cell) > ratio(base.separator, base.cell))
        assertTrue(ratio(easy.cardBorder, easy.cell) > ratio(base.cardBorder, base.cell))
        assertTrue(easy.highlight.alpha > base.highlight.alpha)
        assertTrue(easy.fill.alpha > base.fill.alpha)
        assertFalse(easy.dark)
    }

    @Test fun darkModeGetsLighterLabelsAndStrongerLines() {
        val base = appleColors(true, false, accent)
        val easy = EasyRules.contrast(base)
        assertEquals(Color.White, easy.label)
        assertTrue(ratio(easy.secondaryLabel, easy.cell) > ratio(base.secondaryLabel, base.cell))
        assertTrue(ratio(easy.secondaryLabel, easy.cell) >= 7.0)
        assertTrue(ratio(easy.separator, easy.cell) > ratio(base.separator, base.cell))
        assertTrue(ratio(easy.cardBorder, easy.cell) > ratio(base.cardBorder, base.cell))
        assertTrue(easy.highlight.alpha > base.highlight.alpha)
        assertTrue(easy.dark)
    }

    @Test fun contrastKeepsSurfacesAndAccent() {
        for (dark in listOf(false, true)) {
            val base = appleColors(dark, false, accent)
            val easy = EasyRules.contrast(base)
            assertEquals(base.cell, easy.cell); assertEquals(base.groupedBackground, easy.groupedBackground); assertEquals(accent, easy.accent)
        }
    }

    // ---- the status line ----

    @Test fun easyModeShowsTheSyncLineOnlyWhenSomethingNeedsAttention() {
        assertFalse(SyncText.needsAttention(SyncText.Line("Shared with family", SyncText.Action.NONE), false))
        assertFalse(SyncText.needsAttention(SyncText.Line("Syncing…", SyncText.Action.NONE), false))
        assertFalse(SyncText.needsAttention(SyncText.Line("Not shared yet", SyncText.Action.SET_UP), false))
        assertTrue(SyncText.needsAttention(SyncText.Line("Couldn't sync", SyncText.Action.RETRY), false))
        assertTrue(SyncText.needsAttention(SyncText.Line("Waiting", SyncText.Action.RETRY), false))
        assertTrue(SyncText.needsAttention(SyncText.Line("Set up again", SyncText.Action.SET_UP), true))
    }

    // ---- which loan-form fields show ----

    private fun loan(vararg p: Pair<String, Any?>) = obj(*p)
    private val none = EasyForm.NONE

    @Test fun standardModeShowsEverythingAsBefore() {
        assertTrue(EasyForm.showMethod(false, true, none)); assertFalse(EasyForm.showMethod(false, false, none))
        assertTrue(EasyForm.showFrequency(false, "bank", none)); assertFalse(EasyForm.showFrequency(false, "gold", none))
        assertTrue(EasyForm.showDueDay(false, "bank", "month", none)); assertFalse(EasyForm.showDueDay(false, "bank", "week", none))
        assertTrue(EasyForm.showEvery(false, "hand", true, none)); assertFalse(EasyForm.showEvery(false, "bank", true, none))
        assertTrue(EasyForm.showPaidUpToField(false, true, none)); assertFalse(EasyForm.showPaidUpToField(false, false, none))
        assertFalse(EasyForm.paidUpToNote(false, false, "2026-01-05"))
    }

    @Test fun easyModeHidesTheAdvancedFieldsOfANewLoanOfEveryKind() {
        for (kind in listOf("bank", "hand", "gold")) {
            assertFalse(kind, EasyForm.showMethod(true, true, none))
            assertFalse(kind, EasyForm.showFrequency(true, kind, none))
            assertFalse(kind, EasyForm.showDueDay(true, kind, "month", none))
            assertFalse(kind, EasyForm.showEvery(true, kind, true, none))
            assertFalse(kind, EasyForm.showPaidUpToField(true, true, none))
        }
    }

    @Test fun easyModeKeepsTheEssentialsOfEachKind() {
        // these rules are not Easy mode's to hide: the rate, EMI/months, grams and the repay-or-renew date stay
        assertTrue(LoanFormRules.hasInterest("bank", "month")); assertFalse(LoanFormRules.hasInterest("hand", "none"))
        assertTrue(LoanFormRules.showGrams("gold", "gold")); assertFalse(LoanFormRules.showGrams("gold", "land"))
        assertTrue(LoanFormRules.repayByInMain("gold", "gold")); assertFalse(LoanFormRules.repayByInMain("hand", "gold"))
        assertFalse(EasyForm.showMethod(true, false, none))   // no interest: no method either way
    }

    @Test fun anOldStartDateStillGetsItsSuggestionWithOneQuietLine() {
        assertTrue(EasyForm.paidUpToNote(true, false, "2026-01-05"))
        assertFalse("nothing is due yet, nothing to say", EasyForm.paidUpToNote(true, false, ""))
        assertFalse("an edit never invents Paid up to", EasyForm.paidUpToNote(true, true, "2026-01-05"))
        assertFalse("the field itself is not drawn", EasyForm.showPaidUpToField(true, true, none))
    }

    @Test fun aNewLoanUsesNothingAdvanced() {
        assertEquals(none, EasyForm.extrasOf(null))
        assertFalse(EasyForm.extrasOf(null).any)
    }

    @Test fun aPlainExistingLoanUsesNothingAdvanced() {
        assertFalse(EasyForm.extrasOf(loan("kind" to "bank", "startDate" to "2026-03-15", "dueDay" to 15.0, "frequency" to "month", "interestMethod" to "reducing")).any)
        assertFalse(EasyForm.extrasOf(loan("kind" to "hand", "startDate" to "2026-03-15", "interestEvery" to "month")).any)
        assertFalse(EasyForm.extrasOf(loan("kind" to "gold", "startDate" to "2026-03-15", "interestEvery" to "year")).any)
    }

    @Test fun aFlatLoanKeepsItsMethodVisible() {
        val x = EasyForm.extrasOf(loan("kind" to "bank", "interestMethod" to "flat", "startDate" to "2026-03-15", "dueDay" to 15.0))
        assertTrue(x.method)
        assertTrue(EasyForm.showMethod(true, true, x))
        assertFalse(EasyForm.showFrequency(true, "bank", x))
    }

    @Test fun aWeeklyLoanKeepsItsFrequencyVisible() {
        val x = EasyForm.extrasOf(loan("kind" to "bank", "frequency" to "week", "startDate" to "2026-03-15"))
        assertTrue(x.frequency)
        assertTrue(EasyForm.showFrequency(true, "bank", x))
        assertFalse("a weekly loan has no pay-on day", EasyForm.showDueDay(true, "bank", "week", x))
    }

    @Test fun aPayOnDayDifferentFromTheStartDayStaysVisible() {
        val x = EasyForm.extrasOf(loan("kind" to "bank", "frequency" to "month", "startDate" to "2026-03-15", "dueDay" to 5.0))
        assertTrue(x.dueDay)
        assertTrue(EasyForm.showDueDay(true, "bank", "month", x))
        assertFalse(EasyForm.extrasOf(loan("kind" to "bank", "frequency" to "month", "startDate" to "2026-03-15", "dueDay" to 15.0)).dueDay)
    }

    @Test fun anInterestPaidEveryThatIsNotTheDefaultStaysVisible() {
        assertTrue(EasyForm.extrasOf(loan("kind" to "hand", "interestEvery" to "year")).every)
        assertTrue(EasyForm.extrasOf(loan("kind" to "gold", "interestEvery" to "month")).every)
        assertFalse(EasyForm.extrasOf(loan("kind" to "gold", "interestEvery" to "year")).every)
        assertFalse("a bank loan has no such choice", EasyForm.extrasOf(loan("kind" to "bank", "interestEvery" to "year")).every)
        assertTrue(EasyForm.showEvery(true, "hand", true, EasyForm.Extras(every = true)))
    }

    @Test fun aLoanWithPaidUpToShowsItInEasyMode() {
        val x = EasyForm.extrasOf(loan("kind" to "hand", "paidUpTo" to "2026-02-01"))
        assertTrue(x.paidUpTo)
        assertTrue(EasyForm.showPaidUpToField(true, true, x))
        assertFalse("and then no quiet line", EasyForm.paidUpToNote(true, true, ""))
    }
}
