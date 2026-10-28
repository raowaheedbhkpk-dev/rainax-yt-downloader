package com.rainax.ytdownloader

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * The Music tab, like YouTube Music: mood chips, Listen again, Quick picks and song rows (columns of four
 * songs you swipe through, each row with Play all), and playlists at the end. Drawn with Jetpack Compose
 * ([MusicHomeScreen]); HomeScreen fills it with [submit].
 */
class MusicHome(
    /** Plays songs in the Music player: the list, where to start, its name, and "radio" (similar songs after it). */
    internal val play: (songs: List<VideoItem>, index: Int, from: String, radio: Boolean) -> Unit,
    internal val songMenu: (VideoItem) -> Unit,
    internal val openPlaylist: (VideoItem) -> Unit,
    internal val playPlaylist: (VideoItem) -> Unit,
    internal val downloadPlaylist: (VideoItem) -> Unit,
    /** A mood chip was picked (null = back to the Music home): load its rows. */
    private val onMood: (String?) -> Unit
) {
    /** The mood chip that is on (null = Music home). */
    var mood by mutableStateOf<String?>(null)
        private set

    internal var sections by mutableStateOf<List<MusicSection>>(emptyList())
        private set
    internal var loading by mutableStateOf(false)
        private set
    /** The song playing now (its cover shows the equalizer). */
    internal var nowId by mutableStateOf<String?>(null)
        private set
    /** Bumped to scroll back to the top (a mood was picked). */
    internal var toTop by mutableIntStateOf(0)

    fun submit(list: List<MusicSection>, stillLoading: Boolean) {
        sections = list
        loading = stillLoading
    }

    /** Marks the song playing now in every row. */
    fun setNowPlaying(url: String?) {
        nowId = youtubeId(url)
    }

    internal fun pickMood(name: String) {
        val picked = if (name == mood) null else name
        mood = picked
        toTop++
        onMood(picked)
    }

    companion object {
        const val QUICK_PICKS = "Quick picks"
        const val LISTEN_AGAIN = "Listen again"

        /** "Artist • 3:24" (or "• 107M plays" when YouTube gives the plays). */
        fun songLine(song: VideoItem): String {
            val parts = mutableListOf<String>()
            if (song.uploader.isNotBlank()) parts += song.uploader
            when {
                song.views > 0 -> parts += YtCatalog.count(song.views) + " plays"
                song.seconds > 0 -> parts += YtCatalog.duration(song.seconds)
            }
            return parts.joinToString(" • ")
        }
    }
}

/** A song cover: square, without the black bars of YouTube pictures (RAINAX's loader). */
@Composable
internal fun SquareArt(url: String?, modifier: Modifier, widthPx: Int, fallbacks: List<String> = emptyList()) {
    AndroidView(
        factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { v -> Img.loadSquare(v, url, widthPx = widthPx, fallbacks = fallbacks) },
        modifier = modifier
    )
}

