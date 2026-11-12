// AppDrop in-app updater + feedback. Single drop-in file, no third-party dependencies
// (only the Android SDK, org.json, and androidx.core's FileProvider, which virtually every app has).
//
// The `package appdrop` line is a placeholder: `appdrop add-updater` rewrites it to
// "<your.package>.appdrop". If you copy the file by hand, change it yourself.
//
// Setup (see docs/android-updates.md):
//   Application.onCreate()  ->  AppDropUpdater.init(this, "https://appdrop-store.vercel.app", "<slug>")
//   MainActivity.onResume() ->  AppDropUpdater.check(this)
package com.tally.book.appdrop
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppDropUpdater {
    private const val TAG = "AppDrop"
    private const val PREFS = "appdrop_updater"
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000
    private const val MIME_APK = "application/vnd.android.package-archive"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AppDrop-io").apply { isDaemon = true } }
    private val checking = AtomicBoolean(false)

    private var appContext: Context? = null
    private var baseUrl = ""
    private var slug = ""
    private var betaToken: String? = null

    /** Latest release info as returned by the AppDrop API. */
    private class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        val sha256: String?,
        val pageUrl: String,
    )

    // ───────────────────────── public API ─────────────────────────

    /** Call once from Application.onCreate(). [baseUrl] is e.g. https://appdrop-store.vercel.app */
    fun init(context: Context, baseUrl: String, slug: String) {
        this.appContext = context.applicationContext
        this.baseUrl = baseUrl.trim().trimEnd('/')
        this.slug = slug.trim()
    }

    /** Optional: beta invite token so pre-releases count as "latest" for this user. */
    fun setBetaToken(token: String?) {
        betaToken = token?.takeIf { it.isNotBlank() }
    }

    /**
     * Checks for a newer version and, if there is one, shows an update dialog.
     * Cheap to call from onResume: it runs at most once per 12 hours unless [force] is true
     * (use force = true for a manual "Check for updates" menu item; it also reports "up to date").
     */
    fun check(activity: Activity, force: Boolean = false) {
        try {
            val ctx = appContext ?: activity.applicationContext.also { appContext = it }
            if (baseUrl.isEmpty() || slug.isEmpty()) {
                Log.w(TAG, "check() called before init()")
                return
            }
            // Came back from the "install unknown apps" settings screen with a download waiting?
            if (resumePendingInstall(activity)) return

            val prefs = prefs(ctx)
            if (!force && System.currentTimeMillis() - prefs.getLong("last_check", 0L) < CHECK_INTERVAL_MS) return
            if (!checking.compareAndSet(false, true)) return

            val current = installedVersion(ctx)
            val device = deviceId(ctx)
            val beta = betaToken
            io.execute {
                var release: Release? = null
                var updateAvailable = false
                var failed = false
                try {
                    val json = fetchLatest(current, device, beta)
                    prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                    updateAvailable = json.optBoolean("updateAvailable", false)
                    release = parseRelease(json)
                } catch (t: Throwable) {
                    failed = true
                    Log.w(TAG, "Update check failed: ${t.message}")
                } finally {
                    checking.set(false)
                }
                main.post {
                    try {
                        if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return@post
                        when {
                            failed -> if (force) toast(activity, "Couldn't check for updates. Are you online?")
                            updateAvailable && release != null -> {
                                val skipped = prefs.getString("skipped_version", null)
                                if (force || skipped != release.version) showUpdateDialog(activity, release)
                            }
                            force -> toast(activity, "You're up to date.")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not show update UI", t)
                    }
                }
            }
        } catch (t: Throwable) {
            checking.set(false)
            Log.w(TAG, "check() failed", t)
        }
    }

    /** Opens the app's feedback form on the AppDrop web page in the browser. */
    fun openFeedback(activity: Activity) {
        try {
            openUrl(activity, "$baseUrl/apps/$slug#feedback")
        } catch (t: Throwable) {
            Log.w(TAG, "openFeedback failed", t)
        }
    }

    /**
     * Posts feedback straight to AppDrop (build your own in-app form around this).
     * [callback] runs on the main thread: (true, null) on success, else (false, message).
     */
    fun sendFeedback(
        context: Context,
        message: String,
        name: String? = null,
        contact: String? = null,
        callback: ((success: Boolean, error: String?) -> Unit)? = null,
    ) {
        val ctx = context.applicationContext
        val version = installedVersion(ctx)
        io.execute {
            var error: String? = null
            try {
                val body = JSONObject().apply {
                    put("message", message)
                    if (!name.isNullOrBlank()) put("name", name)
                    if (!contact.isNullOrBlank()) put("contact", contact)
                    if (version.isNotEmpty()) put("version", version)
                    put("source", "app")
                }
                val conn = open("$baseUrl/api/public/apps/$slug/feedback")
                try {
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    if (code != 200) {
                        val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                            ?.bufferedReader()?.use { it.readText() }.orEmpty()
                        error = when (code) {
                            429 -> "Too many messages. Please try again in a few minutes."
                            else -> runCatching { JSONObject(text).optString("error") }.getOrNull()
                                ?.takeIf { it.isNotBlank() } ?: "Server error ($code)"
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "sendFeedback failed: ${t.message}")
                error = "Couldn't send feedback. Are you online?"
            }
            main.post { callback?.invoke(error == null, error) }
        }
    }

    // ───────────────────────── networking ─────────────────────────

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AppDropUpdater/1 (Android ${Build.VERSION.SDK_INT})")
        }

    private fun fetchLatest(current: String, device: String, beta: String?): JSONObject {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = buildString {
            append("$baseUrl/api/public/apps/${enc(slug)}/latest?current=${enc(current)}&device=${enc(device)}")
            if (beta != null) append("&beta=${enc(beta)}")
        }
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code != 200) error("HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): Release? {
        val latest = json.optJSONObject("latest") ?: return null
        val apk = latest.optJSONObject("apk") ?: return null
        val url = apk.optString("url")
        if (url.isEmpty()) return null
        return Release(
            version = latest.optString("version"),
            notes = latest.optString("notes"),
            apkUrl = url,
            apkName = apk.optString("name").ifBlank { "update.apk" },
            size = apk.optLong("size", 0L),
            sha256 = if (apk.isNull("sha256")) null else apk.optString("sha256").lowercase(Locale.ROOT).ifBlank { null },
            pageUrl = json.optString("pageUrl").ifBlank { "$baseUrl/apps/$slug" },
        )
    }

    // ───────────────────────── dialog ─────────────────────────

    private fun showUpdateDialog(activity: Activity, r: Release) {
        val sizeText = if (r.size > 0) " (%.1f MB)".format(Locale.US, r.size / 1_048_576.0) else ""
        val notes = plainNotes(r.notes)
        val message = "Version ${r.version} is ready$sizeText." + if (notes.isNotEmpty()) "\n\n$notes" else ""
        AlertDialog.Builder(activity)
            .setTitle("Update available")
            .setMessage(message)
            .setPositiveButton("Update") { _, _ -> startDownload(activity, r) }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ ->
                prefs(activity).edit().putString("skipped_version", r.version).apply()
            }
            .show()
    }

    /** First ~6 non-empty lines of the release notes with common markdown removed. */
    private fun plainNotes(md: String): String =
        md.lineSequence()
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^[-*+]\\s+"), "• ")
                    .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
                    .replace(Regex("(\\*\\*|__|`)"), "")
            }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .take(6)
            .joinToString("\n")

    // ───────────────────────── download + install ─────────────────────────

    private fun startDownload(activity: Activity, r: Release) {
        val ctx = activity.applicationContext
        try {
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                ?: return fallbackToBrowser(activity, r)
            // One APK at a time: clear out older downloads from earlier updates.
            downloadDir(ctx)?.listFiles()?.forEach { it.delete() }

            val fileName = "${slug}-${r.version}.apk".replace(Regex("[^A-Za-z0-9._-]"), "_")
            val request = DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("${r.apkName} (update)")
                .setDescription("Downloading version ${r.version}")
                .setMimeType(MIME_APK)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "appdrop/$fileName")
            val id = dm.enqueue(request)
            toast(activity, "Downloading update...")

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != id) return
                    runCatching { ctx.unregisterReceiver(this) }
                    onDownloadFinished(activity, dm, id, r)
                }
            }
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Download failed to start", t)
            fallbackToBrowser(activity, r)
        }
    }

    private fun onDownloadFinished(activity: Activity, dm: DownloadManager, id: Long, r: Release) {
        val ctx = activity.applicationContext
        try {
            var file: File? = null
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val local = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        file = local?.let { Uri.parse(it).path }?.let { File(it) }
                    }
                }
            }
            val apk = file?.takeIf { it.isFile }
            if (apk == null) {
                Log.w(TAG, "Download did not complete")
                fallbackToBrowser(activity, r)
                return
            }
            if (r.sha256 != null) {
                val actual = sha256Of(apk)
                if (!actual.equals(r.sha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 mismatch: expected ${r.sha256}, got $actual")
                    apk.delete()
                    toast(activity, "Update download is corrupted (checksum mismatch). Please try again later.", long = true)
                    return
                }
            }
            install(activity, apk, r.version)
        } catch (t: Throwable) {
            Log.w(TAG, "Install step failed", t)
            fallbackToBrowser(activity, r)
        }
    }

    private fun install(activity: Activity, apk: File, version: String) {
        val ctx = activity.applicationContext
        // Always remember the verified APK until the new version is actually running: Android 10+
        // silently blocks starting the installer while the app is in the background, so the next
        // check() (e.g. from onResume) must be able to pick the install up again.
        prefs(ctx).edit().putString("pending_apk", apk.absolutePath).putString("pending_version", version).apply()
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed) || !activity.hasWindowFocus()) {
            Log.i(TAG, "App not in the foreground; install will resume on next check()")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Allow installing updates")
                .setMessage("To install the update, Android needs your permission for this app to install apps. Turn on \"Allow from this source\", then come back.")
                .setPositiveButton("Open settings") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not open install settings", t)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** If an APK was downloaded but the install permission was missing, install it now that it may be granted. */
    private fun resumePendingInstall(activity: Activity): Boolean {
        val ctx = activity.applicationContext
        val p = prefs(ctx)
        val path = p.getString("pending_apk", null) ?: return false
        val version = p.getString("pending_version", null) ?: ""
        val apk = File(path)
        // Already updated (or the file is gone)? Clean up and carry on with a normal check.
        if (!apk.isFile || (version.isNotEmpty() && compareVersions(installedVersion(ctx), version) >= 0)) {
            p.edit().remove("pending_apk").remove("pending_version").apply()
            if (apk.isFile) apk.delete()
            return false
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) return false
        if (!activity.hasWindowFocus()) return false
        return try {
            install(activity, apk, version)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resume install", t)
            false
        }
    }

    private fun fallbackToBrowser(activity: Activity, r: Release) {
        try {
            openUrl(activity, r.apkUrl.ifBlank { r.pageUrl })
        } catch (t: Throwable) {
            Log.w(TAG, "Browser fallback failed", t)
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Compares "v1.10.0" / "1.2" / "2.0.0-beta.1" style versions: <0, 0 or >0. Mirrors the server. */
    internal fun compareVersions(a: String, b: String): Int {
        fun parse(v: String): Pair<List<Int>, String> {
            val s = v.trim().removePrefix("v").removePrefix("V")
            val dash = s.indexOf('-')
            val core = if (dash >= 0) s.substring(0, dash) else s
            val pre = if (dash >= 0) s.substring(dash + 1) else ""
            return core.split('.', '_').map { it.toIntOrNull() ?: 0 } to pre
        }
        val (x, xp) = parse(a)
        val (y, yp) = parse(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return when {
            xp == yp -> 0
            xp.isEmpty() -> 1
            yp.isEmpty() -> -1
            else -> xp.compareTo(yp)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Random per-install id; the server stores only a hash and uses it for anonymous "active devices by version". */
    private fun deviceId(context: Context): String {
        val p = prefs(context)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("device_id", it).apply()
        }
    }

    @Suppress("DEPRECATION")
    private fun installedVersion(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName.orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun downloadDir(context: Context): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "appdrop") }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toast(context: Context, text: String, long: Boolean = false) {
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
}
