package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * YouTube sign-in on Google's own page. When YouTube's sign-in cookies appear, they are kept on this phone
 * (for the user's recommendations, subscriptions, history and liked videos) and the screen closes.
 */
class SignInActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }


    /** Google's page; the rest of the screen is Jetpack Compose (it replaced activity_sign_in.xml). */
    private lateinit var web: WebView
    private var intro by mutableStateOf(true)
    private var loading by mutableStateOf(false)
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        Ui.applyAccent(this)
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        web = WebView(this)
        setContent { RxTheme { Screen() } }

        val ws = web.settings
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
        cm.setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                // only secure pages (Google's sign-in is all https); app links (intent://...) and plain http stay out
                return request?.url?.scheme != "https"
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loading = true
                check()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                loading = false
                check()
                // Google still said "Couldn't sign you in": once more, looking like Firefox (Google allows it)
                if (url != null && rejected(url) && !triedFirefox) {
                    triedFirefox = true
                    web.settings.userAgentString = FIREFOX_UA
                    web.clearHistory()
                    web.loadUrl(SIGN_IN_URL)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) = check()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    intro -> finish()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        // first the page that says what signing in gives; Sign in opens Google's page
        if (savedInstanceState?.getBoolean(STARTED) == true) startSignIn()
        else {
            intro = true
            loading = false
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
        intro = false
        // always (also after the screen was rebuilt), or the page would stay blank
        web.loadUrl(SIGN_IN_URL)
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
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }

    // ---------- drawing ----------

    @Composable
    private fun Screen() {
        val rx = LocalRx.current
        Column(Modifier.fillMaxSize().background(rx.bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clickable { finish() }.semantics { contentDescription = "Close" }.padding(12.dp)) {
                    Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
                }
                Text("Sign in with YouTube", Modifier.weight(1f).padding(start = 8.dp), color = rx.text, fontSize = 20.sp,
                    fontFamily = RxFonts.display, letterSpacing = 0.02.em)
                Image(painterResource(R.drawable.ic_lock), null, Modifier.padding(end = 12.dp).size(26.dp), colorFilter = ColorFilter.tint(rx.text2))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = rx.accent, trackColor = rx.surfaceAlt)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web }, modifier = Modifier.fillMaxSize())
                if (intro) Intro()
            }
        }
    }

    /** First: what signing in gives you, then Sign in opens Google's page. */
    @Composable
    private fun Intro() {
        val rx = LocalRx.current
        Column(
            Modifier.fillMaxSize().background(rx.bg).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).rxDrawableBg(R.drawable.ic_logo))          // (a layer-list: drawn as a picture)
                Image(painterResource(R.drawable.ic_swap_h), null, Modifier.padding(horizontal = 12.dp).size(40.dp), colorFilter = ColorFilter.tint(rx.text2))
                Box(Modifier.size(72.dp).rxDrawableBg(R.drawable.bg_play_badge).padding(20.dp)) {
                    Image(painterResource(R.drawable.ic_p_play), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(LocalRx.current.onAccent))
                }
            }
            Text("Sign in with YouTube", Modifier.padding(top = 20.dp), color = rx.text, fontSize = 32.sp, fontFamily = RxFonts.display,
                textAlign = TextAlign.Center)
            Text("to unlock these features", Modifier.padding(top = 4.dp), color = rx.text2, fontSize = 16.sp, fontFamily = RxFonts.body)
            Column(Modifier.padding(top = 20.dp).fillMaxWidth()) {
                Feature(R.drawable.ic_nav_subs, "Subscriptions", rx.accent)
                Feature(R.drawable.ic_watch_later, "Watch later", rx.accent)
                Feature(R.drawable.ic_history, "History", rx.accent)
                Feature(R.drawable.ic_thumb_up, "Like and dislike videos", rx.accent)
                Feature(R.drawable.ic_person, "Recommendations made for you", rx.accent)
            }
            Button(
                onClick = { startSignIn() }, modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
            ) { Text("Sign in", fontSize = 16.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold) }
            Text(
                "You sign in on Google's own page. RAINAX Tube never sees your password, and you can sign out any time.",
                Modifier.padding(top = 16.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun Feature(icon: Int, text: String, tint: Color) {
        val rx = LocalRx.current
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(icon), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(tint))
            Text(text, Modifier.padding(start = 16.dp), color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body)
        }
    }

    companion object {
        private const val STARTED = "started"
        private val FIREFOX_UA = "Mozilla/5.0 (Android ${android.os.Build.VERSION.RELEASE}; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0"
        private const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
    }
}
