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
    private val planned = ConcurrentHashMap<String, Long>()

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

    /** An X (Twitter) post: x.com / twitter.com /…/status/<id>. */
    private fun xPostId(url: String): String? {
        val u = url.lowercase()
        val host = runCatching { java.net.URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        val isX = host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") ||
            host.endsWith("fxtwitter.com") || host.endsWith("vxtwitter.com") || host.endsWith("fixupx.com")
        if (!isX) return null
        return Regex("/status(?:es)?/(\\d{1,25})").find(u)?.groupValues?.get(1)
    }

    // ---------- download sheet ----------

    /** Blocking. Title, picture and qualities for the download sheet. */
    fun fetch(url: String): PreviewState {
        val m = media(url)
        val video = m.variants.filter { !it.audioOnly }
        val audio = m.variants.firstOrNull { it.audioOnly }
        val choices = mutableListOf<FormatChoice>()
        if (audio != null) {
            choices += FormatChoice("audio:${audio.ext}", "Audio (${audio.ext.uppercase()})", "Sound only", sizeText(audio.size), KIND_AUDIO, bytes = audio.size.coerceAtLeast(0))
        }
        video.sortedByDescending { it.height }.forEachIndexed { i, v ->
            val title = when {
                v.height >= 1080 -> "Full HD (${v.height}p)"
                v.height >= 720 -> "HD (${v.height}p)"
                v.height > 0 -> "Video (${v.height}p)"
                else -> if (i == 0) "Best quality" else "Video"
            }
            choices += FormatChoice("video:${v.height.coerceAtLeast(0)}:$i", title,
                if (isTikTok(url)) "No watermark, MP4" else v.ext.uppercase(), sizeText(v.size), KIND_VIDEO, bytes = v.size.coerceAtLeast(0))
        }
        if (choices.isEmpty()) error("No video found on this page")
        return PreviewState(
            title = m.title,
            subtitle = listOfNotNull(m.uploader?.takeIf { it.isNotBlank() }, duration(m.seconds)).joinToString("  •  "),
            quick = choices,
            all = choices,
            thumbUrl = m.thumb,
            uploader = m.uploader
        )
    }

    /** Blocking. The file for a choice from [fetch] ("video:720:0", "audio:mp3"). */
    fun plan(url: String, spec: String): FastExtractor.Plan {
        cache.remove(key(url))                     // fresh addresses (they expire) and fresh cookies
        // a TikTok asked again within minutes = its file was refused: this time use the second reader first
        val now = System.currentTimeMillis()
        val retry = isTikTok(url) && (planned[key(url)]?.let { now - it < 15 * 60_000 } == true)
        planned[key(url)] = now
        val m = (if (retry) tikwm(url) else null) ?: media(url)
        val pick = if (spec.startsWith("audio")) {
            m.variants.firstOrNull { it.audioOnly } ?: m.variants.firstOrNull() ?: error("No audio found")
        } else {
            val video = m.variants.filter { !it.audioOnly }.sortedByDescending { it.height }
            val index = spec.split(':').getOrNull(2)?.toIntOrNull() ?: 0
            val h = spec.split(':').getOrNull(1)?.toIntOrNull()
            // the quality you picked (by its height; the list may be ordered differently on this new read)
            video.firstOrNull { h != null && h > 0 && it.height == h }
                ?: video.getOrNull(index) ?: video.firstOrNull() ?: error("No video found on this page")
        }
        return FastExtractor.Plan(
            m.title, m.thumb, null, null, FastExtractor.Part(pick.url, pick.size, pick.ext), false, null
        )
    }

    // ---------- reading pages ----------

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun media(url: String): Media {
        cache[key(url)]?.let { if (System.currentTimeMillis() - it.at < 10 * 60_000) return it.media }
        val m = when {
            isTikTok(url) -> tiktok(url)
            isFacebook(url) -> facebook(url)
            instagramCode(url) != null -> instagram(url, instagramCode(url)!!)
            xPostId(url) != null -> xPost(xPostId(url)!!)
            else -> generic(url)
        }
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
            // what a real browser sends when opening a page (Facebook answers "400" without them)
            con.setRequestProperty("Sec-Fetch-Mode", "navigate")
            con.setRequestProperty("Sec-Fetch-Dest", "document")
            con.setRequestProperty("Sec-Fetch-Site", "none")
            con.setRequestProperty("Sec-Fetch-User", "?1")
            con.setRequestProperty("Upgrade-Insecure-Requests", "1")
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

    /**
     * TikTok. Its page often has no video data on the first visit (it first hands out cookies and checks
     * the visitor), so the page is asked up to 3 times with the cookies it gave. If the page still has no
     * video (some videos whose owner turned off downloads, region or bot checks), a second reader is used.
     */
    private fun tiktok(url: String): Media {
        var cookies = ""
        var target = url
        var status = 0
        for (attempt in 0 until 3) {
            val page = runCatching {
                load(target, mapOf("Referer" to "https://www.tiktok.com/") +
                    (if (cookies.isNotEmpty()) mapOf("Cookie" to cookies) else emptyMap()))
            }.getOrNull()
            if (page != null) {
                cookies = mergeCookies(cookies, page.cookies)
                // a short link (vt.tiktok.com, vm.tiktok.com): continue with the full video address
                if (page.finalUrl.contains("/video/") || page.finalUrl.contains("/photo/")) target = page.finalUrl.substringBefore('?')
                val item = tiktokItem(page.html)
                status = tiktokStatus(page.html)
                if (item != null) fromItem(item, cookies)?.let { return it }
            }
            if (attempt < 2) Thread.sleep(500L * (attempt + 1))
        }
        tikwm(target.ifBlank { url })?.let { return it }
        error(
            if (status == 10216 || status == 10222) "This TikTok is private"
            else if (!Net.online()) NO_INTERNET
            else "Couldn't read this TikTok. It may be removed or private. Check the link and try again"
        )
    }

    private fun mergeCookies(old: String, new: String): String {
        val map = LinkedHashMap<String, String>()
        (old.split(';') + new.split(';')).map { it.trim() }.filter { it.contains('=') }.forEach {
            map[it.substringBefore('=')] = it.substringAfter('=')
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** TikTok's own answer code for the video (0 = fine, 10204 = removed, 10216/10222 = private). */
    private fun tiktokStatus(html: String): Int =
        scriptJson(html, "__UNIVERSAL_DATA_FOR_REHYDRATION__")?.optJSONObject("__DEFAULT_SCOPE__")
            ?.optJSONObject("webapp.video-detail")?.optInt("statusCode", 0) ?: 0

    /** The video's files from TikTok's page data; null when the page gave none. */
    private fun fromItem(item: JSONObject, cookies: String): Media? {
        val video = item.optJSONObject("video") ?: return null
        val author = item.optJSONObject("author")
        val headers = mapOf(
            "Referer" to "https://www.tiktok.com/",
            "Cookie" to cookies,
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
            // (downloadAddr is empty when the owner turned off downloads: playAddr is what the app plays)
            listOf(video.optString("playAddr"), video.optString("downloadAddr")).firstOrNull { it.startsWith("http") }?.let {
                variants += Variant(it, video.optInt("height", 0).coerceAtMost(video.optInt("width", 0).takeIf { w -> w > 0 } ?: Int.MAX_VALUE), 0, "mp4")
            }
        }
        if (variants.isEmpty()) return null
        // one file per quality, the biggest (best) of each
        val best = variants.groupBy { it.height }.mapNotNull { (_, l) -> l.maxByOrNull { it.size } }.toMutableList()
        item.optJSONObject("music")?.optString("playUrl")?.takeIf { it.startsWith("http") }?.let {
            best += Variant(it, 0, 0, "mp3", audioOnly = true)
        }
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

    /**
     * Second reader (tikwm.com, a free public TikTok service): gives the no-watermark file even for videos
     * whose page has no video data, e.g. when the owner turned off downloads.
     */
    private fun tikwm(url: String): Media? = runCatching {
        val api = "https://www.tikwm.com/api/?hd=1&url=" + java.net.URLEncoder.encode(url, "UTF-8")
        val con = URL(api).openConnection() as HttpURLConnection
        val text = try {
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "application/json")
            if (con.responseCode != 200) return@runCatching null
            con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
        val root = JSONObject(text)
        if (root.optInt("code", -1) != 0) return@runCatching null
        val d = root.optJSONObject("data") ?: return@runCatching null
        fun abs(u: String?) = when {
            u.isNullOrBlank() -> null
            u.startsWith("http") -> u
            u.startsWith("/") -> "https://www.tikwm.com$u"
            else -> null
        }
        val headers = mapOf("User-Agent" to UA)
        val variants = mutableListOf<Variant>()
        // (heights unknown: the HD file is listed first as "Best quality")
        abs(d.optString("hdplay"))?.let { variants += Variant(it, 0, d.optLong("hd_size", 0), "mp4") }
        abs(d.optString("play"))?.let { if (variants.none { v -> v.url == it }) variants += Variant(it, -1, d.optLong("size", 0), "mp4") }
        if (variants.isEmpty()) return@runCatching null
        abs(d.optString("music"))?.let { variants += Variant(it, 0, 0, "mp3", audioOnly = true) }
        variants.forEach { fileHeaders[it.url] = headers }
        val author = d.optJSONObject("author")
        Media(
            title = d.optString("title").ifBlank { "TikTok video" }.take(120),
            uploader = author?.optString("nickname")?.ifBlank { author.optString("unique_id") },
            thumb = abs(d.optString("origin_cover")) ?: abs(d.optString("cover")),
            seconds = d.optInt("duration", 0),
            variants = variants
        )
    }.getOrNull()

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

    // ---------- X (Twitter) ----------

    /**
     * An X post's video, from X's own public embed data (what embedded posts on websites use): every MP4
     * quality X made, best first. Posts that need a login (age-limited, protected accounts) can't be read.
     */
    private fun xPost(id: String): Media {
        val url = "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${xToken(id)}"
        val con = URL(url).openConnection() as HttpURLConnection
        val text = try {
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "application/json")
            val code = con.responseCode
            if (code == 404) error("This post was removed, or its account is private")
            if (code >= 400) error("X refused the request (error $code). Try again in a minute")
            con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
        val json = runCatching { JSONObject(text) }.getOrNull() ?: error("X sent something unexpected. Try again")
        if (json.optString("__typename") == "TweetTombstone" || json.has("tombstone")) {
            error("This post can't be shown without signing in to X (age limit or private account)")
        }
        val media = json.optJSONArray("mediaDetails")
        var video: JSONObject? = null
        for (i in 0 until (media?.length() ?: 0)) {
            val m = media!!.optJSONObject(i) ?: continue
            if (m.optString("type") == "video" || m.optString("type") == "animated_gif") { video = m; break }
        }
        video ?: error("This post has no video")
        val info = video.optJSONObject("video_info")
        val list = info?.optJSONArray("variants")
        val files = mutableListOf<Pair<Variant, Int>>()
        for (i in 0 until (list?.length() ?: 0)) {
            val v = list!!.optJSONObject(i) ?: continue
            val link = v.optString("url")
            if (!link.startsWith("https://") || !v.optString("content_type").contains("mp4")) continue
            // the picture size is in the address: …/vid/avc1/1280x720/…
            val height = Regex("/(\\d{2,4})x(\\d{2,4})/").find(link)?.groupValues?.get(2)?.toIntOrNull() ?: 0
            files += Variant(link, height, 0, "mp4") to v.optInt("bitrate")
        }
        if (files.isEmpty()) error("No downloadable video in this post")
        val sorted = files.sortedWith(compareByDescending<Pair<Variant, Int>> { it.first.height }.thenByDescending { it.second })
            .map { it.first }.distinctBy { it.height.takeIf { h -> h > 0 } ?: it.url }
        val variants = sorted.mapIndexed { i, v -> if (i < 2) Variant(v.url, v.height, sizeOf(v.url), v.ext) else v }
        val user = json.optJSONObject("user")
        val words = json.optString("text").replace(Regex("https://t\\.co/\\S+"), "").replace(Regex("\\s+"), " ").trim()
        return Media(
            title = words.take(100).ifBlank { user?.optString("name")?.let { "Video by $it" } ?: "X video" },
            uploader = user?.optString("screen_name")?.takeIf { it.isNotBlank() }?.let { "@$it" },
            thumb = video.optString("media_url_https").ifBlank { null },
            seconds = ((info?.optLong("duration_millis") ?: 0L) / 1000).toInt(),
            variants = variants
        )
    }

    /** The number X's embed data asks for: ((id / 1e15) × π) written in base 36 like JavaScript, without 0s and the dot. */
    internal fun xToken(id: String): String = jsRadix(id.toDouble() / 1e15 * Math.PI, 36).replace(Regex("(0+|\\.)"), "")

    /** JavaScript's Number.toString(radix) for a small positive number (the same digits and rounding as Chrome). */
    private fun jsRadix(v0: Double, radix: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        val value = kotlin.math.abs(v0)
        var integer = Math.floor(value)
        var fraction = value - integer
        var delta = maxOf(Double.MIN_VALUE, 0.5 * (Math.nextUp(value) - value))
        val frac = StringBuilder()
        if (fraction >= delta) {
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                frac.append(chars[digit])
                fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // round up, carrying into the digits before
                        while (true) {
                            if (frac.isEmpty()) { integer += 1; break }
                            val c = frac[frac.length - 1]
                            frac.setLength(frac.length - 1)
                            val d = if (c > '9') c - 'a' + 10 else c - '0'
                            if (d + 1 < radix) { frac.append(chars[d + 1]); break }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        val whole = StringBuilder()
        do {
            val rem = integer % radix
            whole.append(chars[rem.toInt()])
            integer = (integer - rem) / radix
        } while (integer > 0)
        return whole.reverse().toString() + if (frac.isNotEmpty()) ".$frac" else ""
    }

    // ---------- other sites ----------

    private val DIRECT = Regex("\\.(mp4|webm|m4v|mov|mp3|m4a)(\\?|$)", RegexOption.IGNORE_CASE)

    /** The post's short code in an Instagram link (/p/…, /reel/…, /reels/…, /tv/…), or null. */
    private fun instagramCode(url: String): String? {
        val host = runCatching { java.net.URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        if (host != "instagram.com" && !host.endsWith(".instagram.com") && host != "instagr.am") return null
        return Regex("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]{5,})").find(url)?.groupValues?.get(1)
    }

    /**
     * Instagram posts and Reels (public accounts). Its normal page has no video for visitors, so the post is read
     * like Instagram's own website reads it (its web data request), then from its embed page (what websites show).
     */
    private fun instagram(url: String, code: String): Media {
        var last: Exception? = null
        try {
            instagramData(code)?.let { return it }
        } catch (e: Exception) { last = e }
        try {
            instagramEmbed(code)?.let { return it }
        } catch (e: Exception) { last = e }
        try {
            return generic(url)
        } catch (e: Exception) { last = e }
        throw IllegalStateException(
            "Couldn't read this Instagram post. Only posts and Reels from public accounts can be saved.", last
        )
    }

    private const val IG_APP_ID = "936619743392459"

    /** Instagram's own web data request for a post (works for public posts without signing in). */
    private fun instagramData(code: String): Media? {
        // a visit first: Instagram hands out the csrftoken cookie its data requests need
        val visit = runCatching { load("https://www.instagram.com/p/$code/") }.getOrNull()
        val cookies = visit?.cookies.orEmpty()
        val csrf = Regex("csrftoken=([^;\\s]+)").find(cookies)?.groupValues?.get(1)
            ?: java.util.UUID.randomUUID().toString().replace("-", "")
        val variables = JSONObject().put("shortcode", code).put("fetch_tagged_user_count", JSONObject.NULL)
            .put("hoisted_comment_id", JSONObject.NULL).put("hoisted_reply_id", JSONObject.NULL).toString()
        val body = "variables=" + java.net.URLEncoder.encode(variables, "UTF-8") + "&doc_id=8845758582119845"
        val con = URL("https://www.instagram.com/graphql/query/").openConnection() as HttpURLConnection
        val text = try {
            con.requestMethod = "POST"
            con.doOutput = true
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Accept", "*/*")
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            con.setRequestProperty("X-IG-App-ID", IG_APP_ID)
            con.setRequestProperty("X-ASBD-ID", "129477")
            con.setRequestProperty("X-IG-WWW-Claim", "0")
            con.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            con.setRequestProperty("X-CSRFToken", csrf)
            con.setRequestProperty("Origin", "https://www.instagram.com")
            con.setRequestProperty("Referer", "https://www.instagram.com/p/$code/")
            con.setRequestProperty("Cookie", if (cookies.contains("csrftoken=")) cookies else "csrftoken=$csrf; $cookies")
            con.outputStream.use { it.write(body.toByteArray()) }
            if (con.responseCode != 200) return null
            con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
        val data = runCatching { JSONObject(text).optJSONObject("data") }.getOrNull() ?: return null
        val media = data.optJSONObject("xdt_shortcode_media") ?: data.optJSONObject("shortcode_media") ?: return null
        // a single video, or the videos of a multi-post (carousel)
        val nodes = mutableListOf<JSONObject>()
        val children = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (children != null) for (i in 0 until children.length()) children.optJSONObject(i)?.optJSONObject("node")?.let { nodes += it }
        else nodes += media
        val videos = nodes.filter { it.optBoolean("is_video") && it.optString("video_url").startsWith("http") }
        if (videos.isEmpty()) return null
        val headers = mapOf("Referer" to "https://www.instagram.com/", "User-Agent" to UA)
        val variants = videos.mapIndexed { i, v ->
            val link = v.optString("video_url")
            fileHeaders[link] = headers
            val h = v.optJSONObject("dimensions")?.optInt("height") ?: 0
            Variant(link, if (videos.size > 1) -1 - i else h, if (i < 2) sizeOf(link) else 0, "mp4")
        }
        val caption = media.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)
            ?.optJSONObject("node")?.optString("text").orEmpty().replace(Regex("\\s+"), " ").trim()
        val user = media.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }
        return Media(
            title = caption.take(100).ifBlank { user?.let { "Instagram video by @$it" } ?: "Instagram video" },
            uploader = user?.let { "@$it" },
            thumb = media.optString("display_url").ifBlank { null },
            seconds = media.optDouble("video_duration", 0.0).toInt(),
            variants = variants
        )
    }

    /** Instagram's embed page for a post: carries the video address for public posts. */
    private fun instagramEmbed(code: String): Media? {
        val page = load("https://www.instagram.com/p/$code/embed/captioned/", mapOf("Referer" to "https://www.instagram.com/"))
        // the address sits in page data, sometimes escaped twice (\\/ and \\u0026)
        var html = page.html
        repeat(2) { html = html.replace("\\\\", "\\") }
        val raw = Regex("""\\?"video_url\\?"\s*:\s*\\?"(https?:.*?)\\?"\s*[,}]""").find(html)?.groupValues?.get(1)
            ?: return null
        val link = unescape(unescape(raw)).replace("\\u0026", "&")
        if (!link.startsWith("http")) return null
        fileHeaders[link] = mapOf("Referer" to "https://www.instagram.com/", "User-Agent" to UA)
        val user = Regex("""\\?"username\\?"\s*:\s*\\?"([A-Za-z0-9._]+)""").find(html)?.groupValues?.get(1)
        val thumb = Regex("class=\"EmbeddedMediaImage\"[^>]*src=\"([^\"]+)\"").find(page.html)?.groupValues?.get(1)?.let { decodeEntities(it) }
        return Media(
            title = user?.let { "Instagram video by @$it" } ?: "Instagram video",
            uploader = user?.let { "@$it" },
            thumb = thumb,
            seconds = 0,
            variants = listOf(Variant(link, 0, sizeOf(link), "mp4"))
        )
    }

    private fun isFacebook(url: String): Boolean {
        val host = runCatching { java.net.URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "fb.watch" || host == "fb.com" || host.endsWith(".fb.com") ||
            host == "facebook.com" || host.endsWith(".facebook.com")
    }

    /** Follows a link's redirects (share/v/…, fb.watch/…) to the real page address, without reading the page. */
    private fun resolve(url: String): String {
        var u = url.trim()
        repeat(6) {
            val con = URL(u).openConnection() as HttpURLConnection
            try {
                con.instanceFollowRedirects = false
                con.connectTimeout = 12_000
                con.readTimeout = 12_000
                con.setRequestProperty("User-Agent", UA)
                con.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                con.setRequestProperty("Sec-Fetch-Mode", "navigate")
                val code = con.responseCode
                val loc = con.getHeaderField("Location")
                if (code !in 300..399 || loc.isNullOrBlank()) return u
                u = URL(URL(u), loc).toString()
                if (u.contains("/login") || u.contains("checkpoint")) return url    // a login page: keep the link
            } finally {
                con.disconnect()
            }
        }
        return u
    }

    /**
     * Facebook videos and Reels (public ones). Share links are followed to the real video first; the page is read
     * as a computer browser, then as a phone browser, then through Facebook's own embedded player
     * (which shows public videos without signing in).
     */
    private fun facebook(url: String): Media {
        val target = runCatching { resolve(url) }.getOrDefault(url).let { u ->
            u.replace("://m.facebook.com/", "://www.facebook.com/").replace("://web.facebook.com/", "://www.facebook.com/")
        }
        val id = Regex("(?:/videos/|/reel/|/watch/?\\?v=|[?&]v=|/share/v/)([A-Za-z0-9]+)").find(target)?.groupValues?.get(1)
        val canonical = if (id != null && id.all { it.isDigit() }) "https://www.facebook.com/watch/?v=$id" else target
        val mobileUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        val tries = listOf(
            target to emptyMap(),
            target.replace("://www.facebook.com/", "://m.facebook.com/") to mapOf("User-Agent" to mobileUa),
            "https://www.facebook.com/plugins/video.php?href=" + java.net.URLEncoder.encode(canonical, "UTF-8") + "&show_text=false" to emptyMap()
        )
        var last: Exception? = null
        for ((u, headers) in tries.distinctBy { it.first }) {
            try {
                return generic(u, headers)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException(
            "Couldn't read this Facebook video. Only public videos can be saved: private ones, " +
                "and ones in private groups, need signing in to Facebook.", last
        )
    }

    private fun generic(url: String, headers: Map<String, String> = emptyMap()): Media {
        // a direct file link
        if (DIRECT.containsMatchIn(url.substringBefore('#'))) {
            val ext = DIRECT.find(url)!!.groupValues[1].lowercase()
            val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Video" }
            return Media(name, null, null, 0, listOf(Variant(url, 0, sizeOf(url), ext, ext == "mp3" || ext == "m4a")))
        }
        val page = load(url, headers)
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
        val candidates = found.entries.filter { (u, _) -> !u.contains(".m3u8") }.map { it.key to it.value }
        if (candidates.isEmpty()) error("No downloadable video on this page. It may be private or need a login")
        // one file per quality: pages like Facebook's list the same video several times (different addresses)
        val byHeight = LinkedHashMap<Int, String>()
        for ((u, h) in candidates) if (h > 0 && h !in byHeight) byHeight[h] = u
        val files = if (byHeight.isNotEmpty()) byHeight.entries.map { it.value to it.key }.sortedByDescending { it.second }.take(3)
        else candidates.take(1)                                    // quality unknown: the page's main video
        val headers = mapOf("Referer" to page.finalUrl, "User-Agent" to UA)
        val variants = files.map { (u, h) ->
            fileHeaders[u] = headers
            val ext = DIRECT.find(u)?.groupValues?.get(1)?.lowercase() ?: "mp4"
            Variant(u, h, sizeOf(u), ext)
        }
        val title = meta(html, "og:title") ?: Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        return Media(
            title = cleanTitle(decodeEntities(title?.trim().orEmpty())).ifBlank { "Video" }.take(150),
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

    /** HTML codes to text, including numbered ones (&#xb7; = ·, &#x1f602; = an emoji). */
    private fun decodeEntities(s: String): String {
        fun cp(n: Int?, m: MatchResult) = n?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        var t = s.replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ")
        t = Regex("&#[xX]([0-9a-fA-F]{1,6});").replace(t) { m -> cp(m.groupValues[1].toIntOrNull(16), m) }
        t = Regex("&#(\\d{1,7});").replace(t) { m -> cp(m.groupValues[1].toIntOrNull(), m) }
        return t.replace("&amp;", "&")
    }

    /** A page title without what sites add around it ("221K views · 498 reactions | …", "… | Facebook"). */
    private fun cleanTitle(t: String): String {
        var x = t.trim()
        x = x.replace(Regex("^[^|]{0,80}\\b(views|reactions|likes|comments|plays)\\b[^|]*\\|\\s*", RegexOption.IGNORE_CASE), "")
        x = x.replace(Regex("\\s*[|\\-–]\\s*(Facebook|Instagram|Watch)\\s*$", RegexOption.IGNORE_CASE), "")
        return x.trim().ifBlank { t.trim() }
    }

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
