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

    private lateinit var b: ActivitySignInBinding
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        super.onCreate(savedInstanceState)
        b = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.signInClose.setOnClickListener { finish() }

        val ws = b.signInWeb.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        // Google refuses sign-in from pages that say they are an app's web view: look like the phone's browser
        ws.userAgentString = WebSettings.getDefaultUserAgent(this).replace("; wv)", ")")
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(b.signInWeb, true)

        b.signInWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme
                return scheme != "http" && scheme != "https"      // app links (intent://...) stay out
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                b.signInProgress.visibility = View.VISIBLE
                check()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                b.signInProgress.visibility = View.GONE
                check()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = check()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.signInWeb.canGoBack()) b.signInWeb.goBack() else finish()
            }
        })

        if (savedInstanceState == null) b.signInWeb.loadUrl(SIGN_IN_URL)
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
        private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
    }
}
