package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.rainax.ytdownloader.databinding.ActivitySignInBinding

/**
 * YouTube sign-in on Google's own page. When YouTube's sign-in cookies appear, they are kept on this phone
 * (for the user's recommendations, subscriptions, history and liked videos) and the screen closes.
 */
class SignInActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }


    private lateinit var b: ActivitySignInBinding
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        Ui.applyAccent(this)
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        b = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.signInClose.setOnClickListener { finish() }

        val ws = b.signInWeb.settings
        ws.allowFileAccess = false              // the sign-in page never needs the phone's files
        ws.allowContentAccess = false
        ws.javaScriptEnabled = true
        // (file:// pages reaching other files is already off on Android 4.1+; no need to set it)
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        ws.domStorageEnabled = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        // Google refuses sign-in from pages that say they are an app's web view ("This browser or app may not be
        // secure"): look like the phone's own Chrome. The app's web view gives itself away in three places: "; wv"
        // and "Version/4.0" in its name, and a header with the app's package name. All three are taken out.
        ws.userAgentString = chromeAgent()
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ws, emptySet())
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(b.signInWeb, true)

        b.signInWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // only secure pages (Google's sign-in is all https); app links (intent://...) and plain http stay out
                return request?.url?.scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                b.signInProgress.visibility = View.VISIBLE
                check()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                b.signInProgress.visibility = View.GONE
                check()
                // Google still said "Couldn't sign you in": once more, looking like Firefox (Google allows it)
                if (url != null && rejected(url) && !triedFirefox) {
                    triedFirefox = true
                    b.signInWeb.settings.userAgentString = FIREFOX_UA
                    b.signInWeb.clearHistory()
                    b.signInWeb.loadUrl(SIGN_IN_URL)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = check()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.signInIntro.visibility == View.VISIBLE -> finish()
                    b.signInWeb.canGoBack() -> b.signInWeb.goBack()
                    else -> finish()
                }
            }
        })

        // first the page that says what signing in gives; Sign in opens Google's page
        b.signInStart.setOnClickListener { startSignIn() }
        if (savedInstanceState?.getBoolean(STARTED) == true) startSignIn()
        else {
            b.signInIntro.visibility = View.VISIBLE
            b.signInProgress.visibility = View.GONE
        }
    }

    private var started = false
    private var triedFirefox = false

    /** The phone's web view name, made to look like the phone's Chrome browser. */
    private fun chromeAgent(): String = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault("")
        .replace("; wv)", ")")
        .replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")
        .ifBlank { "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36" }

    /** Google's "Couldn't sign you in / This browser or app may not be secure" page. */
    private fun rejected(url: String): Boolean =
        url.contains("accounts.google.com") && (url.contains("/rejected") || url.contains("disallowed_useragent"))

    private fun startSignIn() {
        started = true
        b.signInIntro.visibility = View.GONE
        // always (also after the screen was rebuilt), or the page would stay blank
        b.signInWeb.loadUrl(SIGN_IN_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STARTED, started)
    }

    /** Signed in? Keep YouTube's cookies and close. */
    private fun check() {
        if (done) return
        val cookie = CookieManager.getInstance().getCookie("https://www.youtube.com")
        if (!YtAccount.looksSignedIn(cookie)) return
        done = true
        CookieManager.getInstance().flush()
        YtAccount.save(this, cookie!!)
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        b.signInWeb.stopLoading()
        b.signInWeb.destroy()
        super.onDestroy()
    }

    companion object {
        private const val STARTED = "started"
        private val FIREFOX_UA = "Mozilla/5.0 (Android ${android.os.Build.VERSION.RELEASE}; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0"
        private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
    }
}
