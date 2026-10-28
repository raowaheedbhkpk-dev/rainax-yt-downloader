package com.rainax.ytdownloader

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top of the Home feed: your channels (signed in), "Continue watching" (videos you didn't finish) and a strip
 * of Shorts. Drawn with Jetpack Compose (it replaced layout_home_shelf.xml, item_continue.xml,
 * item_short_card.xml and item_channel_avatar.xml); [root] is the view Home's list shows on top.
 */
class HomeShelf(private val act: AppCompatActivity, private val openVideo: (VideoItem) -> Unit) {

    private var subs by mutableStateOf<List<VideoItem>>(emptyList())
    private var continueList by mutableStateOf<List<WatchHistory.Entry>>(emptyList())
    private var shorts by mutableStateOf<List<VideoItem>>(emptyList())
    private var shortsToStart by mutableIntStateOf(0)

    val root: View = ComposeView(act).apply {
        setContent { RxTheme { Shelf() } }
    }

    private var shownVersion = -1
    private var shortsLoading = false
    private var shortsTriedAt = 0L

    /** Tap on a channel picture, and on All (the Subscriptions tab). */
    var openChannel: (VideoItem) -> Unit = {}
    var openAllSubs: () -> Unit = {}
    private var subsLoading = false
    private var subsTriedAt = 0L

    /** Videos you didn't finish are showing (the magazine then puts this shelf near the top). */
    val hasContinue: Boolean get() = continueList.isNotEmpty()

    /** Called when Home shows: newest Continue watching list, and the Shorts strip (loaded once). */
    fun refresh() {
        refreshSubs()
        if (WatchHistory.version != shownVersion || shownVersion < 0) {
            shownVersion = WatchHistory.version
            continueList = WatchHistory.continueList(act)
        }
        if (ShortsFeed.items.isNotEmpty()) showShorts()
        // only a few Shorts so far: get more (the strip should always have plenty to swipe)
        if (ShortsFeed.items.size < 12) loadMoreShorts(force = false)
    }

    /** More Shorts at the end of the strip ([force]: the strip was swiped to its end, no 30 s wait). */
    private fun loadMoreShorts(force: Boolean) {
        if (shortsLoading) return
        val since = System.currentTimeMillis() - shortsTriedAt
        if (since < (if (force) 3_000 else 30_000)) return
        shortsLoading = true
        shortsTriedAt = System.currentTimeMillis()
        act.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { ShortsFeed.fetchMore() }.getOrDefault(emptyList()) }
            val known = ShortsFeed.items.mapTo(HashSet()) { youtubeId(it.url) ?: it.url }
            ShortsFeed.items.addAll(fresh.filter { known.add(youtubeId(it.url) ?: it.url) })
            shortsLoading = false
            showShorts()
        }
    }

    /** Pull to refresh on Home: new Shorts at the front of the strip (not only new videos below). */
    fun refreshShorts() {
        if (shortsLoading) return
        shortsLoading = true
        shortsTriedAt = System.currentTimeMillis()
        act.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { runCatching { ShortsFeed.fetchMore() }.getOrDefault(emptyList()) }
            shortsLoading = false
            if (fresh.isEmpty()) return@launch
            val ids = fresh.mapTo(HashSet()) { youtubeId(it.url) ?: it.url }
            ShortsFeed.items.removeAll { (youtubeId(it.url) ?: it.url) in ids }
            ShortsFeed.items.addAll(0, fresh)
            showShorts()
            shortsToStart++
        }
    }

    /** Signed in: the round pictures of your channels on top (loaded once, kept 30 minutes). */
    private fun refreshSubs() {
        if (!YtAccount.isSignedIn(act)) {
            subs = emptyList()
            return
        }
        subs = SubsChannels.cached()
        if (subsLoading || System.currentTimeMillis() - subsTriedAt < 60_000) return
        subsLoading = true
        subsTriedAt = System.currentTimeMillis()
        act.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { runCatching { SubsChannels.load() }.getOrDefault(emptyList()) }
            subsLoading = false
            if (!YtAccount.isSignedIn(act)) return@launch
            subs = list
        }
    }

    private fun showShorts() {
        shorts = ShortsFeed.items.toList()
    }

    // ---------- drawing ----------

    @Composable
    private fun Shelf() {
        val rx = LocalRx.current
        Column(Modifier.fillMaxWidth()) {
            val channels = subs
            if (channels.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) {
                        items(channels.size) { i -> ChannelAvatar(channels[i]) { openChannel(it) } }
                    }
                    Text("All", Modifier.height(48.dp).clickable { openAllSubs() }.padding(horizontal = 16.dp, vertical = 14.dp),
                        color = rx.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                }
            }
            val watching = continueList
            if (watching.isNotEmpty()) {
                Heading("Continue watching", null)
                LazyRow(Modifier.padding(bottom = 22.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(watching.size) { i -> ContinueCard(watching[i]) }
                }
            }
            val strip = shorts
            if (strip.isNotEmpty()) {
                Heading("Shorts", R.drawable.ic_shorts)
                val state = rememberLazyListState()
                LaunchedEffect(shortsToStart) { if (shortsToStart > 0) state.scrollToItem(0) }
                // swipe to the end of the strip: more Shorts load by themselves
                LaunchedEffect(state) {
                    snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                        if (last > 0 && last >= state.layoutInfo.totalItemsCount - 4) loadMoreShorts(force = true)
                    }
                }
                // about two and a bit Shorts across the screen, tall 9:16 like YouTube's
                val w = (LocalConfiguration.current.screenWidthDp / 2.25f).coerceAtMost(190f).dp
                LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(strip.size) { i -> ShortCard(strip[i], i, w) }
                }
                // clear gap and a thin line before the next video, like YouTube
                Box(Modifier.padding(top = 22.dp, bottom = 16.dp).fillMaxWidth().height(6.dp).background(rx.surfaceAlt))
            }
        }
    }

    @Composable
    private fun Heading(text: String, icon: Int?) {
        val rx = LocalRx.current
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Image(painterResource(icon), null, Modifier.padding(end = 8.dp).size(26.dp), colorFilter = ColorFilter.tint(rx.accent))
            }
            Text(text.uppercase(), color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.display, letterSpacing = 0.02.em)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun ContinueCard(e: WatchHistory.Entry) {
        val rx = LocalRx.current
        Column(
            Modifier.width(232.dp).padding(horizontal = 4.dp)
                .combinedClickable(
                    onClick = { openVideo(VideoItem(e.url, e.title, e.uploader, e.thumb, e.durMs / 1000, -1, null)) },
                    // press and hold: remove it from the row
                    onLongClick = { WatchHistory.remove(act, e.url); refresh() }
                )
                .semantics { contentDescription = e.title.ifBlank { "Video" } }
        ) {
            Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb)) {
                RxNetImage(e.thumb ?: youtubeId(e.url)?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }, Modifier.fillMaxSize(), widthPx = 440)
                Text(YtCatalog.duration((e.durMs - e.posMs) / 1000) + " left",
                    Modifier.align(Alignment.BottomEnd).padding(start = 6.dp, end = 6.dp, bottom = 9.dp).rxDrawableBg(R.drawable.bg_duration)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                val part = e.fraction.coerceIn(0f, 1f)
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    if (part > 0f) Box(Modifier.weight(part.coerceAtLeast(0.01f)).height(3.dp).background(rx.accent))
                    if (part < 1f) Box(Modifier.weight((1f - part).coerceAtLeast(0.01f)).height(3.dp))
                }
            }
            Text(e.title.ifBlank { "Video" }, Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp), color = rx.text, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(e.uploader, Modifier.padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 2.dp), color = rx.text2, fontSize = 12.sp,
                fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun ShortCard(item: VideoItem, index: Int, w: androidx.compose.ui.unit.Dp) {
        Box(
            Modifier.padding(horizontal = 4.dp).width(w).height(w * 16 / 9).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_short_card)
                .clickable { ShortsActivity.open(act, index) }.semantics { contentDescription = item.title }
        ) {
            RxNetImage(shortThumb(item), Modifier.fillMaxSize(), widthPx = 360)
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp).rxDrawableBg(R.drawable.bg_short_scrim))
            Text(item.title, Modifier.align(Alignment.BottomStart).padding(10.dp), color = Color.White, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }

    companion object {
        /** The Short's picture (cropped to fill the tall card). */
        fun shortThumb(item: VideoItem): String? =
            item.thumb ?: youtubeId(item.url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
    }
}
