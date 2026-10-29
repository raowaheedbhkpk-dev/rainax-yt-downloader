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
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.layout.height
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
) {
    // iOS-like off-white page, white cards, a large title, and a floating tab bar with a progressive blur behind it
    val rx = LocalRx.current
    val source = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val zone = with(androidx.compose.ui.platform.LocalDensity.current) { 100.dp.toPx() }
    var pageHeight by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(rx.bg)) {
        Column(
            Modifier.fillMaxSize().onSizeChanged { pageHeight = it.height.toFloat() }.then(
                // the page is drawn into a layer too: the progressive blur draws it again, blurred
                if (navBar != null) Modifier.drawWithContent {
                    source.record { this@drawWithContent.drawContent() }
                    drawLayer(source)
                } else Modifier
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
                    // the normal round pull indicator (v19.8: the film countdown went with the Cinema look)
                    indicator = {
                        androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                            state = pull, isRefreshing = feed.refreshing, modifier = Modifier.align(Alignment.TopCenter),
                            containerColor = rx.surface, color = rx.text
                        )
                    }
                ) {
                    FeedScreen(feed)
                }
                // suggestions, spinner, error and the picks bar stay above the floating tab bar
                Box(Modifier.fillMaxSize().padding(bottom = if (navBar != null) 88.dp else 0.dp)) { HomeOverlay(chrome, a) }
            }
        }
        if (navBar != null) {
            ProgressiveBlurZone(
                source,
                zoneTop = { pageHeight - zone },
                // redraw the blur whenever the list moves or changes
                tick = { feed.list?.let { it.firstVisibleItemIndex * 100_000 + it.firstVisibleItemScrollOffset }; feed.model?.version },
                tint = rx.bg,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(100.dp)
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { navBar() }
        }
    }
}

