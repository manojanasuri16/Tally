package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/** Shared credit limits: a card's own limit, cards on one limit, chains, loops, choices and the limit check. */
class CardLimitTest {
    @Before fun setUp() { DB = freshDb(); ensureBookShape(DB); Clock.fixed = LocalDateTime.of(2026, 11, 11, 9, 0) }
    @After fun tearDown() { Clock.fixed = null }

    private fun card(name: String, outstanding: Double, limit: Double = 0.0, with: String = "", lender: String = ""): Obj {
        val d = obj("kind" to "card", "name" to name, "lender" to lender, "principal" to outstanding, "minDue" to 0.0,
            "startDate" to "2026-11-05", "dueDate" to "2026-11-25")
        if (limit > 0) d["limit"] = limit
        if (with.isNotEmpty()) d["limitWith"] = with
        return saveLoan(d)
    }
    private fun id(l: Obj) = l.s("id")
    private fun view(l: Obj) = cardLimitView(loan(id(l))!!)
    private fun ids(l: List<Obj>) = l.map { it.s("id") }.toSet()

    private fun hdfc(): Triple<Obj, Obj, Obj> {
        val fed = card("Federal card", 20000.0, 100000.0)
        val mil = card("HDFC Millennia", 30000.0, 200000.0)
        val rup = card("HDFC RuPay", 10000.0, with = id(mil))
        return Triple(fed, mil, rup)
    }

    @Test fun sharedAndOwnLimits() {
        val (fed, mil, rup) = hdfc()
        val f = view(fed)
        assertEquals(100000.0, f.num("limit"), 0.0); assertEquals(20000.0, f.num("used"), 0.0)
        assertEquals(80000.0, f.num("available"), 0.0); assertFalse(f.b("shared"))
        for (c in listOf(mil, rup)) {
            val w = view(c)
            assertEquals(200000.0, w.num("limit"), 0.0); assertEquals(40000.0, w.num("used"), 0.0)
            assertEquals(160000.0, w.num("available"), 0.0); assertTrue(w.b("shared"))
            assertEquals(setOf(id(mil), id(rup)), ids(limitGroup(loan(id(c))!!)))
        }
        assertEquals(id(mil), id(limitRoot(loan(id(rup))!!)))
        assertEquals(id(mil), id(limitRoot(loan(id(mil))!!)))
    }

    @Test fun sameBankIsNotAssumedToShare() {
        val a = card("HDFC Millennia", 10000.0, 100000.0, lender = "HDFC Bank")
        val b = card("HDFC RuPay", 5000.0, 50000.0, lender = "HDFC Bank")
        val va = view(a); val vb = view(b)
        assertEquals(100000.0, va.num("limit"), 0.0); assertEquals(10000.0, va.num("used"), 0.0); assertFalse(va.b("shared"))
        assertEquals(50000.0, vb.num("limit"), 0.0); assertEquals(5000.0, vb.num("used"), 0.0); assertFalse(vb.b("shared"))
    }

    @Test fun paymentsLowerUsed() {
        val (_, mil, rup) = hdfc()
        savePayment(obj("loanId" to id(rup), "kind" to "principal", "amount" to 5000.0, "date" to "2026-11-08"))
        val w = view(mil)
        assertEquals(35000.0, w.num("used"), 0.0); assertEquals(165000.0, w.num("available"), 0.0)
    }

    @Test fun chainUsesTheLastCardsLimit() {
        val c = card("Card C", 1000.0, 300000.0)
        val b = card("Card B", 2000.0, with = id(c))
        val a = card("Card A", 3000.0, with = id(b))
        assertEquals(id(c), id(limitRoot(loan(id(a))!!)))
        val w = view(a)
        assertEquals(300000.0, w.num("limit"), 0.0); assertEquals(6000.0, w.num("used"), 0.0); assertTrue(w.b("shared"))
        assertEquals(3, limitGroup(loan(id(a))!!).size)
    }

    @Test fun aLoopEndsInsteadOfHanging() {
        val a = card("Card A", 1000.0, 100000.0)
        val b = card("Card B", 2000.0, with = id(a))
        loan(id(a))!!["limitWith"] = id(b)
        val ra = limitRoot(loan(id(a))!!); val rb = limitRoot(loan(id(b))!!)
        assertTrue(id(ra) in setOf(id(a), id(b))); assertTrue(id(rb) in setOf(id(a), id(b)))
        assertNotNull(view(a)); assertNotNull(view(b))
        assertTrue(limitGroup(loan(id(a))!!).isNotEmpty())
    }

    @Test fun choicesLeaveOutSelfDependentsAndClosedCards() {
        val (fed, mil, rup) = hdfc()
        val closed = card("Old card", 0.0, 50000.0)
        saveLoan(loan(id(closed))!!.copyObj().also { it["closed"] = true })
        val forMil = ids(limitChoices(id(mil)))
        assertFalse(id(mil) in forMil); assertFalse(id(rup) in forMil); assertTrue(id(fed) in forMil); assertFalse(id(closed) in forMil)
        assertEquals(setOf(id(fed), id(mil), id(rup)), ids(limitChoices("")))
    }

    @Test fun trashingTheRootLeavesTheOtherOnItsOwn() {
        val (_, mil, rup) = hdfc()
        trashLoan(id(mil))
        assertNull(loan(id(mil)))
        assertEquals(id(rup), id(limitRoot(loan(id(rup))!!)))
        val w = view(rup)
        assertEquals(0.0, w.num("limit"), 0.0); assertEquals(10000.0, w.num("used"), 0.0)
        assertEquals(0.0, w.num("available"), 0.0); assertFalse(w.b("shared"))
    }

    @Test fun availableNeverGoesBelowZero() {
        val c = card("Federal card", 150000.0, 100000.0)
        val w = view(c)
        assertEquals(150000.0, w.num("used"), 0.0); assertEquals(0.0, w.num("available"), 0.0)
    }

    @Test fun aNegativeLimitIsRejected() {
        val d = obj("kind" to "card", "name" to "Federal card", "principal" to 1000.0, "minDue" to 0.0,
            "startDate" to "2026-11-05", "dueDate" to "2026-11-25", "limit" to -1.0)
        assertEquals("The credit limit cannot be below zero.", validateCard(d))
        d["limit"] = 0.0
        assertNull(validateCard(d))
    }
}
