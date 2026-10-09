package com.rainax.ytdownloader

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Description
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Second way to open a YouTube video, used when the normal one says "not available": videos made for kids
 * (and some others) are refused to the app's usual YouTube client but play in YouTube's embedded
 * player (the one inside other websites) or TV app. This asks as those, unlocks the stream addresses with YouTube's own player code, and returns the
 * same kind of info as the normal way, so playing and downloading work as usual.
 */
object YtFallback {

    private val served = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True when video [id] was opened this second way (its page needs the extra details looked up). */
    fun served(id: String?) = id != null && id in served

    /** Start of the message shown when no way could play the video. */
    const val REFUSED_TEXT = "YouTube refused this video:"

    /** A YouTube app the request pretends to be (the same ones yt-dlp uses for these videos). */
    private class Client(val name: String, val id: String, val version: String, val ua: String, val embedded: Boolean = false)

    private const val SAFARI_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"

    /** In order: YouTube's embedded player (works for most "made for kids" videos), then the TV apps. */
    private val CLIENTS = listOf(
        Client("WEB_EMBEDDED_PLAYER", "56", "2.20260708.00.00", SAFARI_UA, embedded = true),
        Client("TVHTML5", "7", "5.20260707", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"),
        Client(
            "TVHTML5", "7", "7.20260707.07.00",
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        )
    )

    /** True for "not available" answers this second way may solve (not private, paid or blocked-by-country ones). */
    fun canHelp(e: Throwable): Boolean =
        e.javaClass == ContentNotAvailableException::class.java ||
            e is AgeRestrictedContentException ||
            e.message.orEmpty().contains("player response is not valid", ignoreCase = true)

    /** Blocking. The video's info through YouTube's embedded player or TV app. Throws when none works. */
    fun info(pageUrl: String): StreamInfo {
        val id = youtubeId(pageUrl) ?: error("No video id")
        val embed = runCatching { embedPage(id) }.getOrNull()
        // YouTube's current player code: its version goes into the request, and it unlocks the addresses
        val js = embed?.playerId?.let { pid -> runCatching { JsSolver.prepare(pid) }.getOrNull() }
        val sts = js?.sts ?: runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(id) }.getOrNull()
        var last: Exception? = null
        for (c in CLIENTS) {
            try {
                return build(pageUrl, id, player(c, id, sts, embed), js?.id).also { served += id }
            } catch (e: Exception) {
                android.util.Log.w("RAINAX", "${c.name} ${c.version}: ${e.message}")
                last = e
            }
        }
        throw last ?: ContentNotAvailableException("No way to play this video")
    }

    /** Values from YouTube's embed page the embedded player sends (visitor id, host flags). */
    private class Embed(val visitor: String?, val hostFlags: String?, val playerId: String?)

