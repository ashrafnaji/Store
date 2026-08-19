package com.ashrafnaji.store.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * Checks the GitHub repo's latest release for a newer version, downloads the APK asset that
 * matches the device's CPU ABI, and installs it via [PackageInstaller]. If the install fails
 * (e.g. STATUS_FAILURE_CONFLICT from a signing-key mismatch between builds), it falls back to
 * uninstalling the current app and prompting the user to finish installing the downloaded APK.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    const val EXTRA_APK_URI = "com.ashrafnaji.store.EXTRA_APK_URI"
    private const val NOTIF_CHANNEL_ID = "app_update"
    private const val NOTIF_ID = 1001

    interface Listener {
        fun onStatus(message: String)
        fun onUpToDate()
        fun onError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

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
                downloadAndInstall(context.applicationContext, downloadUrl, versionName, listener)
            } catch (e: Exception) {
                postError(listener, e.message ?: "Update check failed")
            }
        }.start()
    }

    private fun postError(listener: Listener, message: String) {
        mainHandler.post { listener.onError(message) }
    }

    /** Returns (versionName, downloadUrlForThisAbi?) or null if the request failed. */
    private fun fetchLatestRelease(): Pair<String, String?>? {
        val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.getString("tag_name").removePrefix("v")
            val assets = json.getJSONArray("assets")

            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            var downloadUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.contains(abi) && name.endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
            return tagName to downloadUrl
        } finally {
            conn.disconnect()
        }
    }

    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun downloadAndInstall(context: Context, downloadUrl: String, versionName: String, listener: Listener) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("Store update $versionName")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "store-$versionName.apk")

        val downloadId = downloadManager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId != downloadId) return
                context.unregisterReceiver(this)

                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        postError(listener, "Download failed")
                        return
                    }
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (cursor.getInt(statusIdx) != DownloadManager.STATUS_SUCCESSFUL) {
                        postError(listener, "Download failed")
                        return
                    }
                }

                val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
                if (apkUri == null) {
                    postError(listener, "Downloaded file not found")
                    return
                }
                mainHandler.post { listener.onStatus("Installing version $versionName...") }
                installApk(context, apkUri, listener)
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(context: Context, apkUri: Uri, listener: Listener) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use { s ->
                context.contentResolver.openInputStream(apkUri).use { input ->
                    requireNotNull(input) { "Cannot open downloaded APK" }
                    s.openWrite("store_update", 0, -1).use { out ->
                        input.copyTo(out)
                        s.fsync(out)
                    }
                }

                val resultIntent = Intent(context, InstallResultReceiver::class.java).apply {
                    putExtra(EXTRA_APK_URI, apkUri.toString())
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
     * cannot silently replace its own signature, remove the old app first, then let the user
     * tap the still-downloaded APK (kept alive by the system Download provider, independent of
     * this app's process) to finish installing the new one.
     */
    fun handleInstallFailure(context: Context, apkUriString: String?) {
        if (apkUriString == null) return
        val apkUri = Uri.parse(apkUriString)

        postFinishInstallNotification(context, apkUri)

        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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
            context, 0, installIntent,
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

        notificationManager.notify(NOTIF_ID, notification)
    }
}
