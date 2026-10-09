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
 * (and some others) are refused to the app's usual YouTube client but play in YouTube's TV app.
 * This asks as the TV app, unlocks the stream addresses with YouTube's own player code, and returns the
 * same kind of info as the normal way, so playing and downloading work as usual.
 */
object YtFallback {

    private const val TV_VERSION = "7.20250923.13.00"
    private const val TV_UA = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
        "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"

    /** True for "not available" answers this second way may solve (not private, paid or blocked-by-country ones). */
    fun canHelp(e: Throwable): Boolean =
        e.javaClass == ContentNotAvailableException::class.java ||
            e is AgeRestrictedContentException ||
            e.message.orEmpty().contains("player response is not valid", ignoreCase = true)

    /** Blocking. The video's info through YouTube's TV app. Throws when that doesn't work either. */
    fun info(pageUrl: String): StreamInfo {
        val id = youtubeId(pageUrl) ?: error("No video id")
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(id) }.getOrNull()
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "TVHTML5").put("clientVersion", TV_VERSION)
                .put("hl", "en").put("gl", java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US")))
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (sts != null) {
            body.put("playbackContext", JSONObject().put("contentPlaybackContext",
                JSONObject().put("signatureTimestamp", sts).put("html5Preference", "HTML5_PREF_WANTS")))
        }
        val res = JSONObject(post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString()))
        val play = res.optJSONObject("playabilityStatus")
        val status = play?.optString("status").orEmpty()
        if (!status.equals("OK", true)) throw ContentNotAvailableException("TV: $status " + play?.optString("reason").orEmpty())
        val details = res.optJSONObject("videoDetails") ?: JSONObject()
        val data = res.optJSONObject("streamingData") ?: throw ContentNotAvailableException("TV: no streams")

        val video = mutableListOf<VideoStream>()
        val videoOnly = mutableListOf<VideoStream>()
        val audio = mutableListOf<AudioStream>()
        fun each(arr: JSONArray?, f: (JSONObject) -> Unit) {
            if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(f)
        }
        val formats = mutableListOf<JSONObject>()
        each(data.optJSONArray("formats")) { formats += it }
        each(data.optJSONArray("adaptiveFormats")) { formats += it }
        for (f in formats) {
            runCatching {
                if (f.optString("type").equals("FORMAT_STREAM_TYPE_OTF", true)) return@runCatching
                val item = ItagItem.getItag(f.getInt("itag"))
                val url = streamUrl(id, f) ?: return@runCatching
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
        if (audio.isEmpty() && video.isEmpty()) throw ContentNotAvailableException("TV: no usable streams")

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

    /** A stream's address, unlocked (signature and speed parameter) with YouTube's player code. */
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

    private fun post(url: String, json: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", TV_UA)
            con.setRequestProperty("X-YouTube-Client-Name", "7")
            con.setRequestProperty("X-YouTube-Client-Version", TV_VERSION)
            con.setRequestProperty("Origin", "https://www.youtube.com")
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
