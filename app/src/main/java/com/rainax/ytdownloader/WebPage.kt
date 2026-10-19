package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import com.rainax.ytdownloader.databinding.PageWebBinding

/**
 * RAINAX's browser for Instagram, Facebook, TikTok and X: the site opens inside the app (sign in there once and your
 * videos can be downloaded too), and the red Download button gets the video that's open. Links to the apps
 * themselves stay inside RAINAX; Back goes back in the site, then closes.
 */
class WebPage(
    private val act: AppCompatActivity,
    private val app: SocialApps.App,
    private val download: (String) -> Unit,
    private val openDownloads: () -> Unit = {}
) {
    private val b = PageWebBinding.inflate(act.layoutInflater)
    private val dialog = Dialog(act, if (AppPrefs.themeMode(act) == 3) R.style.Theme_Rainax_Page_Amoled else R.style.Theme_Rainax_Page)
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var destroyed = false

    private val life = object : DefaultLifecycleObserver {
        override fun onPause(owner: LifecycleOwner) { if (!destroyed) b.webView.onPause() }
        override fun onResume(owner: LifecycleOwner) { if (!destroyed) b.webView.onResume() }
        override fun onDestroy(owner: LifecycleOwner) { runCatching { dialog.dismiss() } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        dialog.setContentView(b.root)
        dialog.window?.let { win ->
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // sign-in boxes stay above the keyboard
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            win.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        b.webBadge.background = SocialApps.badge(app, act)
        b.webBadge.text = app.short
        b.webTitle.text = app.name
        b.webHost.text = Uri.parse(app.web).host.orEmpty()

        val w = b.webView
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            setSupportMultipleWindows(false)                 // "open in new tab" links open here
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
            // the sites show their normal mobile pages to a phone browser (not a cut-down "in-app" page)
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleLink(view, request.url)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                b.webProgress.isVisible = true
                pageChanged(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                b.webProgress.isVisible = false
                pageChanged(url)
                CookieManager.getInstance().flush()          // sign-ins are kept (and used for downloads)
            }

            // the sites change the address without loading a new page (scrolling through Reels, TikTok…)
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = pageChanged(url)
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.webProgress.setProgressCompat(newProgress, true)
                b.webProgress.isVisible = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                b.webTitle.text = title?.takeIf { it.isNotBlank() && !it.startsWith("http") } ?: app.name
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullView != null) { callback.onCustomViewHidden(); return }
                fullView = view
                fullCallback = callback
                b.webFull.addView(view)
                b.webFull.isVisible = true
                b.webDownload.isVisible = false
            }

            override fun onHideCustomView() = exitFull()
        }
        // a link that is a file (an .mp4): straight to the download sheet
        w.setDownloadListener { url, _, _, _, _ -> if (url.startsWith("http")) download(url) }

        b.webClose.setOnClickListener { dialog.dismiss() }
        b.webBack.setOnClickListener { goBack() }
        b.webRefresh.setOnClickListener { w.reload() }
        b.webDownload.setOnClickListener { downloadCurrent() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { goBack(); true }
            else keyCode == KeyEvent.KEYCODE_BACK
        }
        dialog.setOnDismissListener { release() }
        act.lifecycle.addObserver(life)
        dialog.show()
        pageChanged(app.web)
        w.loadUrl(app.web)
    }

    /** The download was added: say so here (with View, which closes the browser and shows Downloads). */
    fun added(what: String) {
        if (destroyed) return
        Snackbar.make(b.webRoot, what, Snackbar.LENGTH_LONG)
            .setAnchorView(b.webDownload)
            .setAction("View") { dialog.dismiss(); openDownloads() }
            .show()
    }

    /** True: RAINAX handled it (app links stay here, intent:// opens its web address). */
    private fun handleLink(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme == "http" || scheme == "https") return false
        if (scheme == "intent") {
            // "open in the app" links: use the web address they carry instead (stay in RAINAX)
            runCatching {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                val fallback = i.getStringExtra("browser_fallback_url")
                if (fallback != null && fallback.startsWith("http")) view.loadUrl(fallback)
            }
            return true
        }
        return true                                           // instagram://, fb://, snssdk…, market://: ignored
    }

    private fun currentUrl(): String = b.webView.url ?: app.web

    private fun pageChanged(url: String) {
        if (destroyed) return
        b.webHost.text = runCatching { Uri.parse(url).let { (it.host.orEmpty().removePrefix("www.") + it.path.orEmpty()).take(80) } }.getOrDefault("")
        val video = isVideoPage(url)
        b.webDownload.alpha = if (video) 1f else 0.55f
        if (video) b.webDownload.extend() else b.webDownload.shrink()
        b.webBack.isEnabled = b.webView.canGoBack()
        b.webBack.alpha = if (b.webView.canGoBack()) 1f else 0.35f
    }

    /** A post with a video (or photos) that RAINAX can download. */
    private fun isVideoPage(url: String): Boolean {
        val u = Uri.parse(url)
        val host = u.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        val path = u.path.orEmpty().lowercase()
        return when {
            host.endsWith("instagram.com") -> Regex("^/(reel|reels|p|tv)/[\\w-]+").containsMatchIn(path) ||
                Regex("^/[\\w.]+/(reel|p)/[\\w-]+").containsMatchIn(path)
            host.endsWith("tiktok.com") -> path.contains("/video/") || path.contains("/photo/") ||
                host.startsWith("vm.") || host.startsWith("vt.")
            host.endsWith("facebook.com") || host == "fb.watch" -> host == "fb.watch" || path.contains("/reel/") ||
                path.contains("/videos/") || path.startsWith("/watch") && u.getQueryParameter("v") != null ||
                path.startsWith("/share/v/") || path.startsWith("/share/r/") || path.contains("/story.php")
            host == "x.com" || host == "twitter.com" -> path.contains("/status/")
            else -> false
        }
    }

    private fun downloadCurrent() {
        val url = currentUrl()
        if (!isVideoPage(url)) {
            Snackbar.make(b.webRoot, "Open a video or post first, then tap Download", Snackbar.LENGTH_SHORT)
                .setAnchorView(b.webDownload).show()
            return
        }
        // Facebook's mobile pages: the same post on the main site reads best
        val clean = url.replace("://m.facebook.com", "://www.facebook.com").replace("://mobile.facebook.com", "://www.facebook.com")
        download(clean)
    }

    private fun goBack() {
        when {
            fullView != null -> exitFull()
            b.webView.canGoBack() -> b.webView.goBack()
            else -> dialog.dismiss()
        }
    }

    private fun exitFull() {
        val v = fullView ?: return
        b.webFull.removeView(v)
        b.webFull.isVisible = false
        b.webDownload.isVisible = true
        fullView = null
        runCatching { fullCallback?.onCustomViewHidden() }
        fullCallback = null
    }

    private fun release() {
        if (destroyed) return
        destroyed = true
        act.lifecycle.removeObserver(life)
        exitFull()
        CookieManager.getInstance().flush()
        runCatching {
            b.webView.stopLoading()
            b.webView.loadUrl("about:blank")
            (b.webView.parent as? android.view.ViewGroup)?.removeView(b.webView)
            b.webView.destroy()
        }
    }
}
