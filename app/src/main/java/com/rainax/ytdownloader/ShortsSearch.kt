package com.rainax.ytdownloader

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube search with its "Shorts" filter (the same filter as on youtube.com), read directly:
 * every result is a Short, and the next page comes with a token. Used for the Shorts feed.
 */
object ShortsSearch {

    private const val WEB_VERSION = "2.20260708.00.00"
    private const val SHORTS_FILTER = "EgIQCQ=="       // search filter "Type: Shorts"

    class Page(val items: List<VideoItem>, val next: String?)

    /** Blocking. First page for [query], or the page after [token]. */
    fun search(query: String, token: String?): Page {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", WEB_VERSION)
            .put("hl", "en").put("gl", java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US")))
        if (token != null) body.put("continuation", token) else body.put("query", query).put("params", SHORTS_FILTER)
        val json = JSONObject(post(body.toString()))
        val items = LinkedHashMap<String, VideoItem>()
        var next: String? = null
        walk(json) { key, o ->
            when (key) {
                "videoRenderer" -> video(o)?.let { items.putIfAbsent(youtubeId(it.url) ?: it.url, it) }
                "reelItemRenderer" -> reel(o)?.let { items.putIfAbsent(youtubeId(it.url) ?: it.url, it) }
                "shortsLockupViewModel" -> lockup(o)?.let { items.putIfAbsent(youtubeId(it.url) ?: it.url, it) }
                "continuationCommand" -> o.optString("token").takeIf { it.isNotBlank() }?.let { next = it }
            }
        }
        return Page(items.values.toList(), next)
    }

    /** Visits every object in the answer (YouTube nests results deeply and changes the layout often). */
    private fun walk(v: Any?, f: (String, JSONObject) -> Unit) {
        when (v) {
            is JSONObject -> v.keys().forEach { k ->
                val c = v.opt(k)
                if (c is JSONObject) f(k, c)
                walk(c, f)
            }
            is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), f)
        }
    }

    private fun text(o: JSONObject?): String =
        o?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: o?.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }
            ?: ""

    private fun thumb(arr: JSONArray?): String? =
        arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.maxByOrNull { it.optInt("height") * it.optInt("width") } }
            ?.optString("url")?.takeIf { it.isNotBlank() }

    private fun seconds(len: String): Long {
        val p = len.split(':').mapNotNull { it.trim().toLongOrNull() }
        return p.fold(0L) { acc, x -> acc * 60 + x }
    }

    private fun item(id: String, title: String, uploader: String, thumbUrl: String?, sec: Long) = VideoItem(
        url = "https://www.youtube.com/shorts/$id", title = title, uploader = uploader,
        thumb = thumbUrl ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg", seconds = sec, views = -1, uploaded = null, isShort = true
    )

    private fun video(o: JSONObject): VideoItem? {
        val id = o.optString("videoId").takeIf { it.length == 11 } ?: return null
        val len = text(o.optJSONObject("lengthText"))
        if (len.isBlank()) return null                       // live streams have no length
        val sec = seconds(len)
        if (sec !in 1..180) return null
        return item(id, text(o.optJSONObject("title")), text(o.optJSONObject("ownerText")),
            thumb(o.optJSONObject("thumbnail")?.optJSONArray("thumbnails")), sec)
    }

    private fun reel(o: JSONObject): VideoItem? {
        val id = o.optString("videoId").takeIf { it.length == 11 } ?: return null
        return item(id, text(o.optJSONObject("headline")), "", thumb(o.optJSONObject("thumbnail")?.optJSONArray("thumbnails")), 0)
    }

    private fun lockup(o: JSONObject): VideoItem? {
        val id = o.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")
            ?.optString("videoId")?.takeIf { it.length == 11 }
            ?: o.optString("entityId").substringAfterLast('-').takeIf { it.length == 11 }
            ?: return null
        val title = o.optJSONObject("overlayMetadata")?.optJSONObject("primaryText")?.optString("content").orEmpty()
        val src = o.optJSONObject("thumbnail")?.optJSONArray("sources")
        return item(id, title, "", thumb(src), 0)
    }

    private fun post(json: String): String {
        val con = URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", FastExtractor.UA)
            con.setRequestProperty("X-YouTube-Client-Name", "1")
            con.setRequestProperty("X-YouTube-Client-Version", WEB_VERSION)
            con.setRequestProperty("Origin", "https://www.youtube.com")
            con.outputStream.use { it.write(json.toByteArray()) }
            val code = con.responseCode
            if (code >= 400) throw java.io.IOException("HTTP error $code")
            return con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
    }
}
