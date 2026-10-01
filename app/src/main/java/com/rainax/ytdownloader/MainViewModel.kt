package com.rainax.ytdownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val _preview = MutableStateFlow<PreviewState?>(null)
    val preview: StateFlow<PreviewState?> = _preview.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var infoJob: Job? = null
    private var updating = false

    init {
        TaskRepository.init(app)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Engine.ensureInit(app) }
            // Daily background update keeps TikTok / Instagram / YouTube extractors working
            val now = System.currentTimeMillis()
            // never swap yt-dlp underneath a running download
            val busy = TaskRepository.tasks.value.any { it.status == Status.RUNNING || it.status == Status.QUEUED }
            if (!busy && AppPrefs.autoUpdate(app) && now - AppPrefs.lastUpdate(app) > 24 * 3600 * 1000L) {
                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                }.onSuccess { AppPrefs.setLastUpdate(app, now) }
            }
        }
    }

    private fun cookiePath(url: String, cookie: String?): String? =
        cookie?.let { CookieHelper.writeFile(app, "info", url, it) } ?: CookieHelper.fresh(app, "info", url)

    private suspend fun doFetch(url: String, cookie: String?, forcePlaylist: Boolean): PreviewState = try {
        withContext(Dispatchers.IO) {
            if (forcePlaylist || isPlaylistUrl(url)) {
                InfoFetcher.fetchPlaylist(app, url, cookiePath(url, cookie))
            } else {
                InfoFetcher.fetch(app, url, cookiePath(url, cookie))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PreviewState(error = friendlyError(e.readable()))
    }

    /** Kills a lookup that is still running so old requests don't pile up. */
    private fun stopInfoProcess() {
        try { YoutubeDL.getInstance().destroyProcessById(InfoFetcher.PROCESS_ID) } catch (e: Exception) { }
    }

    fun fetchInfo(url: String, cookie: String?, forcePlaylist: Boolean = false) {
        infoJob?.cancel()
        stopInfoProcess()
        infoJob = viewModelScope.launch {
            _preview.value = PreviewState(loading = true)
            _preview.value = doFetch(url, cookie, forcePlaylist)
        }
    }

    /** Sheet's "Update & retry": refresh yt-dlp, then look the link up again. */
    fun updateAndRefetch(url: String, cookie: String?) {
        infoJob?.cancel()
        stopInfoProcess()
        infoJob = viewModelScope.launch {
            _preview.value = PreviewState(loading = true, title = "Updating yt-dlp…")
            withContext(Dispatchers.IO) {
                runCatching {
                    Engine.ensureInit(app)
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                    AppPrefs.setLastUpdate(app, System.currentTimeMillis())
                }
            }
            _preview.value = PreviewState(loading = true)
            _preview.value = doFetch(url, cookie, false)
        }
    }

    fun clearPreview() {
        infoJob?.cancel()
        stopInfoProcess()
        _preview.value = null
    }

    /** Queues the links; the foreground service does the actual downloading. */
    fun enqueue(items: List<EnqueueItem>, spec: String, subLang: String?) {
        val info = _preview.value?.takeIf { items.size == 1 && !it.loading && it.error == null }
        viewModelScope.launch(Dispatchers.IO) {
            Downloader.enqueue(app, items, spec, subLang, info)
            _events.emit(if (items.size == 1) "Added to downloads" else "Added ${items.size} items to downloads")
        }
    }

    fun updateYtDlp() {
        if (updating) return
        updating = true
        viewModelScope.launch {
            _events.emit("Updating yt-dlp…")
            val message = try {
                val result = withContext(Dispatchers.IO) {
                    Engine.ensureInit(app)
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                }
                AppPrefs.setLastUpdate(app, System.currentTimeMillis())
                "yt-dlp update: " + (result?.toString()?.replace('_', ' ')?.lowercase() ?: "done")
            } catch (e: Exception) {
                "Update failed: ${e.readable()}"
            }
            updating = false
            _events.emit(message)
        }
    }
}
