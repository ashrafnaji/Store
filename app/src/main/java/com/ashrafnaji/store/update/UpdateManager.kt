package com.ashrafnaji.store.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ashrafnaji.store.BuildConfig
import com.ashrafnaji.store.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
    private const val DOWNLOAD_TIMEOUT_MS = 15 * 60 * 1000L
    private val releaseAbis = setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

    interface Listener {
        fun onStatus(message: String)
        fun onDownloadProgress(downloadedBytes: Long, totalBytes: Long)
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
                    postError(listener, context.getString(R.string.error_github_releases))
                    return@Thread
                }
                val (versionName, downloadUrl) = release
                if (downloadUrl == null) {
                    postError(
                        listener,
                        context.getString(R.string.error_no_device_apk, Build.SUPPORTED_ABIS.joinToString())
                    )
                    return@Thread
                }
                if (!isNewer(versionName, BuildConfig.VERSION_NAME)) {
                    mainHandler.post { listener.onUpToDate() }
                    return@Thread
                }
                mainHandler.post {
                    listener.onStatus(context.getString(R.string.status_downloading_version, versionName))
                }
                val label = context.getString(R.string.self_update_label, versionName)
                downloadAndInstall(context.applicationContext, downloadUrl, context.packageName, label, listener)
            } catch (e: Exception) {
                postError(listener, e.message ?: context.getString(R.string.error_update_check))
            }
        }.start()
    }

    /** Downloads and installs a specific APK for an arbitrary catalog entry. */
    fun installFromUrl(context: Context, downloadUrl: String, packageName: String, label: String, listener: Listener) {
        Thread {
            mainHandler.post {
                listener.onStatus(context.getString(R.string.status_downloading_app, label))
            }
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

    /** Returns (versionName, downloadUrlForThisAbi?) or null if the request failed. */
    private fun fetchLatestRelease(): Pair<String, String?>? {
        fetchLatestReleaseRedirect()?.let { return it }
        return fetchLatestReleaseManifest()
    }

    /**
     * GitHub keeps this redirect current and marks it no-cache. This avoids both the REST API's
     * unauthenticated rate limit and the raw content CDN lag that can leave latest.json stale.
     */
    private fun fetchLatestReleaseRedirect(): Pair<String, String?>? {
        val owner = BuildConfig.GITHUB_OWNER
        val repo = BuildConfig.GITHUB_REPO
        val conn = URL("https://github.com/$owner/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 300..399) return null
            val location = conn.getHeaderField("Location") ?: return null
            val tag = Uri.parse(location).lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
            val versionName = tag.removePrefix("v")
            if (versionName.isBlank()) return null

            val abi = Build.SUPPORTED_ABIS.firstOrNull { it in releaseAbis }
            val downloadUrl = abi?.let {
                "https://github.com/$owner/$repo/releases/download/$tag/app-$it-release.apk"
            }
            return versionName to downloadUrl
        } finally {
            conn.disconnect()
        }
    }

    /** Fallback for GitHub mirrors or networks that do not return the latest-release redirect. */
    private fun fetchLatestReleaseManifest(): Pair<String, String?>? {
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

    /** Downloads without Android's DownloadProvider, which is broken on some vendor Android 9 ROMs. */
    private fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        packageName: String,
        label: String,
        listener: Listener
    ) {
        var downloadedFile: File? = null
        try {
            downloadedFile = downloadApk(context, downloadUrl, packageName, listener)
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                downloadedFile
            )
            mainHandler.post {
                listener.onStatus(context.getString(R.string.status_installing_app, label))
            }
            installApk(context, apkUri, packageName, listener)
        } catch (e: Exception) {
            downloadedFile?.delete()
            postError(listener, context.getString(R.string.error_download_failed, e.message ?: ""))
        }
    }

    private fun downloadApk(
        context: Context,
        downloadUrl: String,
        packageName: String,
        listener: Listener
    ): File {
        val canUsePublicDownloads = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        val directory = if (canUsePublicDownloads) {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.cacheDir, "updates")
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException(context.getString(R.string.error_download_directory))
        }

        val destination = File(directory, "${packageName}-${System.currentTimeMillis()}.apk")
        val partial = File(directory, "${destination.name}.part")
        val connection = URL(downloadUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
        connection.setRequestProperty("User-Agent", "AutoExpert-Store/${BuildConfig.VERSION_NAME}")

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException(context.getString(R.string.error_http_status, responseCode))
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            var downloadedBytes = 0L
            var lastReportedAt = 0L
            var lastPercent = -1
            val startedAt = System.currentTimeMillis()
            postProgress(listener, downloadedBytes, totalBytes)

            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (System.currentTimeMillis() - startedAt > DOWNLOAD_TIMEOUT_MS) {
                            throw IOException(context.getString(R.string.error_download_timed_out))
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloadedBytes += count

                        val now = System.currentTimeMillis()
                        val percent = if (totalBytes > 0L) {
                            ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                        } else {
                            -1
                        }
                        if (percent != lastPercent || now - lastReportedAt >= 250L) {
                            lastPercent = percent
                            lastReportedAt = now
                            postProgress(listener, downloadedBytes, totalBytes)
                        }
                    }
                    output.fd.sync()
                }
            }

            if (totalBytes > 0L && downloadedBytes != totalBytes) {
                throw IOException(context.getString(R.string.error_incomplete_download))
            }
            if (!partial.renameTo(destination)) {
                throw IOException(context.getString(R.string.error_downloaded_file_missing))
            }
            postProgress(listener, downloadedBytes, if (totalBytes > 0L) totalBytes else downloadedBytes)
            return destination
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun postProgress(listener: Listener, downloadedBytes: Long, totalBytes: Long) {
        mainHandler.post { listener.onDownloadProgress(downloadedBytes, totalBytes) }
    }

    private fun installApk(context: Context, apkUri: Uri, packageName: String, listener: Listener) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            session.use { s ->
                context.contentResolver.openInputStream(apkUri).use { input ->
                    requireNotNull(input) { context.getString(R.string.error_cannot_open_apk) }
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
            postError(listener, context.getString(R.string.error_install_failed, e.message ?: ""))
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

        PackageUninstaller.request(context, packageName, packageName)
    }

    private fun postFinishInstallNotification(context: Context, apkUri: Uri) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                context.getString(R.string.update_channel_name),
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
            .setContentTitle(context.getString(R.string.finish_install_title))
            .setContentText(context.getString(R.string.finish_install_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()

        notificationManager.notify(apkUri.hashCode(), notification)
    }
}
