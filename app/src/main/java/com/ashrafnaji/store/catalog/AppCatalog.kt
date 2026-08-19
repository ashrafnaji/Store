package com.ashrafnaji.store.catalog

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ashrafnaji.store.BuildConfig
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * One entry in the store's app list.
 *
 * `type = "self"` means this app (Store) — its APK is resolved dynamically via the existing
 * GitHub "latest release" + device-ABI lookup in [com.ashrafnaji.store.update.UpdateManager].
 * `type = "static"` means a fixed, pre-uploaded APK asset. [downloadUrls] maps ABI (e.g.
 * "arm64-v8a") to that architecture's asset URL, for apps published per-architecture; [downloadUrl]
 * is a single fallback URL used when no per-ABI match is found (or for a universal APK).
 */
data class CatalogItem(
    val id: String,
    val name: String,
    val packageName: String,
    val type: String,
    val version: String?,
    val downloadUrl: String?,
    val downloadUrls: Map<String, String>,
    val description: String?
) {
    /**
     * Picks the download URL for this device's ABI, or null if this app has no build for it.
     *
     * [downloadUrl] is only a fallback for entries with no [downloadUrls] map at all (a
     * universal APK, or an old-style catalog entry) -- once an entry lists specific
     * architectures, a device whose ABI isn't in that list must not match, even if
     * [downloadUrl] happens to be set (the admin panel always sets it to one of the uploaded
     * per-ABI assets for backward compatibility, so treating it as a blanket fallback here
     * would offer e.g. an x86-only build to an arm64 device).
     */
    fun resolveDownloadUrl(): String? {
        if (downloadUrls.isEmpty()) return downloadUrl
        for (abi in Build.SUPPORTED_ABIS) {
            downloadUrls[abi]?.let { return it }
        }
        return null
    }
}

/**
 * Fetches `catalog.json` from this repo's default branch and lists every app the Store should
 * offer. Edit that file on GitHub to add/remove apps — no app update needed to change the list.
 */
object CatalogFetcher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/main/catalog.json"

    fun fetch(onResult: (List<CatalogItem>?) -> Unit) {
        Thread {
            val items = try {
                val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                try {
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        null
                    } else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parse(body)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                null
            }
            mainHandler.post { onResult(items) }
        }.start()
    }

    private fun parse(body: String): List<CatalogItem> {
        val array = JSONArray(body)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val urlsObj = o.optJSONObject("downloadUrls")
            val urls = if (urlsObj == null) emptyMap() else {
                urlsObj.keys().asSequence().associateWith { urlsObj.getString(it) }
            }
            CatalogItem(
                id = o.getString("id"),
                name = o.getString("name"),
                packageName = o.getString("packageName"),
                type = o.optString("type", "static"),
                version = o.optString("version").ifBlank { null },
                downloadUrl = o.optString("downloadUrl").ifBlank { null },
                downloadUrls = urls,
                description = o.optString("description").ifBlank { null }
            )
        }
    }
}
