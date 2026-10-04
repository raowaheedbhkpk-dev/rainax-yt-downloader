package com.rainax.ytdownloader

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The user's YouTube account (optional). Sign-in happens on Google's own page inside the app (SignInActivity);
 * the app keeps the YouTube cookies on the phone and uses them only to show the user's own lists:
 * recommendations, subscriptions, history, liked videos and Watch later. Downloads never use the account.
 */
object YtAccount {

    private const val ORIGIN = "https://www.youtube.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    class Profile(val name: String, val handle: String?, val avatar: String?)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("yt_account", Context.MODE_PRIVATE)

    @Volatile private var cookies: String? = null

    fun load(c: Context) {
        cookies = prefs(c).getString("cookies", null)
    }

    fun isSignedIn(c: Context): Boolean {
        if (cookies == null) load(c)
        return !cookies.isNullOrBlank()
    }

    /** True when these cookies belong to a signed-in YouTube session. */
    fun looksSignedIn(cookieHeader: String?): Boolean {
        val c = cookieHeader.orEmpty()
        return sapisid(c) != null && ("LOGIN_INFO=" in c || "__Secure-3PSID=" in c || "SID=" in c)
    }

    fun save(c: Context, cookieHeader: String) {
        cookies = cookieHeader
        prefs(c).edit().putString("cookies", cookieHeader).apply()
    }

    fun profile(c: Context): Profile? {
        val p = prefs(c)
        val name = p.getString("name", null) ?: return null
        return Profile(name, p.getString("handle", null), p.getString("avatar", null))
    }

    fun signOut(c: Context) {
        cookies = null
        prefs(c).edit().clear().apply()
    }

    // ---------- requests ----------

    private fun sapisid(cookieHeader: String): String? =
        Regex("(?:^|;\\s*)(?:SAPISID|__Secure-3PAPISID)=([^;]+)").find(cookieHeader)?.groupValues?.get(1)

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun clientVersion(): String = try {
        FastExtractor.init()
        org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getClientVersion()
    } catch (e: Exception) {
        "2.20260805.01.00"
    }

    private fun context(): JSONObject {
        val locale = java.util.Locale.getDefault()
        return JSONObject().put(
            "client", JSONObject()
                .put("clientName", "WEB")
                .put("clientVersion", clientVersion())
                .put("hl", locale.language.ifBlank { "en" })
                .put("gl", locale.country.takeIf { it.length == 2 } ?: "US")
        )
    }

