package com.rainax.ytdownloader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.viewinterop.AndroidView

/*
 * The big Music player drawn with Jetpack Compose (it replaced most of page_music_player.xml): Song / Video
 * switch, title, Like / Comments / Download / Share / Lyrics, the seek bar, the play buttons and Up next.
 * The cover (spinning record) or the song's video stays a real view (layout_music_art.xml) inside it.
 * MusicPlayer keeps all the playing; this file only shows [MusicPageUi].
 */

class MusicPageUi {
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var videoMode by mutableStateOf(false)
    var likeText by mutableStateOf("Like")
    var liked by mutableStateOf(false)
    var disliked by mutableStateOf(false)
    var commentsText by mutableStateOf("Comments")
    var upNext by mutableStateOf("Up next")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    /** Player.REPEAT_MODE_OFF / ONE / ALL */
    var repeat by mutableIntStateOf(0)
    /** Played and loaded parts of the song, 0..1000. */
    var part by mutableIntStateOf(0)
    var buffered by mutableIntStateOf(0)
    var pos by mutableStateOf("0:00")
    var dur by mutableStateOf("0:00")
    /** The mini bar: the song's small cover and the bar's colour (the cover's, darker). */
    var miniArt by mutableStateOf<String?>(null)
    var miniTone by mutableIntStateOf(0xFF1E1E24.toInt())
}

class MusicMiniActions(val open: () -> Unit, val play: () -> Unit, val next: () -> Unit, val close: () -> Unit)

/** Mini music bar above the bottom bar (like YouTube Music): tap to open the player; ✕ or swipe sideways to close. */
@Composable
internal fun MusicMiniBar(ui: MusicPageUi, a: MusicMiniActions) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val shift = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    var width by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    val drag = androidx.compose.foundation.gestures.rememberDraggableState { d -> scope.launch { shift.snapTo(shift.value + d) } }
    Box(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp).height(64.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = shift.value
                alpha = (1f - kotlin.math.abs(shift.value) / width).coerceIn(0.2f, 1f)
            }
            .background(Color(ui.miniTone), RoundedCornerShape(12.dp))
            .draggable(drag, androidx.compose.foundation.gestures.Orientation.Horizontal, onDragStopped = {
                if (kotlin.math.abs(shift.value) > width * 0.35f) {
                    shift.animateTo(if (shift.value > 0) width.toFloat() else -width.toFloat(), androidx.compose.animation.core.tween(160))
                    a.close()
                    shift.snapTo(0f)
                } else shift.animateTo(0f, androidx.compose.animation.core.tween(160))
            })
            .clickable { a.open() }
            .semantics { contentDescription = "Music playing. Tap to open the player" }
    ) {
        Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).rxDrawableBg(R.drawable.bg_song_art)) {
                SquareArt(ui.miniArt, Modifier.fillMaxSize(), 200)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(ui.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(ui.artist, color = colorResource(R.color.rx_on_picture2), fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            IconBox(if (ui.playing) R.drawable.ic_p_pause else R.drawable.ic_p_play, "Play or pause", 48.dp, 12.dp) { a.play() }
            IconBox(R.drawable.ic_p_next, "Next", 48.dp, 12.dp) { a.next() }
            IconBox(R.drawable.ic_close, "Stop the music", 44.dp, 12.dp) { a.close() }
        }
        // how far the song is
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp).fillMaxWidth().height(2.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(ui.part / 1000f).height(2.dp).background(Color.White))
        }
    }
}

class MusicPageActions(
    val collapse: () -> Unit,
    val mode: (Boolean) -> Unit,
    val more: () -> Unit,
    val like: () -> Unit,
    val dislike: () -> Unit,
    val comments: () -> Unit,
    val download: () -> Unit,
    val share: () -> Unit,
    val lyrics: () -> Unit,
    /** The seek bar: touched / moved / let go, with where (0..1000). */
    val seekStart: (Int) -> Unit,
    val seekMove: (Int) -> Unit,
    val seekEnd: (Int) -> Unit,
    val shuffle: () -> Unit,
    val previous: () -> Unit,
    val play: () -> Unit,
    val next: () -> Unit,
    val repeat: () -> Unit,
    val upNext: () -> Unit
)

