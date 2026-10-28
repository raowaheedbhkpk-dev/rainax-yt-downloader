package com.rainax.ytdownloader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The full-screen player's buttons drawn with Jetpack Compose (they replaced the overlay of activity_player.xml):
 * top bar, play / 10 s / previous / next, seek bar, lock, aspect, rotate, picture-in-picture, the swipe info and
 * the audio picture. The video itself stays a PlayerView under it; touches the buttons don't take go to the
 * video's swipe and tap gestures (PlayerActivity).
 */

class PlayerUi {
    var title by mutableStateOf("")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var audioOnly by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var unlock by mutableStateOf(false)
    /** Swipe / button feedback in the middle (null = hidden). */
    var info by mutableStateOf<String?>(null)
    var speed by mutableStateOf("1x")
    var subsDim by mutableStateOf(false)
    var aspectShown by mutableStateOf(true)
    var pipShown by mutableStateOf(true)
    var navShown by mutableStateOf(false)
    var hasPrev by mutableStateOf(false)
    var hasNext by mutableStateOf(false)
    var pos by mutableStateOf("0:00")
    var dur by mutableStateOf("0:00")
    /** Played and loaded parts, 0..1000. */
    var part by mutableIntStateOf(0)
    var buffered by mutableIntStateOf(0)
}

