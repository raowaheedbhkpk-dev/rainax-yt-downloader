package rainax.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Pictures from the internet, kept in memory (the last 300). */
object ImageCache {
    private val map = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }

    @Synchronized fun get(url: String): ImageBitmap? = map[url]
    @Synchronized fun put(url: String, img: ImageBitmap) { map[url] = img }

    fun load(url: String): ImageBitmap? {
        get(url)?.let { return it }
        return runCatching {
            val con = URL(url).openConnection() as HttpURLConnection
            con.connectTimeout = 10_000
            con.readTimeout = 15_000
            con.setRequestProperty("User-Agent", Yt.UA)
            val bytes = con.inputStream.use { it.readBytes() }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { put(url, it) }
        }.getOrNull()
    }
}

/** A picture from [url], grey until it arrives. */
@Composable
fun NetImage(url: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val p = LocalPal.current
    val img by produceState(initialValue = url?.let { ImageCache.get(it) }, url) {
        if (url != null && value == null) value = withContext(Dispatchers.IO) { ImageCache.load(url) }
    }
    Box(modifier.background(p.soft)) {
        img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = contentScale) }
    }
}
