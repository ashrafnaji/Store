package com.ashrafnaji.store.catalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object AppIconLoader {

    private const val MAX_ICON_BYTES = 5 * 1024 * 1024
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(url: String, onResult: (Bitmap?) -> Unit) {
        cache[url]?.let {
            onResult(it)
            return
        }

        Thread {
            val bitmap = fetch(url)
            if (bitmap != null) cache[url] = bitmap
            mainHandler.post { onResult(bitmap) }
        }.start()
    }

    private fun fetch(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val declaredSize = conn.contentLengthLong
            if (declaredSize > MAX_ICON_BYTES) return null
            val bytes = conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ICON_BYTES) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_ICON_BYTES) return null
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            conn.disconnect()
        }
    }
}