@Composable
internal fun MusicHomeScreen(m: MusicHome, state: LazyListState, bottomPad: Int) {
    LaunchedEffect(m.toTop) { if (m.toTop > 0) state.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottomPad.dp)) {
        item(key = "moods") { Moods(m) }
        items(m.sections.size, key = { i -> "s$i:" + m.sections[i].title }) { i -> Shelf(m, m.sections[i]) }
        if (m.loading) {
            items(2, key = { "sk$it" }) {
                AndroidView(
                    factory = { c ->
                        val inf = android.view.LayoutInflater.from(c)
                        val v = inf.inflate(R.layout.item_music_skeleton, android.widget.FrameLayout(c), false)
                        val rows = v.findViewById<android.widget.LinearLayout>(R.id.skRows)
                        repeat(4) { rows.addView(inf.inflate(R.layout.item_music_skeleton_row, rows, false)) }
                        v
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun Moods(m: MusicHome) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(YtCatalog.MUSIC_MOODS.size) { i ->
            val name = YtCatalog.MUSIC_MOODS[i].first
            RxChip(name, selected = name == m.mood) { m.pickMood(name) }
        }
    }
}

/** Small grey words above some rows, like YouTube Music. */
private fun labelFor(s: MusicSection): String = when (s.title) {
    MusicHome.QUICK_PICKS -> "Start radio from a song"
    MusicHome.LISTEN_AGAIN -> "Your recent songs"
    else -> ""
}

@Composable
private fun Shelf(m: MusicHome, s: MusicSection) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val label = labelFor(s)
                if (label.isNotEmpty()) Text(label.uppercase(), Modifier.padding(bottom = 2.dp), color = rx.text2, fontSize = 12.sp, letterSpacing = 0.04.em, fontFamily = RxFonts.body)
                Text(s.title.uppercase(), color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.display, letterSpacing = 0.02.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.kind == MusicSection.SONGS && s.items.size > 1) {
                OutlinedButton(
                    onClick = { m.play(s.items, 0, s.title, false) }, modifier = Modifier.padding(start = 8.dp).height(36.dp),
                    shape = RoundedCornerShape(2.dp), contentPadding = PaddingValues(horizontal = 14.dp)
                ) { Text("Play all", color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.Bold) }
            }
        }
        val row = rememberLazyListState()
        LaunchedEffect(s) { row.scrollToItem(0) }
        when (s.kind) {
            MusicSection.SONGS -> {
                // songs in columns of four: each column a bit narrower than the screen, so the next one peeks in
                val w = (LocalConfiguration.current.screenWidthDp * 0.86f).coerceAtMost(420f).dp
                val columns = s.items.chunked(4)
                LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), state = row, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    items(columns.size) { c ->
                        Column(Modifier.width(w)) { columns[c].forEach { song -> SongRow(m, song) } }
                    }
                }
            }
            MusicSection.COVERS -> LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), state = row, contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(s.items.size) { i -> CoverCard(m, s.items[i]) }
            }
            else -> LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp), state = row, contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(s.items.size) { i -> PlaylistCard(m, s.items[i]) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(m: MusicHome, song: VideoItem) {
    val rx = LocalRx.current
    val playing = m.nowId != null && youtubeId(song.url) == m.nowId
    Row(
        Modifier.fillMaxWidth().height(64.dp)
            .combinedClickable(onClick = { m.play(listOf(song), 0, song.title, true) }, onLongClick = { m.songMenu(song) })
            .semantics { contentDescription = "${song.title}, ${song.uploader}" }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_song_art)) {
            SquareArt(MusicArt.small(song.url, song.thumb), Modifier.fillMaxSize(), 240)
            if (playing) {
                Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_song_now).padding(14.dp)) {
                    Image(painterResource(R.drawable.ic_equalizer), null, Modifier.fillMaxSize())
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(song.title, color = rx.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(MusicHome.songLine(song), Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(44.dp).clickable { m.songMenu(song) }.semantics { contentDescription = "More" }.padding(10.dp)) {
            Image(painterResource(R.drawable.ic_more), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text2))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverCard(m: MusicHome, song: VideoItem) {
    val rx = LocalRx.current
    Column(
        Modifier.padding(horizontal = 6.dp).width(140.dp)
            .combinedClickable(onClick = { m.play(listOf(song), 0, song.title, true) }, onLongClick = { m.songMenu(song) })
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb)) {
            SquareArt(MusicArt.small(song.url, song.thumb), Modifier.fillMaxSize(), 400,
                listOfNotNull(youtubeId(song.url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }))
        }
        Text(song.title, Modifier.padding(top = 8.dp), color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.uploader, color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistCard(m: MusicHome, item: VideoItem) {
    val rx = LocalRx.current
    Column(Modifier.padding(horizontal = 6.dp).width(156.dp).clickable { m.openPlaylist(item) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(2.dp)).rxDrawableBg(R.drawable.bg_thumb)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 400)
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(40.dp).rxDrawableBg(R.drawable.bg_mini_button)
                    .clickable { m.playPlaylist(item) }.semantics { contentDescription = "Play" }.padding(10.dp)
            ) { Image(painterResource(R.drawable.ic_p_play), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White)) }
        }
        Text(item.title, Modifier.padding(top = 8.dp), color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val sub = when {
                item.uploader.isNotBlank() -> item.uploader
                item.count > 0 -> "${item.count} songs"
                else -> "Playlist"
            }
            Text(sub, Modifier.weight(1f), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.size(36.dp).clickable { m.downloadPlaylist(item) }.semantics { contentDescription = "Download" }.padding(6.dp)) {
                Image(painterResource(R.drawable.ic_download), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
            }
        }
    }
}
