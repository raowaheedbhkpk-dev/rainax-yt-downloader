package com.rainax.ytdownloader

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/*
 * The Shorts screen drawn with Jetpack Compose (it replaced activity_shorts.xml and item_short_page.xml): one
 * full-screen Short at a time in a vertical pager. The single video surface (a PlayerView) sits on the Short on
 * screen. ShortsActivity keeps the playing, liking and loading.
 */

class ShortsUi {
    val items = mutableStateListOf<VideoItem>()
    /** The Short playing (index in [items]); -1 before the first one. */
    var current by mutableIntStateOf(-1)
    var start = 0
    var paused by mutableStateOf(false)
    /** By link: its video shows (picture and spinner go) / it couldn't play. */
    val started = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, Boolean>()
    /** By link: title and channel from the full details (better than the list's). */
    val names = mutableStateMapOf<String, Pair<String, String>>()
    /** By video id: like / dislike counts and your own rating ("LIKE", "DISLIKE", "INDIFFERENT"). */
    val likes = mutableStateMapOf<String, Long>()
    val dislikes = mutableStateMapOf<String, Long>()
    val rating = mutableStateMapOf<String, String>()
}

class ShortsActions(
    val back: () -> Unit,
    val select: (Int) -> Unit,
    val tap: (Int) -> Unit,
    val like: (VideoItem) -> Unit,
    val dislike: (VideoItem) -> Unit,
    val comments: (VideoItem) -> Unit,
    val download: (VideoItem) -> Unit,
    val share: (VideoItem) -> Unit,
    /** A tab on the floating tab bar (Home, Music, You or +). */
    val nav: (Int) -> Unit = {}
)

@Composable
internal fun ShortsScreen(ui: ShortsUi, playerView: android.view.View, a: ShortsActions) {
    val pager = rememberPagerState(initialPage = ui.start) { ui.items.size }
    LaunchedEffect(pager) {
        // a Short came on screen (also when the first ones arrive)
        snapshotFlow { pager.currentPage to ui.items.size }.collect { (page, _) -> a.select(page) }
    }
    Box(Modifier.fillMaxSize()) {
        VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { i -> ui.items.getOrNull(i)?.url ?: i }) { i ->
            val item = ui.items.getOrNull(i)
            if (item != null) ShortPage(ui, item, i, playerView, a)
        }
        // top: back and the name
        Row(
            Modifier.fillMaxWidth().rxDrawableBg(R.drawable.bg_short_top).statusBarsPadding()
                .padding(start = 4.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp).clickable { a.back() }.semantics { contentDescription = "Back" }.padding(12.dp)) {
                Image(painterResource(R.drawable.ic_back), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
            }
            Text("Shorts", Modifier.padding(start = 4.dp), color = Color.White, fontSize = 20.sp, fontFamily = RxFonts.display,
                letterSpacing = 0.02.em)
        }
        // the same floating tab bar as the rest of the app (Shorts chosen): tap another place to go there
        val nav = remember { NavUi().apply { selected = R.id.nav_shorts } }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            FloatingTabBar(nav) { id -> if (id != R.id.nav_shorts) a.nav(id) }
        }
    }
}

@Composable
private fun ShortPage(ui: ShortsUi, item: VideoItem, index: Int, playerView: android.view.View, a: ShortsActions) {
    val started = ui.started[item.url] == true
    val error = ui.errors[item.url] == true
    val playingHere = index == ui.current
    val shadow = TextStyle(shadow = Shadow(colorResource(R.color.rx_scrim), blurRadius = 6f))
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { a.tap(index) }
    ) {
        // tall picture, same size as the video will be (no small picture that then jumps bigger)
        if (!started || !playingHere) {
            AndroidView(
                factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                update = { v ->
                    if (v.tag != item.url) {
                        v.tag = item.url
                        val id = youtubeId(item.url)
                        Img.loadPortrait(v, listOfNotNull(
                            id?.let { "https://i.ytimg.com/vi/$it/oar2.jpg" },
                            id?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" },
                            id?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
                            item.thumb
                        ))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (playingHere) {
            AndroidView(factory = { (playerView.parent as? android.view.ViewGroup)?.removeView(playerView); playerView }, modifier = Modifier.fillMaxSize())
        }
        if (playingHere && !started && !error) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White)
        }
        if (playingHere && ui.paused) {
            Box(Modifier.align(Alignment.Center).size(76.dp).rxDrawableBg(R.drawable.bg_mini_button).padding(20.dp)) {
                Image(painterResource(R.drawable.ic_play), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
            }
        }
        if (error) {
            Text("Couldn't play this Short. Swipe for the next one.", Modifier.align(Alignment.Center).padding(24.dp), color = Color.White,
                textAlign = TextAlign.Center, fontFamily = RxFonts.body)
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp).rxDrawableBg(R.drawable.bg_short_scrim))

        // title and channel, and the buttons on the right
        val names = ui.names[item.url]
        val id = youtubeId(item.url)
        val likes = id?.let { ui.likes[it] } ?: -1L
        val dislikes = id?.let { ui.dislikes[it] } ?: -1L
        val mine = id?.let { ui.rating[it] }
        val accent = LocalRx.current.accent
        Row(
            // above the floating tab bar
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 10.dp, bottom = 100.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(names?.second ?: item.uploader, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis, style = shadow)
                Text(names?.first ?: item.title, Modifier.padding(top = 6.dp), color = Color.White, fontSize = 14.sp,
                    fontFamily = RxFonts.body, maxLines = 3, overflow = TextOverflow.Ellipsis, style = shadow)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SideButton(R.drawable.ic_thumb_up, "Like", if (likes > 0) YtCatalog.count(likes) else "Like", if (mine == "LIKE") accent else Color.White, shadow) { a.like(item) }
                SideButton(R.drawable.ic_thumb_down, "Dislike", if (dislikes > 0) YtCatalog.count(dislikes) else "Dislike", if (mine == "DISLIKE") accent else Color.White, shadow) { a.dislike(item) }
                SideButton(R.drawable.ic_comment, "Comments", "Comments", Color.White, shadow) { a.comments(item) }
                SideButton(R.drawable.ic_download, "Download", "Download", Color.White, shadow) { a.download(item) }
                SideButton(R.drawable.ic_share, "Share", "Share", Color.White, shadow) { a.share(item) }
            }
        }
    }
}

@Composable
private fun SideButton(icon: Int, label: String, text: String, tint: Color, style: TextStyle, onClick: () -> Unit) {
    Box(
        Modifier.padding(top = 14.dp).size(48.dp).rxDrawableBg(R.drawable.bg_mini_button).clickable(onClick = onClick)
            .semantics { contentDescription = label }.padding(12.dp)
    ) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(tint))
    }
    Text(text, Modifier.padding(top = 3.dp), color = Color.White, fontSize = 12.sp, fontFamily = RxFonts.body, style = style)
}
