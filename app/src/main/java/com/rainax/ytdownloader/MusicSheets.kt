package com.rainax.ytdownloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The Music player's sheets drawn with Jetpack Compose (they replaced sheet_music_queue.xml and sheet_lyrics.xml):
 * Up next (the queue) and Lyrics.
 */

/** One song in Up next: where it sits in the player ([index]), its title, line and cover. */
class QueueSong(val index: Int, val title: String, val line: String, val art: String?)

class QueueUi {
    var from by mutableStateOf("")
    var shuffle by mutableStateOf(false)
    /** Player.REPEAT_MODE_OFF / ONE / ALL */
    var repeat by mutableIntStateOf(0)
    var songs by mutableStateOf<List<QueueSong>>(emptyList())
    var now by mutableIntStateOf(-1)
    /** Scroll so the song playing shows (row number). */
    internal var jump by mutableIntStateOf(-1)
}

class QueueActions(val shuffle: () -> Unit, val repeat: () -> Unit, val play: (Int) -> Unit, val remove: (Int) -> Unit)

@Composable
internal fun QueueScreen(ui: QueueUi, a: QueueActions) {
    val rx = LocalRx.current
    val state = rememberLazyListState()
    LaunchedEffect(ui.jump) { if (ui.jump > 0) state.scrollToItem(ui.jump) }
    Column(Modifier.fillMaxWidth().background(rx.surface).padding(top = 6.dp)) {
        Box(Modifier.padding(top = 6.dp).align(Alignment.CenterHorizontally).size(36.dp, 4.dp).rxDrawableBg(R.drawable.bg_handle))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Playing from", color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                Text(ui.from, color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(44.dp).clickable { a.shuffle() }.semantics { contentDescription = "Shuffle" }.padding(10.dp)) {
                Image(painterResource(R.drawable.ic_shuffle), null, Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(if (ui.shuffle) rx.accent else rx.text))
            }
            Box(Modifier.size(44.dp).clickable { a.repeat() }.semantics { contentDescription = "Repeat" }.padding(10.dp)) {
                Image(painterResource(if (ui.repeat == 1) R.drawable.ic_repeat_one else R.drawable.ic_repeat), null, Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(if (ui.repeat != 0) rx.accent else rx.text))
            }
        }
        val songs = ui.songs
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 2000.dp).nestedScroll(rememberNestedScrollInteropConnection()),
            state = state, contentPadding = PaddingValues(start = 8.dp, bottom = 16.dp)
        ) {
            items(songs.size, key = { i -> songs[i].index }) { i -> QueueRow(songs[i], songs[i].index == ui.now, a) }
        }
    }
}

@Composable
private fun QueueRow(s: QueueSong, playing: Boolean, a: QueueActions) {
    val rx = LocalRx.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable { a.play(s.index) }.semantics { contentDescription = s.title }.padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).rxDrawableBg(R.drawable.bg_song_art)) {
            SquareArt(s.art, Modifier.fillMaxSize(), 200)
            if (playing) {
                Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_song_now).padding(14.dp)) {
                    Image(painterResource(R.drawable.ic_equalizer), null, Modifier.fillMaxSize())
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(s.title, color = if (playing) rx.accentText else rx.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.line, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!playing) {
            Box(Modifier.size(44.dp).clickable { a.remove(s.index) }.semantics { contentDescription = "Remove from queue" }.padding(12.dp)) {
                Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text2))
            }
        }
    }
}

class LyricsUi {
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var loading by mutableStateOf(true)
    var text by mutableStateOf<String?>(null)
    var source by mutableStateOf<String?>(null)
    var empty by mutableStateOf<String?>(null)
}

/** A dark cinema sheet: the words centred like subtitles, YouTube Music's source under them. */
@Composable
internal fun LyricsScreen(ui: LyricsUi) {
    val rx = LocalRx.current
    val text3 = colorResource(R.color.rx_cinema_text3)
    Column(Modifier.fillMaxWidth().background(colorResource(R.color.rx_cinema_bg)).padding(top = 10.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(colorResource(R.color.rx_cinema_line)))
        Text("Lyrics", Modifier.fillMaxWidth().padding(top = 16.dp), color = colorResource(R.color.rx_cinema_gold), fontSize = 12.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.em, textAlign = TextAlign.Center)
        Text(ui.title, Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp), color = Color.White,
            fontSize = 24.sp, fontFamily = RxFonts.display, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (ui.artist.isNotBlank()) {
            Text(ui.artist, Modifier.fillMaxWidth().padding(top = 2.dp), color = text3, fontSize = 14.sp, fontFamily = RxFonts.body,
                textAlign = TextAlign.Center)
        }
        Box(Modifier.padding(top = 12.dp).align(Alignment.CenterHorizontally).size(44.dp, 3.dp).background(rx.accent))
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            ui.text?.let { words ->
                Column(
                    Modifier.fillMaxSize().nestedScroll(rememberNestedScrollInteropConnection()).verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp)
                ) {
                    Text(words, Modifier.fillMaxWidth(), color = colorResource(R.color.rx_cinema_text), fontSize = 20.sp,
                        lineHeight = 29.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center)
                    ui.source?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.fillMaxWidth().padding(top = 20.dp), color = colorResource(R.color.rx_cinema_text4),
                            fontSize = 12.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center)
                    }
                }
            }
            if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = rx.accent)
            ui.empty?.let {
                Text(it, Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 32.dp), color = text3, fontSize = 14.sp,
                    fontFamily = RxFonts.body, textAlign = TextAlign.Center)
            }
        }
    }
}
