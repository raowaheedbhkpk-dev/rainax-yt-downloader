package com.rainax.ytdownloader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Home's whole page in one Jetpack Compose view (it replaced page_home.xml and its SwipeRefreshLayout): the top
 * bar and chips (HomeTop.kt), the lists with pull to refresh (FeedCompose.kt) and the overlay on top
 * (suggestions, spinner, error, picks bar).
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomePage(
    chrome: HomeChrome, a: HomeChromeActions, feed: FeedHost,
    /** Home's own floating tab bar (null = the app's normal bottom bar shows). */
    navBar: (@Composable () -> Unit)?,
    onPull: () -> Unit
) = RxModern {
    // Home is the first screen in the new look (ModernStyle.kt): iOS-like off-white page, white cards, a large
    // title, and a floating tab bar with a progressive blur behind it
    val rx = LocalRx.current
    val blur = rememberProgressiveBlur()
    val zone = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.toPx() }
    Box(Modifier.fillMaxSize().background(rx.bg)) {
        Column(
            Modifier.fillMaxSize().then(
                if (navBar != null) Modifier.drawWithContent { drawWithProgressiveBlur(blur, zone, rx.bg) { this@drawWithContent.drawContent() } }
                else Modifier
            )
        ) {
            // the top bar's height: autoplay in feed doesn't count cards hidden under it
            Box(Modifier.fillMaxWidth().onSizeChanged { feed.preview.padTop = it.height }) { HomeTopBar(chrome, a) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val pull = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = feed.refreshing,
                    onRefresh = onPull,
                    state = pull,
                    modifier = Modifier.fillMaxSize(),
                    indicator = { FilmCountdown(feed.refreshing) }
                ) {
                    val glow = if (LocalModern.current) null else feed.glow       // (no colour in the black-and-white look)
                    Box(
                        Modifier.fillMaxSize().then(
                            if (glow != null) Modifier.background(Brush.verticalGradient(listOf(Color(glow), Color.Transparent))) else Modifier
                        )
                    ) { FeedScreen(feed) }
                }
                // suggestions, spinner, error and the picks bar stay above the floating tab bar
                Box(Modifier.fillMaxSize().padding(bottom = if (navBar != null) 88.dp else 0.dp)) { HomeOverlay(chrome, a) }
            }
        }
        if (navBar != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { navBar() }
    }
}

/** Pull to refresh: 3 · 2 · 1 like an old film leader, round and round until the new list is in. */
@Composable
private fun BoxScope.FilmCountdown(running: Boolean) {
    if (!running) return
    var n by remember { mutableIntStateOf(3) }
    val pop = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fade.animateTo(1f, tween(150))
        while (true) {
            pop.snapTo(1.25f)
            pop.animateTo(1f, tween(260))
            kotlinx.coroutines.delay(260)
            n = if (n == 1) 3 else n - 1
        }
    }
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = 18.dp).size(56.dp).alpha(fade.value).rxDrawableBg(R.drawable.bg_countdown),
        contentAlignment = Alignment.Center
    ) {
        Text(n.toString(), Modifier.scale(pop.value), color = Color.White, fontSize = 24.sp, fontFamily = rxHeadFont())
    }
}
