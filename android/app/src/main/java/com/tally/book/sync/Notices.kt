package com.tally.book.sync

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tally.book.MainActivity
import com.tally.book.R
import com.tally.book.core.*
import java.util.concurrent.TimeUnit

/*
 * The quiet background check for join requests (docs/DESIGN.md, "Notices"). No push service: every few hours WorkManager asks
 * the relay how many requests wait (tally_pending, with the member's notice credential, which can do nothing else) and, when
 * there are more than the phone last saw, posts one notification that names nobody: "Someone wants to join your family".
 * Tapping it opens the app on the Family page (MainActivity.EXTRA_OPEN). Only an admin's phone schedules the check.
 *
 * The notice credential is kept in DevicePrefs ("tally-notice": family id, member id, notice token) because the worker runs
 * while the book may be sealed by the lock; the book has its own copy (DB.family.me.notice), so a restored phone gets it
 * back on its first sync. DevicePrefs is never in a backup.
 */
object Notices {
    const val CHANNEL = "family"
    private const val WORK = "tally-join-check"
    private const val NOTIFICATION_ID = 4101
    private const val PREF = "tally-notice"
    private const val SEEN = "tally-notice-seen"
    /** Android 13+ needs this permission to show the notification; ask with [ui.family.rememberNoticePermission]. */
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"     // Manifest.permission.POST_NOTIFICATIONS, API 33

    @SuppressLint("StaticFieldLeak")      // the application context, which lives as long as the process
    private var app: Context? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // keep the check in place across app updates and reboots (WorkManager keeps it too; KEEP makes this a no-op)
        val c = cred()
        if (c != null && c.admin) schedule(ctx.applicationContext) else cancelWork(ctx.applicationContext)
    }

    /** True when the notification cannot be shown until the person allows it (Android 13+, not granted yet). */
    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, PERMISSION) != PackageManager.PERMISSION_GRANTED

    val store: NoticeStore = object : NoticeStore {
        override fun update(cred: NoticeCred?, pending: Int) {
            val ctx = app ?: return
            if (cred == null) {
                DevicePrefs.remove(PREF); DevicePrefs.remove(SEEN)
                cancelWork(ctx); dismiss(ctx)
                return
            }
            DevicePrefs.set(PREF, JsonCodec.stringify(obj("fam" to cred.familyId, "member" to cred.memberId, "notice" to cred.notice, "admin" to cred.admin)))
            // the app has just shown these requests: the background check only speaks about newer ones
            DevicePrefs.set(SEEN, pending.toString())
            dismiss(ctx)
            if (cred.admin) schedule(ctx) else cancelWork(ctx)
        }
    }

    private fun cred(): NoticeCred? {
        val o = DevicePrefs.get(PREF)?.let { runCatching { JsonCodec.parseObj(it) }.getOrNull() } ?: return null
        return NoticeCred(o.s("fam"), o.s("member"), o.s("notice"), o.b("admin")).takeIf { it.familyId.isNotEmpty() && it.notice.isNotEmpty() }
    }

    private fun schedule(ctx: Context) {
        runCatching {
            val req = PeriodicWorkRequestBuilder<JoinCheckWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    private fun cancelWork(ctx: Context) { runCatching { WorkManager.getInstance(ctx).cancelUniqueWork(WORK) } }

    private fun dismiss(ctx: Context) { runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_ID) } }

    /** One background check. */
    suspend fun check(ctx: Context) {
        val c = cred() ?: run { cancelWork(ctx); return }
        if (!Sync.configured) return
        when (val r = Sync.relay.pending(c.familyId, c.memberId, c.notice)) {
            is RelayResult.Ok -> {
                val seen = DevicePrefs.get(SEEN)?.toIntOrNull() ?: 0
                if (r.value > seen) post(ctx)
                if (r.value == 0) dismiss(ctx)
                DevicePrefs.set(SEEN, r.value.toString())
            }
            is RelayResult.Err -> if (r.error == SyncError.Unauthorised) { DevicePrefs.remove(PREF); cancelWork(ctx) }
        }
    }

    @SuppressLint("MissingPermission")    // checked just below (needsPermission)
    private fun post(ctx: Context) {
        if (needsPermission(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, t("notice.channel", "Family"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "family")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // names nobody, shows nothing from the book: safe on a locked phone and while amounts are hidden
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_glyph)
            .setContentTitle(t("notice.join", "Someone wants to join your family"))
            .setContentText(t("notice.join.open", "Open Tally to answer."))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n) }
    }
}

class JoinCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { Notices.check(applicationContext) }
        return Result.success()
    }
}
