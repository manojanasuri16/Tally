package com.tally.book.data

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tally.book.MainActivity
import com.tally.book.R
import com.tally.book.core.*
import com.tally.book.model.DB
import com.tally.book.model.dueDatesAhead
import com.tally.book.sync.Notices
import com.tally.book.widget.NextPaymentData
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/*
 * Payment reminders (docs/DESIGN.md, "Reminders"): once a day, around 9 in the morning, one quiet notification when a payment is
 * due today or overdue (money I took) or money is expected today (money I gave). It names no person, loan or amount, so it is the
 * same on a locked phone and while amounts are hidden. Tapping it opens the home screen. It is never posted while the app is showing.
 *
 * The job runs while the book may be sealed by the app lock, so it cannot read the book. Instead the app keeps a small copy of
 * only the DATES on which something is due or expected (no names, ids or amounts), in DevicePrefs ("tally-due-cache": device-only,
 * never in a backup), refreshed whenever the book changes. It holds every due date for the 60 days after the last refresh. A date that
 * has passed counts as overdue until the next refresh after it was paid. The setting "Payment reminders" is on unless
 * switched off; nothing is shown until Android 13+ notifications are allowed.
 */
object DueNotices {
    const val CHANNEL = "due"
    private const val WORK = "tally-due-check"
    private const val NOTIFICATION_ID = 4102
    private const val ON = "tally-due-notices"
    private const val CACHE = "tally-due-cache"
    private const val ASKED = "tally-due-asked"
    private const val HOUR = 9

    /** What the one notification says. */
    enum class Kind { OVERDUE, DUE_TODAY, EXPECTED }

    /** The setting: on unless the person switched it off. */
    var enabled: Boolean
        get() = DevicePrefs.get(ON) != "0"
        set(v) { DevicePrefs.set(ON, if (v) "1" else "0") }

    // ---- pure ----

    /** Which message the dates call for: overdue first, then due today, then money expected (today or earlier); null when none. */
    fun pick(taken: List<String>, given: List<String>, today: LocalDate): Kind? {
        val t = taken.mapNotNull { parseISO(it) }
        val g = given.mapNotNull { parseISO(it) }
        return when {
            t.any { it.isBefore(today) } -> Kind.OVERDUE
            t.any { it == today } -> Kind.DUE_TODAY
            g.any { !it.isAfter(today) } -> Kind.EXPECTED
            else -> null
        }
    }

    /** The notification's words: (title, text). They name nothing. */
    fun words(kind: Kind): Pair<String, String> {
        val open = t("notice.due.open", "Open Tally to see.")
        return when (kind) {
            Kind.OVERDUE -> t("notice.due.overdue", "Payments are overdue") to open
            Kind.DUE_TODAY -> t("notice.due.today", "A payment is due today") to open
            Kind.EXPECTED -> t("notice.due.expected", "Money is expected today") to open
        }
    }

    /** Milliseconds from [nowMs] to the next 9:00 in [zone] (tomorrow's once today's has passed). */
    fun delayToNine(nowMs: Long, zone: ZoneId): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var next = now.toLocalDate().atTime(HOUR, 0).atZone(zone)
        if (!next.isAfter(now)) next = now.toLocalDate().plusDays(1).atTime(HOUR, 0).atZone(zone)
        return next.toInstant().toEpochMilli() - nowMs
    }

    /** The cache text for [dueDatesAhead]: only the dates, split into money I took and money I gave. */
    fun cacheOf(dates: List<Pair<LocalDate, Boolean>>): String {
        fun list(given: Boolean) = arr().also { a -> dates.filter { it.second == given }.map { it.first.toString() }.distinct().sorted().forEach { a.add(it) } }
        return JsonCodec.stringify(obj("taken" to list(false), "given" to list(true)))
    }

    /** The two date lists back from cache text (empty when it is missing or unreadable). */
    fun readCache(text: String?): Pair<List<String>, List<String>> {
        val o = text?.let { runCatching { JsonCodec.parseObj(it) }.getOrNull() } ?: return emptyList<String>() to emptyList()
        fun list(k: String) = o.aa(k).map { it.toString() }
        return list("taken") to list("given")
    }

    // ---- the app side ----

    @SuppressLint("StaticFieldLeak")      // the application context, which lives as long as the process
    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (enabled) schedule(ctx.applicationContext) else cancel(ctx.applicationContext)
    }

    /** Sets the setting and puts the daily job in or out. */
    fun turn(on: Boolean) {
        enabled = on
        val ctx = app ?: return
        if (on) { schedule(ctx); refreshCache() } else { cancel(ctx); if (!NextPaymentData.placed) DevicePrefs.remove(CACHE); dismiss(ctx) }
    }

    /**
     * Re-reads the dates from the open book. Call on the main thread while the book is open. The copy is kept while the reminders
     * are on or a "Next payment" widget is placed (it uses the same dates while the app is locked); otherwise it is removed.
     */
    fun refreshCache() {
        if (!enabled && !NextPaymentData.placed) { DevicePrefs.remove(CACHE); return }
        DevicePrefs.set(CACHE, cacheOf(dueDatesAhead(Clock.today(), 60)))
    }

    /** The cached dates of money I took (null when no copy was ever saved), for the widget. */
    fun takenDates(): List<String>? = DevicePrefs.get(CACHE)?.let { readCache(it).first }

    /** [refreshCache] if the book is open (a widget was just placed); otherwise the next time the app opens does it. */
    fun refreshCacheIfOpen() { if (Ledger.phase.value == Ledger.Phase.READY) refreshCache() }

    /** True once: the first time a loan is saved with the setting on and notifications not yet allowed, so the person is asked in plain words. */
    fun shouldOffer(ctx: Context): Boolean {
        if (!enabled || !Notices.needsPermission(ctx) || DevicePrefs.get(ASKED) != null) return false
        DevicePrefs.set(ASKED, "1")
        return true
    }

    private fun schedule(ctx: Context) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<DueCheckWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayToNine(Clock.millis(), ZoneId.systemDefault()), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    private fun cancel(ctx: Context) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) } }

    private fun dismiss(ctx: Context) { runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID) } }

    /** One daily check, from the cached dates (never the book). */
    fun check(ctx: Context) {
        if (!enabled || MainActivity.visible || Notices.needsPermission(ctx)) return
        val (taken, given) = readCache(DevicePrefs.get(CACHE))
        val kind = pick(taken, given, Clock.today()) ?: return
        post(ctx, kind)
    }

    @SuppressLint("MissingPermission")    // checked in check() (Notices.needsPermission)
    private fun post(ctx: Context, kind: Kind) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("notice.due.channel", "Payment reminders"), NotificationManager.IMPORTANCE_LOW))
        }
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val (title, text) = words(kind)
        // names nothing: safe on a locked phone and while amounts are hidden
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) }
    }
}

class DueCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { DueNotices.check(applicationContext) }
        return Result.success()
    }
}