    private fun embedPage(id: String): Embed {
        val con = URL("https://www.youtube.com/embed/$id?html5=1").openConnection() as HttpURLConnection
        val html = try {
            con.connectTimeout = 10_000
            con.readTimeout = 15_000
            con.setRequestProperty("User-Agent", SAFARI_UA.substringBefore(",gzip"))
            con.setRequestProperty("Referer", "https://www.reddit.com/")
            con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
        fun find(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        val playerId = Regex("/s/player/([a-zA-Z0-9_-]{6,})/").find(html)?.groupValues?.get(1)
        return Embed(find("VISITOR_DATA"), find("encryptedHostFlags"), playerId)
    }

    /** One player request as [c]; returns the answer when YouTube says the video can play. */
    private fun player(c: Client, id: String, sts: Int?, embed: Embed?): JSONObject {
        val client = JSONObject().put("clientName", c.name).put("clientVersion", c.version)
            .put("hl", "en").put("gl", java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US")
            .put("userAgent", c.ua)
        embed?.visitor?.let { client.put("visitorData", it) }
        val context = JSONObject().put("client", client)
        if (c.embedded) context.put("thirdParty", JSONObject().put("embedUrl", "https://www.reddit.com/"))
        val playback = JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
        if (sts != null) playback.put("signatureTimestamp", sts)
        if (c.embedded) embed?.hostFlags?.let { playback.put("encryptedHostFlags", it) }
        val body = JSONObject()
            .put("context", context)
            .put("videoId", id)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", playback))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        val res = JSONObject(post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString(), c, embed?.visitor))
        val play = res.optJSONObject("playabilityStatus")
        val status = play?.optString("status").orEmpty()
        if (!status.equals("OK", true)) throw ContentNotAvailableException("$status " + play?.optString("reason").orEmpty())
        if (res.optJSONObject("streamingData") == null) throw ContentNotAvailableException("no streams")
        return res
    }

    private fun build(pageUrl: String, id: String, res: JSONObject, playerId: String?): StreamInfo {
        val details = res.optJSONObject("videoDetails") ?: JSONObject()
        val data = res.getJSONObject("streamingData")
        val raw = mutableListOf<JSONObject>()
        each(data.optJSONArray("formats")) { raw += it }
        each(data.optJSONArray("adaptiveFormats")) { raw += it }
        val urls = unlock(id, raw, playerId)
        // check one address really opens (a wrong unlock gives "403 refused"): then the next way is tried
        urls.values.firstOrNull()?.let { if (!opens(it)) throw ContentNotAvailableException("stream refused (403)") }

        val video = mutableListOf<VideoStream>()
        val videoOnly = mutableListOf<VideoStream>()
        val audio = mutableListOf<AudioStream>()
        for (f in raw) {
            runCatching {
                if (f.optString("type").equals("FORMAT_STREAM_TYPE_OTF", true)) return@runCatching
                val item = ItagItem.getItag(f.getInt("itag"))
                val url = urls[f] ?: return@runCatching
                val mime = f.optString("mimeType")
                item.setBitrate(f.optInt("bitrate"))
                item.setWidth(f.optInt("width"))
                item.setHeight(f.optInt("height"))
                item.setQuality(f.optString("quality"))
                item.setCodec(if (mime.contains("codecs")) mime.split('"').getOrElse(1) { "" } else "")
                item.setContentLength(f.optString("contentLength").toLongOrNull() ?: -1L)
                item.setApproxDurationMs(f.optString("approxDurationMs").toLongOrNull() ?: -1L)
                when (item.itagType) {
                    ItagItem.ItagType.AUDIO -> {
                        item.setSampleRate(f.optString("audioSampleRate").toIntOrNull() ?: 44100)
                        item.setAudioChannels(f.optInt("audioChannels", 2))
                        audio += AudioStream.Builder()
                            .setId(item.id.toString())
                            .setContent(url, true)
                            .setMediaFormat(item.mediaFormat)
                            .setAverageBitrate(item.averageBitrate)
                            .setItagItem(item)
                            .build()
                    }
                    else -> {
                        item.setFps(f.optInt("fps"))
                        val only = item.itagType == ItagItem.ItagType.VIDEO_ONLY
                        val s = VideoStream.Builder()
                            .setId(item.id.toString())
                            .setContent(url, true)
                            .setMediaFormat(item.mediaFormat)
                            .setIsVideoOnly(only)
                            .setItagItem(item)
                            .setResolution(item.resolutionString ?: "")
                            .build()
                        if (only) videoOnly += s else video += s
                    }
                }
            }
        }
        if (audio.isEmpty() && video.isEmpty()) throw ContentNotAvailableException("no usable streams")

        val info = StreamInfo(
            ServiceList.YouTube.serviceId, pageUrl, pageUrl, StreamType.VIDEO_STREAM, id,
            details.optString("title"), 0
        )
        info.duration = details.optString("lengthSeconds").toLongOrNull() ?: 0L
        info.uploaderName = details.optString("author")
        details.optString("channelId").takeIf { it.isNotBlank() }?.let { info.uploaderUrl = "https://www.youtube.com/channel/$it" }
        info.viewCount = details.optString("viewCount").toLongOrNull() ?: -1L
        info.description = Description(details.optString("shortDescription"), Description.Type.PLAIN_TEXT)
        val thumbs = mutableListOf<Image>()
        each(details.optJSONObject("thumbnail")?.optJSONArray("thumbnails")) { t ->
            val h = t.optInt("height")
            thumbs += Image(t.optString("url").substringBefore('?'), h, t.optInt("width"), Image.ResolutionLevel.fromHeight(h))
        }
        if (thumbs.isEmpty()) thumbs += Image("https://i.ytimg.com/vi/$id/hqdefault.jpg", 360, 480, Image.ResolutionLevel.MEDIUM)
        info.thumbnails = thumbs
        info.videoStreams = video
        info.videoOnlyStreams = videoOnly
        info.audioStreams = audio
        return info
    }

    private fun opens(url: String): Boolean = try {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 10_000
            con.readTimeout = 10_000
            con.setRequestProperty("User-Agent", FastExtractor.UA)
            con.setRequestProperty("Range", "bytes=0-0")
            con.responseCode != 403
        } finally {
            con.disconnect()
        }
    } catch (e: Exception) {
        true                                        // no answer (network): don't blame the address
    }

    private fun each(arr: JSONArray?, f: (JSONObject) -> Unit) {
        if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(f)
    }

    /** One stream's address parts: the base address, its "n" value, and its signature (if it has one). */
    private class Raw(val base: String, val n: String?, val s: String?, val sp: String)

    private fun parts(f: JSONObject): Raw? {
        var url = f.optString("url")
        var s: String? = null
        var sp = "signature"
        if (url.isBlank()) {
            val cipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
            if (cipher.isBlank()) return null
            val p = cipher.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            url = p["url"] ?: return null
            s = p["s"] ?: return null
            sp = p["sp"] ?: "signature"
        }
        val n = Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
        return Raw(url, n, s, sp)
    }

    /**
     * Every stream's working address. First with YouTube's own player code (the yt-dlp solver in a WebView),
     * then, if that can't run, with the extractor's older way.
     */
    private fun unlock(id: String, formats: List<JSONObject>, playerId: String?): Map<JSONObject, String> {
        val raws = formats.associateWith { parts(it) }
        if (playerId != null) {
            try {
                val nList = raws.values.mapNotNull { it?.n }.toSet()
                val sList = raws.values.mapNotNull { it?.s }.toSet()
                val (nOut, sOut) = JsSolver.solve(playerId, nList, sList)
                val out = HashMap<JSONObject, String>()
                for ((f, r) in raws) {
                    if (r == null) continue
                    var u = r.base
                    if (r.s != null) u += "&" + r.sp + "=" + (sOut[r.s] ?: continue)
                    if (r.n != null) {
                        val solved = nOut[r.n] ?: continue
                        u = u.replace(Regex("([?&])n=[^&]+"), "$1n=" + java.net.URLEncoder.encode(solved, "UTF-8"))
                    }
                    out[f] = u
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                android.util.Log.w("RAINAX", "player code solver failed: ${e.message}")
            }
        }
        return formats.mapNotNull { f -> runCatching { streamUrl(id, f) }.getOrNull()?.let { f to it } }.toMap()
    }

    /** A stream's address, unlocked (signature and speed parameter) with the extractor's older way. */
    private fun streamUrl(id: String, f: JSONObject): String? {
        var url = f.optString("url")
        if (url.isBlank()) {
            val cipher = f.optString("signatureCipher").ifBlank { f.optString("cipher") }
            if (cipher.isBlank()) return null
            val parts = cipher.split('&').associate {
                val k = it.substringBefore('=')
                k to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val s = parts["s"] ?: return null
            val base = parts["url"] ?: return null
            url = base + "&" + (parts["sp"] ?: "signature") + "=" + YoutubeJavaScriptPlayerManager.deobfuscateSignature(id, s)
        }
        return YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(id, url)
    }

    private fun post(url: String, json: String, c: Client, visitor: String?): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", c.ua)
            con.setRequestProperty("X-YouTube-Client-Name", c.id)
            con.setRequestProperty("X-YouTube-Client-Version", c.version)
            con.setRequestProperty("Origin", "https://www.youtube.com")
            if (c.embedded) con.setRequestProperty("Referer", "https://www.reddit.com/")
            visitor?.let { con.setRequestProperty("X-Goog-Visitor-Id", it) }
            con.outputStream.use { it.write(json.toByteArray()) }
            val code = con.responseCode
            val text = (if (code >= 400) con.errorStream else con.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code >= 400) throw java.io.IOException("HTTP error $code")
            return text
        } finally {
            con.disconnect()
        }
    }
}
