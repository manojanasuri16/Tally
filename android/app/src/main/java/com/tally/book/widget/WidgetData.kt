package com.tally.book.widget

import android.annotation.SuppressLint
import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tally.book.core.*
import com.tally.book.data.Crypto
import com.tally.book.data.Ledger
import com.tally.book.model.DB
import com.tally.book.model.NextPayment
import com.tally.book.model.nextPaymentFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/*
 * The widget's device-only data (docs/DESIGN.md "Widget"). Two things in DevicePrefs, neither ever in a backup:
 *  - "tally-due-cache": the dates only (data/DueNotices.kt), used while the app is locked or amounts are hidden;
 *  - "tally-widget-next": the SNAPSHOT, the one place a loan name and an amount are kept outside the book. It exists only while a
 *    widget is placed, the app is open, NO app lock is set and amounts are shown. It is removed the moment a lock is set, the app
 *    locks, amounts are hidden, the book is replaced by a locked one, or the last widget is removed.
 */
object NextPaymentData {
    const val SNAP = "tally-widget-next"
    /** Set while at least one widget is placed (the receiver sets and clears it). */
    const val ON = "tally-widget-on"
    private const val WORK = "tally-widget-day"

    var placed: Boolean
        get() = DevicePrefs.get(ON) == "1"
        set(v) { if (v) DevicePrefs.set(ON, "1") else DevicePrefs.remove(ON) }

    // ---- pure ----

    /**
     * The snapshot text to keep, or null when none may be kept: a lock is set, amounts are hidden, or nothing is placed.
     * The one rule that decides whether a name or an amount may sit outside the book.
     */
    fun snapshotText(placed: Boolean, lockOn: Boolean, hidden: Boolean, next: NextPayment): String? {
        if (!placed || lockOn || hidden) return null
        return JsonCodec.stringify(obj(
            "name" to next.name, "date" to (next.date?.toString() ?: ""), "amount" to next.amount,
            "overdue" to next.overdue.toDouble(), "late" to next.late,
        ))
    }

    fun readSnap(text: String?): WidgetText.Snap? {
        val o = text?.let { runCatching { JsonCodec.parseObj(it) }.getOrNull() } ?: return null
        return WidgetText.Snap(o.s("name"), o.s("date"), o.num("amount"), o.num("overdue").toInt(), o.b("late"))
    }

    /** Milliseconds from [nowMs] to just after the next midnight in [zone]. */
    fun delayToNextDay(nowMs: Long, zone: ZoneId): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val next = now.toLocalDate().plusDays(1).atTime(0, 5).atZone(zone)
        return next.toInstant().toEpochMilli() - nowMs
    }

    // ---- the app side ----

    @SuppressLint("StaticFieldLeak")      // the application context, which lives as long as the process
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (placed) scheduleDay(ctx.applicationContext)
        // the app locks (or loads sealed): the names go at once. When it opens, the snapshot is written again.
        scope.launch {
            Ledger.phase.collect { p ->
                when (p) {
                    Ledger.Phase.LOCKED -> clear()
                    Ledger.Phase.READY -> refresh()
                    else -> {}
                }
            }
        }
    }

    /** What the app wrote, for the widget. */
    fun snap(): WidgetText.Snap? = readSnap(DevicePrefs.get(SNAP))

    /** Removes the snapshot and redraws the widget with the dates-only text. */
    fun clear() {
        DevicePrefs.remove(SNAP)
        redraw()
    }

    /** Main thread, book open: keeps the snapshot in step with the book (or removes it when it may not be kept) and redraws. */
    fun refresh() {
        if (Ledger.phase.value != Ledger.Phase.READY) return
        val text = if (placed && !Crypto.locked && !DB.b("hideAmounts"))
            snapshotText(true, false, false, nextPaymentFor()) else null
        if (text == null) DevicePrefs.remove(SNAP) else DevicePrefs.set(SNAP, text)
        redraw()
    }

    /** Whether the snapshot may exist right now: the cheap check the app makes at once on every change, before the debounced [refresh]. */
    fun mayKeep(): Boolean = placed && !Crypto.locked && !DB.b("hideAmounts")

    /** A widget was added or the last one removed. */
    fun placedChanged(ctx: Context, on: Boolean) {
        placed = on
        if (on) { scheduleDay(ctx); com.tally.book.data.DueNotices.refreshCacheIfOpen(); if (Ledger.phase.value == Ledger.Phase.READY) refresh() }
        else { DevicePrefs.remove(SNAP); runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) } }
    }

    private fun redraw() {
        val ctx = app ?: return
        scope.launch(Dispatchers.Default) { runCatching { NextPaymentWidget().updateAll(ctx) } }
    }

    private fun scheduleDay(ctx: Context) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<WidgetDayWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayToNextDay(Clock.millis(), ZoneId.systemDefault()), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** At the start of each day: redraw, so "Tomorrow" becomes "Today" and a passed date counts as overdue. Reads no book. */
class WidgetDayWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { NextPaymentWidget().updateAll(applicationContext) }
        return Result.success()
    }
}