@Composable
internal fun MusicPageScreen(ui: MusicPageUi, art: android.view.View, a: MusicPageActions) {
    val white = Color.White
    val dim = colorResource(R.color.rx_on_picture2)
    Column(Modifier.fillMaxSize()) {
        // top: close, Song / Video, more
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBox(R.drawable.ic_chevron_down, "Close player", 48.dp, 12.dp) { a.collapse() }
            Spacer(Modifier.weight(1f))
            Row(Modifier.height(44.dp).rxDrawableBg(R.drawable.bg_mp_toggle).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
                ModeButton(R.drawable.ic_headphones, "Song", !ui.videoMode) { a.mode(false) }
                ModeButton(R.drawable.ic_video, "Video", ui.videoMode) { a.mode(true) }
            }
            Spacer(Modifier.weight(1f))
            IconBox(R.drawable.ic_more, "More options", 48.dp, 12.dp) { a.more() }
        }

        // the cover (or the video), as big as fits
        AndroidView(
            factory = { (art.parent as? android.view.ViewGroup)?.removeView(art); art },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        // title and artist
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp)) {
            MarqueeTitle(ui.title)
            Text(ui.artist, Modifier.padding(top = 2.dp), color = dim, fontSize = 16.sp, fontFamily = RxFonts.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        // Like | Dislike, Comments, Download, Share, Lyrics
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val accent = LocalRx.current.accent
            Row(Modifier.height(40.dp).rxDrawableBg(R.drawable.bg_mp_toggle), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.height(40.dp).clickable { a.like() }.semantics { contentDescription = "Like" }.padding(start = 14.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.ic_thumb_up), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (ui.liked) accent else white))
                    Text(ui.likeText, Modifier.padding(start = 8.dp), color = white, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                }
                Box(Modifier.size(1.dp, 22.dp).background(Color(0x40FFFFFF)))
                Box(Modifier.size(48.dp, 40.dp).clickable { a.dislike() }.semantics { contentDescription = "Dislike" }, contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_thumb_down), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(if (ui.disliked) accent else white))
                }
            }
            Pill(R.drawable.ic_comment, ui.commentsText) { a.comments() }
            Pill(R.drawable.ic_download, "Download") { a.download() }
            Pill(R.drawable.ic_share, "Share") { a.share() }
            Pill(R.drawable.ic_lyrics, "Lyrics") { a.lyrics() }
        }

        // seek bar and times
        SeekLine(ui, a)
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(ui.pos, Modifier.weight(1f), color = dim, fontSize = 12.sp, fontFamily = RxFonts.body)
            Text(ui.dur, color = dim, fontSize = 12.sp, fontFamily = RxFonts.body)
        }

        // shuffle, previous, play, next, repeat
        Row(Modifier.fillMaxWidth().height(92.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).alpha(if (ui.shuffle) 1f else 0.5f), contentAlignment = Alignment.Center) {
                IconBox(R.drawable.ic_shuffle, "Shuffle", 52.dp, 14.dp) { a.shuffle() }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { IconBox(R.drawable.ic_p_prev, "Previous", 60.dp, 14.dp) { a.previous() } }
            Box(
                Modifier.padding(horizontal = 6.dp).size(76.dp).rxDrawableBg(R.drawable.bg_mp_play).clickable { a.play() }
                    .semantics { contentDescription = "Play or pause" },
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(if (ui.playing) R.drawable.ic_p_pause else R.drawable.ic_p_play), null, Modifier.size(36.dp),
                    colorFilter = ColorFilter.tint(Color.Black))
                if (ui.buffering) CircularProgressIndicator(Modifier.fillMaxSize(), color = Color(0x55000000), strokeWidth = 3.dp)
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { IconBox(R.drawable.ic_p_next, "Next", 60.dp, 14.dp) { a.next() } }
            Box(Modifier.weight(1f).alpha(if (ui.repeat != 0) 1f else 0.5f), contentAlignment = Alignment.Center) {
                IconBox(if (ui.repeat == 1) R.drawable.ic_repeat_one else R.drawable.ic_repeat, "Repeat", 52.dp, 14.dp) { a.repeat() }
            }
        }

        // Up next
        Column(
            Modifier.fillMaxWidth().clickable { a.upNext() }.padding(top = 8.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(36.dp, 4.dp).rxDrawableBg(R.drawable.bg_mp_handle))
            Text(ui.upNext, Modifier.padding(start = 24.dp, end = 24.dp, top = 10.dp), color = white, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Long titles scroll slowly, like before. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeTitle(title: String) {
    Text(title, Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), color = Color.White, fontSize = 24.sp,
        fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em, maxLines = 1)
}

@Composable
private fun IconBox(icon: Int, label: String, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clickable(onClick = onClick).semantics { contentDescription = label }.padding(pad)) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
private fun ModeButton(icon: Int, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(60.dp, 38.dp).then(if (on) Modifier.rxDrawableBg(R.drawable.bg_mp_toggle_on) else Modifier)
            .alpha(if (on) 1f else 0.7f).clickable(onClick = onClick).semantics { contentDescription = label }.padding(8.dp)
    ) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
    }
}

@Composable
private fun Pill(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).rxDrawableBg(R.drawable.bg_mp_pill).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
        Text(label, Modifier.padding(start = 8.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
    }
}

/** The seek bar: the played part in white, the loaded part lighter; drag or tap to jump. */
@Composable
private fun SeekLine(ui: MusicPageUi, a: MusicPageActions) {
    Box(
        Modifier.fillMaxWidth().padding(top = 18.dp).height(40.dp).padding(horizontal = 24.dp)
            .semantics { contentDescription = "Seek. ${ui.pos} of ${ui.dur}" }
            .pointerInput(Unit) {
                fun where(x: Float) = if (size.width > 0) (x / size.width * 1000).toInt().coerceIn(0, 1000) else 0
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var at = where(down.position.x)
                    a.seekStart(at)
                    drag(down.id) { ch ->
                        at = where(ch.position.x)
                        a.seekMove(at)
                        ch.consume()
                    }
                    a.seekEnd(at)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            val h = 3.dp.toPx()
            val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Color(0x4DFFFFFF), Offset(0f, y - h / 2), Size(size.width, h), r)
            drawRoundRect(Color(0x66FFFFFF), Offset(0f, y - h / 2), Size(size.width * ui.buffered / 1000f, h), r)
            val x = size.width * ui.part / 1000f
            drawRoundRect(Color.White, Offset(0f, y - h / 2), Size(x, h), r)
            drawCircle(Color.White, 7.dp.toPx(), Offset(x, y))
        }
    }
}
