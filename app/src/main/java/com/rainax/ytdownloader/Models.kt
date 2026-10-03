package com.rainax.ytdownloader

import android.graphics.Bitmap

enum class Status { QUEUED, RUNNING, PAUSED, WAITING, DONE, FAILED }

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val format: String,          // "video:1080", "video:0" (best), "audio:m4a"
    val thumbPath: String?,
    val status: Status,
    val progress: Int,
    val message: String,
    val retries: Int,
    val fileUri: String?,
    val mime: String?,
    val createdAt: Long,
    val subLang: String? = null,
    val thumbUrl: String? = null
) {
    val isAudio: Boolean
        get() = format.startsWith("audio") || (mime?.startsWith("audio") == true)
}

const val KIND_AUDIO = 0
const val KIND_VIDEO = 1
const val KIND_NONE = 2

/** One selectable row in the download sheet (Audio, High quality (720p), ...). */
data class FormatChoice(
    val spec: String,
    val title: String,
    val desc: String = "",
    val size: String = "",
    val kind: Int = KIND_VIDEO,
    val badge: String? = null
)

data class SubtitleOption(val code: String, val label: String)

data class PlaylistEntry(val url: String, val title: String, val thumbUrl: String? = null, val seconds: Long = 0)

/**
 * Rough download size of a whole playlist for a quality choice, from the videos' total length
 * (typical YouTube bitrates, picture + sound). Shown as "≈ 1.2 GB".
 */
fun estimatePlaylistBytes(spec: String, seconds: Long): Long {
    val perSecond = when {
        spec.startsWith("audio") -> 16_500L            // ~128 kbit/s M4A
        else -> when (spec.split(':').getOrNull(1)?.toIntOrNull() ?: 0) {
            in 1..240 -> 40_000L
            in 241..360 -> 90_000L
            in 361..480 -> 150_000L
            in 481..720 -> 250_000L
            in 721..1080 -> 500_000L
            in 1081..1440 -> 1_100_000L
            in 1441..Int.MAX_VALUE -> 2_300_000L
            else -> 500_000L                            // best available: at least 1080p
        }
    }
    return perSecond * seconds
}

data class EnqueueItem(
    val url: String,
    val title: String = "",
    val cookie: String? = null,
    val thumbUrl: String? = null
)

data class PreviewState(
    val loading: Boolean = false,
    val title: String? = null,
    val subtitle: String? = null,
    val thumb: Bitmap? = null,
    val error: String? = null,
    val raw: String? = null,
    val quick: List<FormatChoice> = emptyList(),
    val all: List<FormatChoice> = emptyList(),
    val subtitles: List<SubtitleOption> = emptyList(),
    val playlist: List<PlaylistEntry> = emptyList(),
    // for background play (no extra lookup when the headphones button is tapped)
    val audioUrl: String? = null,
    val thumbUrl: String? = null,
    val uploader: String? = null,
    val related: List<PlaylistEntry> = emptyList()      // "up next" videos: the background player's next tracks
)

/** Used when we have no format info (several links at once, playlists, or a failed lookup). */
val PRESETS = listOf(
    FormatChoice("audio:m4a", "Audio (M4A)", "Original quality, plays on all phones", "", KIND_AUDIO),
    FormatChoice("video:360", "Fast (360p)", "", "", KIND_VIDEO),
    FormatChoice("video:720", "High quality (720p)", "", "", KIND_VIDEO),
    FormatChoice("video:1080", "High quality (1080p)", "", "", KIND_VIDEO),
    FormatChoice("video:0", "Best available", "", "", KIND_VIDEO)
)
