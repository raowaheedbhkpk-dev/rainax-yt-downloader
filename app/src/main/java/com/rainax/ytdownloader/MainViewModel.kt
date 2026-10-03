package com.rainax.ytdownloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
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

    /** Each lookup gets its own process id, so stopping an old one can never touch the new one. */
    @Volatile private var procId = InfoFetcher.PROCESS_ID

    // ---------- instant info: cache + look-ahead ----------
    // yt-dlp needs a few seconds per lookup on a phone. While a YouTube video is open we look it up quietly in the
    // background, and keep results for 30 minutes, so the download sheet usually has the sizes at once.
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
     * Uses only the fast Java extractor (light); yt-dlp never runs in the background just for browsing.
     */
    fun prefetch(url: String) {
        if (!FastExtractor.supports(url)) return
        val key = cacheKey(url)
        if (cached(url) != null || aheadKey == key) return
        aheadJob?.cancel()                       // only one look-ahead at a time
        aheadKey = key
        val job = viewModelScope.async(Dispatchers.IO) {
            (fastInfo(url) ?: PreviewState(error = "fast lookup failed")).also { remember(url, it) }
        }
        aheadJob = job
        job.invokeOnCompletion {
            if (aheadJob === job) { aheadJob = null; aheadKey = null }
        }
    }

    private suspend fun doFetch(url: String, forcePlaylist: Boolean, id: String = procId): PreviewState = try {
        withContext(Dispatchers.IO) {
            if (forcePlaylist || isPlaylistUrl(url)) InfoFetcher.fetchPlaylist(app, url, null, id)
            else fastInfo(url) ?: run {
                ensureActive()                   // cancelled meanwhile: don't start a yt-dlp nobody can stop
                InfoFetcher.fetch(app, url, null, id)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val raw = e.readable()
        PreviewState(error = friendlyError(raw), raw = raw)
    }

    /** YouTube videos: the fast Java extractor first (about a second); null means "use yt-dlp". */
    private suspend fun fastInfo(url: String): PreviewState? {
        if (!FastExtractor.supports(url)) return null
        // Runs on its own, so a slow network can never hold us past the time limit
        val work = fastScope.async { runInterruptible { FastExtractor.fetch(url) } }
        return try {
            withTimeoutOrNull(FAST_TIMEOUT_MS) { work.await() } ?: run { work.cancel(); null }
        } catch (e: CancellationException) {
            work.cancel()
            currentCoroutineContext().ensureActive()     // we were cancelled: stop here
            null
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            null       // YouTube changed something the fast extractor doesn't know yet: yt-dlp handles it
        }
    }

    private val fastScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCleared() {
        fastScope.cancel()
        aheadJob?.cancel()
        stopInfoProcess()
        super.onCleared()
    }

    /** Kills a lookup that is still running so old requests don't pile up. */
    private fun stopInfoProcess() {
        try { YoutubeDL.getInstance().destroyProcessById(procId) } catch (e: Exception) { }
    }

    @Suppress("UNUSED_PARAMETER")
    fun fetchInfo(url: String, cookie: String?, forcePlaylist: Boolean = false) {
        infoJob?.cancel()
        stopInfoProcess()
        procId = "info-" + System.nanoTime()
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
            // A site changed and the engine is out of date? Update it quietly and try once more.
            if (result.error != null && looksOutdated(result.raw) &&
                System.currentTimeMillis() - AppPrefs.lastUpdate(app) > AUTO_FIX_GAP_MS
            ) {
                _preview.value = PreviewState(loading = true, title = "Updating the download engine…")
                val updated = withContext(Dispatchers.IO) { updateEngine() }
                if (updated) {
                    _preview.value = PreviewState(loading = true)
                    result = doFetch(url, forcePlaylist)
                }
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
        private const val FAST_TIMEOUT_MS = 10_000L               // fast extractor gets 10 s, then yt-dlp takes over
        private const val CACHE_MS = 30 * 60 * 1000L              // looked-up info stays fresh for 30 minutes
    }
}
