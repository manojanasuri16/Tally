package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** Whose entry it is (owner / by / me) and Recently deleted (trash, restore, the 30 days, deleting for good). */
class OwnerAndTrashTest {
    private val start = LocalDateTime.of(2026, 11, 11, 9, 0)

    @Before fun setUp() { DB = freshDb(); ensureBookShape(DB); Clock.fixed = start }
    @After fun tearDown() { Clock.fixed = null }

    /** A hand loan with monthly interest taken in October, so this month's interest is due. [owner] null means no owner field. */
    private fun interestLoan(name: String, principal: Double, owner: String? = null): Obj {
        val d = obj("kind" to "hand", "name" to name, "principal" to principal, "rate" to 2.0, "rateUnit" to "month",
            "startDate" to "2026-10-03")
        if (owner != null) d["owner"] = owner
        return saveLoan(d)
    }

    // ---- whose entry ----

    @Test fun ownerFallsBackToByThenToMe() {
        val me = myId()
        assertEquals(me, ownerOf(obj()))                                        // no owner, no by: me
        assertEquals("m_sam", ownerOf(obj("by" to "m_sam")))                    // no owner: whoever added it
        assertEquals("m_mom", ownerOf(obj("by" to "m_sam", "owner" to "m_mom")))  // owner wins over by
        assertEquals("m_mom", ownerOf(obj("owner" to "m_mom")))
    }

    @Test fun isMineFollowsTheOwner() {
        assertTrue(isMine(obj("owner" to myId())))
        assertTrue(isMine(obj("by" to myId())))
        assertTrue(isMine(obj("by" to "m_sam", "owner" to myId())))             // added by Sam on my behalf, still mine
        assertFalse(isMine(obj("owner" to "m_mom")))
        assertFalse(isMine(obj("by" to "m_sam")))
        assertFalse(isMine(obj("by" to myId(), "owner" to "m_mom")))            // I added it for Mom
    }

    @Test fun hasOthersEntriesOnlyOnceSomeoneElseOwnsOne() {
        assertFalse(hasOthersEntries())                                         // empty book
        interestLoan("Mine", 100000.0)
        assertFalse(hasOthersEntries())                                         // everything is mine
        interestLoan("Moms", 50000.0, owner = "m_mom")
        assertTrue(hasOthersEntries())
    }

    @Test fun hasOthersEntriesIgnoresTrashedOnes() {
        interestLoan("Mine", 100000.0)
        val mom = interestLoan("Moms", 50000.0, owner = "m_mom")
        trashLoan(mom.s("id"))
        assertFalse(hasOthersEntries())                                         // loans() hides the trashed one
    }

    // ---- the Mine filter on the due list and totals ----

    @Test fun totalsAndDueListCanBeLimitedToMine() {
        val mine = interestLoan("Mine", 100000.0)
        val mom = interestLoan("Moms", 50000.0, owner = "m_mom")
        val all = totals(); val onlyMine = totals(only = ::isMine)

        val mineDue = onlyMine.num("dueThisMonth")
        assertTrue(mineDue > 0.0)
        val momDue = totals(only = { it.s("id") == mom.s("id") }).num("dueThisMonth")
        assertTrue(momDue > 0.0)
        assertEquals(mineDue + momDue, all.num("dueThisMonth"), 0.001)          // unfiltered is the sum of both
        assertEquals(2.0, all.num("count"), 0.0); assertEquals(1.0, onlyMine.num("count"), 0.0)

        val owedMine = onlyMine.num("owed")
        val owedMom = totals(only = { !isMine(it) }).num("owed")
        assertTrue(owedMine > 0.0 && owedMom > 0.0)
        assertEquals(owedMine + owedMom, all.num("owed"), 0.001)

        val rows = dueList(only = ::isMine)
        assertEquals(1, rows.size)
        assertEquals(mine.s("id"), rows[0].o("loan").s("id"))
        assertEquals(2, dueList().size)
    }

