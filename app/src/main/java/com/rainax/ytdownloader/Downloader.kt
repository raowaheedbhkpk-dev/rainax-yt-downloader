package com.rainax.ytdownloader

import android.content.Context
import java.util.UUID

/** Turns links into queued tasks and wakes the download service. */
object Downloader {

    fun enqueue(context: Context, items: List<EnqueueItem>, spec: String, subLang: String?, info: PreviewState?) {
        TaskRepository.init(context)
        val single = items.size == 1
        val now = System.currentTimeMillis()
        val tasks = items.mapIndexed { index, item ->
            val id = UUID.randomUUID().toString()
            val thumb = if (single) info?.thumb?.let { InfoFetcher.saveThumb(context, id, it) } else null
            DownloadTask(
                id = id,
                url = item.url,
                title = item.title.ifBlank { if (single) info?.title.orEmpty() else "" },
                format = spec,
                thumbPath = thumb,
                status = Status.QUEUED,
                progress = 0,
                message = "Queued",
                retries = 0,
                fileUri = null,
                mime = null,
                createdAt = now + index,
                subLang = subLang,
                thumbUrl = item.thumbUrl
            )
        }
        TaskRepository.addAll(tasks)         // a single write, even for a 500-video playlist
        DownloadService.send(context, DownloadService.ACTION_PUMP)
    }
}
