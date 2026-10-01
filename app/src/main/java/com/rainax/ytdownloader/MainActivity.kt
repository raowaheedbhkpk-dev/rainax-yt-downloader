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
import android.os.SystemClock
import android.provider.Settings
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
    private var topTab = 0         // Search / YouTube / Music / More
    private var webSite = 0        // which top tab the WebView is currently showing
    private var pendingUrl: String? = null
    @Volatile internal var lastBeat = 0L   // last time the in-page Download button reported in
    private val lastUrl = mutableMapOf(1 to YT_HOME, 2 to MUSIC_HOME)

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


    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> exitFullscreen()
                selecting && tab == 1 -> exitSelection()
                tab != 0 -> b.bottomNav.selectedItemId = R.id.nav_home
                (topTab == 1 || topTab == 2) && hm.webView.canGoBack() -> hm.webView.goBack()
                else -> hm.topTabs.getTabAt(0)?.select()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
        requestNotificationPermission()

        setupHome()
        setupPlay()
        setupSettings()

        b.bottomNav.setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_home -> { showTab(0); true }
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
            b.bottomNav.selectedItemId = when (savedInstanceState.getInt("tab", 0)) {
                1 -> R.id.nav_downloads
                2 -> R.id.nav_settings
                else -> R.id.nav_home
            }
            hm.topTabs.getTabAt(savedInstanceState.getInt("topTab", 0))?.select()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TaskRepository.tasks.collect { renderTasks(it) } }
                launch { vm.events.collect { message(it) } }
                launch {
                    while (true) {
                        updateNativeBar()
                        delay(1000)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
        outState.putInt("topTab", topTab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) checkClipboard()
    }

    override fun onPause() {
        hm.webView.onPause()
        CookieManager.getInstance().flush()      // keep the YouTube session
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        hm.webView.onResume()
    }

    override fun onDestroy() {
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
        hm.topTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) { selectTop(tab?.position ?: 0) }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        hm.searchBtn.setOnClickListener { onSearchSubmit(hm.urlInput.text?.toString().orEmpty()) }
        hm.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                onSearchSubmit(hm.urlInput.text?.toString().orEmpty()); true
            } else false
        }
        hm.pasteBtn.setOnClickListener { pasteFromClipboard() }
        hm.morePasteBtn.setOnClickListener { pasteFromClipboard() }
        renderRecents()

        listOf(
            "TikTok", "Instagram", "Facebook", "X (Twitter)", "Vimeo", "Dailymotion",
            "Reddit", "Twitch", "SoundCloud", "Pinterest", "Bilibili", "1000+ more"
        ).forEach { name ->
            val chip = Chip(this)
            chip.text = name
            chip.isClickable = false
            chip.isCheckable = false
            hm.moreChips.addView(chip)
        }

        // Lets the Download button that we add under the YouTube player talk to the app
        hm.webView.addJavascriptInterface(JsBridge(this), "YtdlBridge")

        // Docked fallback bar (only visible when the in-page button is missing)
        hm.nbDownload.setOnClickListener { hm.webView.url?.let { onJsDownload(it, false) } }
        hm.nbAudio.setOnClickListener { hm.webView.url?.let { onJsDownload(it, true) } }
        hm.nbMore.setOnClickListener { hm.webView.url?.let { onJsMore(it) } }

        // gradient RAINAX wordmark
        hm.brandWord.post {
            val w = hm.brandWord.paint.measureText(hm.brandWord.text.toString())
            hm.brandWord.paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(
                    ContextCompat.getColor(this, R.color.rx_grad_start),
                    ContextCompat.getColor(this, R.color.rx_grad_end)
                ),
                null, Shader.TileMode.CLAMP
            )
            hm.brandWord.invalidate()
        }

        val ws = hm.webView.settings
        hm.webView.setBackgroundColor(ContextCompat.getColor(this, R.color.rx_bg))
        ws.forceDark = if (isDarkUi()) WebSettings.FORCE_DARK_AUTO else WebSettings.FORCE_DARK_OFF
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = true
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(hm.webView, true)

        hm.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                val scheme = uri.scheme
                if (scheme != "http" && scheme != "https") return true
                return !isAllowedHost(uri.host.orEmpty())      // in-app browsing is YouTube only
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

            override fun onPageFinished(view: WebView?, url: String?) {
                onPageChanged(url)
            }
        }
        hm.webView.webChromeClient = object : WebChromeClient() {
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

    private fun isAllowedHost(host: String): Boolean {
        val h = host.lowercase()
        return listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "google.com",
            "googleusercontent.com", "gstatic.com", "ytimg.com"
        ).any { h == it || h.endsWith(".$it") }
    }

    /** Which top tab a URL belongs to: 2 = YouTube Music, 1 = YouTube. */
    private fun siteOf(url: String): Int =
        if (Uri.parse(url).host.orEmpty().lowercase() == "music.youtube.com") 2 else 1

    private fun defaultUrl(index: Int) = if (index == 2) MUSIC_HOME else YT_HOME

    private fun selectTop(index: Int) {
        topTab = index
        hm.searchFrame.isVisible = index == 0
        hm.webFrame.isVisible = index == 1 || index == 2
        hm.moreFrame.isVisible = index == 3
        if (index == 1 || index == 2) {
            var target = pendingUrl ?: lastUrl[index] ?: defaultUrl(index)
            if (siteOf(target) != index) target = defaultUrl(index)   // never show the other site
            val shown = hm.webView.url.orEmpty()
            val wrongSite = shown.isNotEmpty() && shown != "about:blank" && siteOf(shown) != index
            if (pendingUrl != null || webSite != index || wrongSite) {
                hm.webView.stopLoading()     // drop a load that is still running for the other tab
                hm.webView.loadUrl(target)
            }
            webSite = index
            pendingUrl = null
        }
        updateBack()
    }

    private fun openYoutube(url: String) {
        if (topTab == 1) {
            hm.webView.loadUrl(url)
        } else {
            pendingUrl = url
            hm.topTabs.getTabAt(1)?.select()
        }
    }

    private fun onPageChanged(url: String?) {
        if (url.isNullOrBlank() || url == "about:blank") return
        val host = Uri.parse(url).host.orEmpty().lowercase()
        if (host == "music.youtube.com") lastUrl[2] = url
        else if (host == "m.youtube.com" || host == "www.youtube.com" || host == "youtube.com") lastUrl[1] = url
        // Hides the "Open app" prompts and adds the Download button under the player
        if (isAllowedUrl(url)) {
            hm.webView.evaluateJavascript(
                "window.__ytdlAdBlock=$adBlockOn;window.__rxLight=${!isDarkUi()};", null
            )
            hm.webView.evaluateJavascript(INJECT_JS, null)
        }
    }

    private fun updateNativeBar() {
        val url = hm.webView.url.orEmpty()
        val onWatchPage = tab == 0 && (topTab == 1 || topTab == 2) &&
            YT_VIDEO.containsMatchIn(url) && isAllowedUrl(url)
        val inPageBarAlive = SystemClock.elapsedRealtime() - lastBeat < 2500
        hm.nativeBar.isVisible = onWatchPage && !inPageBarAlive
    }

    private fun isAllowedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return (uri.scheme == "http" || uri.scheme == "https") && isAllowedHost(uri.host.orEmpty())
    }

    /** Called from the in-page Download button (headphones = audio). */
    internal fun onJsDownload(url: String, audio: Boolean) {
        if (isAllowedUrl(url)) showDownloadSheet(listOf(url), preferAudio = audio)
    }

    /** Called from the in-page "more" button. */
    internal fun onJsMore(url: String) {
        if (!isAllowedUrl(url)) return
        AlertDialog.Builder(this)
            .setItems(arrayOf("Copy link", "Share link", "Open in browser")) { _, which ->
                when (which) {
                    0 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("link", url))
                        AppPrefs.setLastClip(this, url)
                        message("Link copied")
                    }
                    1 -> startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null
                        )
                    )
                    2 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .show()
    }

    /** Text becomes a YouTube search; anything that looks like a link opens the format sheet. */
    private fun onSearchSubmit(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        hideKeyboard()
        val urls = extractUrls(text)
        when {
            urls.isNotEmpty() -> showDownloadSheet(urls)
            Patterns.WEB_URL.matcher(text).matches() -> showDownloadSheet(listOf("https://$text"))
            else -> {
                AppPrefs.addRecent(this, text)
                renderRecents()
                openYoutube("https://m.youtube.com/results?search_query=" + Uri.encode(text))
            }
        }
    }

    private fun renderRecents() {
        val items = AppPrefs.recents(this)
        hm.recentTitle.isVisible = items.isNotEmpty()
        hm.recentGroup.isVisible = items.isNotEmpty()
        hm.recentGroup.removeAllViews()
        items.forEach { q ->
            val chip = Chip(this)
            chip.text = q
            chip.setOnClickListener { hm.urlInput.setText(q); onSearchSubmit(q) }
            hm.recentGroup.addView(chip)
        }
    }

    private fun showTab(index: Int) {
        if (index != 1 && selecting) exitSelection()
        if (index == 2) renderAccountStatus()
        tab = index
        hm.root.isVisible = index == 0
        pl.root.isVisible = index == 1
        st.root.isVisible = index == 2
        updateBack()
    }

    private fun updateBack() {
        backCallback.isEnabled = customView != null || selecting || tab != 0 || topTab != 0
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
        val urls = extractUrls(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        if (urls.isNotEmpty()) {
            AppPrefs.setLastClip(this, urls[0])
            showDownloadSheet(urls)
        }
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val urls = extractUrls(text)
        if (urls.isEmpty()) {
            message("No link found in clipboard")
        } else {
            AppPrefs.setLastClip(this, urls[0])
            showDownloadSheet(urls)
        }
    }

    /** Offer to download as soon as a link is copied in another app. */
    private fun checkClipboard() {
        if (!AppPrefs.clipDetect(this) || sheet?.isShowing == true) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (cm.primaryClipDescription?.hasMimeType("text/*") != true) return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val urls = extractUrls(text)
        val first = urls.firstOrNull() ?: return
        if (first == AppPrefs.lastClip(this)) return
        AppPrefs.setLastClip(this, first)
        Snackbar.make(b.root, "Link copied: ${Uri.parse(first).host ?: first}", Snackbar.LENGTH_LONG)
            .setAnchorView(b.bottomNav)
            .setAction("Download") { showDownloadSheet(urls) }
            .show()
    }

    private fun showDownloadSheet(urls: List<String>, preferAudio: Boolean = false) {
        sheet?.dismiss()
        val sb = SheetDownloadBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sb.root)
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener { dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED }
        sheet = dialog

        val single = urls.size == 1
        val firstUrl = urls[0]
        val cookies = urls.associateWith { CookieHelper.cookieString(it) }
        val screenH = resources.displayMetrics.heightPixels

        var current: PreviewState? = null
        var mode = MODE_QUICK
        var selectedSpec: String? = if (preferAudio) "audio:mp3:128" else null
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
            val presets = !single || error != null || isPlaylist
            val quick = if (presets) PRESETS else p?.quick.orEmpty()
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
                loading -> p?.title ?: "Fetching info…"
                error != null -> Uri.parse(firstUrl).host.orEmpty()
                isPlaylist -> "${p!!.title}  •  ${p.subtitle}"
                else -> p?.title.orEmpty()
            }
            sb.previewLoading.isVisible = loading

            sb.errorBlock.isVisible = error != null
            sb.signInBtn.isVisible = error != null && error.contains("sign in", ignoreCase = true)
            if (error != null) {
                sb.errorText.text = error + "\n\nTip: tap Update & retry — sites change often. " +
                    "You can still try a format below."
            }
            val hasList = Uri.parse(firstUrl).getQueryParameter("list") != null
            sb.playlistBtn.isVisible =
                mode == MODE_QUICK && single && hasList && !loading && !isPlaylist && error == null

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
        sb.signInBtn.setOnClickListener {
            dialog.dismiss()
            openSignIn()
        }
        sb.updateBtn.setOnClickListener { vm.updateAndRefetch(firstUrl, cookies[firstUrl]) }
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
                else -> listOf(EnqueueItem(firstUrl, p?.title.orEmpty(), cookies[firstUrl]))
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
            vm.clearPreview()
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

        pl.emptySearchBtn.setOnClickListener {
            b.bottomNav.selectedItemId = R.id.nav_home
            hm.topTabs.getTabAt(0)?.select()
        }
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
        activeAdapter.submitList(active.asReversed())

        pl.doneSection.isVisible = done.isNotEmpty()
        pl.doneTitle.text = "Downloaded (${done.size})"
        doneAdapter.submitList(done)

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
            try {
                if (contentResolver.delete(Uri.parse(uri), null, null) > 0) deleted++
            } catch (e: Exception) {
                // not ours any more (e.g. after a reinstall) or already gone
            }
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
        popup.menu.add(0, 2, 1, "Share")
        popup.menu.add(0, 3, 2, "Copy link")
        popup.menu.add(0, 4, 3, "Remove from list")
        popup.menu.add(0, 5, 4, "Delete file")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openFile(t)
                2 -> shareFile(t)
                3 -> {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("link", t.url))
                    AppPrefs.setLastClip(this, t.url)
                    message("Link copied")
                }
                4 -> TaskRepository.remove(t.id)
                5 -> {
                    try { t.fileUri?.let { contentResolver.delete(Uri.parse(it), null, null) } } catch (e: Exception) { }
                    TaskRepository.remove(t.id)
                    message("File deleted")
                }
            }
            true
        }
        popup.show()
    }

    private fun openFile(t: DownloadTask) {
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
        st.autoUpdateSwitch.isChecked = AppPrefs.autoUpdate(this)
        st.autoUpdateSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setAutoUpdate(this, on) }
        st.clipSwitch.isChecked = AppPrefs.clipDetect(this)
        st.clipSwitch.setOnCheckedChangeListener { _, on -> AppPrefs.setClipDetect(this, on) }
        st.themeGroup.check(
            when (AppPrefs.themeMode(this)) {
                1 -> R.id.themeLight
                2 -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        st.themeGroup.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.themeLight -> 1
                R.id.themeDark -> 2
                else -> 0
            }
            if (mode != AppPrefs.themeMode(this)) {
                AppPrefs.setThemeMode(this, mode)
                RainaxApp.applyTheme(mode)      // recreates the screen with the new colours
            }
        }

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

        st.updateNowBtn.setOnClickListener { vm.updateYtDlp() }
        st.openFolderBtn.setOnClickListener {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            } catch (e: Exception) {
                message("Can't open the Downloads folder")
            }
        }
        st.clearRecentBtn.setOnClickListener {
            AppPrefs.clearRecents(this)
            renderRecents()
            message("Search history cleared")
        }
        st.signInBtn.setOnClickListener { openSignIn() }
        st.signOutBtn.setOnClickListener {
            confirm(
                "Sign out of YouTube?",
                "Private and age-restricted videos will stop downloading until you sign in again."
            ) {
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    renderAccountStatus()
                    if (webSite != 0) hm.webView.reload()
                    message("Signed out")
                }
            }
        }
        renderAccountStatus()

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            ""
        }
        st.versionText.text = "RAINAX YT DOWNLOADER  v$version"
    }

    private fun renderAccountStatus() {
        val signedIn = CookieHelper.isSignedIn()
        st.accountStatus.text = if (signedIn) "Signed in to YouTube" else "Not signed in"
        st.signInBtn.text = if (signedIn) "Open YouTube sign-in again" else "Sign in to YouTube"
        st.signOutBtn.isEnabled = signedIn
    }

    /** Opens Google's own sign-in page in the YouTube tab. */
    private fun openSignIn() {
        b.bottomNav.selectedItemId = R.id.nav_home
        openYoutube(SIGN_IN_URL)
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

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.root.windowToken, 0)
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
        private const val SIGN_IN_URL =
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F"
        private const val MUSIC_HOME = "https://music.youtube.com"

        private const val MODE_QUICK = 0
        private const val MODE_ALL = 1
        private const val MODE_SUBS = 2

        private val AD_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "imasdk.googleapis.com", "2mdn.net", "moatads.com", "adsrvr.org", "taboola.com", "outbrain.com"
        )
        private val AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info")

        /**
         * Runs inside the YouTube page: hides "Open app" prompts and puts a Download button
         * (with headphones for audio and a more menu) right under the player.
         */
        private const val INJECT_JS = """
(function(){
 if(window.__ytdlInit2) return;
 window.__ytdlInit2=true;

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

 // YouTube Music has no room under its player: the app's docked Download bar is used there instead
 function isMusic(){return location.hostname==='music.youtube.com';}
 function isWatch(){if(isMusic()) return false; var p=location.pathname;return p==='/watch'||p.indexOf('/shorts/')===0;}

 // "Open in the YouTube (Music) app" dialogs: press "Not now" and remove them
 var PROMPT_HINTS=['open in the youtube music app','open in the youtube app','open in app','get the youtube music app','get the youtube app','try the youtube app'];
 function dismissAppPrompts(){
  var cands=document.querySelectorAll('tp-yt-paper-dialog,ytmusic-popup-container,ytmusic-mealbar-promo-renderer,ytm-mealbar-promo-renderer,ytmusic-dialog,yt-dialog,[role="dialog"],dialog');
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
  +'ytmusic-statement-banner-renderer,ytmusic-mealbar-promo-renderer,.ad-container,.video-ads,.ytp-ad-module,.ytp-ad-image-overlay,'
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

 // Pick icon colour from the page background (white on dark pages, black on light pages)
 function isDark(){
  function lum(c){
   var m=c&&c.match(/[\d.]+/g);
   if(!m||m.length<3) return null;
   if(m.length>=4&&parseFloat(m[3])===0) return null;
   return 0.299*m[0]+0.587*m[1]+0.114*m[2];
  }
  var l=lum(getComputedStyle(document.body).backgroundColor);
  if(l===null) l=lum(getComputedStyle(document.documentElement).backgroundColor);
  if(l===null) return true;
  return l<128;
 }

 // DOM APIs only: YouTube blocks innerHTML (Trusted Types)
 function svgIcon(d,color){
  var ns='http://www.w3.org/2000/svg';
  var s=document.createElementNS(ns,'svg');
  s.setAttribute('width','26'); s.setAttribute('height','26');
  s.setAttribute('viewBox','0 0 24 24'); s.setAttribute('fill',color);
  var p=document.createElementNS(ns,'path'); p.setAttribute('d',d); s.appendChild(p);
  return s;
 }
 function mkBtn(svg,label){
  var b=document.createElement('button');
  b.appendChild(svg); b.setAttribute('aria-label',label);
  b.style.cssText='width:44px;height:44px;border:0;padding:0;background:transparent;display:flex;align-items:center;justify-content:center;';
  return b;
 }
 function makeBar(){
  var color=isDark()?'#FFFFFF':'#0F0F0F';
  var bar=document.createElement('div'); bar.id='ytdl-bar';
  bar.style.cssText='display:flex;align-items:center;gap:10px;padding:10px 14px;box-sizing:border-box;width:100%;';
  var dl=document.createElement('button'); dl.textContent='Download';
  dl.style.cssText='flex:1;height:46px;border:0;border-radius:23px;background:linear-gradient(90deg,#6A4CFF,#1E90F2);color:#FFFFFF;font-size:16px;font-weight:700;';
  dl.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();YtdlBridge.download(location.href,false);});
  var au=mkBtn(svgIcon('M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 3-1.34 3-3v-7c0-4.97-4.03-9-9-9z',color),'Download audio');
  au.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();YtdlBridge.download(location.href,true);});
  var mo=mkBtn(svgIcon('M12 8a2 2 0 100-4 2 2 0 000 4zm0 2a2 2 0 100 4 2 2 0 000-4zm0 6a2 2 0 100 4 2 2 0 000-4z',color),'More');
  mo.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();YtdlBridge.more(location.href);});
  bar.appendChild(dl); bar.appendChild(au); bar.appendChild(mo);
  return bar;
 }

 // Finds the video title on the page, then climbs out of wrappers and horizontal rows
 function findTitleBlock(){
  var m=document.querySelector('meta[property="og:title"]');
  var t=norm((m&&m.content)||document.title.replace(/\s*-\s*YouTube.*$/i,''));
  if(!t) return null;
  var nodes=document.querySelectorAll('h1,h2,h3,yt-formatted-string,[class*="title"]');
  for(var i=0;i<nodes.length;i++){
   var n=nodes[i];
   if(n.closest('#ytdl-bar')) continue;
   if(norm(n.textContent)!==t) continue;
   var r=n.getBoundingClientRect();
   if(r.width===0&&r.height===0) continue;
   var a=n;
   for(var k=0;k<8;k++){
    var p=a.parentElement;
    if(!p||p===document.body) break;
    var cs=getComputedStyle(p);
    var row=(cs.display.indexOf('flex')>=0&&cs.flexDirection.indexOf('row')===0)||cs.display.indexOf('grid')>=0||cs.display==='table-row';
    if(row||p.children.length===1){a=p;continue;}
    break;
   }
   return a;
  }
  return null;
 }
 function placeAfterTitle(bar){
  var a=findTitleBlock();
  if(a&&a.parentNode){a.parentNode.insertBefore(bar,a.nextSibling);bar.setAttribute('data-anchor','title');return true;}
  return false;
 }
 function placeElsewhere(bar){
  var a=document.querySelector('ytm-slim-video-action-bar-renderer');
  if(a&&a.parentNode){a.parentNode.insertBefore(bar,a);return true;}
  var sels=['ytm-slim-video-metadata-section-renderer','ytm-slim-video-information-renderer','#player-container-id','.player-container','#player'];
  for(var i=0;i<sels.length;i++){
   var e=document.querySelector(sels[i]);
   if(e&&e.parentNode){e.parentNode.insertBefore(bar,e.nextSibling);return true;}
  }
  var v=document.querySelector('video');
  if(v){
   var c=v.closest('#player-container-id,.player-container,#player,#player-container');
   if(c&&c.parentNode){c.parentNode.insertBefore(bar,c.nextSibling);return true;}
  }
  return false;
 }
 function beat(){try{YtdlBridge.barShown(true);}catch(e){}}

 // YouTube Music only ships a dark page: in the light theme it is inverted into a light one
 function musicTheme(){
  var root=document.documentElement;
  var on=isMusic()&&window.__rxLight===true;
  if(on){
   if(root.style.getPropertyValue('filter')===''){
    root.style.setProperty('filter','invert(1) hue-rotate(180deg)','important');
    root.style.setProperty('background-color','#ffffff','important');
   }
   try{
    if(!window.__rxSheet){var sh=new CSSStyleSheet();sh.replaceSync('img,video,canvas{filter:invert(1) hue-rotate(180deg)!important}');window.__rxSheet=sh;}
    if(document.adoptedStyleSheets.indexOf(window.__rxSheet)<0) document.adoptedStyleSheets=document.adoptedStyleSheets.concat([window.__rxSheet]);
   }catch(e){}
  }else if(root.style.getPropertyValue('filter')!==''){
   root.style.removeProperty('filter');root.style.removeProperty('background-color');
   try{ if(window.__rxSheet) document.adoptedStyleSheets=document.adoptedStyleSheets.filter(function(x){return x!==window.__rxSheet;}); }catch(e){}
  }
 }

 function tick(){
  try{ musicTheme(); }catch(e){}
  try{ hideOpenApp(); }catch(e){}
  try{ dismissAppPrompts(); }catch(e){}
  try{ hideAds(); }catch(e){}
  try{
   var bar=document.getElementById('ytdl-bar');
   if(!isWatch()){ if(bar&&bar.parentNode) bar.parentNode.removeChild(bar); return; }
   if(bar&&bar.isConnected){
    // not under the title yet? keep trying to move it there
    if(bar.getAttribute('data-anchor')!=='title') placeAfterTitle(bar);
    beat(); return;
   }
   bar=makeBar();
   if(!placeAfterTitle(bar)){
    if(placeElsewhere(bar)){ bar.setAttribute('data-anchor','other'); }
    else{
     bar.style.cssText+='position:fixed;left:0;right:0;bottom:0;z-index:2147483647;background:rgba(20,20,20,0.85);';
     document.body.appendChild(bar);
     bar.setAttribute('data-anchor','fixed');
    }
   }
   beat();
  }catch(e){}
 }
 setInterval(tick,700);
 setInterval(function(){try{dismissAppPrompts();}catch(e){} try{adTick();}catch(e){}},300);
 tick();
})();
"""

        /** Pages where the Download button is active: videos, shorts, music and playlists. */
        private val YT_VIDEO = Regex(
            "(youtube\\.com/(watch|shorts/|playlist\\?)|youtu\\.be/|music\\.youtube\\.com/(watch|playlist))"
        )
    }
}

/** Exposed to the YouTube page as `YtdlBridge`; only YouTube pages can be loaded in the WebView. */
class JsBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun download(url: String?, audio: Boolean) {
        val u = url ?: return
        activity.runOnUiThread { activity.onJsDownload(u, audio) }
    }

    @JavascriptInterface
    fun barShown(shown: Boolean) {
        if (shown) activity.lastBeat = SystemClock.elapsedRealtime()
    }

    @JavascriptInterface
    fun more(url: String?) {
        val u = url ?: return
        activity.runOnUiThread { activity.onJsMore(u) }
    }
}