    @Test fun mineFilterAlsoLimitsPaidThisMonth() {
        val mine = interestLoan("Mine", 100000.0)
        val mom = interestLoan("Moms", 50000.0, owner = "m_mom")
        savePayment(obj("loanId" to mine.s("id"), "date" to "2026-11-05", "amount" to 700.0))
        savePayment(obj("loanId" to mom.s("id"), "date" to "2026-11-06", "amount" to 300.0))
        assertEquals(1000.0, totals().num("paidThisMonth"), 0.001)
        assertEquals(700.0, totals(only = ::isMine).num("paidThisMonth"), 0.001)
    }

    // ---- recently deleted ----

    @Test fun trashedLoanDisappearsFromEveryRead() {
        val a = interestLoan("Mine", 100000.0)
        val b = interestLoan("Second", 50000.0)
        savePayment(obj("loanId" to a.s("id"), "date" to "2026-11-05", "amount" to 700.0))
        val before = totals()
        assertEquals(2, loans().size); assertEquals(2, dueList().size)

        trashLoan(a.s("id"))
        assertEquals(listOf(b.s("id")), loans().map { it.s("id") })
        assertNull(loan(a.s("id")))
        assertEquals(listOf(b.s("id")), dueList().map { it.o("loan").s("id") })
        val after = totals()
        assertEquals(1.0, after.num("count"), 0.0)
        assertTrue(after.num("dueThisMonth") < before.num("dueThisMonth"))
        assertEquals(0.0, after.num("paidThisMonth"), 0.0)                      // the payment logged on it no longer counts

        val trashed = trashedLoans()
        assertEquals(listOf(a.s("id")), trashed.map { it.s("id") })
        assertEquals(myId(), trashed[0].s("trashedBy"))
        assertTrue(isTrashed(trashed[0]))
        assertTrue(trashed[0].num("trashed") > 0.0)
    }

    @Test fun trashedListIsNewestFirst() {
        val a = interestLoan("First", 1000.0); val b = interestLoan("Second", 1000.0)
        trashLoan(a.s("id"))
        Clock.fixed = start.plusHours(1)
        trashLoan(b.s("id"))
        assertEquals(listOf(b.s("id"), a.s("id")), trashedLoans().map { it.s("id") })
    }

    @Test fun restoreBringsTheLoanBackWithItsPayments() {
        val a = interestLoan("Mine", 100000.0)
        savePayment(obj("loanId" to a.s("id"), "date" to "2026-11-05", "amount" to 700.0))
        val before = totals()

        trashLoan(a.s("id"))
        restoreLoan(a.s("id"))

        assertEquals(1, loans().size)
        assertTrue(trashedLoans().isEmpty())
        assertFalse(isTrashed(loan(a.s("id"))!!))
        assertEquals("", loan(a.s("id"))!!.s("trashedBy"))
        assertEquals(1, paymentsOf(a.s("id")).size)
        assertEquals(700.0, totals().num("paidThisMonth"), 0.001)
        assertEquals(before.num("dueThisMonth"), totals().num("dueThisMonth"), 0.001)
    }

    @Test fun trashingTwiceOrRestoringALiveLoanIsHarmless() {
        val a = interestLoan("Mine", 100000.0)
        restoreLoan(a.s("id"))                                                  // not trashed: nothing happens
        assertEquals(1, loans().size); assertTrue(trashedLoans().isEmpty())

        trashLoan(a.s("id"))
        val stamp = trashedLoans()[0].num("trashed")
        Clock.fixed = start.plusDays(3)
        trashLoan(a.s("id"))                                                    // second time keeps the first stamp
        assertEquals(1, trashedLoans().size)
        assertEquals(stamp, trashedLoans()[0].num("trashed"), 0.0)

        trashLoan("no_such_id"); restoreLoan("no_such_id")                      // unknown ids are ignored
        assertEquals(1, trashedLoans().size)
    }

