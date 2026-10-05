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
        "errno 7" in m || "no address associated" in m || "name resolution" in m ||
            "network is unreachable" in m || "timed out" in m || "unable to resolve host" in m ->
            "Can't reach the site right now. Check your internet connection, then tap Retry."
        else -> raw
    }
}
