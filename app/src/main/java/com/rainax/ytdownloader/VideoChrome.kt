package com.rainax.ytdownloader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Everything on the video page around the player drawn with Jetpack Compose (it replaced the views of
 * page_video.xml): over the video the top buttons, quality, stats, the double-tap hints, the brightness /
 * volume box and the lock; under the list the picks bar and the "Next" bar. The video itself stays a PlayerView
 * under the overlay (touches the overlay doesn't use go on to the video's own gestures and controls).
 */

class VideoChromeUi {
    // over the video
    var topShown by mutableStateOf(true)
    var closeShown by mutableStateOf(true)
    var title by mutableStateOf("")
    var titleShown by mutableStateOf(false)
    var quality by mutableStateOf<String?>(null)
    var lockShown by mutableStateOf(false)
    var stats by mutableStateOf<String?>(null)
    var thumb by mutableStateOf<String?>(null)
    var thumbShown by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var locked by mutableStateOf(false)
    /** Double tap: "«  20s" on the left (-1) or right (1); [seekTick] restarts its fade. */
    var seekText by mutableStateOf("")
    var seekSide by mutableIntStateOf(0)
    var seekTick by mutableIntStateOf(0)
    /** Swipe in full screen: 1 = brightness, 2 = volume; [hudTick] keeps it on screen a moment. */
    var hudMode by mutableIntStateOf(1)
    var hudPct by mutableIntStateOf(0)
    var hudTick by mutableIntStateOf(0)
    // under the list
    var picks by mutableIntStateOf(0)
    var picksShown by mutableStateOf(false)
    var nextTitle by mutableStateOf<String?>(null)
    var nextFrom by mutableStateOf("")
}

class VideoChromeActions(
    val close: () -> Unit,
    val settings: () -> Unit,
    val lock: () -> Unit,
    val unlock: () -> Unit,
    val closeStats: () -> Unit,
    val picksView: () -> Unit,
    val picksClear: () -> Unit,
    val picksDownload: () -> Unit,
    val next: () -> Unit
)

/** Over the video (inside the player box). */
@Composable
internal fun VideoOverlay(ui: VideoChromeUi, a: VideoChromeActions) {
    Box(Modifier.fillMaxSize()) {
        // the video's picture until the first frame shows
        if (ui.thumbShown) RxNetImage(ui.thumb, Modifier.fillMaxSize(), widthPx = 960)
        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White)

        // top controls (shown with the player controls)
        if (ui.topShown && !ui.locked) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(56.dp).rxDrawableBg(R.drawable.bg_player_top).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ui.closeShown) Btn(R.drawable.ic_chevron_down, "Close", 44.dp, 10.dp) { a.close() }
                // the title (full screen only, like YouTube)
                Text(if (ui.titleShown) ui.title else "", Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ui.quality?.let {
                    Text(it, Modifier.padding(end = 2.dp).rxDrawableBg(R.drawable.bg_duration).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                // lock the screen (full screen only): touches do nothing until Unlock
                if (ui.lockShown) Btn(R.drawable.ic_lock, "Lock screen", 44.dp, 11.dp) { a.lock() }
                Btn(R.drawable.ic_gear, "Quality and speed", 44.dp, 10.dp) { a.settings() }
            }
        }

        // Stats for nerds (gear menu): live player details; tap to close
        ui.stats?.let {
            Text(it, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 52.dp).background(Color(0xB3000000))
                .clickable { a.closeStats() }.padding(8.dp),
                color = Color.White, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
        }

        // double tap on the left / right: back or forward 10 s (the hint fades in on that side)
        val hint = remember { Animatable(0f) }
        val pop = remember { Animatable(1f) }
        LaunchedEffect(ui.seekTick) {
            if (ui.seekTick == 0) return@LaunchedEffect
            hint.snapTo(1f); pop.snapTo(0.9f)
            pop.animateTo(1f, tween(120))
            kotlinx.coroutines.delay(550)
            hint.animateTo(0f, tween(250))
        }
        if (hint.value > 0f) {
            Box(
                Modifier.align(if (ui.seekSide < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 24.dp)
                    .size(96.dp).alpha(hint.value).scale(pop.value).rxDrawableBg(R.drawable.bg_seek_hint),
                contentAlignment = Alignment.Center
            ) { Text(ui.seekText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        }

        // swipe up / down in full screen: brightness (left half) or volume (right half)
        val hud = remember { Animatable(0f) }
        LaunchedEffect(ui.hudTick) {
            if (ui.hudTick == 0) return@LaunchedEffect
            hud.snapTo(1f)
            kotlinx.coroutines.delay(700)
            hud.animateTo(0f, tween(250))
        }
        if (hud.value > 0f) {
            Row(
                Modifier.align(Alignment.Center).alpha(hud.value).rxDrawableBg(R.drawable.bg_gesture_hud).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = if (ui.hudMode == 1) R.drawable.ic_brightness else if (ui.hudPct == 0) R.drawable.ic_volume_off else R.drawable.ic_volume_up
                Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(Color.White))
                LinearProgressIndicator(
                    progress = { ui.hudPct / 100f }, modifier = Modifier.padding(start = 12.dp).width(120.dp).height(4.dp),
                    color = LocalRx.current.accent, trackColor = Color(0x4DFFFFFF), gapSize = 0.dp, drawStopIndicator = {}
                )
                Text("${ui.hudPct}%", Modifier.padding(start = 8.dp).width(40.dp), color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
        }

        // Lock screen (from the gear menu): touches do nothing until Unlock
        if (ui.locked) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { }) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(48.dp).alpha(0.85f).rxDrawableBg(R.drawable.bg_mini_button)
                        .clickable { a.unlock() }.semantics { contentDescription = "Unlock screen" }.padding(12.dp)
                ) { Image(painterResource(R.drawable.ic_lock_open), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White)) }
            }
        }
    }
}

@Composable
private fun Btn(icon: Int, label: String, size: androidx.compose.ui.unit.Dp, pad: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clickable(onClick = onClick).semantics { contentDescription = label }.padding(pad)) {
        Image(painterResource(icon), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
    }
}

/** Under the list: the picks bar (videos picked with +) or the "Next" bar (tap: the Up next list). */
@Composable
internal fun VideoBottomBars(ui: VideoChromeUi, a: VideoChromeActions) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth()) {
        if (ui.picksShown && ui.picks > 0) {
            val n = ui.picks
            Row(
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().height(60.dp).rxDrawableBg(R.drawable.bg_glass_nav)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (n == 1) "1 selected · View" else "$n selected · View", Modifier.weight(1f).clickable { a.picksView() }.padding(vertical = 10.dp),
                    color = rx.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
                Text("Clear", Modifier.clickable { a.picksClear() }.padding(horizontal = 12.dp, vertical = 10.dp), color = rx.accentText,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
                Row(
                    Modifier.height(44.dp).background(rx.accent, RoundedCornerShape(2.dp)).clickable { a.picksDownload() }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                    Text(if (n > 1) "Download $n" else "Download", Modifier.padding(start = 8.dp), color = rx.onAccent, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
                }
            }
        }
        val next = ui.nextTitle
        if (next != null) {
            Row(
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).fillMaxWidth().height(64.dp).rxDrawableBg(R.drawable.bg_glass_card)
                    .clickable { a.next() }.padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_radio), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(next, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(ui.nextFrom, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                Image(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(40.dp).padding(8.dp).rotate(180f),
                    colorFilter = ColorFilter.tint(rx.text))
            }
        }
    }
}