    @Test fun trashingMarksTheLoanForSync() {
        val a = interestLoan("Mine", 100000.0)
        DB.oo("sync").oo("dirty").clear()
        trashLoan(a.s("id"))
        assertTrue(DB.oo("sync").oo("dirty").b(a.s("id")))
    }

    // ---- the 30 days ----

    @Test fun daysLeftCountsDownFromThirty() {
        val a = interestLoan("Mine", 100000.0)
        trashLoan(a.s("id"))
        assertEquals(30, TRASH_DAYS)
        assertEquals(30, trashDaysLeft(trashedLoans()[0]))
        Clock.fixed = start.plusDays(10)
        assertEquals(20, trashDaysLeft(trashedLoans()[0]))
        Clock.fixed = start.plusDays(31)
        assertEquals(0, trashDaysLeft(trashedLoans()[0]))                       // never negative
    }

    @Test fun expiredTrashWaitsThirtyDaysAndAsksMay() {
        val a = interestLoan("Mine", 100000.0)
        trashLoan(a.s("id"))
        assertTrue(expiredTrash { true }.isEmpty())                             // just trashed
        Clock.fixed = start.plusDays(29)
        assertTrue(expiredTrash { true }.isEmpty())
        Clock.fixed = start.plusDays(31)
        assertEquals(listOf(a.s("id")), expiredTrash { true }.map { it.s("id") })
        assertTrue(expiredTrash { false }.isEmpty())                            // this phone may not delete it
    }

    @Test fun expiredTrashPassesEachLoanToMay() {
        val a = interestLoan("Mine", 100000.0)
        val b = interestLoan("Moms", 50000.0, owner = "m_mom")
        trashLoan(a.s("id")); trashLoan(b.s("id"))
        Clock.fixed = start.plusDays(31)
        assertEquals(listOf(a.s("id")), expiredTrash(may = ::isMine).map { it.s("id") })
    }

    @Test fun deletingAnExpiredLoanForGoodTombstonesIt() {
        val a = interestLoan("Mine", 100000.0)
        val p = savePayment(obj("loanId" to a.s("id"), "date" to "2026-11-05", "amount" to 700.0))
        trashLoan(a.s("id"))
        Clock.fixed = start.plusDays(31)

        expiredTrash { true }.forEach { deleteLoan(it.s("id")) }

        assertTrue(trashedLoans().isEmpty())                                    // a tombstone is not in Recently deleted
        assertTrue(expiredTrash { true }.isEmpty())
        assertTrue(loans().isEmpty())
        assertTrue(paymentsOf(a.s("id")).isEmpty())
        val tomb = DB.objs("loans").first { it.s("id") == a.s("id") }
        assertTrue(tomb.b("deleted"))
        assertTrue(DB.objs("payments").first { it.s("id") == p.s("id") }.b("deleted"))
    }

    @Test fun filesOfLoanAllCoversLoanAndPaymentFilesButNotOthers() {
        val a = interestLoan("Mine", 100000.0)
        val b = interestLoan("Second", 50000.0)
        val p = savePayment(obj("loanId" to a.s("id"), "date" to "2026-11-05", "amount" to 700.0))
        val files = DB.aa("files")
        files.add(obj("id" to "f_loan", "ownerType" to "loan", "ownerId" to a.s("id")))
        files.add(obj("id" to "f_pay", "ownerType" to "payment", "ownerId" to p.s("id")))
        files.add(obj("id" to "f_other", "ownerType" to "loan", "ownerId" to b.s("id")))
        files.add(obj("id" to "f_gone", "ownerType" to "loan", "ownerId" to a.s("id"), "deleted" to true))

        assertEquals(setOf("f_loan", "f_pay"), filesOfLoanAll(a.s("id")).toSet())
        trashLoan(a.s("id"))                                                    // trashing leaves files untouched
        assertEquals(setOf("f_loan", "f_pay"), filesOfLoanAll(a.s("id")).toSet())
        assertEquals(listOf("f_other"), filesOfLoanAll(b.s("id")))
    }
}