class PlayerActions(
    val back: () -> Unit,
    val speed: () -> Unit,
    val audio: () -> Unit,
    val subs: () -> Unit,
    val more: () -> Unit,
    val prev: () -> Unit,
    val rew: () -> Unit,
    val play: () -> Unit,
    val fwd: () -> Unit,
    val next: () -> Unit,
    val seekStart: (Int) -> Unit,
    val seekMove: (Int) -> Unit,
    val seekEnd: (Int) -> Unit,
    val lock: () -> Unit,
    val unlock: () -> Unit,
    val aspect: () -> Unit,
    val rotate: () -> Unit,
    val pip: () -> Unit
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerOverlay(ui: PlayerUi, a: PlayerActions) {
    val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    Box(Modifier.fillMaxSize()) {
        // audio files: big note + title instead of a black screen
        if (ui.audioOnly) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(120.dp).rxDrawableBg(R.drawable.bg_player_play).padding(30.dp)) {
                    Image(painterResource(R.drawable.ic_p_audio), null, Modifier.fillMaxSize())
                }
                Text(ui.title, Modifier.padding(top = 18.dp).width(280.dp), color = Color.White, fontSize = 16.sp,
                    fontFamily = RxFonts.body, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ui.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Color.White)

        AnimatedVisibility(ui.controls, Modifier.fillMaxSize(), enter = fadeIn(tween(150)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize()) {
                // top: back, title, speed, audio, subtitles, more
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().rxDrawableBg(R.drawable.bg_player_scrim_top)
                        .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Btn(R.drawable.ic_p_back, "Back", 44.dp, 10.dp) { a.back() }
                    Text(ui.title, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(
                        Modifier.height(44.dp).widthIn(min = 44.dp).rxDrawableBg(R.drawable.bg_player_btn).clickable { a.speed() }
                            .semantics { contentDescription = "Playback speed ${ui.speed}" }.padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(ui.speed, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body) }
                    Btn(R.drawable.ic_p_audio, "Audio track", 44.dp, 10.dp) { a.audio() }
                    Box(Modifier.alpha(if (ui.subsDim) 0.6f else 1f)) { Btn(R.drawable.ic_p_cc, "Subtitles", 44.dp, 10.dp) { a.subs() } }
                    Btn(R.drawable.ic_p_more, "More", 44.dp, 10.dp) { a.more() }
                }

                // middle: previous, back 10 s, play, forward 10 s, next
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.navShown) Box(Modifier.alpha(if (ui.hasPrev) 1f else 0.4f)) { Btn(R.drawable.ic_p_prev, "Previous", 52.dp, 12.dp) { a.prev() } }
                    Box(Modifier.padding(horizontal = 10.dp)) { Btn(R.drawable.ic_p_rew, "Back 10 seconds", 56.dp, 13.dp) { a.rew() } }
                    Box(
                        Modifier.size(76.dp).rxDrawableBg(R.drawable.bg_player_play).clickable { a.play() }
                            .semantics { contentDescription = "Play or pause" }.padding(18.dp)
                    ) { Image(painterResource(if (ui.playing) R.drawable.ic_p_pause else R.drawable.ic_p_play), null, Modifier.fillMaxSize()) }
                    Box(Modifier.padding(horizontal = 10.dp)) { Btn(R.drawable.ic_p_fwd, "Forward 10 seconds", 56.dp, 13.dp) { a.fwd() } }
                    if (ui.navShown) Box(Modifier.alpha(if (ui.hasNext) 1f else 0.4f)) { Btn(R.drawable.ic_p_next, "Next", 52.dp, 12.dp) { a.next() } }
                }

                // bottom: times and seek bar; lock, aspect, rotate, picture-in-picture
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().rxDrawableBg(R.drawable.bg_player_scrim_bottom)
                        .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ui.pos, Modifier.widthIn(min = 48.dp), color = Color.White, fontSize = 14.sp, fontFamily = RxFonts.body)
                        SeekLine(ui, a, Modifier.weight(1f))
                        Text(ui.dur, Modifier.widthIn(min = 48.dp), color = Color.White, fontSize = 14.sp, fontFamily = RxFonts.body,
                            textAlign = TextAlign.End)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Btn(R.drawable.ic_p_lock, "Lock screen", 44.dp, 10.dp) { a.lock() }
                        Spacer(Modifier.weight(1f))
                        if (ui.aspectShown) Btn(R.drawable.ic_p_aspect, "Aspect ratio", 44.dp, 10.dp) { a.aspect() }
                        Btn(R.drawable.ic_p_rotate, "Rotate", 44.dp, 10.dp) { a.rotate() }
                        if (ui.pipShown) Btn(R.drawable.ic_p_pip, "Picture in picture", 44.dp, 10.dp) { a.pip() }
                    }
                }
            }
        }

        // swipe feedback: volume / brightness / seek / zoom
        ui.info?.let {
            Text(it, Modifier.align(Alignment.Center).rxDrawableBg(R.drawable.bg_player_pill).padding(horizontal = 22.dp, vertical = 12.dp),
                color = Color.White, fontSize = 20.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }

        if (ui.unlock) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 24.dp).size(56.dp).rxDrawableBg(R.drawable.bg_player_play)
                    .clickable { a.unlock() }.semantics { contentDescription = "Unlock" }.padding(15.dp)
            ) { Image(painterResource(R.drawable.ic_p_unlock), null, Modifier.fillMaxSize()) }
        }
    }
}

@Composable
private fun Btn(icon: Int, label: String, size: Dp, pad: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).rxDrawableBg(R.drawable.bg_player_btn).clickable(onClick = onClick)
            .semantics { contentDescription = label }.padding(pad)
    ) { Image(painterResource(icon), null, Modifier.fillMaxSize()) }
}

/** The seek bar: watched part in the accent colour, the loaded part lighter; drag or tap to jump. */
@Composable
private fun SeekLine(ui: PlayerUi, a: PlayerActions, modifier: Modifier) {
    val accent = LocalRx.current.accent
    Box(
        modifier.height(40.dp).padding(horizontal = 8.dp)
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
            drawRoundRect(Color(0x66FFFFFF), Offset(0f, y - h / 2), Size(size.width, h), r)
            drawRoundRect(Color(0x99FFFFFF), Offset(0f, y - h / 2), Size(size.width * ui.buffered / 1000f, h), r)
            val x = size.width * ui.part / 1000f
            drawRoundRect(accent, Offset(0f, y - h / 2), Size(x, h), r)
            drawCircle(Color.White, 7.dp.toPx(), Offset(x, y))
        }
    }
}
