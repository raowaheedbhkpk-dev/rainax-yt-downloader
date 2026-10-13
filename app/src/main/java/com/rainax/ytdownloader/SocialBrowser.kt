package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.rainax.ytdownloader.databinding.LayoutBrowserBinding

/**
 * TikTok, Instagram and Facebook inside RAINAX (like Snaptube): browse normally, sign in if you like, and when a
 * video or Reel is open, Download sends it to the download sheet. Links to other apps stay inside.
 * The page lives over the main screen, so the download sheet opens on top and you keep browsing after.
 */
class SocialBrowser(
    private val act: AppCompatActivity,
    private val parent: ViewGroup,
    private val onDownload: (String) -> Unit
) {
    /** Where each app starts. */
    private val startPages = mapOf(
        "TikTok" to "https://www.tiktok.com/foryou",
        "Instagram" to "https://www.instagram.com/reels/",
        "Facebook" to "https://m.facebook.com/watch/"
    )

    fun canBrowse(app: SocialApps.App) = app.name in startPages

    private var b: LayoutBrowserBinding? = null
    private var web: WebView? = null
    private var app: SocialApps.App? = null

    val isOpen get() = b?.root?.isVisible == true

    @SuppressLint("SetJavaScriptEnabled")
    fun open(target: SocialApps.App) {
        val start = startPages[target.name] ?: return
        app = target
        val v = b ?: LayoutBrowserBinding.inflate(LayoutInflater.from(act), parent, false).also { nb ->
            parent.addView(nb.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            nb.browserClose.setOnClickListener { close() }
            nb.browserReload.setOnClickListener { web?.reload() }
            nb.browserMenu.setOnClickListener { menu(it) }
            nb.browserDownload.setOnClickListener { download() }
            b = nb
        }
        v.root.isVisible = true
        v.root.bringToFront()
        v.browserBadge.background = SocialApps.badge(target, act)
        v.browserBadge.text = target.short
        v.browserTitle.text = target.name
        v.browserHost.text = Uri.parse(start).host
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        val w = WebView(act)
        web = w
        v.browserWebBox.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)          // "open in new tab" links open here
            loadWithOverviewMode = true
            useWideViewPort = true
            // the phone's normal browser look (sites hide features from in-app web views)
            userAgentString = WebSettings.getDefaultUserAgent(act).replace("; wv)", ")")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)   // Facebook and Instagram sign-in need it
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme) {
                    "https", "http" -> false
                    "intent" -> {
                        // "open in the app" links: use their web address instead, and stay here
                        val fallback = Regex("S\\.browser_fallback_url=([^;]+)").find(u.toString())?.groupValues?.get(1)
                        fallback?.let { f -> Uri.decode(f).takeIf { it.startsWith("https://") }?.let { view.loadUrl(it) } }
                        true
                    }
                    else -> true                                     // fb://, instagram://, snssdk…: ignored
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = update(url)
            override fun onPageFinished(view: WebView, url: String?) {
                update(url)
                CookieManager.getInstance().flush()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = update(url)
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b?.browserProgress?.isVisible = newProgress in 1..99
                b?.browserProgress?.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                b?.browserTitle?.text = title?.takeIf { it.isNotBlank() } ?: app?.name.orEmpty()
            }
        }
        update(start)
        w.loadUrl(start)
    }

    /** True when the address is a single video, Reel or post that can be downloaded. */
    private fun isVideoPage(url: String?): Boolean {
        val u = url?.lowercase() ?: return false
        return when {
            "tiktok.com" in u -> Regex("/video/\\d+|/photo/\\d+|//(vm|vt)\\.tiktok\\.com/").containsMatchIn(u)
            "instagram.com" in u -> Regex("/(p|reel|reels|tv)/[a-z0-9_-]{5,}").containsMatchIn(u)
            "facebook.com" in u || "fb.watch" in u ->
                Regex("/reel/\\d+|/videos/|/watch/?\\?v=|video\\.php|/share/(v|r)/|fb\\.watch/").containsMatchIn(u)
            else -> false
        }
    }

    private fun update(url: String?) {
        val v = b ?: return
        url?.let { v.browserHost.text = Uri.parse(it).host.orEmpty().removePrefix("www.") + Uri.parse(it).path.orEmpty() }
        val ready = isVideoPage(url)
        v.browserDownload.alpha = if (ready) 1f else 0.55f
        v.browserDownloadText.text = if (ready) "Download" else "Open a video"
    }

    private fun download() {
        val url = web?.url
        if (!isVideoPage(url)) {
            Toast.makeText(act, "Open a video or Reel first, then tap Download", Toast.LENGTH_SHORT).show()
            return
        }
        // the cookies of a signed-in page go with the link, so videos you can see here can be read
        CookieManager.getInstance().flush()
        onDownload(url!!)
    }

    private fun menu(anchor: View) {
        val m = PopupMenu(act, anchor)
        m.menu.add(0, 1, 0, "Back")
        m.menu.add(0, 2, 1, "Home page")
        m.menu.add(0, 3, 2, "Open in ${app?.name ?: "the app"}")
        m.menu.add(0, 4, 3, "Sign out of ${app?.name ?: "this site"}")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> if (web?.canGoBack() == true) web?.goBack()
                2 -> app?.let { a -> startPages[a.name]?.let { s -> web?.loadUrl(s) } }
                3 -> app?.let { a -> SocialApps.open(act, a) }
                4 -> signOut()
            }
            true
        }
        m.show()
    }

    /** Removes this site's sign-in from RAINAX (its cookies), then reloads. */
    private fun signOut() {
        val a = app ?: return
        val cm = CookieManager.getInstance()
        val hosts = a.hosts.flatMap { h -> listOf("https://$h", "https://www.$h", "https://m.$h") }
        for (h in hosts) {
            cm.getCookie(h)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                a.hosts.forEach { d -> cm.setCookie(h, "$name=; Max-Age=0; Domain=.$d; Path=/") }
                cm.setCookie(h, "$name=; Max-Age=0; Path=/")
            }
        }
        cm.flush()
        Toast.makeText(act, "Signed out of ${a.name} in RAINAX", Toast.LENGTH_SHORT).show()
        startPages[a.name]?.let { web?.loadUrl(it) }
    }

    /** Back button: the page goes back first, then the browser closes. True when handled. */
    fun back(): Boolean {
        if (!isOpen) return false
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else close()
        return true
    }

    fun close() {
        b?.root?.isVisible = false
        web?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
    }

    fun pause() { web?.onPause() }
    fun resume() { web?.onResume() }
}
