package com.ashrafnaji.store.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.ashrafnaji.store.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.widget.Toast

/**
 * Downloads an APK (either this app's own latest GitHub release, or an arbitrary catalog
 * entry's asset) and installs it via [PackageInstaller]. If the install fails (e.g.
 * STATUS_FAILURE_CONFLICT from a signing-key mismatch between builds), it falls back to
 * uninstalling whatever's currently installed under that package name and prompting the user
 * to finish installing the downloaded APK.
 */
object UpdateManager {

    const val EXTRA_APK_URI = "com.ashrafnaji.store.EXTRA_APK_URI"
    const val EXTRA_PACKAGE_NAME = "com.ashrafnaji.store.EXTRA_PACKAGE_NAME"
    private const val NOTIF_CHANNEL_ID = "app_update"

    interface Listener {
        fun onStatus(message: String)
        fun onUpToDate()
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Checks this app's own latest GitHub release and self-updates if newer. */
    fun checkAndUpdate(context: Context, listener: Listener) {
        Thread {
            try {
                val release = fetchLatestRelease()
                if (release == null) {
                    postError(listener, "Could not reach GitHub releases")
                    return@Thread
                }
                val (versionName, downloadUrl) = release
                if (downloadUrl == null) {
                    postError(listener, "No APK found for this device's CPU (${Build.SUPPORTED_ABIS.joinToString()})")
                    return@Thread
                }
                if (!isNewer(versionName, BuildConfig.VERSION_NAME)) {
                    mainHandler.post { listener.onUpToDate() }
                    return@Thread
                }
                mainHandler.post { listener.onStatus("Downloading version $versionName...") }
                downloadAndInstall(context.applicationContext, downloadUrl, context.packageName, "Store $versionName", listener)
            } catch (e: Exception) {
                postError(listener, e.message ?: "Update check failed")
            }
        }.start()
    }

    /** Downloads and installs a specific APK for an arbitrary catalog entry. */
    fun installFromUrl(context: Context, downloadUrl: String, packageName: String, label: String, listener: Listener) {
        Thread {
            mainHandler.post { listener.onStatus("Downloading $label...") }
            downloadAndInstall(context.applicationContext, downloadUrl, packageName, label, listener)
        }.start()
    }

    private fun postError(listener: Listener, message: String) {
        mainHandler.post { listener.onError(message) }
    }

    /** Fetches the repo's short description (shown as the store listing's blurb). */
    fun fetchRepoDescription(onResult: (String?) -> Unit) {
        Thread {
            val description = try {
                val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) null
                    else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        if (json.isNull("description")) null else json.optString("description").ifBlank { null }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            mainHandler.post { onResult(description) }
        }.start()
    }

    /**
     * Returns (versionName, downloadUrlForThisAbi?) or null if the request failed.
     *
     * Reads `latest.json` from the repo's raw content CDN rather than calling
     * `api.github.com/repos/.../releases/latest`: the REST API caps unauthenticated requests
     * at 60/hour per IP, which a single shared network (e.g. several units behind one router)
     * can burn through in minutes. raw.githubusercontent.com isn't subject to that limit.
     */
    private fun fetchLatestRelease(): Pair<String, String?>? {
        val url = URL("https://raw.githubusercontent.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/main/latest.json")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val versionName = json.getString("version")
            val assets = json.getJSONObject("assets")

            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val downloadUrl = if (assets.has(abi)) assets.getString(abi) else null
            return versionName to downloadUrl
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    /**
     * Downloads synchronously on the calling (background) thread and installs immediately
     * after, instead of handing the download off to [DownloadManager] and waiting for its
     * `ACTION_DOWNLOAD_COMPLETE` broadcast. That broadcast goes to a receiver registered with
     * [Context.registerReceiver], which is tied to this process's lifetime -- on a device that
     * kills backgrounded apps aggressively (common on infotainment units running many
     * concurrent apps), the process can die while the download is still in progress, the
     * receiver is lost with it, and the download completes with nothing left to act on it. The
     * observable symptom was the exact same "Downloading version X..." status repeating forever
     * across separate launches, while DownloadManager's own records showed every one of those
     * downloads had actually finished successfully.
     */
    private fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        packageName: String,
        label: String,
        listener: Listener
    ) {
        try {
            val fileName = "${packageName}-${System.currentTimeMillis()}.apk"
            val apkUri = downloadToPublicDownloads(context, downloadUrl, fileName)
            mainHandler.post { listener.onStatus("Installing $label...") }
            installApk(context, apkUri, packageName, listener)
        } catch (e: Exception) {
            postError(listener, "Download failed: ${e.message}")
        }
    }

    /**
     * Downloads [downloadUrl] into the shared Downloads collection and returns a content Uri
     * owned by the system (MediaStore on API 29+, a plain file Uri below that) -- not by this
     * app's own FileProvider, so it (and the install-failure fallback that reuses it) keeps
     * working even if this app is uninstalled partway through.
     */
    private fun downloadToPublicDownloads(context: Context, downloadUrl: String, fileName: String): Uri {
        val resolver = context.contentResolver
        val uri: Uri
        val outputStream: java.io.OutputStream

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create download entry")
            outputStream = resolver.openOutputStream(uri)
                ?: throw java.io.IOException("Could not open output stream")
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = java.io.File(downloadsDir, fileName)
            outputStream = java.io.FileOutputStream(file)
            uri = Uri.fromFile(file)
        }

        val connection = URL(downloadUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }

        return uri
    }

    private fun installApk(context: Context, apkUri: Uri, packageName: String, listener: Listener) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use { s ->
                context.contentResolver.openInputStream(apkUri).use { input ->
                    requireNotNull(input) { "Cannot open downloaded APK" }
                    s.openWrite("update", 0, -1).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }

                val resultIntent = Intent(context, InstallResultReceiver::class.java).apply {
                    putExtra(EXTRA_APK_URI, apkUri.toString())
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                s.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            postError(listener, "Install failed: ${e.message}")
        }
    }

    /**
     * Called by [InstallResultReceiver] when a session install fails outright (not just
     * pending user confirmation) — most commonly STATUS_FAILURE_CONFLICT when the downloaded
     * APK is signed with a different key than the currently installed app. Since a plain app
     * cannot silently replace another app's signature, remove the old one first, then let the
     * user tap the still-downloaded APK (kept alive by the system Download provider,
     * independent of any app's process) to finish installing the new one.
     */
    fun handleInstallFailure(context: Context, packageName: String?, apkUriString: String?) {
        if (apkUriString == null || packageName == null) return
        val apkUri = Uri.parse(apkUriString)

        postFinishInstallNotification(context, apkUri)

        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            // Use Uri.fromParts to construct a proper package URI for uninstall
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Inform the user that uninstall is being initiated
        Toast.makeText(context, "Uninstalling $packageName", Toast.LENGTH_SHORT).show()
        context.startActivity(uninstallIntent)
    }

    private fun postFinishInstallNotification(context: Context, apkUri: Uri) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "App updates",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, apkUri.hashCode(), installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Finish installing update")
            .setContentText("The old app was removed. Tap here to install the new version.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        notificationManager.notify(apkUri.hashCode(), notification)
    }
}
