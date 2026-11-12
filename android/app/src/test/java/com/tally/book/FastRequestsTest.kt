package com.tally.book

import com.tally.book.core.*
import com.tally.book.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The cheap join-request check (SyncEngine.refreshRequests) used every few seconds while the app is open. */
class FastRequestsTest {
    private lateinit var relay: FakeRelay
    @Before fun setUp() { TestClock.start(); relay = FakeRelay() }
    @After fun tearDown() { TestClock.stop() }

    private fun <T> rb(block: suspend () -> T): T = runBlocking { block() }

    private fun admin(): Phone {
        val a = Phone("A", relay)
        a.loan("l1", "Home loan")
        assertTrue(rb { a.engine.createFamily("Home") } is SyncResult.Done)
        return a
    }

    @Test fun anAdminSeesANewRequestWithoutAFullSyncAndOnlyOnce() {
        val a = admin(); val b = Phone("B", relay)
        assertFalse(rb { a.engine.refreshRequests() })                       // nothing waiting
        assertEquals(SyncResult.JoinPending, rb { b.engine.requestJoin(a.invite()) })
        val synced = a.book.db.o("sync").num("lastAt")
        assertTrue(rb { a.engine.refreshRequests() })                         // the new request is picked up
        assertEquals("B", a.engine.requests.value.single().name)
        assertEquals(1, a.notices.pending)
        assertFalse(rb { a.engine.refreshRequests() })                        // the same count: nothing more to do
        assertEquals(synced, a.book.db.o("sync").num("lastAt"), 0.0)          // it was not a full sync
        assertFalse(a.engine.state.value.running)
    }

    @Test fun aMemberOrAPhoneWithoutAFamilyDoesNothing() {
        val a = admin(); val b = Phone("B", relay)
        assertFalse(rb { b.engine.refreshRequests() })
        b.joinVia(a)
        assertFalse(rb { b.engine.refreshRequests() })
        assertTrue(b.engine.requests.value.isEmpty())
    }

    @Test fun anAnsweredRequestLeavesTheListOnTheNextCheck() {
        val a = admin(); val b = Phone("B", relay)
        rb { b.engine.requestJoin(a.invite()) }
        assertTrue(rb { a.engine.refreshRequests() })
        val req = a.engine.requests.value.single()
        assertTrue(rb { a.engine.deny(req.id) } is SyncResult.Done)
        assertTrue(a.engine.requests.value.isEmpty())
        assertFalse(rb { a.engine.refreshRequests() })
    }

    @Test fun aFailureIsSilent() {
        val a = admin()
        val offline = Phone("Z", object : Relay by relay {
            override suspend fun pending(familyId: String, memberId: String, notice: String): RelayResult<Int> = RelayResult.Err(SyncError.Offline)
        })
        assertFalse(rb { offline.engine.refreshRequests() })
        assertTrue(a.engine.requests.value.isEmpty())
    }
}
