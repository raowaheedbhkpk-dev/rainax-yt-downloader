package com.rainax.ytdownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small picture loader for the lists (no extra library): memory cache, 6 downloads at a time, scaled to fit. */
object Img {

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val io = Executors.newFixedThreadPool(6).asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Shows [url] in [view] ([circle] for channel pictures). Safe in recycled list rows. */
    fun load(view: ImageView, url: String?, circle: Boolean = false, widthPx: Int = 640) {
        (view.getTag(R.id.img_job) as? Job)?.cancel()
        view.setTag(R.id.img_url, url)
        if (url.isNullOrBlank()) {
            view.setImageDrawable(null)
            return
        }
        cache.get(url)?.let { show(view, it, circle); return }
        view.setImageDrawable(null)
        val job = scope.launch {
            val bmp = withContext(io) { fetch(url, widthPx) } ?: return@launch
            cache.put(url, bmp)
            if (view.getTag(R.id.img_url) == url) show(view, bmp, circle)
        }
        view.setTag(R.id.img_job, job)
    }

    private fun show(view: ImageView, bmp: Bitmap, circle: Boolean) {
        if (circle) {
            view.setImageDrawable(RoundedBitmapDrawableFactory.create(view.resources, bmp).apply { isCircular = true })
        } else {
            view.setImageBitmap(bmp)
        }
    }

    private fun fetch(url: String, widthPx: Int): Bitmap? {
        var con: HttpURLConnection? = null
        return try {
            con = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", FastExtractor.UA)
            }
            val bytes = con.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } finally {
            con?.disconnect()
        }
    }
}