    /** Blocking. A signed-in YouTube request; throws a readable error. */
    private fun post(endpoint: String, body: JSONObject): JSONObject {
        val cookie = cookies ?: error("Sign in to YouTube first")
        val sid = sapisid(cookie) ?: error("Your YouTube sign-in has ended. Sign in again")
        val ts = System.currentTimeMillis() / 1000
        val auth = "SAPISIDHASH ${ts}_" + sha1("$ts $sid $ORIGIN")
        val con = URL("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Cookie", cookie)
            con.setRequestProperty("Authorization", auth)
            con.setRequestProperty("Origin", ORIGIN)
            con.setRequestProperty("X-Origin", ORIGIN)
            con.setRequestProperty("X-Goog-AuthUser", "0")
            con.setRequestProperty("X-Youtube-Client-Name", "1")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = con.responseCode
            if (code == 401 || code == 403) error("Your YouTube sign-in has ended. Sign in again")
            if (code !in 200..299) error("YouTube didn't answer (error $code). Try again")
            val text = con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return JSONObject(text)
        } finally {
            con.disconnect()
        }
    }

    /** Blocking. Name, @handle and picture of the signed-in account (saved for the app's top bar). */
    fun refreshProfile(c: Context): Profile? {
        val json = post("account/account_menu", JSONObject().put("context", context()))
        val header = find(json, "activeAccountHeaderRenderer") ?: return null
        val name = text(header.optJSONObject("accountName")) ?: return null
        val handle = text(header.optJSONObject("channelHandle"))
        val avatar = lastThumb(header.optJSONObject("accountPhoto"))
        prefs(c).edit().putString("name", name).putString("handle", handle).putString("avatar", avatar).apply()
        return Profile(name, handle, avatar)
    }

    /**
     * Blocking. One page of a personal list: "FEwhat_to_watch" (recommended), "FEsubscriptions",
     * "FEhistory", "VLLL" (liked), "VLWL" (Watch later). The next page is in [FeedPage.next].
     */
    fun browse(browseId: String, page: Page?): FeedPage {
        val body = JSONObject().put("context", context())
        val token = page?.id
        if (token != null) body.put("continuation", token) else body.put("browseId", browseId)
        val json = post("browse", body)
        val items = mutableListOf<VideoItem>()
        val next = arrayOfNulls<String>(1)
        collect(json, items, next)
        val seen = HashSet<String>()
        return FeedPage(items.filter { seen.add(it.url) }, next[0]?.let { Page(browseId, it) })
    }

    // ---------- reading YouTube's answers ----------

    private val VIDEO_KEYS = listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "videoWithContextRenderer", "playlistVideoRenderer")

    private fun collect(node: Any?, out: MutableList<VideoItem>, next: Array<String?>) {
        when (node) {
            is JSONObject -> {
                for (k in VIDEO_KEYS) node.optJSONObject(k)?.let { r -> video(r)?.let { out += it }; return }
                node.optJSONObject("lockupViewModel")?.let { r -> lockup(r)?.let { out += it }; return }
                node.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { next[0] = it }
                node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { next[0] = it }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, next)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, next)
        }
    }

    private fun video(r: JSONObject): VideoItem? {
        val id = r.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(r.optJSONObject("title")) ?: text(r.optJSONObject("headline")) ?: return null
        val owner = text(r.optJSONObject("ownerText")) ?: text(r.optJSONObject("shortBylineText"))
            ?: text(r.optJSONObject("longBylineText")) ?: ""
        val length = text(r.optJSONObject("lengthText"))
        val details = listOfNotNull(
            text(r.optJSONObject("shortViewCountText")) ?: text(r.optJSONObject("viewCountText")),
            text(r.optJSONObject("publishedTimeText"))
        ).joinToString(" • ")
        val avatar = lastThumb(
            r.optJSONObject("channelThumbnailSupportedRenderers")?.optJSONObject("channelThumbnailWithLinkRenderer")?.optJSONObject("thumbnail")
                ?: r.optJSONObject("channelThumbnail")
        )
        return VideoItem(
            url = "https://www.youtube.com/watch?v=$id", title = title, uploader = owner,
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg", seconds = seconds(length),
            views = -1, uploaded = details.ifBlank { null }, avatar = avatar
        )
    }

    private fun lockup(r: JSONObject): VideoItem? {
        val id = r.optString("contentId").takeIf { it.isNotBlank() } ?: return null
        val type = r.optString("contentType")
        val meta = r.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content")?.takeIf { it.isNotBlank() } ?: return null
        val rows = meta.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        val lines = mutableListOf<String>()
        if (rows != null) for (i in 0 until rows.length()) {
            val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            val line = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("text")?.optString("content")?.takeIf { s -> s.isNotBlank() } }
            if (line.isNotEmpty()) lines += line.joinToString(" • ")
        }
        val thumbVm = r.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
            ?: r.optJSONObject("contentImage")?.optJSONObject("collectionThumbnailViewModel")?.optJSONObject("primaryThumbnail")?.optJSONObject("thumbnailViewModel")
        val sources = thumbVm?.optJSONObject("image")?.optJSONArray("sources")
        val thumb = sources?.optJSONObject(sources.length() - 1)?.optString("url")
        var badge: String? = null
        val overlays = thumbVm?.optJSONArray("overlays")
        if (overlays != null) for (i in 0 until overlays.length()) {
            val badges = overlays.optJSONObject(i)?.optJSONObject("thumbnailOverlayBadgeViewModel")?.optJSONArray("thumbnailBadges") ?: continue
            if (badges.length() > 0) badge = badges.optJSONObject(0)?.optJSONObject("thumbnailBadgeViewModel")?.optString("text")
        }
        val avatar = meta.optJSONObject("image")?.optJSONObject("decoratedAvatarViewModel")?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        val playlist = type.contains("PLAYLIST") || type.contains("ALBUM")
        return VideoItem(
            url = if (playlist) "https://www.youtube.com/playlist?list=$id" else "https://www.youtube.com/watch?v=$id",
            title = title,
            uploader = lines.firstOrNull().orEmpty(),
            thumb = thumb ?: if (playlist) null else "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            seconds = if (playlist) 0 else seconds(badge),
            views = -1,
            uploaded = lines.drop(1).joinToString(" • ").ifBlank { null },
            isPlaylist = playlist,
            avatar = avatar
        )
    }

    /** "4:01" / "1:02:03" -> seconds (0 when unknown, -1 for LIVE). */
    private fun seconds(text: String?): Long {
        val t = text?.trim().orEmpty()
        if (t.equals("LIVE", true)) return -1
        val parts = t.split(':').map { it.trim().toLongOrNull() ?: return 0 }
        return parts.fold(0L) { acc, v -> acc * 60 + v }
    }

    private fun text(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        o.optString("content").takeIf { it.isNotBlank() }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return null
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
    }

    private fun lastThumb(o: JSONObject?): String? {
        val list = o?.optJSONArray("thumbnails") ?: return null
        if (list.length() == 0) return null
        return list.optJSONObject(list.length() - 1)?.optString("url")?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    /** First object stored under [key] anywhere in [node]. */
    private fun find(node: Any?, key: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(key)?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) find(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
