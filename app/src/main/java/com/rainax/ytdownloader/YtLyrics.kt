package com.rainax.ytdownloader

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Song lyrics from YouTube Music itself (the Lyrics tab of its player). They come with YouTube Music's own
 * source line (e.g. "Source: LyricFind"), which is shown with them. Nothing is stored or shared.
 */
object YtLyrics {

    class Lyrics(val text: String, val source: String?)

    private const val ORIGIN = "https://music.youtube.com"
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()
    private val none = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun context(): JSONObject {
        val locale = java.util.Locale.getDefault()
        return JSONObject().put(
            "client", JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250310.01.00")
                .put("hl", locale.language.ifBlank { "en" })
                .put("gl", locale.country.takeIf { it.length == 2 } ?: "US")
        )
    }

    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val con = URL("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            con.setRequestProperty("Origin", ORIGIN)
            con.setRequestProperty("X-Origin", ORIGIN)
            con.outputStream.use { it.write(body.put("context", context()).toString().toByteArray()) }
            if (con.responseCode !in 200..299) error("YouTube Music didn't answer (error ${con.responseCode})")
            return JSONObject(con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } finally {
            con.disconnect()
        }
    }

    /** First object anywhere inside [node] that has the key [key]. */
    private fun find(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                for (k in node.keys()) find(node.opt(k), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), key)?.let { return it }
        }
        return null
    }

    private fun runs(o: JSONObject?): String? = o?.optJSONArray("runs")?.let { r ->
        (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() }
    }?.takeIf { it.isNotBlank() }

    /** Blocking. The song's lyrics, or null when YouTube Music has none for it. */
    fun get(videoId: String): Lyrics? {
        cache[videoId]?.let { return it }
        if (videoId in none) return null
        val next = post("next", JSONObject().put("videoId", videoId).put("isAudioOnly", true))
        // the Lyrics tab: a browse id starting with MPLYt
        val tabs = find(next, "watchNextTabbedResultsRenderer")?.optJSONArray("tabs")
        var browseId: String? = null
        if (tabs != null) for (i in 0 until tabs.length()) {
            val id = find(tabs.optJSONObject(i), "browseEndpoint")?.optString("browseId").orEmpty()
            if (id.startsWith("MPLYt")) { browseId = id; break }
        }
        if (browseId == null) { none += videoId; return null }
        val page = post("browse", JSONObject().put("browseId", browseId))
        val shelf = find(page, "musicDescriptionShelfRenderer")
        val text = runs(shelf?.optJSONObject("description"))
        if (text == null) { none += videoId; return null }
        return Lyrics(text.trim(), runs(shelf?.optJSONObject("footer"))).also { cache[videoId] = it }
    }
}
