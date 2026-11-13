package com.tally.book

import com.tally.book.core.*
import com.tally.book.model.*
import org.junit.Assert.*
import org.junit.Test

/** A person's entry is known by the person; bank and pledged entries by their own name. */
class LoanTitleTest {
    @Test fun personLeadsAPersonsEntry() {
        val l = obj("kind" to "hand", "name" to "Borrowed", "lender" to "Mr Smith")
        assertEquals("Mr Smith", loanTitle(l)); assertEquals("Borrowed", loanSecond(l))
        val given = obj("kind" to "hand", "direction" to "given", "name" to "For the shop", "lender" to "Sam")
        assertEquals("Sam", loanTitle(given)); assertEquals("For the shop", loanSecond(given))
    }

    @Test fun nothingSaidTwiceOrLeftBlank() {
        assertEquals("", loanSecond(obj("kind" to "hand", "name" to "sam", "lender" to "Sam")))
        val noPerson = obj("kind" to "hand", "name" to "Borrowed", "lender" to "")
        assertEquals("Borrowed", loanTitle(noPerson)); assertEquals("", loanSecond(noPerson))
    }

    @Test fun bankAndPledgedKeepTheirName() {
        val b = obj("kind" to "bank", "name" to "Home EMI", "lender" to "City Bank")
        assertEquals("Home EMI", loanTitle(b)); assertEquals("City Bank", loanSecond(b))
        val g = obj("kind" to "gold", "name" to "", "lender" to "City Finance")
        assertEquals("City Finance", loanTitle(g)); assertEquals("", loanSecond(g))
    }
}
