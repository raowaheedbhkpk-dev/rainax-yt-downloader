package com.rainax.ytdownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    init {
        TaskRepository.init(app)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Engine.ensureInit(app) }
            // yt-dlp is checked and updated in the background every time the app opens, then every 6 hours
            var first = true
            while (true) {
                val busy = TaskRepository.tasks.value.any { it.status == Status.RUNNING || it.status == Status.QUEUED }
                val due = first || System.currentTimeMillis() - AppPrefs.lastUpdate(app) > UPDATE_EVERY_MS
                if (!busy && due) {         // never swap yt-dlp underneath a running download
                    updateEngine()
                    first = false
                    _events.tryEmit(ENGINE_REFRESHED)
                }
                delay(CHECK_EVERY_MS)
            }
        }
    }

    /** Silent yt-dlp update. Returns true when it ran without error. */
    private fun updateEngine(): Boolean = try {
        Engine.ensureInit(app)
        YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
        AppPrefs.setLastUpdate(app, System.currentTimeMillis())
        true
    } catch (e: Exception) {
        false
    }

    private var updating = false

    /** Settings > Update yt-dlp now. */
    fun updateNow() {
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
                "yt-dlp: " + (result?.toString()?.replace('_', ' ')?.lowercase() ?: "updated")
            } catch (e: Exception) {
                "Update failed: ${e.readable()}"
            }
            updating = false
            _events.emit(message)
            _events.emit(ENGINE_REFRESHED)
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
        val raw = e.readable()
        PreviewState(error = friendlyError(raw), raw = raw)
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
            var result = doFetch(url, cookie, forcePlaylist)
            // Just opened from Share and the phone's network is not ready yet? Quietly try again.
            var tries = 0
            while (result.error != null && isNetworkGlitch(result.raw) && tries < 3) {
                tries++
                _preview.value = PreviewState(loading = true, title = "Connecting…")
                delay(1500L * tries)
                result = doFetch(url, cookie, forcePlaylist)
            }
            // A site changed and the engine is out of date? Update it quietly and try once more.
            if (result.error != null && looksOutdated(result.raw) &&
                System.currentTimeMillis() - AppPrefs.lastUpdate(app) > AUTO_FIX_GAP_MS
            ) {
                _preview.value = PreviewState(loading = true, title = "Updating the download engine…")
                val updated = withContext(Dispatchers.IO) { updateEngine() }
                if (updated) {
                    _preview.value = PreviewState(loading = true)
                    result = doFetch(url, cookie, forcePlaylist)
                }
            }
            _preview.value = result
        }
    }

    /** Short network hiccups: no DNS answer yet, timeouts, connection resets. */
    private fun isNetworkGlitch(raw: String?): Boolean {
        val m = raw?.lowercase() ?: return false
        return listOf(
            "errno 7", "no address associated", "name resolution", "temporary failure",
            "network is unreachable", "timed out", "connection reset", "connection refused",
            "transporterror", "unable to download webpage"
        ).any { it in m }
    }

    private fun looksOutdated(raw: String?): Boolean {
        val m = raw?.lowercase() ?: return false
        return listOf(
            "unable to extract", "nsig", "signature", "unsupported url", "http error 403",
            "no video formats", "extractor error", "player response", "precondition check failed"
        ).any { it in m }
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

    companion object {
        /** Not shown to the user: tells the screen to refresh the "last updated" text. */
        const val ENGINE_REFRESHED = "\u0000engine"

        private const val UPDATE_EVERY_MS = 6 * 3600 * 1000L      // check for a new yt-dlp every 6 hours
        private const val CHECK_EVERY_MS = 30 * 60 * 1000L        // while the app is open
        private const val AUTO_FIX_GAP_MS = 30 * 60 * 1000L       // at most one quick fix per 30 minutes
    }
}
