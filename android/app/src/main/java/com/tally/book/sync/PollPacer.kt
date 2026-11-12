package com.tally.book.sync

/*
 * How often the open app asks about join requests: every 5 seconds, then every 30 seconds once 2 minutes have passed with nothing
 * new. A touch on the screen or a new request starts the 5 seconds again. Pure (the caller passes the clock), so it can be tested.
 */
class PollPacer(private val fastMs: Long = FAST_MS, private val slowMs: Long = SLOW_MS, private val quietAfterMs: Long = QUIET_AFTER_MS) {
    private var since = 0L

    /** Starts (or restarts) the fast pace at [nowMs]. */
    fun reset(nowMs: Long) { since = nowMs }

    /** Something changed (a new request) or the person touched the screen: back to the fast pace. */
    fun activity(nowMs: Long) { since = nowMs }

    /** How long to wait before the next check. */
    fun intervalAt(nowMs: Long): Long = if (nowMs - since >= quietAfterMs) slowMs else fastMs

    companion object {
        const val FAST_MS = 5_000L
        const val SLOW_MS = 30_000L
        const val QUIET_AFTER_MS = 120_000L
    }
}
