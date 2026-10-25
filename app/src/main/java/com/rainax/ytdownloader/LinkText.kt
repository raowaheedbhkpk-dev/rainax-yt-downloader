package com.rainax.ytdownloader

/**
 * Finds the links in text copied or shared from another app.
 *
 * Apps add their own advert links to what they share: TikTok Lite puts "https://www.tiktok.com/tiktoklite"
 * after the video link, others add "get the app" pages. Those were taken as a second download that only
 * kept failing ("Retrying 1/5"). They are left out now; if the text has nothing else, the links are kept.
 */
object LinkText {

    private val URL = Regex("https?://\\S+")

    fun find(text: String, max: Int = 1000): List<String> {
        val all = URL.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '"', '\'', '!', '?', ';', ':', '>', '»', '”', '’') }
            .filter { it.length > 10 }
            .distinct()
            .take(max)
            .toList()
        val real = all.filterNot { isAppAdvert(it) }
        return real.ifEmpty { all }
    }

    /** A link to an app's own home or "get the app" page, not to a video or post. */
    fun isAppAdvert(url: String): Boolean {
        val u = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
        val path = u.path.orEmpty().trim('/').lowercase()
        val hasQueryTarget = !u.query.isNullOrBlank() && (u.getQueryParameter("v") != null || u.getQueryParameter("story_fbid") != null)
        if (path.isEmpty() && !hasQueryTarget) {
            // a bare site address (www.tiktok.com, instagram.com…) is never a video
            return SITES.any { host == it || host.endsWith(".$it") }
        }
        if (host == "tiktok.com" || host.endsWith(".tiktok.com")) {
            if (host.startsWith("vm.") || host.startsWith("vt.")) return false        // short video links
            return path in TIKTOK_PAGES || path.startsWith("download") || path.startsWith("tiktoklite")
        }
        if (host == "play.google.com" || host == "apps.apple.com" || host == "onelink.me" || host.endsWith(".onelink.me")
            || host == "app.link" || host.endsWith(".app.link")) return true
        return false
    }

    private val SITES = listOf(
        "tiktok.com", "instagram.com", "facebook.com", "fb.com", "twitter.com", "x.com", "youtube.com",
        "snapchat.com", "likee.video", "pinterest.com", "threads.net", "whatsapp.com", "soundcloud.com"
    )
    private val TIKTOK_PAGES = setOf("tiktoklite", "lite", "download", "foryou", "en", "explore", "about", "app")
}
