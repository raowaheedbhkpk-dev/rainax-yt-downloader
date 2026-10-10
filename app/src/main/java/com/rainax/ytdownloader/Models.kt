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
    val badge: String? = null,
    val bytes: Long = 0          // the real size behind [size] (sizes of several videos are added up)
)

data class SubtitleOption(val code: String, val label: String)

data class PlaylistEntry(val url: String, val title: String, val thumbUrl: String? = null, val seconds: Long = 0)

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
    // for background play (no extra lookup when it starts)
    val audioUrl: String? = null,
    val thumbUrl: String? = null,
    val uploader: String? = null,
    val related: List<PlaylistEntry> = emptyList()      // "up next" videos: the background player's next tracks
)
