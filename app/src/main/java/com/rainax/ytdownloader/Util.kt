package com.rainax.ytdownloader

fun Exception.readable(): String {
    val last = message?.lineSequence()?.map { it.trim() }?.lastOrNull { it.isNotEmpty() }
    return (last ?: javaClass.simpleName).take(300)
}

/** Errors where retrying is pointless (bad link, removed video, ...). */
fun String.isFatalError(): Boolean {
    val m = lowercase()
    return listOf(
        "unsupported url", "video unavailable", "private video", "is not available",
        "has been removed", "members-only", "copyright",
        "confirm your age", "inappropriate for some users", "join this channel",
        "live stream", "no downloadable", "no video format", "no audio found", "can't be downloaded",
        "enospc", "no space left", "not enough storage"
    ).any { it in m }
}

fun formatEta(seconds: Long): String = "${seconds / 60}:${"%02d".format(seconds % 60)}"

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""                           // unknown size
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

fun isPlaylistUrl(url: String): Boolean =
    (url.contains("list=") && !url.contains("v=") && !url.contains("youtu.be/")) ||
        url.contains("/playlist?")

/** Turns a raw error into something a person can act on. */
fun friendlyError(raw: String): String {
    val m = raw.lowercase()
    return when {
        raw.startsWith(YtFallback.REFUSED_TEXT) -> raw          // already says exactly what YouTube answered
        "private video" in m -> "Private video — skipped."
        "sign in to confirm" in m || "not a bot" in m ->
            "YouTube blocked this request. Try again in a while."
        "confirm your age" in m || "age-restricted" in m || "inappropriate for some users" in m ->
            "Age-restricted video — can't be downloaded."
        "members-only" in m || "join this channel" in m -> "Members-only video."
        "video unavailable" in m || "has been removed" in m || "is not available" in m ->
            "Video removed or unavailable."
        "copyright" in m -> "Blocked for copyright reasons."
        "enospc" in m || "no space left" in m || "not enough storage" in m ->
            "Not enough storage on your phone. Free some space, then tap Retry."
        "unsupported url" in m -> "This site isn't supported. RAINAX downloads from ${FastExtractor.SUPPORTED}."
        isNetworkError(m) -> if (Net.online()) "Connection problem. Check your internet, then tap Retry."
            else NO_INTERNET
        else -> raw
    }
}

const val NO_INTERNET = "No internet connection. Turn on Wi-Fi or mobile data, then try again."

/** Words of network errors (no connection, host not reachable, DNS, timeouts). */
fun isNetworkError(text: String): Boolean {
    val m = text.lowercase()
    return listOf(
        "errno 7", "no address associated", "name resolution", "network is unreachable", "timed out",
        "unable to resolve host", "host not reachable", "unknownhost", "failed to connect", "connection refused",
        "connection reset", "software caused connection abort", "no internet", "enetunreach", "econnrefused"
    ).any { it in m }
}

/** Is the phone connected to the internet right now? */
object Net {
    @Volatile private var app: android.content.Context? = null

    fun init(c: android.content.Context) { app = c.applicationContext }

    fun online(c: android.content.Context? = null): Boolean {
        val ctx = c?.applicationContext ?: app ?: return true
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Opens the phone's Wi-Fi / mobile data panel. */
    fun openSettings(c: android.content.Context) {
        val panel = android.content.Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
        val wireless = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        try { c.startActivity(panel) } catch (e: Exception) {
            try { c.startActivity(wireless) } catch (ignored: Exception) { }
        }
    }
}
