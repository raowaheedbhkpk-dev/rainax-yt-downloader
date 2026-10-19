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

    internal fun context(): JSONObject {
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
    private fun post(endpoint: String, body: JSONObject): JSONObject = request(endpoint, body, true)

    /** Blocking. A YouTube request, signed in when the user is (required when [needAuth]). */
    internal fun request(endpoint: String, body: JSONObject, needAuth: Boolean): JSONObject {
        val cookie = cookies
        if (cookie == null && needAuth) error("Sign in to YouTube first")
        val sid = cookie?.let { sapisid(it) }
        if (sid == null && needAuth) error("Your YouTube sign-in has ended. Sign in again")
        val ts = System.currentTimeMillis() / 1000
        val auth = sid?.let { "SAPISIDHASH ${ts}_" + sha1("$ts $it $ORIGIN") }
        val con = URL("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15_000
            con.readTimeout = 20_000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json")
            con.setRequestProperty("User-Agent", UA)
            if (cookie != null && auth != null) {
                con.setRequestProperty("Cookie", cookie)
                con.setRequestProperty("Authorization", auth)
            }
            con.setRequestProperty("Origin", ORIGIN)
            con.setRequestProperty("X-Origin", ORIGIN)
            con.setRequestProperty("X-Goog-AuthUser", "0")
            con.setRequestProperty("X-Youtube-Client-Name", "1")
            con.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = con.responseCode
            if ((code == 401 || code == 403) && auth != null) error("Your YouTube sign-in has ended. Sign in again")
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
        if (!isSignedIn(c)) return null                 // signed out while this was loading: don't bring it back
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

    // ---------- notifications (the bell) ----------

    /** Notifications older than this are left out (YouTube's own bell shows about the last month). */
    private const val NOTIFY_MAX_DAYS = 30

    /**
     * Blocking. The bell, like YouTube: your recent notifications (new uploads, likes, replies...) that open a
     * video, newest first, only from about the last month. When YouTube's bell list can't be read, recent new
     * videos from your subscriptions (last 7 days) are shown instead.
     */
    fun notifications(page: Page?): FeedPage = bellPage(page?.id)      // no feed videos in place of notifications

    private const val NOTIFY_ID = "notifications"

    private fun bellPage(token: String?): FeedPage {
        val body = JSONObject().put("context", context())
        if (token != null) body.put("ctoken", token)
        else body.put("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")
        val json = post("notification/get_notification_menu", body)
        val found = mutableListOf<JSONObject>()
        var next: String? = null
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("notificationRenderer")?.let { found += it; return }
                    n.optJSONObject("getNotificationMenuEndpoint")?.optString("ctoken")?.takeIf { it.isNotBlank() }?.let { next = it }
                    val keys = n.keys()
                    while (keys.hasNext()) walk(n.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        var tooOld = false
        val items = found.mapNotNull { r ->
            val when_ = text(r.optJSONObject("sentTimeText"))
            if ((ageDays(when_) ?: 0) > NOTIFY_MAX_DAYS) { tooOld = true; return@mapNotNull null }
            val id = (findValue(r.optJSONObject("navigationEndpoint"), "videoId") as? String)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val message = text(r.optJSONObject("shortMessage")) ?: return@mapNotNull null
            VideoItem(
                url = "https://www.youtube.com/watch?v=$id", title = message, uploader = "",
                thumb = lastThumb(r.optJSONObject("videoThumbnail")) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                seconds = 0, views = -1, uploaded = when_, avatar = lastThumb(r.optJSONObject("thumbnail"))
            )
        }
        val seen = HashSet<String>()
        val nextPage = if (tooOld) null else next?.let { Page(NOTIFY_ID, it) }
        return FeedPage(items.filter { seen.add(it.title + it.url) }, nextPage)
    }

    // ---------- the Notifications page (YouTube's inbox, as the YouTube app shows it) ----------

    /** One notification row: "For you" / video title / "2 weeks ago", channel picture left, video picture right. */
    class Notif(
        val id: String,
        val heading: String,
        val body: String,
        val time: String,
        val avatar: String?,
        val thumb: String?,
        /** Video ("watch?v=") or channel link it opens; null when it opens nothing in the app. */
        val url: String?,
        val videoId: String?,
        val read: Boolean,
        /** A reply to you or an @mention (the Mentions chip). */
        val mention: Boolean
    )

    class NotifPage(val items: List<Notif>, val next: String?)

    /**
     * Blocking. Your YouTube notifications, newest first, exactly the inbox the bell shows ([token] = next page).
     * Throws a readable error when YouTube's list can't be read (no fake list of feed videos instead).
     */
    fun inbox(token: String?): NotifPage {
        // 1) the bell menu (web), as YouTube's bell does
        val menu = runCatching {
            val body = JSONObject().put("context", context())
            if (token != null) body.put("ctoken", token)
            else body.put("notificationsMenuRequestType", "NOTIFICATIONS_MENU_REQUEST_TYPE_INBOX")
            parseInbox(post("notification/get_notification_menu", body))
        }.onFailure { android.util.Log.w("RAINAX", "inbox menu: ${it.message}") }
        menu.getOrNull()?.let { if (it.items.isNotEmpty() || token != null) return it }
        if (token != null) throw menu.exceptionOrNull()!!
        // 2) the inbox page the YouTube app opens
        val inbox = runCatching {
            parseInbox(post("browse", JSONObject().put("context", context()).put("browseId", "FEnotifications_inbox")))
        }.onFailure { android.util.Log.w("RAINAX", "inbox browse: ${it.message}") }
        inbox.getOrNull()?.let { if (it.items.isNotEmpty()) return it }
        // nothing anywhere: really empty when the bell answered; otherwise say why
        menu.getOrNull()?.let { return it }
        throw menu.exceptionOrNull() ?: inbox.exceptionOrNull() ?: IllegalStateException("Couldn't read your notifications")
    }

    private fun parseInbox(json: JSONObject): NotifPage {
        val found = mutableListOf<JSONObject>()
        var next: String? = null
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("notificationRenderer")?.let { found += it; return }
                    n.optJSONObject("getNotificationMenuEndpoint")?.optString("ctoken")?.takeIf { it.isNotBlank() }?.let { next = it }
                    n.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { if (next == null) next = it }
                    val keys = n.keys()
                    while (keys.hasNext()) walk(n.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        val seen = HashSet<String>()
        val items = found.mapNotNull { r ->
            val message = text(r.optJSONObject("shortMessage"))?.trim() ?: return@mapNotNull null
            val nav = r.optJSONObject("navigationEndpoint")
            val videoId = (findValue(nav, "videoId") as? String)?.takeIf { it.isNotBlank() }
            val browseId = (findValue(nav, "browseId") as? String)?.takeIf { it.startsWith("UC") }
            val url = when {
                videoId != null -> "https://www.youtube.com/watch?v=$videoId"
                browseId != null -> "https://www.youtube.com/channel/$browseId"
                else -> null
            }
            val (heading, body) = splitMessage(message)
            val id = r.optString("notificationId").ifBlank { message + url }
            if (!seen.add(id)) return@mapNotNull null
            val low = message.lowercase()
            Notif(
                id = id, heading = heading, body = body,
                time = text(r.optJSONObject("sentTimeText")).orEmpty(),
                avatar = lastThumb(r.optJSONObject("thumbnail")),
                thumb = lastThumb(r.optJSONObject("videoThumbnail")) ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                url = url, videoId = videoId,
                read = r.optBoolean("read", true),
                mention = low.contains("replied") || low.contains("mentioned") || low.contains("tagged you")
            )
        }
        return NotifPage(items, next)
    }

    /**
     * "Recommended: Title" -> ("For you", "Title"); "Channel uploaded: Title" -> ("Channel", "Uploaded: Title");
     * "Channel uploaded 6 videos" -> ("Channel", "Uploaded 6 videos"); "Your comment got 1 like: \"Jobs\"" ->
     * ("Your comment got 1 like", "\"Jobs\""). Anything else stays one line.
     */
    internal fun splitMessage(m: String): Pair<String, String> {
        Regex("^(?:Recommended|For you|Suggested)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(m)?.let {
            return "For you" to it.groupValues[1].trim()
        }
        Regex("^(.+?)\\s+(uploaded|is live|premieres?|premiering|posted|shared|replied|mentioned you|commented)(.*)$", RegexOption.IGNORE_CASE).find(m)?.let {
            val who = it.groupValues[1].trim()
            val rest = (it.groupValues[2] + it.groupValues[3]).trim()
            return who to rest.replaceFirstChar { c -> c.uppercase() }
        }
        val colon = m.indexOf(": ")
        if (colon in 1..60) return m.substring(0, colon).trim() to m.substring(colon + 2).trim()
        return m to ""
    }

    /** "3 days ago", "2 weeks ago", "Streamed 1 month ago" -> days (null when it can't be read, e.g. other languages). */
    internal fun ageDays(text: String?): Int? {
        val t = text?.lowercase() ?: return null
        val m = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year)").find(t) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return when (m.groupValues[2]) {
            "second", "minute", "hour" -> 0
            "day" -> n
            "week" -> n * 7
            "month" -> n * 30
            else -> n * 365
        }
    }

    /**
     * Blocking. Clears the signed-in account's YouTube watch history, the same as "Clear all watch history" on
     * YouTube's History page. Returns false when YouTube didn't offer the button (nothing was changed).
     */
    fun clearYouTubeHistory(): Boolean {
        val page = post("browse", JSONObject().put("context", context()).put("browseId", "FEhistory"))
        val tokens = mutableListOf<String>()
        // the "Clear all watch history" button: its confirm dialog holds the feedback token
        fun walk(n: Any?, inClear: Boolean) {
            when (n) {
                is JSONObject -> {
                    val label = (text(n.optJSONObject("text")) ?: n.optString("title")).lowercase()
                    val here = inClear || (label.contains("clear") && label.contains("history"))
                    if (here) n.optJSONObject("feedbackEndpoint")?.optString("feedbackToken")?.takeIf { it.isNotBlank() }?.let { tokens += it }
                    val keys = n.keys()
                    while (keys.hasNext()) walk(n.opt(keys.next()), here)
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), inClear)
            }
        }
        walk(page, false)
        if (tokens.isEmpty()) return false
        val body = JSONObject().put("context", context()).put("feedbackTokens", JSONArray().apply { tokens.distinct().forEach { put(it) } })
            .put("isFeedbackTokenUnencrypted", false).put("shouldMerge", false)
        val r = post("feedback", body)
        val ok = r.optJSONArray("feedbackResponses")?.let { arr -> (0 until arr.length()).any { arr.optJSONObject(it)?.optBoolean("isProcessed") == true } }
        return ok ?: true
    }

    // ---------- actions (like YouTube) ----------

    /** What the signed-in user did on a video: subscribed to its channel? liked / disliked? */
    class VideoState(val subscribed: Boolean?, val likeStatus: String?, val channelId: String?)

    /** Blocking. Subscription and like state for a video. */
    fun videoState(videoId: String): VideoState {
        val json = post("next", JSONObject().put("context", context()).put("videoId", videoId))
        val sub = find(json, "subscribeButtonRenderer")
        val subscribed = sub?.let { if (it.has("subscribed")) it.optBoolean("subscribed") else null }
            ?: findValue(json, "subscriptionStateEntity")?.let { (it as? JSONObject)?.optBoolean("subscribed") }
        val like = (findValue(json, "likeStatus") as? String)
        val channelId = sub?.optString("channelId")?.takeIf { it.isNotBlank() }
        return VideoState(subscribed, like, channelId)
    }

    /** Blocking. Is the user subscribed to this channel? (null = unknown) */
    fun channelSubscribed(channelId: String): Boolean? {
        val json = post("browse", JSONObject().put("context", context()).put("browseId", channelId))
        find(json, "subscribeButtonRenderer")?.let { if (it.has("subscribed")) return it.optBoolean("subscribed") }
        return (findValue(json, "subscriptionStateEntity") as? JSONObject)?.optBoolean("subscribed")
    }

    /** Blocking. Subscribe to / unsubscribe from a channel ("UC..."). */
    fun subscribe(channelId: String, on: Boolean) {
        val body = JSONObject().put("context", context()).put("channelIds", JSONArray().put(channelId))
        post(if (on) "subscription/subscribe" else "subscription/unsubscribe", body)
    }

    /** Blocking. "LIKE", "DISLIKE" or "INDIFFERENT" (remove). */
    fun rate(videoId: String, status: String) {
        val endpoint = when (status) {
            "LIKE" -> "like/like"
            "DISLIKE" -> "like/dislike"
            else -> "like/removelike"
        }
        post(endpoint, JSONObject().put("context", context()).put("target", JSONObject().put("videoId", videoId)))
    }

    /** Blocking. Adds a video to Watch later. */
    fun watchLater(videoId: String) {
        val body = JSONObject().put("context", context()).put("playlistId", "WL").put(
            "actions", JSONArray().put(JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId))
        )
        post("browse/edit_playlist", body)
    }

    // ---------- Save to playlist (like YouTube's "Save video to…") ----------

    /** One of your playlists in the Save sheet: [has] = this video is already in it. */
    class PlaylistOption(val id: String, val title: String, var has: Boolean, val privacy: String)

    /** Blocking. Your playlists (Watch later first), each saying whether [videoId] is in it. */
    fun saveOptions(videoId: String): List<PlaylistOption> {
        val json = post("playlist/get_add_to_playlist", JSONObject().put("context", context())
            .put("videoIds", JSONArray().put(videoId)).put("excludeWatchLater", false))
        val out = mutableListOf<PlaylistOption>()
        fun walk(n: Any?) {
            when (n) {
                is JSONObject -> {
                    n.optJSONObject("playlistAddToOptionRenderer")?.let { r ->
                        val id = r.optString("playlistId")
                        val title = text(r.optJSONObject("title")) ?: return@let
                        if (id.isNotBlank()) out += PlaylistOption(
                            id, title, r.optString("containsSelectedVideos") == "ALL",
                            text(r.optJSONObject("privacy"))?.takeIf { it.isNotBlank() } ?: r.optString("privacy")
                        )
                        return
                    }
                    val keys = n.keys()
                    while (keys.hasNext()) walk(n.opt(keys.next()))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
            }
        }
        walk(json)
        return out.sortedByDescending { it.id == "WL" }
    }

    /** Blocking. Adds [videoId] to (or removes it from) the playlist [playlistId]. */
    fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) {
        val action = if (add) JSONObject().put("action", "ACTION_ADD_VIDEO").put("addedVideoId", videoId)
        else JSONObject().put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").put("removedVideoId", videoId)
        post("browse/edit_playlist", JSONObject().put("context", context()).put("playlistId", playlistId)
            .put("actions", JSONArray().put(action)))
    }

    /** Blocking. A new playlist ("PRIVATE", "UNLISTED" or "PUBLIC") with [videoId] in it. Returns its id. */
    fun createPlaylist(title: String, privacy: String, videoId: String): String? {
        val json = post("playlist/create", JSONObject().put("context", context()).put("title", title)
            .put("privacyStatus", privacy).put("videoIds", JSONArray().put(videoId)))
        return json.optString("playlistId").takeIf { it.isNotBlank() }
    }

    // ---------- Not interested / Don't recommend channel ----------

    /** Blocking. Tells YouTube (a token from the video's ⋯ menu in your feed). */
    fun sendFeedback(token: String) {
        post("feedback", JSONObject().put("context", context()).put("feedbackTokens", JSONArray().put(token))
            .put("isFeedbackTokenUnencrypted", false).put("shouldMerge", false))
    }

    /** The feed menu's "Not interested" and "Don't recommend channel" tokens of one video ([r] = its renderer). */
    private fun feedbackTokens(r: JSONObject): Pair<String?, String?> {
        var notInterested: String? = null
        var noChannel: String? = null
        fun label(o: JSONObject): String? =
            text(o.optJSONObject("text")) ?: o.optJSONObject("title")?.let { t -> t.optString("content").takeIf { it.isNotBlank() } ?: text(t) }
        fun walk(n: Any?, lab: String?) {
            when (n) {
                is JSONObject -> {
                    val here = label(n)?.lowercase() ?: lab
                    n.optJSONObject("feedbackEndpoint")?.optString("feedbackToken")?.takeIf { it.isNotBlank() }?.let { tok ->
                        when {
                            here == null -> {}
                            here.contains("not interested") && notInterested == null -> notInterested = tok
                            here.contains("recommend") && here.contains("channel") && noChannel == null -> noChannel = tok
                        }
                    }
                    val keys = n.keys()
                    while (keys.hasNext()) walk(n.opt(keys.next()), here)
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), lab)
            }
        }
        walk(r, null)
        return notInterested to noChannel
    }

    // ---------- watch history on your YouTube account ----------

    /**
     * Blocking. Adds a video to the signed-in account's YouTube watch history, the way YouTube's own player does
     * (it reports that playback started). Best effort: false when YouTube didn't take it.
     */
    fun addToHistory(videoId: String): Boolean {
        val cookie = cookies ?: return false
        val sid = sapisid(cookie) ?: return false
        val json = post("player", JSONObject().put("context", context()).put("videoId", videoId))
        val base = (findValue(json.optJSONObject("playbackTracking"), "videostatsPlaybackUrl") as? JSONObject)
            ?.optString("baseUrl")?.takeIf { it.startsWith("http") } ?: return false
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val cpn = (1..16).map { abc[java.util.concurrent.ThreadLocalRandom.current().nextInt(abc.length)] }.joinToString("")
        val url = base + (if (base.contains('?')) "&" else "?") + "ver=2&c=WEB&cpn=$cpn"
        val ts = System.currentTimeMillis() / 1000
        val con = URL(url).openConnection() as HttpURLConnection
        return try {
            con.connectTimeout = 15_000
            con.readTimeout = 15_000
            con.setRequestProperty("User-Agent", UA)
            con.setRequestProperty("Cookie", cookie)
            con.setRequestProperty("Authorization", "SAPISIDHASH ${ts}_" + sha1("$ts $sid $ORIGIN"))
            con.setRequestProperty("Origin", ORIGIN)
            con.setRequestProperty("X-Goog-AuthUser", "0")
            con.responseCode in 200..299
        } finally {
            con.disconnect()
        }
    }

    /** Blocking. Posts a comment on a video as the signed-in user. */
    fun postComment(videoId: String, text: String) {
        // 1) the video's comment section (its first page carries the "add a comment" key)
        val page = post("next", JSONObject().put("context", context()).put("videoId", videoId))
        val section = findSection(page, "comment-item-section") ?: page
        val token = (findValue(section, "continuationCommand") as? JSONObject)?.optString("token")?.takeIf { it.isNotBlank() }
            ?: error("Comments are turned off for this video")
        val comments = post("next", JSONObject().put("context", context()).put("continuation", token))
        val params = findValue(comments, "createCommentParams") as? String ?: error("You can't comment on this video")
        // 2) the comment itself
        post(
            "comment/create_comment",
            JSONObject().put("context", context()).put("createCommentParams", params).put("commentText", text)
        )
    }

    /** The object whose "sectionIdentifier" is [id] (YouTube's comment section). */
    internal fun findSection(node: Any?, id: String): JSONObject? {
        when (node) {
            is JSONObject -> {
                if (node.optString("sectionIdentifier") == id) return node
                val keys = node.keys()
                while (keys.hasNext()) findSection(node.opt(keys.next()), id)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findSection(node.opt(i), id)?.let { return it }
        }
        return null
    }

    // ---------- reading YouTube's answers ----------

    private val VIDEO_KEYS = listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "videoWithContextRenderer", "playlistVideoRenderer")

    private fun collect(node: Any?, out: MutableList<VideoItem>, next: Array<String?>) {
        when (node) {
            is JSONObject -> {
                for (k in VIDEO_KEYS) node.optJSONObject(k)?.let { r -> video(r)?.let { out += it }; return }
                node.optJSONObject("lockupViewModel")?.let { r -> lockup(r)?.let { out += it }; return }
                for (k in CHANNEL_KEYS) node.optJSONObject(k)?.let { r -> channel(r)?.let { out += it }; return }
                node.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { next[0] = it }
                node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { next[0] = it }
                val keys = node.keys()
                while (keys.hasNext()) collect(node.opt(keys.next()), out, next)
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, next)
        }
    }

    private val CHANNEL_KEYS = listOf("channelRenderer", "gridChannelRenderer", "channelListItemRenderer")

    private fun channel(r: JSONObject): VideoItem? {
        val id = r.optString("channelId").takeIf { it.isNotBlank() } ?: return null
        val name = text(r.optJSONObject("title")) ?: return null
        val details = listOfNotNull(
            text(r.optJSONObject("subscriberCountText")), text(r.optJSONObject("videoCountText"))
        ).joinToString(" • ")
        return VideoItem(
            url = "https://www.youtube.com/channel/$id", title = name, uploader = "",
            thumb = lastThumb(r.optJSONObject("thumbnail")), seconds = 0, views = -1,
            uploaded = details.ifBlank { null }, isChannel = true
        )
    }

    private fun video(r: JSONObject): VideoItem? {
        val id = r.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(r.optJSONObject("title")) ?: text(r.optJSONObject("headline")) ?: return null
        val owner = text(r.optJSONObject("ownerText")) ?: text(r.optJSONObject("shortBylineText"))
            ?: text(r.optJSONObject("longBylineText")) ?: ""
        val length = text(r.optJSONObject("lengthText")) ?: badgeTime(r.opt("thumbnailOverlays"))
        val details = listOfNotNull(
            text(r.optJSONObject("shortViewCountText")) ?: text(r.optJSONObject("viewCountText")),
            text(r.optJSONObject("publishedTimeText"))
        ).joinToString(" • ")
        val avatar = lastThumb(
            r.optJSONObject("channelThumbnailSupportedRenderers")?.optJSONObject("channelThumbnailWithLinkRenderer")?.optJSONObject("thumbnail")
                ?: r.optJSONObject("channelThumbnail")
        )
        val (ni, nc) = feedbackTokens(r)
        return VideoItem(
            url = "https://www.youtube.com/watch?v=$id", title = title, uploader = owner,
            thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg", seconds = seconds(length),
            views = -1, uploaded = details.ifBlank { null }, avatar = avatar,
            notInterestedToken = ni, noChannelToken = nc
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
        // the time badge ("19:10"): YouTube moves it between overlay kinds, so look in all of them
        val badge = badgeTime(thumbVm?.opt("overlays")) ?: badgeTime(r.optJSONObject("contentImage"))
        val avatar = meta.optJSONObject("image")?.optJSONObject("decoratedAvatarViewModel")?.optJSONObject("avatar")
            ?.optJSONObject("avatarViewModel")?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        val playlist = type.contains("PLAYLIST") || type.contains("ALBUM")
        val (ni, nc) = feedbackTokens(r)
        return VideoItem(
            url = if (playlist) "https://www.youtube.com/playlist?list=$id" else "https://www.youtube.com/watch?v=$id",
            title = title,
            uploader = lines.firstOrNull().orEmpty(),
            thumb = thumb ?: if (playlist) null else "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            seconds = if (playlist) 0 else seconds(badge),
            views = -1,
            uploaded = lines.drop(1).joinToString(" • ").ifBlank { null },
            isPlaylist = playlist,
            avatar = avatar,
            notInterestedToken = ni, noChannelToken = nc
        )
    }

    private val TIME = Regex("^\\d{1,2}(:\\d{2}){1,2}$")

    /** The video length shown on a thumbnail ("19:10", "1:02:03" or "LIVE"), wherever it sits in [node]. */
    private fun badgeTime(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                for (k in listOf("thumbnailBadgeViewModel", "thumbnailOverlayTimeStatusRenderer")) {
                    val o = node.optJSONObject(k) ?: continue
                    val t = (o.optString("text").takeIf { it.isNotBlank() } ?: text(o.optJSONObject("text")))?.trim()
                    if (t != null && (TIME.matches(t) || t.equals("LIVE", true))) return t
                }
                val keys = node.keys()
                while (keys.hasNext()) badgeTime(node.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) badgeTime(node.opt(i))?.let { return it }
        }
        return null
    }

    /** "4:01" / "1:02:03" -> seconds (0 when unknown, -1 for LIVE). */
    private fun seconds(text: String?): Long {
        val t = text?.trim().orEmpty()
        if (t.equals("LIVE", true)) return -1
        val parts = t.split(':').map { it.trim().toLongOrNull() ?: return 0 }
        return parts.fold(0L) { acc, v -> acc * 60 + v }
    }

    internal fun text(o: JSONObject?): String? {
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

    /** First value stored under [key] anywhere in [node]. */
    internal fun findValue(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.opt(key)
                val keys = node.keys()
                while (keys.hasNext()) findValue(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findValue(node.opt(i), key)?.let { return it }
        }
        return null
    }

    /** First object stored under [key] anywhere in [node]. */
    internal fun find(node: Any?, key: String): JSONObject? {
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
