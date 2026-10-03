package com.rainax.ytdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.LinearGradient
import android.graphics.Shader
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.rainax.ytdownloader.databinding.ActivityMainBinding
import com.rainax.ytdownloader.databinding.SheetDownloadBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val hm get() = b.homePage
    private val pl get() = b.playPage
    private val st get() = b.settingsPage

    private var tab = 0            // bottom navigation: 0 Download, 1 Play, 2 Settings
    private val topTab = 1         // the home screen is the YouTube browser
    private var pendingUrl: String? = null
    private var lastUrl = YT_HOME
    private var lastVideoTime = 0.0          // seconds into the open video (kept across theme changes)
    private var pageLoaded = false

    private var latestTasks: List<DownloadTask> = emptyList()
    private var selecting = false
    private val selected = linkedSetOf<String>()
    @Volatile private var adBlockOn = true
    private var sheet: BottomSheetDialog? = null

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val activeAdapter = DownloadAdapter(
        { onTaskAction(it) }, { t, v -> onTaskClose(t, v) }, { openFile(it) },
        { toggleSelect(it) }, { startSelect(it) }
    )
    private val doneAdapter = DownloadAdapter(
        { onTaskAction(it) }, { t, v -> onTaskClose(t, v) }, { openFile(it) },
        { toggleSelect(it) }, { startSelect(it) }
    )

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                AppPrefs.setSaveTree(this, uri.toString())
                renderFolder()
                message("New downloads will be saved in this folder")
            } catch (e: Exception) {
                message("That folder can't be used. Pick another one.")
            }
        }


    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                selecting && tab == 1 -> exitSelection()
                tab != 0 -> b.bottomNav.selectedItemId = R.id.nav_home
                hm.webView.canGoBack() -> hm.webView.goBack()
                else -> {                    // nothing to go back to: leave the app normally
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)   // pure black
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        // Phone makers (MIUI, ColorOS, ...) must not re-colour our screens: the app owns its light and dark themes
        b.root.isForceDarkAllowed = false
        onBackPressedDispatcher.addCallback(this, backCallback)

        TaskRepository.init(applicationContext)
        adBlockOn = AppPrefs.adBlock(this)
        if (AppPrefs.autoClear(this)) {
            TaskRepository.removeDoneOlderThan(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
        }
        if (savedInstanceState == null) requestNotificationPermission()

        setupHome()
        setupPlay()
        setupSettings()

        b.bottomNav.itemIconTintList = null     // icons colour themselves (keeps the profile photo in colour)
        updateAccountIcon()
        b.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> { showTab(0); true }
                R.id.nav_account -> { onAccountClick(); false }
                R.id.nav_downloads -> { showTab(1); true }
                R.id.nav_settings -> { showTab(2); true }
                else -> false
            }
        }

        // Anything left in the queue (app or service was killed) continues automatically
        val pending = TaskRepository.tasks.value.any {
            it.status == Status.QUEUED || it.status == Status.RUNNING || it.status == Status.WAITING
        }
        if (pending) DownloadService.send(this, DownloadService.ACTION_PUMP)

        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            // Theme switch or rotation rebuilds the screen: reopen the same page at the same second
            savedInstanceState.getString("webUrl")?.takeIf { isAllowedUrl(it) }?.let {
                lastUrl = withTime(it, savedInstanceState.getDouble("webTime", 0.0))
            }
            b.bottomNav.selectedItemId = when (savedInstanceState.getInt("tab", 0)) {
                1 -> R.id.nav_downloads
                2 -> R.id.nav_settings
                else -> R.id.nav_home
            }
        }
        showYoutube()

        // New RAINAX version? (quiet check, a few seconds after start)
        if (savedInstanceState == null) hm.root.postDelayed({ if (!isFinishing) AppUpdater.checkOnStart(this) }, 4000)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TaskRepository.tasks.collect { renderTasks(it) } }
                launch {
                    vm.events.collect {
                        message(it)
                    }
                }
                launch {
                    while (true) {          // YouTube changes pages without reloading: keep the buttons in sync
                        updateFloatingBar()
                        trackVideoTime()
                        lookAhead()
                        delay(800)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        if (pageLoaded) {
            (hm.webView.url ?: lastUrl).let { outState.putString("webUrl", it) }
            outState.putDouble("webTime", lastVideoTime)
        }
    }

    /** Adds the start second to a video link (watch pages only). */
    private fun withTime(url: String, sec: Double): String {
        if (sec < 2 || !url.contains("/watch")) return url
        val uri = Uri.parse(url)
        val b = uri.buildUpon().clearQuery()
        uri.queryParameterNames.filter { it != "t" }.forEach { n ->
            uri.getQueryParameters(n).forEach { v -> b.appendQueryParameter(n, v) }
        }
        b.appendQueryParameter("t", "${sec.toInt()}s")
        return b.build().toString()
    }

    /** Remembers how far the open video has played. */
    private fun trackVideoTime() {
        if (topTab != 1 || currentVideoUrl() == null) return
        hm.webView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return v?v.currentTime:0;})()"
        ) { r -> r?.replace("\"", "")?.toDoubleOrNull()?.let { if (it > 0) lastVideoTime = it } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        onLeaveApp()                  // Home / recent apps (the screen is still visible, so Android lets the player start)
    }

    override fun onPause() {
        snapshotVideo()
        hm.webView.onPause()
        CookieManager.getInstance().flush()      // keep the YouTube session
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        leftApp = false
        if (controller != null) handBackToVideo() else connectPlayer()   // the video continues from the sound
    }

    override fun onStop() {
        onLeaveApp()                  // screen off and other ways of leaving
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        AppUpdater.resumeInstall(this)
        if (leftApp) {               // back from recent apps without the screen being hidden
            leftApp = false
            handBackToVideo()
        }
        hm.webView.onResume()
    }

    override fun onDestroy() {
        disconnectPlayer()            // background sound keeps playing in its own service
        sheet?.dismiss()
        (hm.webView.parent as? ViewGroup)?.removeView(hm.webView)
        hm.webView.stopLoading()
        hm.webView.destroy()
        super.onDestroy()
    }

    // =====================================================================
    // Download tab: Search / YouTube / Music / More
    // =====================================================================

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHome() {
        // Floating buttons (native, never injected into the page)
        hm.nbDownload.setOnClickListener { currentVideoUrl()?.let { onDownloadClick(it, false) } }

        val ws = hm.webView.settings
        hm.webView.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(hm.webView, R.attr.rxBg))
        ws.forceDark = if (isDarkUi()) WebSettings.FORCE_DARK_AUTO else WebSettings.FORCE_DARK_OFF
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = true
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        // Sign in works like in Chrome: Google refuses sign-in from pages that say they are an app's web view
        ws.userAgentString = WebSettings.getDefaultUserAgent(this).replace("; wv)", ")")
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ws, emptySet())
            }
        } catch (e: Exception) { }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(hm.webView, true)

        hm.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val scheme = uri.scheme
                if (scheme != "http" && scheme != "https") return true
                val host = uri.host.orEmpty()
                // Sign-in passes through Google's country sites (google.com.pk, google.co.uk...) before YouTube
                if (isGoogleSignInHost(host)) return false
                return !isAllowedHost(host)      // in-app browsing is YouTube only (no YouTube Music)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (adBlockOn && request != null && isAdRequest(request.url)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                onPageChanged(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                // as early as possible: strip ads from the player data, hide ad frames
                if (url != null && isYoutubePage(url)) {
                    hm.webView.evaluateJavascript("window.__ytdlAdBlock=$adBlockOn;", null)
                    hm.webView.evaluateJavascript(AD_STRIP_JS, null)
                    hm.webView.evaluateJavascript(INJECT_JS, null)
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (url != null && isYoutubePage(url)) hm.webView.evaluateJavascript(INJECT_JS, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onPageChanged(url)
            }
        }
        // Runs before any page script (when the WebView supports it): ads never reach the player
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    hm.webView, AD_STRIP_JS + "\n" + INJECT_JS,
                    setOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com")
                )
            }
        } catch (e: Exception) { }
        hm.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                // remember which page this title belongs to (YouTube updates it a moment after the address)
                pageTitle = title
                pageTitleUrl = view?.url
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                hm.webProgress.isVisible = newProgress < 100
                hm.webProgress.setProgressCompat(newProgress, true)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null) enterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }
    }

    /**
     * YouTube remembers its own light/dark choice in a cookie, so it would keep the old look after the app theme
     * changes. Before every load we write the app's current theme into that cookie.
     */
    private fun syncYoutubeTheme() {
        try {
            val cm = CookieManager.getInstance()
            val site = "https://www.youtube.com"
            val pref = cm.getCookie(site).orEmpty().split(";").map { it.trim() }
                .firstOrNull { it.startsWith("PREF=") }?.removePrefix("PREF=").orEmpty()
            val map = linkedMapOf<String, String>()
            pref.split("&").filter { it.contains("=") }.forEach { map[it.substringBefore("=")] = it.substringAfter("=") }
            val old = map["f6"]?.toLongOrNull(16) ?: 0L
            val themeBits = 0x400L or 0x80000L                       // dark bit / light bit
            val now = (old and themeBits.inv()) or (if (isDarkUi()) 0x400L else 0x80000L)
            map["f6"] = java.lang.Long.toHexString(now)
            val value = map.entries.joinToString("&") { it.key + "=" + it.value }
            cm.setCookie(site, "PREF=$value; Domain=.youtube.com; Path=/; Secure; Max-Age=31536000")
            cm.flush()
        } catch (e: Exception) {
            // the page then simply follows the device theme
        }
    }

    private fun isDarkUi() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Ad servers and YouTube's ad tracking endpoints are answered with an empty response. */
    private fun isAdRequest(uri: Uri): Boolean {
        val host = uri.host.orEmpty().lowercase()
        if (AD_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        if (host.endsWith("youtube.com")) {
            val path = uri.path.orEmpty()
            return AD_PATHS.any { path.startsWith(it) }
        }
        return false
    }

    /** YouTube's own pages (not Google's sign-in pages, which are left untouched). */
    private fun isYoutubePage(url: String): Boolean {
        val h = Uri.parse(url).host.orEmpty().lowercase()
        return h == "youtube.com" || h.endsWith(".youtube.com") && !h.startsWith("accounts.")
    }

    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "music.youtube.com") return false
        return listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "google.com",
            "googleusercontent.com", "gstatic.com", "ytimg.com"
        ).any { h == it || h.endsWith(".$it") }
    }

    /** Loads YouTube in the home screen (once; afterwards the page keeps its place). */
    private fun showYoutube() {
        val target = pendingUrl ?: lastUrl
        if (pendingUrl != null || !pageLoaded) {
            syncYoutubeTheme()
            hm.webView.stopLoading()
            hm.webView.loadUrl(target)
            pageLoaded = true
        }
        pendingUrl = null
        updateFloatingBar()
        updateBack()
    }

    private fun openYoutube(url: String) {
        syncYoutubeTheme()
        hm.webView.loadUrl(url)
        pageLoaded = true
    }

    private fun onPageChanged(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        if (isAllowedUrl(url)) {
            if (url != lastUrl && Uri.parse(url).getQueryParameter("v") != Uri.parse(lastUrl).getQueryParameter("v")) lastVideoTime = 0.0
            lastUrl = url
        }
        // Hides "Open app" prompts and ads (the Download button is native, not part of the page)
        if (isYoutubePage(url)) {
            hm.webView.evaluateJavascript("window.__ytdlAdBlock=$adBlockOn;", null)
            hm.webView.evaluateJavascript(INJECT_JS, null)
        }
        updateFloatingBar()
        updateBack()
        lookAhead()                    // a new video page: start reading its info right away
        readAccount(url)
    }

    /** The video page that is open right now, or null when the page is not a video. */
    private fun currentVideoUrl(): String? {
        val url = hm.webView.url.orEmpty()
        val uri = Uri.parse(url)
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty()
        val yt = host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
        val video = host == "youtu.be" || path.startsWith("/watch") || path.startsWith("/shorts/") || path == "/playlist"
        return if (yt && video && host != "music.youtube.com") url else null
    }

    /** The floating Download buttons appear only on video pages of the YouTube tab. */
    private fun updateFloatingBar() {
        val url = currentVideoUrl()
        hm.floatingBar.isVisible = tab == 0 && topTab == 1 && customView == null && url != null
        val playlist = url != null && url.contains("/playlist?")
        val label = if (playlist) "Download playlist" else "Download"
        if (hm.nbDownload.text.toString() != label) hm.nbDownload.text = label
    }

    // =====================================================================
    // Background play (Settings switch): leaving the app while a video plays
    // continues its sound in BgPlayService; coming back continues the video.
    // =====================================================================

    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null
    private var controller: androidx.media3.session.MediaController? = null
    private var leftApp = false
    private var snapUrl: String? = null          // video playing when the screen was last paused
    private var snapSec = -1.0
    private var snapAt = 0L

    private fun connectPlayer() {
        if (controllerFuture != null) return
        val token = androidx.media3.session.SessionToken(this, android.content.ComponentName(this, BgPlayService::class.java))
        val future = androidx.media3.session.MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = try { future.get() } catch (e: Exception) { null } ?: return@addListener
            if (controllerFuture !== future) { c.release(); return@addListener }
            controller = c
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) handBackToVideo()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun disconnectPlayer() {
        controllerFuture?.let { androidx.media3.session.MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    /** The video on screen (not a playlist page) in the YouTube tab, if any. */
    private fun playableVideoUrl(): String? =
        currentVideoUrl()?.takeIf { tab == 0 && !it.contains("/playlist?") }

    /** The screen is pausing: remember if a video is playing (YouTube may pause it once hidden). */
    private fun snapshotVideo() {
        val url = playableVideoUrl() ?: run { snapUrl = null; return }
        hm.webView.evaluateJavascript(STATE_JS) { r ->
            snapSec = r?.replace("\"", "")?.toDoubleOrNull() ?: -1.0
            snapUrl = url
            snapAt = System.currentTimeMillis()
        }
    }

    /** Home, recent apps, screen off...: continue the sound in the background, or stop it (Settings). */
    private fun onLeaveApp() {
        if (leftApp || isChangingConfigurations) return
        leftApp = true
        val url = playableVideoUrl() ?: return
        if (!AppPrefs.backgroundPlay(this)) {
            hm.webView.evaluateJavascript(PAUSE_JS, null)   // background play is off: the video stops
            return
        }
        hm.webView.evaluateJavascript(HANDOFF_JS) { r ->
            var sec = r?.replace("\"", "")?.toDoubleOrNull() ?: -1.0
            if (sec < 0 && snapUrl == url && snapSec >= 0 && System.currentTimeMillis() - snapAt < 5000) sec = snapSec
            if (sec >= 0) startBackground(url, sec)
        }
    }

    /** Plays [url]'s sound from [sec] in BgPlayService, with "Up next" as next tracks. */
    private fun startBackground(url: String, sec: Double) {
        lifecycleScope.launch {
            val c = controller ?: return@launch
            val info = try { vm.audioInfo(url) } catch (e: Exception) { null } ?: return@launch
            val audio = info.audioUrl ?: return@launch
            if (!leftApp) return@launch                       // already back in the app
            BgPlayService.remember(url, audio)
            val meta = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(info.title)
                .setArtist(info.uploader)
                .setArtworkUri(info.thumbUrl?.let { Uri.parse(it) })
                .build()
            val item = androidx.media3.common.MediaItem.Builder()
                .setUri(BgPlayService.lazyUri(url))
                .setMediaId(url)
                .setMediaMetadata(meta)
                .setRequestMetadata(
                    androidx.media3.common.MediaItem.RequestMetadata.Builder().setMediaUri(BgPlayService.lazyUri(url)).build()
                )
                .build()
            val next = info.related.map { e ->
                androidx.media3.common.MediaItem.Builder()
                    .setUri(BgPlayService.lazyUri(e.url))
                    .setMediaId(e.url)
                    .setRequestMetadata(
                        androidx.media3.common.MediaItem.RequestMetadata.Builder()
                            .setMediaUri(BgPlayService.lazyUri(e.url)).build()
                    )
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder()
                            .setTitle(e.title)
                            .setArtworkUri(e.thumbUrl?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()
            }
            try {
                c.setMediaItems(listOf(item) + next, 0, (sec * 1000).toLong())
                c.prepare()
                c.play()
            } catch (e: Exception) { }
        }
    }

    /** Stops background sound (Settings switch turned off). */
    private fun stopBackground() {
        controller?.run { stop(); clearMediaItems() }
    }

    /**
     * Back in the app: the video continues from where the sound is, and background play stops.
     * If the player moved on to another track, that video opens at the same second.
     */
    private fun handBackToVideo() {
        val c = controller ?: return
        if (c.mediaItemCount == 0 || c.playbackState == androidx.media3.common.Player.STATE_IDLE) return
        val url = currentVideoUrl()
        val pos = c.currentPosition / 1000.0
        val wasPlaying = c.isPlaying || c.playWhenReady
        val other = c.currentMediaItem?.mediaId
        c.stop()
        c.clearMediaItems()
        if (other == null || other == url) {
            val play = if (wasPlaying) "v.play();" else ""
            hm.webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return;try{v.currentTime=$pos;}catch(e){}$play})()", null
            )
        } else if (FastExtractor.supports(other)) {
            if (tab != 0) b.bottomNav.selectedItemId = R.id.nav_home
            openYoutube(withTime(other, pos))
        }
    }


    // =====================================================================
    // Bottom bar: the YouTube account (sign in / profile photo)
    // =====================================================================

    /** Google's own sites in any country: accounts.google.com, www.google.com.pk, accounts.youtube.com... */
    private fun isGoogleSignInHost(host: String): Boolean {
        val h = host.lowercase()
        return Regex("(^|\\.)google(\\.com|\\.co)?\\.[a-z]{2,3}$").containsMatchIn(h) ||
            h == "accounts.youtube.com" || h.endsWith(".gstatic.com")
    }

    private fun isSignedIn(): Boolean {
        val c = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        return "SAPISID=" in c || "LOGIN_INFO=" in c
    }

    private fun onAccountClick() {
        if (!isSignedIn()) {
            showTab(0)
            b.bottomNav.menu.findItem(R.id.nav_home)?.isChecked = true
            openYoutube(SIGN_IN_URL)
            return
        }
        val anchor = b.bottomNav.findViewById<View>(R.id.nav_account) ?: b.bottomNav
        val menu = androidx.appcompat.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Your YouTube")
        menu.menu.add(0, 2, 1, "Switch account")
        menu.menu.add(0, 3, 2, "Sign out")
        menu.setOnMenuItemClickListener { item ->
            showTab(0)
            b.bottomNav.menu.findItem(R.id.nav_home)?.isChecked = true
            when (item.itemId) {
                1 -> openYoutube("https://m.youtube.com/feed/you")
                2 -> openYoutube(SIGN_IN_URL)
                3 -> {
                    CookieHelper.signOut()
                    AppPrefs.setAvatarUrl(this, null)
                    updateAccountIcon()
                    openYoutube(YT_HOME)
                    message("Signed out of YouTube")
                }
            }
            true
        }
        menu.show()
    }

    /** Reads the signed-in user's photo from the YouTube page (top bar), once it changes. */
    private fun readAccount(url: String) {
        if (!isYoutubePage(url)) return
        if (!isSignedIn()) {
            if (AppPrefs.avatarUrl(this) != null) AppPrefs.setAvatarUrl(this, null)
            updateAccountIcon()
            return
        }
        hm.webView.evaluateJavascript(AVATAR_JS) { r ->
            val src = r?.trim('"')?.replace("\\u003d", "=")?.replace("\\u0026", "&").orEmpty()
            if (src.startsWith("https://") && src != AppPrefs.avatarUrl(this)) {
                AppPrefs.setAvatarUrl(this, src)
                avatarBitmap = null
            }
            updateAccountIcon()
            // just signed in: the photo appears a moment after the page, look again
            if (!src.startsWith("https://") && AppPrefs.avatarUrl(this) == null && avatarTries < 4) {
                avatarTries++
                hm.webView.postDelayed({ hm.webView.url?.let { readAccount(it) } }, 2500)
            } else if (src.startsWith("https://")) avatarTries = 0
        }
    }

    private var avatarTries = 0

    private var avatarBitmap: android.graphics.Bitmap? = null
    private var avatarLoading: String? = null

    /** Empty profile icon + "Sign in", or the user's YouTube photo + "You". */
    private fun updateAccountIcon() {
        val item = b.bottomNav.menu.findItem(R.id.nav_account) ?: return
        val signedIn = isSignedIn()
        item.title = if (signedIn) "You" else "Sign in"
        val url = if (signedIn) AppPrefs.avatarUrl(this) else null
        val bmp = avatarBitmap
        if (url == null) {
            item.setIcon(R.drawable.ic_nav_account)
            return
        }
        if (bmp != null) {
            item.icon = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp).apply { isCircular = true }
            return
        }
        item.setIcon(R.drawable.ic_nav_account)
        if (avatarLoading == url) return
        avatarLoading = url
        lifecycleScope.launch {
            val big = url.replace(Regex("=s\\d+"), "=s96")
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { InfoFetcher.loadBitmap(big) ?: InfoFetcher.loadBitmap(url) }
            avatarLoading = null
            if (loaded != null && url == AppPrefs.avatarUrl(this@MainActivity)) {
                val side = minOf(loaded.width, loaded.height)
                avatarBitmap = android.graphics.Bitmap.createBitmap(loaded, (loaded.width - side) / 2, (loaded.height - side) / 2, side, side)
                updateAccountIcon()
            }
        }
    }

    private fun isAllowedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return (uri.scheme == "http" || uri.scheme == "https") && isAllowedHost(uri.host.orEmpty())
    }

    /** Floating Download button (headphones = audio only). */
    private fun onDownloadClick(url: String, audio: Boolean) {
        if (!isAllowedUrl(url)) return
        // The page already knows the title: show it at once while the sizes load
        // (only when the page really shows this video; YouTube updates the title a moment after the address)
        val title = if (pageTitleUrl != url) null else pageTitle.orEmpty()
            .removeSuffix(" - YouTube").replace(Regex("^\\(\\d+\\)\\s*"), "").trim()
            .takeIf { it.isNotBlank() && it != "YouTube" }
        showDownloadSheet(listOf(url), preferAudio = audio, knownTitle = title)
    }

    private var pageTitle: String? = null
    private var pageTitleUrl: String? = null

    // Look-ahead: when a video page stays open for a moment, fetch its info in the background
    private var aheadUrl: String? = null

    private fun lookAhead() {
        val url = currentVideoUrl()
        if (url == null || url.contains("/playlist") || tab != 0 || topTab != 1) {
            aheadUrl = null
            return
        }
        // The moment a video opens, read its sizes in the background (no waiting when Download is tapped)
        if (url != aheadUrl) {
            aheadUrl = url
            vm.prefetch(url)
        }
    }

    private fun showTab(index: Int) {
        if (index != 1 && selecting) exitSelection()
        tab = index
        hm.root.isVisible = index == 0
        pl.root.isVisible = index == 1
        st.root.isVisible = index == 2
        updateFloatingBar()
        updateBack()
    }

    private fun updateBack() {
        backCallback.isEnabled = customView != null || selecting || tab != 0 || hm.webView.canGoBack()
    }

    // ----- fullscreen video -----

    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback?) {
        if (customView != null) { cb?.onCustomViewHidden(); return }
        customView = view
        customCallback = cb
        b.fullscreenContainer.addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        b.fullscreenContainer.isVisible = true
        updateFloatingBar()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        updateBack()
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        b.fullscreenContainer.removeView(v)
        b.fullscreenContainer.isVisible = false
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        updateFloatingBar()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        updateBack()
    }

    // =====================================================================
    // Links in / download sheet
    // =====================================================================

    private fun extractUrls(text: String): List<String> =
        Regex("https?://\\S+").findAll(text).map { it.value }.distinct().toList()

    /** Links shared from other apps (Share > RAINAX). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val urls = extractUrls(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
        if (urls.isNotEmpty()) {
            AppPrefs.setLastClip(this, urls[0])
            showDownloadSheet(urls)
        }
    }

    private fun showDownloadSheet(urls: List<String>, preferAudio: Boolean = false, knownTitle: String? = null) {
        sheet?.dismiss()
        val sb = SheetDownloadBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sb.root)
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener { dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet = dialog

        val single = urls.size == 1
        val firstUrl = urls[0]
        val cookies = urls.associateWith<String, String?> { null }
        val screenH = resources.displayMetrics.heightPixels

        var current: PreviewState? = null
        var mode = MODE_QUICK
        var selectedSpec: String? = if (preferAudio) "audio:m4a" else null
        var selectedSub: String? = null

        var choiceAction: (FormatChoice) -> Unit = {}
        var navAction: (String) -> Unit = {}
        val adapter = FormatAdapter({ choiceAction(it) }, { navAction(it) })
        sb.formatList.layoutManager = LinearLayoutManager(this)
        sb.formatList.adapter = adapter

        fun group(list: List<FormatChoice>, detailed: Boolean): List<FormatRow> {
            val out = mutableListOf<FormatRow>()
            val audio = list.filter { it.kind == KIND_AUDIO }
            val video = list.filter { it.kind == KIND_VIDEO }
            if (audio.isNotEmpty()) {
                out += FormatRow.Section("Music")
                audio.forEach { out += FormatRow.Choice(it, it.spec == selectedSpec, detailed) }
            }
            if (video.isNotEmpty()) {
                out += FormatRow.Section("Video")
                video.forEach { out += FormatRow.Choice(it, it.spec == selectedSpec, detailed) }
            }
            return out
        }

        fun render() {
            val p = current
            val loading = single && (p == null || p.loading)
            val error = if (single) p?.error else null
            val isPlaylist = p?.playlist?.isNotEmpty() == true
            // While the exact sizes load, the usual choices are already there: pick one and download at once
            val presets = !single || error != null || isPlaylist || (loading && p?.quick.isNullOrEmpty() && !isPlaylistUrl(firstUrl))
            val quick = when {
                // playlist: show the approximate total size of every choice
                isPlaylist -> {
                    val list = p!!.playlist
                    val known = list.filter { it.seconds > 0 }
                    val avg = if (known.isNotEmpty()) known.sumOf { it.seconds } / known.size else 240L
                    val total = list.sumOf { if (it.seconds > 0) it.seconds else avg }
                    PRESETS.map { it.copy(size = "≈ " + formatSize(estimatePlaylistBytes(it.spec, total))) }
                }
                presets -> PRESETS
                else -> p?.quick.orEmpty()
            }
            val all = if (presets) emptyList() else p?.all.orEmpty()
            val subs = if (presets) emptyList() else p?.subtitles.orEmpty()

            sb.sheetHeading.text = when (mode) {
                MODE_ALL -> "More formats"
                MODE_SUBS -> "Subtitles/CC"
                else -> if (single) "Download video as" else "Download ${urls.size} links as"
            }
            sb.backBtn.isVisible = mode != MODE_QUICK
            sb.sheetSub.text = when {
                !single -> "They will download one after another"
                loading -> (knownTitle ?: p?.title)?.let { "$it  •  getting sizes…" } ?: "Fetching info…"
                error != null -> Uri.parse(firstUrl).host.orEmpty()
                isPlaylist -> "${p!!.title}  •  ${p.subtitle}"
                else -> p?.title.orEmpty()
            }
            sb.previewLoading.isVisible = loading

            sb.errorBlock.isVisible = error != null
            if (error != null) {
                sb.errorText.text = error + "\n\nTap Retry, or pick a format below to try anyway."
            }
            sb.playlistBtn.isVisible = false

            val rows: List<FormatRow> = when (mode) {
                MODE_SUBS -> {
                    val list = mutableListOf<FormatRow>()
                    list += FormatRow.Choice(FormatChoice("", "None", kind = KIND_NONE), selectedSub == null, false)
                    subs.forEach {
                        list += FormatRow.Choice(FormatChoice(it.code, it.label, kind = KIND_NONE), selectedSub == it.code, false)
                    }
                    list
                }
                MODE_ALL -> {
                    val list = group(all, true).toMutableList()
                    if (subs.isNotEmpty()) {
                        val label = subs.firstOrNull { it.code == selectedSub }?.label ?: "Off"
                        list += FormatRow.Nav("subs", "Subtitles/CC", label, R.drawable.ic_cc)
                    }
                    list
                }
                else -> {
                    val list = group(quick, false).toMutableList()
                    if (all.isNotEmpty()) list += FormatRow.Nav("all", "More formats", "All", null)
                    list
                }
            }
            adapter.submitList(rows)
            sb.downloadBtn.isEnabled = selectedSpec != null

            val fraction = if (mode == MODE_QUICK) 0.66 else 0.9
            sb.root.minimumHeight = (screenH * fraction).toInt()
        }

        choiceAction = { c ->
            if (mode == MODE_SUBS) {
                selectedSub = c.spec.ifEmpty { null }
                mode = MODE_ALL
            } else {
                selectedSpec = c.spec
            }
            render()
        }
        navAction = { id ->
            mode = if (id == "all") MODE_ALL else MODE_SUBS
            render()
        }
        sb.backBtn.setOnClickListener {
            mode = if (mode == MODE_SUBS) MODE_ALL else MODE_QUICK
            render()
        }
        sb.retryBtn.setOnClickListener { vm.fetchInfo(firstUrl, cookies[firstUrl]) }
        sb.playlistBtn.setOnClickListener {
            val id = Uri.parse(firstUrl).getQueryParameter("list").orEmpty()
            vm.fetchInfo("https://www.youtube.com/playlist?list=$id", cookies[firstUrl], forcePlaylist = true)
        }

        sb.downloadBtn.setOnClickListener {
            val spec = selectedSpec ?: return@setOnClickListener
            val p = current
            val sub = if (spec.startsWith("video")) selectedSub else null
            val items = when {
                !single -> urls.map { EnqueueItem(it, "", cookies[it]) }
                p != null && p.playlist.isNotEmpty() ->
                    p.playlist.map { EnqueueItem(it.url, it.title, cookies[firstUrl], it.thumbUrl) }
                // never save a status text like "Connecting…" as the title (blank = looked up by the service)
                else -> listOf(
                    EnqueueItem(firstUrl, p?.takeIf { !it.loading }?.title ?: knownTitle.orEmpty(), cookies[firstUrl], youtubeThumb(firstUrl))
                )
            }
            vm.enqueue(items, spec, sub)
            dialog.dismiss()
            if (!askBatteryOptimization()) {
                Snackbar.make(b.root, "Added to downloads", Snackbar.LENGTH_LONG)
                    .setAnchorView(b.bottomNav)
                    .setAction("View") { b.bottomNav.selectedItemId = R.id.nav_downloads }
                    .show()
            }
        }

        var collectJob: Job? = null
        if (single) {
            vm.fetchInfo(firstUrl, cookies[firstUrl])
            collectJob = lifecycleScope.launch {
                vm.preview.collect { current = it; render() }
            }
        }
        render()

        dialog.setOnDismissListener {
            collectJob?.cancel()
            // A newer sheet may already be open (dismiss runs later): only clean up our own
            if (sheet === dialog) {
                sheet = null
                vm.clearPreview()
            }
        }
        dialog.show()
    }

    // =====================================================================
    // Play tab (downloaded + downloading)
    // =====================================================================

    private fun setupPlay() {
        pl.activeList.layoutManager = LinearLayoutManager(this)
        pl.activeList.adapter = activeAdapter
        pl.doneList.layoutManager = LinearLayoutManager(this)
        pl.doneList.adapter = doneAdapter

        pl.emptySearchBtn.setOnClickListener { b.bottomNav.selectedItemId = R.id.nav_home }
        pl.selectActiveBtn.setOnClickListener { selectAll(done = false) }
        pl.selectDoneBtn.setOnClickListener { selectAll(done = true) }
        pl.removeAllBtn.setOnClickListener { removeAllDone() }
        pl.deleteAllBtn.setOnClickListener { deleteAllDone() }
        pl.clearFailedBtn.setOnClickListener {
            TaskRepository.removeFailed()
            message("Removed failed downloads")
        }

        // global actions
        pl.pauseAllBtn.setOnClickListener { DownloadService.send(this, DownloadService.ACTION_PAUSE_ALL) }
        pl.resumeAllBtn.setOnClickListener { DownloadService.send(this, DownloadService.ACTION_RESUME_ALL) }
        pl.cancelAllBtn.setOnClickListener {
            val n = latestTasks.count { it.status != Status.DONE }
            if (n > 0) {
                confirm("Cancel all $n downloads?", "Partial files will be deleted.") {
                    DownloadService.send(this, DownloadService.ACTION_CANCEL_ALL)
                }
            }
        }

        // selection bar
        pl.closeSelBtn.setOnClickListener { exitSelection() }
        pl.selAllBtn.setOnClickListener { toggleSelectAll() }
        pl.actPause.setOnClickListener { pauseSelected() }
        pl.actResume.setOnClickListener { resumeSelected() }
        pl.actCancel.setOnClickListener { cancelSelected() }
        pl.actRemove.setOnClickListener { removeSelected() }
        pl.actDelete.setOnClickListener { deleteSelected() }
    }

    private fun renderTasks(all: List<DownloadTask>) {
        latestTasks = all
        val active = all.filter { it.status != Status.DONE }
        val done = all.filter { it.status == Status.DONE }

        // forget selected items that no longer exist
        val before = selected.size
        selected.retainAll(all.map { it.id }.toSet())
        val selectionChanged = before != selected.size || (selecting && selected.isEmpty())
        if (selecting && selected.isEmpty()) selecting = false

        pl.emptyState.isVisible = all.isEmpty()
        pl.playScroll.isVisible = all.isNotEmpty()

        pl.activeSection.isVisible = active.isNotEmpty()
        pl.clearFailedBtn.isVisible = active.any { it.status == Status.FAILED }
        pl.activeTitle.text = "Downloading (${active.size})"
        // Newest always on top, in both lists
        val newActive = active.sortedByDescending { it.createdAt }
        val grew = newActive.size > activeAdapter.itemCount && newActive.firstOrNull()?.id != activeAdapter.currentList.firstOrNull()?.id
        activeAdapter.submitList(newActive) { if (grew) pl.playScroll.smoothScrollTo(0, 0) }

        pl.doneSection.isVisible = done.isNotEmpty()
        pl.doneTitle.text = "Downloaded (${done.size})"
        doneAdapter.submitList(done.sortedByDescending { it.createdAt })

        val badge = b.bottomNav.getOrCreateBadge(R.id.nav_downloads)
        badge.isVisible = active.isNotEmpty()
        badge.number = active.size

        if (selectionChanged) refreshSelectionUi() else updateSelectionBar()
    }

    // ----- multi-select -----

    private fun startSelect(t: DownloadTask) {
        selecting = true
        selected.clear()
        selected += t.id
        refreshSelectionUi()
    }

    private fun toggleSelect(t: DownloadTask) {
        if (!selected.add(t.id)) selected.remove(t.id)
        if (selected.isEmpty()) selecting = false
        refreshSelectionUi()
    }

    private fun toggleSelectAll() {
        val all = latestTasks.map { it.id }
        if (all.isEmpty() || selected.size == all.size) {
            exitSelection()
            return
        }
        selected.clear()
        selected += all
        selecting = true
        refreshSelectionUi()
    }

    private fun exitSelection() {
        selecting = false
        selected.clear()
        refreshSelectionUi()
    }

    private fun refreshSelectionUi() {
        activeAdapter.setSelection(selecting, selected)
        doneAdapter.setSelection(selecting, selected)
        updateSelectionBar()
        updateBack()
    }

    private fun updateSelectionBar() {
        pl.selectionBar.isVisible = selecting
        pl.selectionActions.isVisible = selecting
        pl.selectionCount.text = "${selected.size} selected"
        if (!selecting) return

        // only show the actions that apply to what is selected
        val sel = selectedTasks()
        pl.actPause.isVisible = sel.any {
            it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
        }
        pl.actResume.isVisible = sel.any { it.status == Status.PAUSED || it.status == Status.FAILED }
        pl.actCancel.isVisible = sel.any { it.status != Status.DONE }
        pl.actRemove.isVisible = sel.any { it.status == Status.DONE }
        pl.actDelete.isVisible = sel.any { it.status == Status.DONE }
    }

    private fun selectedTasks() = latestTasks.filter { it.id in selected }

    private fun pauseSelected() {
        val ids = selectedTasks().filter {
            it.status == Status.RUNNING || it.status == Status.QUEUED || it.status == Status.WAITING
        }.map { it.id }
        if (ids.isEmpty()) message("Nothing to pause")
        else DownloadService.send(this, DownloadService.ACTION_PAUSE_SEL, ids = ids.toTypedArray())
        exitSelection()
    }

    private fun resumeSelected() {
        val ids = selectedTasks().filter {
            it.status == Status.PAUSED || it.status == Status.FAILED
        }.map { it.id }
        if (ids.isEmpty()) message("Nothing to resume")
        else DownloadService.send(this, DownloadService.ACTION_RESUME_SEL, ids = ids.toTypedArray())
        exitSelection()
    }

    /** Cancel: only for unfinished downloads (stops them and deletes partial files). */
    private fun cancelSelected() {
        val ids = selectedTasks().filter { it.status != Status.DONE }.map { it.id }
        if (ids.isEmpty()) return
        confirm("Cancel ${ids.size} download(s)?", "Partial files will be deleted.") {
            DownloadService.send(this, DownloadService.ACTION_CANCEL_SEL, ids = ids.toTypedArray())
            exitSelection()
        }
    }

    /** Remove: finished items leave the list, the files stay in Downloads. */
    private fun removeSelected() {
        val ids = selectedTasks().filter { it.status == Status.DONE }.map { it.id }
        if (ids.isEmpty()) return
        TaskRepository.removeMany(ids)
        exitSelection()
        message("Removed ${ids.size} from the list")
    }

    /** Delete: finished items are removed from the list and their files deleted. */
    private fun deleteSelected() {
        val done = selectedTasks().filter { it.status == Status.DONE }
        if (done.isEmpty()) return
        confirm("Delete ${done.size} file(s)?", "They will be removed from your phone.") {
            deleteFilesOf(done)
            exitSelection()
        }
    }

    private fun removeAllDone() {
        val n = latestTasks.count { it.status == Status.DONE }
        if (n == 0) return
        confirm("Remove $n items from the list?", "The files stay in your Downloads folder.") {
            TaskRepository.removeDone()
        }
    }

    private fun deleteAllDone() {
        val done = latestTasks.filter { it.status == Status.DONE }
        if (done.isEmpty()) return
        confirm("Delete all ${done.size} files?", "They will be removed from your phone.") {
            deleteFilesOf(done)
        }
    }

    private fun deleteFilesOf(tasks: List<DownloadTask>) {
        var deleted = 0
        for (t in tasks) {
            val uri = t.fileUri ?: continue
            if (FileStore.delete(this, uri)) deleted++
        }
        TaskRepository.removeMany(tasks.map { it.id })
        message("Deleted $deleted of ${tasks.size} files")
    }

    /** Selects every unfinished (done = false) or finished (done = true) item. */
    private fun selectAll(done: Boolean) {
        val ids = latestTasks.filter { (it.status == Status.DONE) == done }.map { it.id }
        if (ids.isEmpty()) return
        selected.clear()
        selected += ids
        selecting = true
        refreshSelectionUi()
    }

    private fun confirm(title: String, text: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun onTaskAction(t: DownloadTask) {
        when (t.status) {
            Status.RUNNING, Status.QUEUED, Status.WAITING ->
                DownloadService.send(this, DownloadService.ACTION_PAUSE, t.id)
            Status.PAUSED, Status.FAILED ->
                DownloadService.send(this, DownloadService.ACTION_RESUME, t.id)
            Status.DONE -> openFile(t)
        }
    }

    private fun onTaskClose(t: DownloadTask, anchor: View) {
        if (t.status != Status.DONE) {
            DownloadService.send(this, DownloadService.ACTION_CANCEL, t.id)
            return
        }
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Play")
        popup.menu.add(0, 6, 1, "Open with another app")
        popup.menu.add(0, 2, 1, "Share")
        popup.menu.add(0, 3, 2, "Copy link")
        popup.menu.add(0, 4, 3, "Remove from list")
        popup.menu.add(0, 5, 4, "Delete file")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openFile(t)
                2 -> shareFile(t)
                6 -> openWithOtherApp(t)
                3 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
                    AppPrefs.setLastClip(this, t.url)
                    message("Link copied")
                }
                4 -> TaskRepository.remove(t.id)
                5 -> {
                    t.fileUri?.let { FileStore.delete(this, it) }
                    TaskRepository.remove(t.id)
                    message("File deleted")
                }
            }
            true
        }
        popup.show()
    }

    /** YouTube thumbnail address from the video id (no lookup needed). */
    private fun youtubeThumb(url: String): String? =
        if (!FastExtractor.supports(url)) null else Regex("(?:[?&]v=|youtu\\.be/|shorts/)([\\w-]{6,})").find(url)?.groupValues?.get(1)
            ?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }

    private fun isPlayable(t: DownloadTask): Boolean {
        val m = t.mime.orEmpty()
        if (m.startsWith("video/") || m.startsWith("audio/")) return true
        val name = (t.title + " " + t.fileUri.orEmpty()).lowercase()
        return t.isAudio || listOf(".mp4", ".mkv", ".webm", ".mov", ".m4a", ".mp3", ".opus", ".ogg").any { name.contains(it) }
    }

    /** Finished videos and music always open in the RAINAX player (with the other downloads as a playlist). */
    private fun openFile(t: DownloadTask) {
        val uri = t.fileUri ?: return
        if (isPlayable(t)) {
            val list = TaskRepository.tasks.value.filter { it.status == Status.DONE && it.fileUri != null && isPlayable(it) }
            val index = list.indexOfFirst { it.id == t.id }.coerceAtLeast(0)
            try {
                if (list.isEmpty()) PlayerActivity.open(this, listOf(uri), listOf(t.title), 0)
                else PlayerActivity.open(this, list.map { it.fileUri!! }, list.map { it.title }, index)
                return
            } catch (e: Exception) { /* fall back to another app */ }
        }
        openWithOtherApp(t)
    }

    private fun openWithOtherApp(t: DownloadTask) {
        val uri = t.fileUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), t.mime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(t: DownloadTask) {
        val uri = t.fileUri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(t.mime ?: "*/*")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show()
        }
    }

    // =====================================================================
    // Settings tab
    // =====================================================================

    private fun setupSettings() {
        st.wifiSwitch.isChecked = AppPrefs.wifiOnly(this)
        st.wifiSwitch.setOnCheckedChangeListener { _, on ->
            AppPrefs.setWifiOnly(this, on)
            nudgeService()
        }
        st.themeGroup.check(
            when (AppPrefs.themeMode(this)) {
                1 -> R.id.themeLight
                2 -> R.id.themeDark
                3 -> R.id.themeAmoled
                else -> R.id.themeSystem
            }
        )
        st.themeGroup.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.themeLight -> 1
                R.id.themeDark -> 2
                R.id.themeAmoled -> 3
                else -> 0
            }
            val old = AppPrefs.themeMode(this)
            if (mode != old) {
                AppPrefs.setThemeMode(this, mode)
                RainaxApp.applyTheme(mode)      // recreates the screen with the new colours
                // Dark <-> AMOLED keeps night mode, so rebuild the screen ourselves
                if (old == 3 || mode == 3) window.decorView.post { if (!isDestroyed) recreate() }
            }
        }

        st.autoRetrySwitch.isChecked = AppPrefs.autoRetry(this)
        st.autoRetrySwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoRetry(this, on) }
        st.bgPlaySwitch.isChecked = AppPrefs.backgroundPlay(this)
        st.bgPlaySwitch.setOnCheckedChangeListener { _, on ->
            AppPrefs.setBackgroundPlay(this, on)
            if (!on) stopBackground()
        }
        st.checkAppUpdateBtn.setOnClickListener { AppUpdater.check(this, manual = true) }

        st.adSwitch.isChecked = AppPrefs.adBlock(this)
        st.adSwitch.setOnCheckedChangeListener { _, on ->
            AppPrefs.setAdBlock(this, on)
            adBlockOn = on
            hm.webView.evaluateJavascript("window.__ytdlAdBlock=$on;", null)
            message(if (on) "Ads are blocked" else "Ads allowed (reload the page)")
        }

        st.autoClearSwitch.isChecked = AppPrefs.autoClear(this)
        st.autoClearSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoClear(this, on) }

        st.parallelGroup.check(
            when (AppPrefs.maxParallel(this)) {
                1 -> R.id.par1
                3 -> R.id.par3
                4 -> R.id.par4
                else -> R.id.par2
            }
        )
        st.parallelGroup.setOnCheckedStateChangeListener { _, ids ->
            val n = when (ids.firstOrNull()) {
                R.id.par1 -> 1
                R.id.par3 -> 3
                R.id.par4 -> 4
                else -> 2
            }
            AppPrefs.setMaxParallel(this, n)
            nudgeService()
        }

        renderFolder()
        st.chooseFolderBtn.setOnClickListener {
            try { pickFolder.launch(null) } catch (e: Exception) { message("This phone has no folder picker") }
        }
        st.resetFolderBtn.setOnClickListener {
            AppPrefs.setSaveTree(this, "")
            renderFolder()
            message("Using Downloads/${FileStore.DEFAULT_FOLDER}")
        }
        st.openFolderBtn.setOnClickListener {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e: Exception) {
                message("Can't open the Downloads folder")
            }
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        st.versionText.text = "RAINAX YT DOWNLOADER  v$version"
    }

    private fun renderFolder() {
        st.folderPath.text = FileStore.describe(this)
    }

    private fun nudgeService() {
        val needs = TaskRepository.tasks.value.any {
            it.status == Status.QUEUED || it.status == Status.WAITING || it.status == Status.RUNNING
        }
        if (needs) DownloadService.send(this, DownloadService.ACTION_PUMP)
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun message(text: String) {
        Snackbar.make(b.root, text, Snackbar.LENGTH_SHORT).setAnchorView(b.bottomNav).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** One-time prompt so the system doesn't kill background downloads. Returns true if shown. */
    private fun askBatteryOptimization(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("battery_asked", false)) {
            return false
        }
        prefs.edit().putBoolean("battery_asked", true).apply()
        Snackbar.make(b.root, "Allow background activity so downloads keep going", Snackbar.LENGTH_INDEFINITE)
            .setAnchorView(b.bottomNav)
            .setAction("Allow") {
                try {
                    startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                } catch (e: Exception) { /* not available on this device */ }
            }
            .show()
        return true
    }

    companion object {
        private const val YT_HOME = "https://m.youtube.com"

        private const val MODE_QUICK = 0
        private const val MODE_ALL = 1
        private const val MODE_SUBS = 2

        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "imasdk.googleapis.com", "2mdn.net", "moatads.com", "adsrvr.org", "taboola.com", "outbrain.com"
        )
        private val AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info", "/api/stats/atr")

        /**
         * Runs inside the YouTube page: hides "Open app" prompts and ads.
         * It adds no buttons: the Download buttons are native and float above the page.
         */
        /** Pauses the page's video and returns the second it was at. */
        /** Playing: pauses the video and returns its second (0 during an ad). Not playing: -1. */
        private const val HANDOFF_JS = "(function(){var v=document.querySelector('video');if(!v||v.paused||v.ended)return -1;" +
            "var t=document.querySelector('.ad-showing')?0:v.currentTime;try{v.pause();}catch(e){}return t;})()"

        /** The second the video is at if it is playing, else -1 (changes nothing). */
        private const val STATE_JS = "(function(){var v=document.querySelector('video');if(!v||v.paused||v.ended)return -1;" +
            "return document.querySelector('.ad-showing')?0:v.currentTime;})()"

        private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

        /**
         * The signed-in user's photo ("" until found). Looks in the top bar first; otherwise asks YouTube
         * for the account menu (a signed-in request made by the page) and returns the answer on the next call.
         */
        private const val AVATAR_JS = """(function(){
 var RX=/https:\/\/yt3\.(ggpht|googleusercontent)\.com\/[^"'\s\\]+/;
 var q=['ytm-topbar-menu-button-renderer img','.topbar-menu-button-avatar-button img','ytm-mobile-topbar-renderer img'];
 for(var i=0;i<q.length;i++){var l=document.querySelectorAll(q[i]);for(var k=0;k<l.length;k++){var s=l[k].src||'';if(RX.test(s))return s;}}
 if(window.__rxAvatar)return window.__rxAvatar;
 if(!window.__rxAvatarAsked&&window.crypto&&crypto.subtle&&window.ytcfg){
  window.__rxAvatarAsked=true;
  try{
   var m=document.cookie.match(/(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID)=([^;]+)/);
   if(m){
    var ts=Math.floor(Date.now()/1000), origin=location.origin;
    crypto.subtle.digest('SHA-1',new TextEncoder().encode(ts+' '+m[1]+' '+origin)).then(function(buf){
     var hex=Array.prototype.map.call(new Uint8Array(buf),function(x){return ('0'+x.toString(16)).slice(-2);}).join('');
     return fetch(origin+'/youtubei/v1/account/account_menu?prettyPrint=false',{method:'POST',credentials:'include',
      headers:{'Content-Type':'application/json','Authorization':'SAPISIDHASH '+ts+'_'+hex,'X-Origin':origin,'X-Goog-AuthUser':'0'},
      body:JSON.stringify({context:ytcfg.get('INNERTUBE_CONTEXT')})});
    }).then(function(r){return r.text();}).then(function(t){
     var i=t.indexOf('accountPhoto'); var f=(i>=0?t.substring(i):t).match(RX); if(f)window.__rxAvatar=f[0];
    }).catch(function(){window.__rxAvatarAsked=false;});
   }
  }catch(e){window.__rxAvatarAsked=false;}
 }
 return '';
})()"""

        private const val PAUSE_JS = "(function(){var v=document.querySelector('video');if(v){try{v.pause();}catch(e){}}})()"


        private const val INJECT_JS = """
(function(){
 if(window.__ytdlInit3) return;
 window.__ytdlInit3=true;

 function norm(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();}
 var BAD={'open app':1,'open in app':1,'open the app':1,'get app':1,'use app':1,'try app':1,'open youtube app':1};
 var HIDE_SEL='ytm-mealbar-promo-renderer,ytm-open-app-button,ytm-app-promo-renderer,ytm-upsell-dialog-renderer,.open-app-button,'
  +'a[href^="intent:"],a[href^="vnd.youtube:"],a[href*="youtube.com/app/"]';

 function hideOpenApp(){
  var l=document.querySelectorAll(HIDE_SEL);
  for(var i=0;i<l.length;i++) l[i].style.setProperty('display','none','important');
  var nodes=document.querySelectorAll('a,button,[role="button"],span,yt-formatted-string');
  for(var j=0;j<nodes.length;j++){
   var n=nodes[j], tc=n.textContent;
   if(!tc||tc.length>20) continue;
   if(BAD[norm(tc)]){
    var t=n.closest('a,button,ytm-button-renderer,[role="button"]')||n;
    t.style.setProperty('display','none','important');
   }
  }
 }

 // "Open in the YouTube app" dialogs: press "Not now" and remove them
 var PROMPT_HINTS=['open in the youtube app','open in app','get the youtube app','try the youtube app'];
 function dismissAppPrompts(){
  var cands=document.querySelectorAll('tp-yt-paper-dialog,ytm-mealbar-promo-renderer,yt-dialog,[role="dialog"],dialog');
  var dismissed=false;
  for(var i=0;i<cands.length;i++){
   var c=cands[i];
   var t=norm(c.textContent);
   if(!t||t.length>500) continue;
   var hit=false;
   for(var h=0;h<PROMPT_HINTS.length;h++){ if(t.indexOf(PROMPT_HINTS[h])>=0){hit=true;break;} }
   if(!hit) continue;
   var btns=c.querySelectorAll('button,a,[role="button"],yt-button-shape,tp-yt-paper-button');
   for(var b=0;b<btns.length;b++){
    var bt=norm(btns[b].textContent);
    if(bt==='not now'||bt==='no thanks'||bt==='dismiss'||bt==='close'){ try{btns[b].click();}catch(e){} break; }
   }
   c.style.setProperty('display','none','important');
   dismissed=true;
  }
  if(dismissed){
   var bd=document.querySelectorAll('tp-yt-iron-overlay-backdrop');
   for(var k=0;k<bd.length;k++) bd[k].style.setProperty('display','none','important');
   document.body.style.removeProperty('overflow');
   document.documentElement.style.removeProperty('overflow');
  }
 }

 // ----- ads: hide ad blocks and skip video ads -----
 var AD_SEL='ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ytm-display-ad-renderer,'
  +'ytm-ad-slot-renderer,ytm-in-feed-ad-layout-renderer,ytm-statement-banner-renderer,ytm-brand-video-singleton-renderer,'
  +'ytm-banner-promo-renderer,ytm-search-pyv-renderer,ytm-action-companion-ad-renderer,ytm-promoted-sparkles-text-search-renderer,'
  +'.ad-container,.video-ads,.ytp-ad-module,.ytp-ad-image-overlay,'
  +'.ytp-ad-text-overlay,#player-ads,#masthead-ad,ytd-ad-slot-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,'
  +'ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytd-companion-slot-renderer';
 function hideAds(){
  if(window.__ytdlAdBlock===false) return;
  var l=document.querySelectorAll(AD_SEL);
  for(var i=0;i<l.length;i++) l[i].style.setProperty('display','none','important');
 }
 var rateChanged=false, mutedByUs=false;
 function adTick(){
  if(window.__ytdlAdBlock===false) return;
  var player=document.querySelector('.html5-video-player');
  var adOn=!!(player&&(player.classList.contains('ad-showing')||player.classList.contains('ad-interrupting')))
   ||!!document.querySelector('.ytp-ad-player-overlay,.ytp-ad-player-overlay-layout');
  var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
  if(adOn&&v){
   if(!v.muted){v.muted=true;mutedByUs=true;}
   try{v.playbackRate=16;rateChanged=true;}catch(e){}
   if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.2){try{v.currentTime=v.duration-0.1;}catch(e){}}
  }else if(v){
   if(rateChanged){try{v.playbackRate=1;}catch(e){}rateChanged=false;}
   if(mutedByUs){v.muted=false;mutedByUs=false;}
  }
  var skip=document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.ytp-ad-overlay-close-button,.ytp-ad-overlay-close-container');
  for(var k=0;k<skip.length;k++){try{skip[k].click();}catch(e){}}
 }

 // ----- layout: the app has its own bottom bar; no menu button over a playing video -----
 function rxLayout(){
  if(!document.getElementById('rx-layout')){
   var ls=document.createElement('style'); ls.id='rx-layout';
   ls.textContent='ytm-pivot-bar-renderer{display:none!important}'
    +'html.rx-watch ytm-topbar-menu-button-renderer,html.rx-watch .topbar-menu-button-avatar-button{display:none!important}';
   (document.head||document.documentElement).appendChild(ls);
  }
  document.documentElement.classList.toggle('rx-watch',/^\/(watch|shorts)/.test(location.pathname));
 }
 try{ rxLayout(); }catch(e){}
 setInterval(function(){
  try{ rxLayout(); }catch(e){}
  try{ hideOpenApp(); }catch(e){}
  try{ dismissAppPrompts(); }catch(e){}
  try{ hideAds(); }catch(e){}
 },700);
 setInterval(function(){try{dismissAppPrompts();}catch(e){} try{adTick();}catch(e){}},120);
 ['loadedmetadata','durationchange','playing','timeupdate'].forEach(function(n){
  document.addEventListener(n,function(){try{adTick();}catch(e){}},true);
 });
 // While an ad is being skipped show a plain "Loading video" cover instead of a frozen ad frame
 try{
  if(window.__ytdlAdBlock!==false && !document.getElementById('ytdl-adcover')){
   var st=document.createElement('style'); st.id='ytdl-adcover';
   st.textContent='.html5-video-player.ad-showing .html5-main-video{opacity:0!important}'
    +'.html5-video-player.ad-showing::after{content:"Loading video\\2026";position:absolute;left:0;top:0;right:0;bottom:0;'
    +'background:#000;color:#fff;display:flex;align-items:center;justify-content:center;font:500 14px sans-serif;z-index:60;pointer-events:none}';
   (document.head||document.documentElement).appendChild(st);
  }
 }catch(e){}
})();
"""

        /** Removes ad data from YouTube's player responses, so no ad is ever scheduled. */
        private const val AD_STRIP_JS = """
(function(){
 try{
  if(window.__ytdlStrip) return; window.__ytdlStrip=true;
  var KEYS=['adPlacements','playerAds','adSlots'];
  function clean(o){
   if(!o||typeof o!=='object'||window.__ytdlAdBlock===false) return o;
   try{
    for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o) delete o[KEYS[i]]; }
    if(o.playerResponse&&typeof o.playerResponse==='object'){
     for(var j=0;j<KEYS.length;j++){ if(KEYS[j] in o.playerResponse) delete o.playerResponse[KEYS[j]]; }
    }
    if(Array.isArray(o)){ for(var k=0;k<o.length;k++){ var r=o[k]; if(r&&r.playerResponse) clean(r); } }
   }catch(e){}
   return o;
  }
  var P=JSON.parse;
  JSON.parse=function(){ return clean(P.apply(this,arguments)); };
  var RJ=Response.prototype.json;
  Response.prototype.json=function(){ return RJ.apply(this,arguments).then(clean); };
  var _ipr;
  Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,
   get:function(){return _ipr;}, set:function(v){_ipr=clean(v);} });
 }catch(e){}
})();
"""

    }
}
