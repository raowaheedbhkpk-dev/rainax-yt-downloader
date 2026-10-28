package com.rainax.ytdownloader

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/**
 * Subscriptions tab, like YouTube: the round pictures of your channels (tap one for its page, All for the
 * list) and the newest videos from them, each with its Download button. Needs the YouTube account.
 * Drawn with Jetpack Compose (it replaced page_subs.xml).
 */
class SubsScreen(
    private val act: AppCompatActivity,
    private val view: ComposeView,
    private val openVideo: (VideoItem) -> Unit,
    private val download: (VideoItem) -> Unit,
    private val openChannel: (VideoItem) -> Unit,
    private val openAllChannels: () -> Unit,
    private val signIn: () -> Unit
) {
    /** What the page shows. */
    private inner class UiState {
        var videos by mutableStateOf<List<VideoItem>>(emptyList())
        var channels by mutableStateOf<List<VideoItem>>(emptyList())
        var loading by mutableStateOf(false)
        var refreshing by mutableStateOf(false)
        var loadingMore by mutableStateOf(false)
        var emptyText by mutableStateOf<String?>(null)
        var emptyAction by mutableStateOf<String?>(null)
        /** Bumped to scroll back to the top (a fresh list). */
        var toTop by mutableIntStateOf(0)
    }

    private val ui = UiState()
    /** Autoplay in feed for this list (the video you stop on plays silently in its picture). */
    private val preview = ComposePreviewList(view)
    private var next: Page? = null
    private var job: Job? = null
    private var loadedFor: Boolean? = null           // signed-in state the list was loaded for

    fun setup() {
        view.setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent { RxTheme { SubsPage() } }
        FeedAutoplay.attach(preview)
    }

    /** The tab is on screen: load once (again after signing in or out). */
    fun onShown() {
        val signedIn = YtAccount.isSignedIn(act)
        if (loadedFor == signedIn && (ui.videos.isNotEmpty() || job?.isActive == true)) return
        load(reset = true)
    }

    /** Signed in or out: start again. */
    fun onAccountChanged() {
        loadedFor = null
        SubsChannels.clear()
        ui.videos = emptyList()
        ui.channels = emptyList()
    }

    private fun showEmpty(text: String?, action: String? = null) {
        ui.emptyText = text
        ui.emptyAction = action
    }

    private fun load(reset: Boolean, fresh: Boolean = false) {
        val signedIn = YtAccount.isSignedIn(act)
        loadedFor = signedIn
        if (!signedIn) {
            job?.cancel()
            ui.refreshing = false
            ui.loading = false
            ui.channels = emptyList()
            ui.videos = emptyList()
            showEmpty("Sign in to YouTube to see new videos\nfrom the channels you subscribe to.", "Sign in")
            return
        }
        if (reset) {
            job?.cancel()
            next = null
            showEmpty(null)
            if (ui.videos.isEmpty() && !ui.refreshing) ui.loading = true
        } else if (job?.isActive == true || next == null) {
            return
        }
        val page = next
        job = act.lifecycleScope.launch {
            try {
                if (reset) {
                    val ch = withContext(Dispatchers.IO) { runCatching { SubsChannels.load(fresh) }.getOrDefault(emptyList()) }
                    ui.channels = ch.distinctBy { it.url }
                }
                val res = withContext(Dispatchers.IO) { YtAccount.browse("FEsubscriptions", page) }
                val items = res.items.filter { !it.isChannel }.distinctBy { it.url }
                val added = if (reset) {
                    ui.videos = items
                    items.size
                } else {
                    val have = ui.videos.map { it.url }.toHashSet()
                    val fresh2 = items.filter { have.add(it.url) }
                    ui.videos = ui.videos + fresh2
                    fresh2.size
                }
                next = if (added == 0 && !reset) null else res.next
                ui.loadingMore = next != null
                if (reset) ui.toTop++
                if (reset && items.isEmpty()) showEmpty("No videos from your subscriptions yet.\nSubscribe to channels and their new videos show here.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.loadingMore = false
                if (reset || ui.videos.isEmpty()) {
                    val offline = !Net.online(act)
                    showEmpty(if (offline) NO_INTERNET else friendlyError(e.message ?: "Couldn't load"), "Try again")
                }
            } finally {
                // (a load replaced by a newer one leaves the spinner to that one)
                if (job === coroutineContext[Job]) {
                    ui.loading = false
                    ui.refreshing = false
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SubsPage() {
        val rx = LocalRx.current
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SUBSCRIPTIONS", Modifier.weight(1f), color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.display, letterSpacing = 0.02.em)
                IconButton(onClick = { load(reset = true, fresh = true) }) {
                    Image(painterResource(R.drawable.ic_refresh), "Refresh", Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
                }
            }
            if (ui.channels.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(ui.channels, key = { it.url }) { c -> ChannelAvatar(c) { openChannel(it) } }
                    }
                    Text(
                        "All", Modifier.height(48.dp).clickable { openAllChannels() }.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = rx.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list = rememberLazyListState()
                LaunchedEffect(ui.toTop) { if (ui.toTop > 0) list.scrollToItem(0) }
                // more videos when the end comes near
                val nearEnd by remember {
                    derivedStateOf {
                        val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= list.layoutInfo.totalItemsCount - 5
                    }
                }
                LaunchedEffect(nearEnd, ui.videos.size) { if (nearEnd && ui.videos.isNotEmpty()) load(reset = false) }
                // autoplay in feed: knows when the list moves and rests
                LaunchedEffect(list) {
                    androidx.compose.runtime.snapshotFlow { list.isScrollInProgress to list.firstVisibleItemScrollOffset }
                        .collect { (moving, _) -> preview.setScrolling(moving) }
                }

                PullToRefreshBox(
                    isRefreshing = ui.refreshing,
                    onRefresh = { ui.refreshing = true; load(reset = true, fresh = true) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                        items(ui.videos, key = { it.url }) { v -> VideoCard(v, openVideo, download, preview = preview) }
                        if (ui.loadingMore && ui.videos.isNotEmpty()) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(28.dp), color = rx.accent, strokeWidth = 3.dp)
                                }
                            }
                        }
                    }
                }
                if (ui.loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = rx.accent)
                }
                val empty = ui.emptyText
                if (empty != null) {
                    Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(R.drawable.ic_nav_subs), null, Modifier.size(64.dp), colorFilter = ColorFilter.tint(rx.text2))
                        Text(empty, Modifier.padding(top = 14.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center)
                        val action = ui.emptyAction
                        if (action != null) {
                            Button(
                                onClick = { if (YtAccount.isSignedIn(act)) load(reset = true, fresh = true) else signIn() },
                                modifier = Modifier.padding(top = 16.dp), shape = RoundedCornerShape(2.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                            ) { Text(action, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}
