package com.rainax.ytdownloader

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * The welcome guide after the first install: a few pages on what RAINAX Tube does, swiped or with Next,
 * then "Get started" (or Skip). Shown once; the first-run questions (storage, updates) come right after.
 *
 * The first RAINAX screen built with Jetpack Compose (see [RxTheme]); it looks the same as the old one.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }

    private class Page(val icon: Int, val tint: Boolean, val title: String, val text: String)

    private val pages = listOf(
        Page(R.drawable.ic_logo, false, "Welcome to RAINAX Tube",
            "Watch YouTube and save any video to your phone, in every quality up to 8K."),
        Page(R.drawable.ic_download, true, "One tap to download",
            "Tap Download under any video, choose Video or Audio and a quality. Sizes are ready at once."),
        Page(R.drawable.ic_link, true, "TikTok, Instagram and Facebook",
            "Share a link to RAINAX Tube or paste it on the Social page. Links you copy are found by themselves."),
        Page(R.drawable.ic_headphones, true, "Music and background play",
            "Save songs as MP3 and keep listening with the screen off. Search with your voice from the search box."),
        Page(R.drawable.ic_lock, true, "Private folder and WhatsApp",
            "Hide videos behind a PIN, pattern or password, and save WhatsApp statuses in one tap.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        Ui.applyAccent(this)
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        setContent { RxTheme { Guide() } }
    }

    private fun finishGuide() {
        AppPrefs.setGuideSeen(this)
        setResult(RESULT_OK)
        finish()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Guide() {
        val rx = LocalRx.current
        val pager = rememberPagerState { pages.size }
        val scope = rememberCoroutineScope()
        val pos = pager.currentPage
        val last = pos == pages.lastIndex

        BackHandler {
            if (pos > 0) scope.launch { pager.animateScrollToPage(pos - 1) } else finishGuide()
        }

        Column(Modifier.fillMaxSize().background(rx.bg).systemBarsPadding()) {
            // top bar: logo, name, Skip
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(rxDrawable(R.drawable.ic_logo, 28), null, Modifier.padding(start = 8.dp).size(28.dp))
                Text(
                    "RAINAX Tube", Modifier.weight(1f).padding(start = 8.dp),
                    color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.display
                )
                TextButton(onClick = { finishGuide() }, modifier = Modifier.alpha(if (last) 0f else 1f), enabled = !last) {
                    Text("Skip", color = rx.accentText, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { i ->
                // swiping: the page going away shrinks and fades a little, the picture moves slower than the text
                val off = ((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                val a = kotlin.math.abs(off)
                PageView(
                    pages[i],
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = 1f - a * 0.6f
                        scaleX = 1f - a * 0.08f
                        scaleY = 1f - a * 0.08f
                    },
                    iconShift = off
                )
            }

            // dots: the current one is a longer accent bar
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                pages.indices.forEach { i ->
                    val on = i == pos
                    val w = animateDpAsState(if (on) 22.dp else 8.dp, label = "dot").value
                    val al = animateFloatAsState(if (on) 1f else 0.35f, label = "dotAlpha").value
                    Box(
                        Modifier.padding(horizontal = 4.dp).width(w).height(8.dp).alpha(al)
                            .background(if (on) rx.accent else rx.text2, RoundedCornerShape(12.dp))
                    )
                }
            }

            Button(
                onClick = { if (!last) scope.launch { pager.animateScrollToPage(pos + 1) } else finishGuide() },
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
            ) {
                Text(if (last) "Get started" else "Next", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
            }
        }
    }

    @Composable
    private fun PageView(p: Page, modifier: Modifier, iconShift: Float) {
        val rx = LocalRx.current
        Column(
            modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier.size(168.dp)
                    .graphicsLayer { translationX = iconShift * size.width * 0.6f }
                    .background(rx.accentContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (p.tint) Image(painterResource(p.icon), null, Modifier.size(80.dp), colorFilter = ColorFilter.tint(rx.accent))
                else Image(rxDrawable(p.icon, 80), null, Modifier.size(80.dp))      // the logo is a layer-list
            }
            Spacer(Modifier.height(40.dp))
            Text(
                p.title, Modifier.fillMaxWidth(), color = rx.text, fontSize = 24.sp,
                fontFamily = RxFonts.display, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                p.text, Modifier.fillMaxWidth(), color = rx.text2, fontSize = 14.sp, lineHeight = 21.sp,
                fontFamily = RxFonts.body, textAlign = TextAlign.Center
            )
        }
    }
}
