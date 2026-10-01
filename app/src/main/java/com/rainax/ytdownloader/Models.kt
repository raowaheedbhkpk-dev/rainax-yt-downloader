package com.rainax.ytdownloader

import android.graphics.Bitmap

enum class Status { QUEUED, RUNNING, PAUSED, WAITING, DONE, FAILED }

data class DownloadTask(
    val id: String,
    val url: String,
    val title: String,
    val format: String,          // "video:1080", "video:0" (best), "audio:mp3:128", "audio:m4a"
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

/** One selectable row in the download sheet (Fast, Classic MP3, High quality (720p), ...). */
data class FormatChoice(
    val spec: String,
    val title: String,
    val desc: String = "",
    val size: String = "",
    val kind: Int = KIND_VIDEO,
    val badge: String? = null
)

data class SubtitleOption(val code: String, val label: String)

data class PlaylistEntry(val url: String, val title: String, val thumbUrl: String? = null)

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
    val playlist: List<PlaylistEntry> = emptyList()
)

/** Used when we have no format info (several links at once, playlists, or a failed lookup). */
val PRESETS = listOf(
    FormatChoice("audio:m4a", "Fast", "Original audio quality", "", KIND_AUDIO),
    FormatChoice("audio:mp3:128", "Classic MP3", "Works on all players", "", KIND_AUDIO),
    FormatChoice("video:360", "Fast (360p)", "", "", KIND_VIDEO),
    FormatChoice("video:720", "High quality (720p)", "", "", KIND_VIDEO),
    FormatChoice("video:1080", "High quality (1080p)", "", "", KIND_VIDEO),
    FormatChoice("video:0", "Best available", "", "", KIND_VIDEO)
)
