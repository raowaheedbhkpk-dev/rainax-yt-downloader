package com.rainax.ytdownloader

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * What TikTok's site loads in RAINAX's browser, so the video playing on screen can be matched to its post:
 *  - the feed's own video lists (read natively as they pass through: RAINAX fetches them for the page and keeps
 *    each video's id, author, caption, cover picture and file addresses),
 *  - the video ids inside TikTok's "open in the app" links (Share, Open app: snssdk… / onelink.me links).
 */
object TikTokFeed {

    class Item(val id: String, val author: String, val desc: String, val files: Set<String>, val covers: Set<String>)

    private val items = object : LinkedHashMap<String, Item>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>?) = size > 400
    }

    /** TikTok's address paths that return lists of videos. */
    private val API = Regex("^/api/.*(item_list|item/detail|aweme/v\\d/feed|recommend)", RegexOption.IGNORE_CASE)

    fun link(it: Item) = "https://www.tiktok.com/@${it.author.ifBlank { "tiktok" }}/video/${it.id}"

    /** The part of an address that names the file (no ?query, no host: TikTok serves one file from many hosts). */
    fun pathOf(url: String?): String? = runCatching { URL(url).path.trimEnd('/') }.getOrNull()?.takeIf { it.length > 8 }

    // ---------- the feed's data, read natively ----------

    /**
     * For [android.webkit.WebViewClient.shouldInterceptRequest]: TikTok's video lists are fetched here (same
     * address, headers and cookies as the page would use), remembered, and handed to the page unchanged.
     * Null for everything else (the page loads it itself).
     */
    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (!request.method.equals("GET", true)) return null
        val u = request.url
        val host = u.host?.lowercase() ?: return null
        if (!(host == "tiktok.com" || host.endsWith(".tiktok.com"))) return null
        if (!API.containsMatchIn(u.path.orEmpty())) return null
        return runCatching { fetch(request) }.onFailure { android.util.Log.w("RAINAX", "tiktok feed: ${it.message}") }.getOrNull()
    }

    private fun fetch(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.instanceFollowRedirects = true
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            var hasCookie = false
            for ((k, v) in request.requestHeaders) {
                if (k.equals("Accept-Encoding", true)) continue          // plain body (we read it)
                if (k.equals("Cookie", true)) hasCookie = true
                con.setRequestProperty(k, v)
            }
            if (!hasCookie) CookieManager.getInstance().getCookie(url)?.let { con.setRequestProperty("Cookie", it) }
            val code = con.responseCode
            if (code !in 200..299) return null                          // let the page load it itself
            val body = con.inputStream.use { it.readBytes() }
            // cookies the site sets stay with the browser
            con.headerFields["Set-Cookie"]?.forEach { CookieManager.getInstance().setCookie(url, it) }
            runCatching { record(JSONObject(String(body, Charsets.UTF_8))) }
            val headers = HashMap<String, String>()
            for ((k, v) in con.headerFields) {
                if (k == null || v.isNullOrEmpty()) continue
                if (k.equals("Content-Encoding", true) || k.equals("Content-Length", true) ||
                    k.equals("Transfer-Encoding", true) || k.equals("Set-Cookie", true)) continue
                headers[k] = v.joinToString(", ")
            }
            val type = con.contentType ?: "application/json; charset=utf-8"
            val mime = type.substringBefore(';').trim()
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1) ?: "utf-8"
            val reason = con.responseMessage?.takeIf { it.isNotBlank() } ?: "OK"
            return WebResourceResponse(mime, charset, code, reason, headers, ByteArrayInputStream(body))
        } finally {
            con.disconnect()
        }
    }

    /** Remembers every video found anywhere in a TikTok answer (or page data). */
    fun record(json: Any?) {
        fun urls(o: Any?, out: MutableSet<String>) {
            when (o) {
                is String -> if (o.startsWith("http")) pathOf(o)?.let { out += it }
                is JSONArray -> for (i in 0 until o.length()) urls(o.opt(i), out)
                is JSONObject -> {
                    o.optJSONArray("UrlList")?.let { urls(it, out) }
                    o.optJSONArray("url_list")?.let { urls(it, out) }
                }
            }
        }
        fun walk(n: Any?, depth: Int) {
            if (depth > 12) return
            when (n) {
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), depth + 1)
                is JSONObject -> {
                    val id = (n.optString("id").ifBlank { n.optString("aweme_id") }).takeIf { it.length >= 15 && it.all(Char::isDigit) }
                    val video = n.optJSONObject("video")
                    if (id != null && video != null) {
                        val author = n.optJSONObject("author")?.let { a -> a.optString("uniqueId").ifBlank { a.optString("unique_id") } }.orEmpty()
                        val files = HashSet<String>()
                        for (k in listOf("playAddr", "downloadAddr", "play_addr", "download_addr", "playApi")) urls(video.opt(k), files)
                        (video.optJSONArray("bitrateInfo") ?: video.optJSONArray("bit_rate"))?.let { arr ->
                            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { b ->
                                urls(b.opt("PlayAddr") ?: b.opt("play_addr"), files)
                            }
                        }
                        val covers = HashSet<String>()
                        for (k in listOf("cover", "originCover", "dynamicCover", "origin_cover", "dynamic_cover")) urls(video.opt(k), covers)
                        synchronized(items) {
                            items[id] = Item(id, author, n.optString("desc"), files, covers)
                        }
                    }
                    val keys = n.keys()
                    while (keys.hasNext()) {
                        val c = n.opt(keys.next())
                        if (c is JSONObject || c is JSONArray) walk(c, depth + 1)
                    }
                }
            }
        }
        walk(json, 0)
    }

    // ---------- matching the video on screen ----------

    /**
     * The post of the video on screen: by its file address, else its cover picture, else its author and caption
     * (the newest such video loaded). Null when nothing matches.
     */
    fun find(src: List<String>, poster: String?, authors: List<String>, text: String): Item? {
        val list = synchronized(items) { items.values.toList() }
        val paths = src.mapNotNull { pathOf(it) }
        for (p in paths) list.firstOrNull { p in it.files }?.let { return it }
        // TikTok sometimes plays the same file from a slightly different address: compare the file's own name
        for (p in paths) {
            val name = p.substringAfterLast('/').takeIf { it.length >= 16 } ?: continue
            list.firstOrNull { it.files.any { f -> f.endsWith(name) } }?.let { return it }
        }
        pathOf(poster)?.let { pp ->
            list.firstOrNull { pp in it.covers }?.let { return it }
            val name = pp.substringAfterLast('/').substringBefore('~').takeIf { it.length >= 16 }
            if (name != null) list.firstOrNull { it.covers.any { c -> c.contains(name) } }?.let { return it }
        }
        val who = authors.map { it.removePrefix("@").lowercase() }.filter { it.isNotBlank() }.toSet()
        if (who.isNotEmpty()) {
            val byAuthor = list.filter { it.author.lowercase() in who }
            if (byAuthor.size == 1) return byAuthor[0]
            if (byAuthor.size > 1) {
                val t = text.lowercase()
                byAuthor.lastOrNull { it.desc.isNotBlank() && t.contains(it.desc.take(30).lowercase()) }?.let { return it }
                return byAuthor.last()
            }
        }
        return null
    }

    // ---------- "open in the app" links ----------

    /** The video id inside a TikTok app link (snssdk…://aweme/detail/<id>, onelink.me/…?af_dp=…, item_id=…). */
    fun idFromAppLink(url: String): String? {
        var u = url
        repeat(3) { u = runCatching { URLDecoder.decode(u, "UTF-8") }.getOrDefault(u) }
        val m = Regex("(?:aweme/detail/|/video/|item_id=|share_item_id=|aweme_id=|group_id=|object_id=)(\\d{15,21})").find(u)
        return m?.groupValues?.get(1)
    }

    /** A link for a video id (the author is filled in when the video was seen in the feed). */
    fun linkForId(id: String): String = synchronized(items) { items[id] }?.let { link(it) } ?: "https://www.tiktok.com/@tiktok/video/$id"
}
