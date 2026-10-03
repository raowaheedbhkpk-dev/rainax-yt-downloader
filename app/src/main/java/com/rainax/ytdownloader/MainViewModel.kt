package com.rainax.ytdownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
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
    }

    // ---------- instant info: cache + look-ahead ----------
    // While a YouTube video is open we look it up quietly in the background, and keep results for 30 minutes,
    // so the download sheet usually has the sizes at once.
    private class Cached(val at: Long, val state: PreviewState)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    @Volatile private var aheadJob: Deferred<PreviewState>? = null
    @Volatile private var aheadKey: String? = null

    private fun cacheKey(url: String): String {
        if (!FastExtractor.supports(url)) return url.trim()        // video ids only mean something on YouTube
        return Regex("(?:[?&]v=|youtu\\.be/|shorts/)([\\w-]{6,})").find(url)?.groupValues?.get(1) ?: url.trim()
    }

    private fun cached(url: String): PreviewState? {
        val c = cache[cacheKey(url)] ?: return null
        if (System.currentTimeMillis() - c.at > CACHE_MS) { cache.remove(cacheKey(url)); return null }
        return c.state
    }

    private fun remember(url: String, state: PreviewState) {
        if (state.error != null || state.loading) return
        if (cache.size > 15) cache.clear()        // each entry holds a thumbnail: keep memory small
        cache[cacheKey(url)] = Cached(System.currentTimeMillis(), state)
    }

    /**
     * Called while a YouTube video page stays open: fetch its info before the user taps Download.
     */
    fun prefetch(url: String) {
        if (!FastExtractor.supports(url)) return
        val key = cacheKey(url)
        if (cached(url) != null || aheadKey == key) return
        aheadJob?.cancel()                       // only one look-ahead at a time
        aheadKey = key
        val job = viewModelScope.async(Dispatchers.IO) {
            doFetch(url, false).also { remember(url, it) }
        }
        aheadJob = job
        job.invokeOnCompletion {
            if (aheadJob === job) { aheadJob = null; aheadKey = null }
        }
    }

    /** Reads info with RAINAX's own extractor. Never throws: problems come back as an error state. */
    private suspend fun doFetch(url: String, forcePlaylist: Boolean): PreviewState {
        val playlist = forcePlaylist || isPlaylistUrl(url)
        // Runs on its own, so a slow network can never hold us past the time limit
        val work = fastScope.async {
            runInterruptible { if (playlist) FastExtractor.fetchPlaylist(url) else FastExtractor.fetch(url) }
        }
        return try {
            withTimeoutOrNull(if (playlist) PLAYLIST_TIMEOUT_MS else INFO_TIMEOUT_MS) { work.await() } ?: run {
                work.cancel()
                PreviewState(error = "This is taking too long. Check your internet, then tap Retry.", raw = "timed out")
            }
        } catch (e: CancellationException) {
            work.cancel()
            currentCoroutineContext().ensureActive()     // we were cancelled: stop here
            PreviewState(error = "Stopped", raw = "stopped")
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            val raw = e.message?.takeIf { it.isNotBlank() } ?: FastExtractor.readable(e)
            PreviewState(error = friendlyError(raw), raw = raw)
        }
    }

    private val fastScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCleared() {
        fastScope.cancel()
        aheadJob?.cancel()
        super.onCleared()
    }

    @Suppress("UNUSED_PARAMETER")
    fun fetchInfo(url: String, cookie: String?, forcePlaylist: Boolean = false) {
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            // Already looked up (or being looked up in the background)? Use that instead of starting again.
            if (!forcePlaylist) {
                cached(url)?.let { _preview.value = it; return@launch }
            }
            _preview.value = PreviewState(loading = true)
            val ahead = aheadJob?.takeIf { !forcePlaylist && aheadKey == cacheKey(url) && it.isActive }
            var result = if (ahead != null) {
                val r = try {
                    ahead.await()
                } catch (e: CancellationException) {
                    ensureActive()               // the sheet was closed: stop
                    null
                }
                r?.takeIf { it.error == null } ?: doFetch(url, forcePlaylist)
            } else doFetch(url, forcePlaylist)
            // Just opened from Share and the phone's network is not ready yet? Quietly try again.
            var tries = 0
            while (result.error != null && isNetworkGlitch(result.raw) && tries < 3) {
                tries++
                _preview.value = PreviewState(loading = true, title = "Connecting…")
                delay(1500L * tries)
                result = doFetch(url, forcePlaylist)
            }
            remember(url, result)
            _preview.value = result
        }
    }

    /** Short network hiccups: no DNS answer yet, timeouts, connection resets. */
    private fun isNetworkGlitch(raw: String?): Boolean {
        val m = raw?.lowercase() ?: return false
        return listOf(
            "errno 7", "no address associated", "name resolution", "temporary failure",
            "network is unreachable", "timed out", "connection reset", "connection refused",
            "unable to resolve host", "failed to connect", "connection abort", "socket", "timeout"
        ).any { it in m }
    }

    fun clearPreview() {
        infoJob?.cancel()
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
        private const val INFO_TIMEOUT_MS = 25_000L               // one video
        private const val PLAYLIST_TIMEOUT_MS = 120_000L          // up to 1000 playlist entries
        private const val CACHE_MS = 30 * 60 * 1000L              // looked-up info stays fresh for 30 minutes
    }
}
