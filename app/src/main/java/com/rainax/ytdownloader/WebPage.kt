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
    private val clipboard = act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    private var lastClip: String? = null

    /** "Copy link" on the site (its Share menu): that video goes straight to the download sheet. */
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (destroyed || !dialog.isShowing) return@OnPrimaryClipChangedListener
        val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString() }.getOrNull() ?: return@OnPrimaryClipChangedListener
        val link = Regex("https?://\\S+").find(text)?.value ?: return@OnPrimaryClipChangedListener
        if (link == lastClip) return@OnPrimaryClipChangedListener
        lastClip = link
        val short = link.contains("vm.tiktok.com") || link.contains("vt.tiktok.com")
        if (short || isVideoPage(link)) {
            AppPrefs.setClipSeen(act, link)                // (the app won't ask again about this link)
            b.webRoot.post { if (!destroyed) downloadLink(link) }
        }
    }

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
                view.evaluateJavascript(CLEANER_JS, null)
                CookieManager.getInstance().flush()          // sign-ins are kept (and used for downloads)
            }

            // the sites change the address without loading a new page (scrolling through Reels, TikTok…)
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                pageChanged(url)
                view.evaluateJavascript(CLEANER_JS, null)
            }
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
        runCatching { clipboard.addPrimaryClipChangedListener(clipListener) }
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
        // feeds play videos too (TikTok For You, Reels, Facebook Watch): the button is always ready
        b.webDownload.alpha = 1f
        b.webDownload.extend()
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
        if (isVideoPage(url)) { downloadLink(url); return }
        // a feed (TikTok For You, Reels, Facebook Watch, X timeline): find the video playing on screen and its link
        b.webView.evaluateJavascript(FIND_PLAYING_JS) { result ->
            if (destroyed) return@evaluateJavascript
            val link = runCatching { org.json.JSONArray("[$result]").optString(0) }.getOrNull().orEmpty()
            if (link.startsWith("http") && isVideoPage(link)) downloadLink(link)
            else Snackbar.make(b.webRoot, "Play a video first (or open it), then tap Download", Snackbar.LENGTH_SHORT)
                .setAnchorView(b.webDownload).show()
        }
    }

    private fun downloadLink(url: String) {
        // Facebook's mobile pages: the same post on the main site reads best
        val clean = url.replace("://m.facebook.com", "://www.facebook.com").replace("://mobile.facebook.com", "://www.facebook.com")
        download(clean)
    }

    private companion object {
        /**
         * Hides the sites' "Open app" buttons and closes "Get the full app experience" windows (taps their
         * "Not now"), now and whenever they appear again. Installed once per page.
         */
        val CLEANER_JS = """
            (function(){
              if (window.__rxClean) { window.__rxClean(); return; }
              var HIDE = /^(open app|open tiktok|open the app|open in app|open instagram|open facebook|use the app|get the app|open x|get app|switch to the app)$/i;
              var CLOSE = /^(not now|not now\.|maybe later|continue in browser|stay on web|continue on web)$/i;
              var MODAL = /(get the full app experience|open in the tiktok app|see more on the app|watch more on the app|download the app|use the app for the best experience)/i;
              function txt(e){ return (e.innerText || e.textContent || '').trim(); }
              function clean(){
                var all = document.querySelectorAll('a,button,div[role=button],span,div');
                for (var i = 0; i < all.length; i++) {
                  var e = all[i];
                  if (e.__rxDone) continue;
                  var t = txt(e);
                  if (!t || t.length > 40) continue;
                  // "Not now": tap it (the innermost element; the tap reaches the site's own button)
                  if (CLOSE.test(t) && (e.tagName == 'BUTTON' || e.tagName == 'A' || e.getAttribute('role') == 'button' || e.children.length == 0)) {
                    e.__rxDone = true; try { e.click(); } catch (x) {}
                  } else if (HIDE.test(t)) {
                    var b = e.closest('a,button,[role=button]') || e;
                    if (txt(b).length <= 40) { b.style.setProperty('display', 'none', 'important'); e.__rxDone = true; }
                  }
                }
                var blocks = document.querySelectorAll('div,section');
                for (var j = 0; j < blocks.length; j++) {
                  var d = blocks[j];
                  if (d.__rxDone || d.children.length > 12) continue;
                  var s = txt(d);
                  if (s.length > 0 && s.length < 300 && MODAL.test(s)) {
                    // climb to the window that floats over the page and hide it (and its dark backdrop)
                    var p = d, k = 0;
                    while (p && p !== document.body && k++ < 8) {
                      var st = getComputedStyle(p);
                      if (st.position == 'fixed') {
                        p.style.setProperty('display', 'none', 'important'); d.__rxDone = true;
                        // its dark backdrop: another full-screen layer beside it with no video in it
                        var sib = p.parentElement ? p.parentElement.children : [];
                        for (var q = 0; q < sib.length; q++) {
                          var o = sib[q], os = getComputedStyle(o);
                          if (o !== p && os.position == 'fixed' && !o.querySelector('video') && txt(o).length < 5 &&
                              o.offsetWidth >= innerWidth * 0.9 && o.offsetHeight >= innerHeight * 0.9) o.style.setProperty('display', 'none', 'important');
                        }
                        break;
                      }
                      p = p.parentElement;
                    }
                  }
                }
                document.body && (document.body.style.overflow = '');
              }
              window.__rxClean = clean;
              clean();
              var pend = false;
              new MutationObserver(function(){ if (pend) return; pend = true; setTimeout(function(){ pend = false; clean(); }, 250); })
                .observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()

        /**
         * The link of the video playing on screen: the playing (or most visible) video, then the nearest link to a
         * post around it (TikTok: its id in the page). Empty when there is none.
         */
        val FIND_PLAYING_JS = """
            (function(){
              function seen(v){ var r = v.getBoundingClientRect();
                var h = Math.min(r.bottom, innerHeight) - Math.max(r.top, 0), w = Math.min(r.right, innerWidth) - Math.max(r.left, 0);
                return (h > 0 && w > 0) ? h * w : 0; }
              var vids = [].slice.call(document.querySelectorAll('video')).filter(function(v){ return seen(v) > 0; });
              if (!vids.length) return '';
              vids.sort(function(a, b){ return ((b.paused ? 0 : 1e9) + seen(b)) - ((a.paused ? 0 : 1e9) + seen(a)); });
              var v = vids[0];
              var post = /(\/@[\w.-]+\/(video|photo)\/\d{8,}|\/reels?\/[\w-]{5,}|\/p\/[\w-]{5,}|\/tv\/[\w-]{5,}|\/videos\/[\w.%-]{5,}|\/watch\/?\?v=\d+|\/share\/[vr]\/[\w-]+|\/status\/\d+|\/video\/\d{8,})/;
              var tiktok = location.host.indexOf('tiktok') >= 0;
              var el = v;
              for (var i = 0; i < 16 && el && el !== document.body; i++) {
                if (i > 0 && el.querySelectorAll('video').length > 1) break;      // left this video's own box
                var as = el.querySelectorAll ? el.querySelectorAll('a[href]') : [];
                for (var j = 0; j < as.length; j++) if (post.test(as[j].href)) return as[j].href;
                if (tiktok) {
                  // TikTok keeps the video's id (19 digits) in attributes of its box or of the elements in it
                  var nodes = [el].concat([].slice.call(el.querySelectorAll('*'), 0, 400));
                  for (var n = 0; n < nodes.length; n++) {
                    var attrs = nodes[n].attributes || [];
                    for (var k = 0; k < attrs.length; k++) {
                      if (attrs[k].name == 'style' || attrs[k].name == 'class') continue;
                      var m = /(?:^|\D)(\d{18,20})(?:\D|$)/.exec(attrs[k].value);
                      if (m) {
                        var au = el.querySelector('a[href*="/@"]');
                        var who = au ? ((au.getAttribute('href').match(/\/@[\w.-]+/) || [''])[0]) : '';
                        return 'https://www.tiktok.com' + (who || '/@tiktok') + '/video/' + m[1];
                      }
                    }
                  }
                }
                el = el.parentElement;
              }
              return '';
            })()
        """.trimIndent()
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
        runCatching { clipboard.removePrimaryClipChangedListener(clipListener) }
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
