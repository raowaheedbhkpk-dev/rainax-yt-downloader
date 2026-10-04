package com.rainax.ytdownloader

import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * TikTok and other social sites (Facebook, Instagram, X/Twitter, Dailymotion, Vimeo pages, direct video links...).
 * TikTok has its own reader (no watermark when TikTok offers it); other sites are read from the video data
 * their pages carry (the same data the site's own player uses). YouTube, SoundCloud, Bandcamp and PeerTube
 * stay with [FastExtractor].
 */
object SocialExtractor {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** One downloadable file. */
    class Variant(val url: String, val height: Int, val size: Long, val ext: String, val audioOnly: Boolean = false)

    class Media(
        val title: String,
        val uploader: String?,
        val thumb: String?,
        val seconds: Int,
        val variants: List<Variant>
    )

    private class Cached(val at: Long, val media: Media)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** Request headers a file needs (TikTok wants its cookies and Referer). Keyed by file address. */
    private val fileHeaders = ConcurrentHashMap<String, Map<String, String>>()

    fun headersFor(url: String): Map<String, String> = fileHeaders[url] ?: emptyMap()

    /** True for links this reader handles (everything web that isn't YouTube or another NewPipe site). */
    fun handles(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.startsWith("http")) return false
        if (u.contains("youtube.com/") || u.contains("youtu.be/")) return false
        return try {
            FastExtractor.init()
            NewPipe.getServiceByUrl(url)
            false                                  // SoundCloud, Bandcamp, PeerTube...: NewPipe reads these
        } catch (e: Exception) {
            true
        }
    }

    private fun isTikTok(url: String) = url.lowercase().let { it.contains("tiktok.com") }

    // ---------- download sheet ----------

    /** Blocking. Title, picture and qualities for the download sheet. */
    fun fetch(url: String): PreviewState {
        val m = media(url)
        val video = m.variants.filter { !it.audioOnly }
        val audio = m.variants.firstOrNull { it.audioOnly }
        val choices = mutableListOf<FormatChoice>()
        if (audio != null) {
            choices += FormatChoice("audio:${audio.ext}", "Audio (${audio.ext.uppercase()})", "Sound only", sizeText(audio.size), KIND_AUDIO)
        }
        video.sortedByDescending { it.height }.forEachIndexed { i, v ->
            val title = when {
                v.height >= 1080 -> "Full HD (${v.height}p)"
                v.height >= 720 -> "HD (${v.height}p)"
                v.height > 0 -> "Video (${v.height}p)"
                else -> if (i == 0) "Best quality" else "Video"
            }
            choices += FormatChoice("video:${v.height.coerceAtLeast(0)}:$i", title,
                if (isTikTok(url)) "No watermark, MP4" else v.ext.uppercase(), sizeText(v.size), KIND_VIDEO)
        }
        if (choices.isEmpty()) error("No video found on this page")
        return PreviewState(
            title = m.title,
            subtitle = listOfNotNull(m.uploader?.takeIf { it.isNotBlank() }, duration(m.seconds)).joinToString("  •  "),
            thumb = m.thumb?.let { InfoFetcher.loadBitmap(it) },
            quick = choices,
            all = choices,
            thumbUrl = m.thumb,
            uploader = m.uploader
        )
    }

    /** Blocking. The file for a choice from [fetch] ("video:720:0", "audio:mp3"). */
    fun plan(url: String, spec: String): FastExtractor.Plan {
        cache.remove(key(url))                     // fresh addresses (they expire) and fresh cookies
        val m = media(url)
        val pick = if (spec.startsWith("audio")) {
            m.variants.firstOrNull { it.audioOnly } ?: m.variants.firstOrNull() ?: error("No audio found")
        } else {
            val video = m.variants.filter { !it.audioOnly }.sortedByDescending { it.height }
            val index = spec.split(':').getOrNull(2)?.toIntOrNull() ?: 0
            video.getOrNull(index) ?: video.firstOrNull() ?: error("No video found on this page")
        }
        return FastExtractor.Plan(
            m.title, m.thumb, null, null, FastExtractor.Part(pick.url, pick.size, pick.ext), false, null
        )
    }

    // ---------- reading pages ----------

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun media(url: String): Media {
        cache[key(url)]?.let { if (System.currentTimeMillis() - it.at < 10 * 60_000) return it.media }
        val m = if (isTikTok(url)) tiktok(url) else generic(url)
        cache[key(url)] = Cached(System.currentTimeMillis(), m)
        return m
    }

    private class PageResult(val html: String, val finalUrl: String, val cookies: String)

    private fun load(url: String, extra: Map<String, String> = emptyMap()): PageResult {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            extra.forEach { (k, v) -> con.setRequestProperty(k, v) }
            val code = con.responseCode
            if (code == 404) error("This video was removed or the link is wrong")
            if (code >= 400) error("The site refused the request (error $code). The video may be private")
            val cookies = con.headerFields.entries
                .filter { it.key.equals("Set-Cookie", true) }
                .flatMap { it.value }
                .joinToString("; ") { it.substringBefore(';') }
            val html = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return PageResult(html, con.url.toString(), cookies)
        } finally {
            con.disconnect()
        }
    }

    // ---------- TikTok ----------

    private fun tiktok(url: String): Media {
        val page = load(url, mapOf("Referer" to "https://www.tiktok.com/"))
        val item = tiktokItem(page.html) ?: error("Couldn't read this TikTok. It may be private or removed")
        val video = item.optJSONObject("video") ?: error("This TikTok has no video")
        val author = item.optJSONObject("author")
        val headers = mapOf(
            "Referer" to "https://www.tiktok.com/",
            "Cookie" to page.cookies,
            "User-Agent" to UA
        )
        val variants = mutableListOf<Variant>()
        // every quality TikTok offers (no watermark)
        val rates = video.optJSONArray("bitrateInfo")
        if (rates != null) for (i in 0 until rates.length()) {
            val r = rates.optJSONObject(i) ?: continue
            val play = r.optJSONObject("PlayAddr") ?: continue
            val list = play.optJSONArray("UrlList") ?: continue
            val u = (0 until list.length()).map { list.optString(it) }.firstOrNull { it.startsWith("http") } ?: continue
            val h = play.optInt("Height", 0).takeIf { it > 0 } ?: video.optInt("height", 0)
            val w = play.optInt("Width", 0)
            variants += Variant(u, minOf(h, if (w > 0) w else h).takeIf { it > 0 } ?: h, play.optLong("DataSize", 0), "mp4")
        }
        if (variants.isEmpty()) {
            listOf(video.optString("playAddr"), video.optString("downloadAddr")).firstOrNull { it.startsWith("http") }?.let {
                variants += Variant(it, video.optInt("height", 0).coerceAtMost(video.optInt("width", 0).takeIf { w -> w > 0 } ?: Int.MAX_VALUE), 0, "mp4")
            }
        }
        // one file per quality, the biggest (best) of each
        val best = variants.groupBy { it.height }.mapNotNull { (_, l) -> l.maxByOrNull { it.size } }.toMutableList()
        item.optJSONObject("music")?.optString("playUrl")?.takeIf { it.startsWith("http") }?.let {
            best += Variant(it, 0, 0, "mp3", audioOnly = true)
        }
        if (best.none { !it.audioOnly }) error("TikTok didn't give a video for this link. Try again")
        best.forEach { fileHeaders[it.url] = headers }
        val desc = item.optString("desc").ifBlank { "TikTok video" }
        return Media(
            title = desc.take(120),
            uploader = author?.optString("nickname")?.ifBlank { author.optString("uniqueId") },
            thumb = listOf(video.optString("originCover"), video.optString("cover"), video.optString("dynamicCover"))
                .firstOrNull { it.startsWith("http") },
            seconds = video.optInt("duration", 0),
            variants = best
        )
    }

    /** The video's data from TikTok's page (two page formats are in use). */
    private fun tiktokItem(html: String): JSONObject? {
        scriptJson(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")?.let { root ->
            root.optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail")
                ?.optJSONObject("itemInfo")?.optJSONObject("itemStruct")?.let { return it }
        }
        scriptJson(html, "SIGI_STATE")?.let { root ->
            val module = root.optJSONObject("ItemModule") ?: return null
            val k = module.keys()
            if (k.hasNext()) return module.optJSONObject(k.next())
        }
        return null
    }

    private fun scriptJson(html: String, id: String): JSONObject? {
        val m = Regex("<script[^>]*id=\"$id\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        return runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
    }

    // ---------- other sites ----------

    private val DIRECT = Regex("\\.(mp4|webm|m4v|mov|mp3|m4a)(\\?|$)", RegexOption.IGNORE_CASE)

    private fun generic(url: String): Media {
        // a direct file link
        if (DIRECT.containsMatchIn(url.substringBefore('#'))) {
            val ext = DIRECT.find(url)!!.groupValues[1].lowercase()
            val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Video" }
            return Media(name, null, null, 0, listOf(Variant(url, 0, sizeOf(url), ext, ext == "mp3" || ext == "m4a")))
        }
        val page = load(url)
        val html = page.html
        val found = LinkedHashMap<String, Int>()       // file -> height (0 unknown)
        fun add(raw: String?, height: Int = 0) {
            val u = unescape(raw ?: return).trim()
            if (u.startsWith("http") && !found.containsKey(u)) found[u] = height
        }
        // Facebook / Instagram player data
        for ((key, h) in listOf(
            "browser_native_hd_url" to 720, "playable_url_quality_hd" to 720, "hd_src" to 720,
            "browser_native_sd_url" to 360, "playable_url" to 360, "sd_src" to 360, "video_url" to 0, "contentUrl" to 0
        )) {
            Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").findAll(html).forEach { add(it.groupValues[1], h) }
        }
        // page tags used by most sites
        for (prop in listOf("og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream")) {
            meta(html, prop)?.let { if (DIRECT.containsMatchIn(it) || it.contains("video", true)) add(it) }
        }
        Regex("<video[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.groupValues[1]) }
        Regex("<source[^>]+src=\"([^\"]+)\"", RegexOption.IGNORE_CASE).findAll(html).forEach { add(it.groupValues[1]) }
        if (found.isEmpty()) {
            Regex("https?:[^\"'\\s<>]+?\\.mp4[^\"'\\s<>]*").findAll(html).take(5).forEach { add(it.value) }
        }
        val files = found.entries.filter { (u, _) -> !u.contains(".m3u8") }.take(4)
        if (files.isEmpty()) error("No downloadable video on this page. It may be private or need a login")
        val headers = mapOf("Referer" to page.finalUrl, "User-Agent" to UA)
        val variants = files.mapIndexed { i, (u, h) ->
            fileHeaders[u] = headers
            val ext = DIRECT.find(u)?.groupValues?.get(1)?.lowercase() ?: "mp4"
            Variant(u, if (h > 0) h else (if (i == 0) 0 else -1), if (i < 2) sizeOf(u) else 0, ext)
        }
        val title = meta(html, "og:title") ?: Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        return Media(
            title = decodeEntities(title?.trim().orEmpty()).ifBlank { "Video" }.take(150),
            uploader = meta(html, "og:site_name"),
            thumb = meta(html, "og:image")?.let { decodeEntities(it) },
            seconds = 0,
            variants = variants
        )
    }

    private fun meta(html: String, prop: String): String? {
        val p = Regex.escape(prop)
        return Regex("<meta[^>]+(?:property|name)=\"$p\"[^>]+content=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            ?: Regex("<meta[^>]+content=\"([^\"]*)\"[^>]+(?:property|name)=\"$p\"", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
    }

    /** JSON string escapes as they appear inside page data. */
    private fun unescape(s: String): String {
        var t = s.replace("\\/", "/")
        t = Regex("\\\\u([0-9a-fA-F]{4})").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        return decodeEntities(t)
    }

    private fun decodeEntities(s: String) =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** File size from the server (0 if it doesn't say). */
    private fun sizeOf(url: String): Long {
        var con: HttpURLConnection? = null
        return try {
            con = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Range", "bytes=0-0")
                headersFor(url).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            con.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                ?: con.contentLengthLong.takeIf { it > 1 } ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            con?.disconnect()
        }
    }

    private fun sizeText(bytes: Long) = if (bytes > 0) formatSize(bytes) else ""

    private fun duration(s: Int): String? = if (s <= 0) null else "%d:%02d".format(s / 60, s % 60)

}
