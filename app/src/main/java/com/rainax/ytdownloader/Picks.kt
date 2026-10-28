package com.rainax.ytdownloader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Videos picked with + (Home, search, channels, the video page's Up next and Mix list), to download them
 * together. One list for the whole app, so picks made on the video page and on Home add up.
 */
object Picks {
    /** With more picks than this, the download sheet reads only this many videos and estimates the rest. */
    const val SAMPLE = 12

    private val map = LinkedHashMap<String, VideoItem>()
    private val listeners = mutableListOf<() -> Unit>()

    val size get() = map.size

    fun has(url: String) = map.containsKey(url)

    fun all(): List<VideoItem> = map.values.toList()

    fun toggle(item: VideoItem) {
        if (map.remove(item.url) == null) map[item.url] = item
        changed()
    }

    fun clear() {
        if (map.isEmpty()) return
        map.clear()
        changed()
    }

    /** A new main screen is being built (e.g. theme change): forget the old screen's listeners. */
    fun clearListeners() = listeners.clear()

    fun unlisten(l: () -> Unit) {
        listeners.remove(l)
    }

    /** [l] runs on every change (the bars and + buttons update). */
    fun listen(l: () -> Unit) {
        listeners += l
    }

    private fun changed() = listeners.toList().forEach { it() }
}

/**
 * The picked videos as a list (tap the "N selected" text on the pick bar): see what is picked, remove any
 * with ✕, clear all, or download them. Drawn with Jetpack Compose (it used item_song_row.xml rows).
 */
object PicksSheet {

    fun show(act: androidx.appcompat.app.AppCompatActivity, onDownload: (List<VideoItem>) -> Unit) {
        if (Picks.size == 0) return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(act)
        var items by androidx.compose.runtime.mutableStateOf(Picks.all())
        val listener: () -> Unit = {
            if (dialog.isShowing) {
                if (Picks.size == 0) dialog.dismiss() else items = Picks.all()
            }
        }
        Picks.listen(listener)
        dialog.setOnDismissListener { Picks.unlisten(listener) }
        val view = androidx.compose.ui.platform.ComposeView(act)
        view.setContent {
            RxTheme {
                PicksList(items, clear = { Picks.clear() }, remove = { Picks.toggle(it) }) {
                    val all = Picks.all()
                    dialog.dismiss()
                    if (all.isNotEmpty()) {
                        Picks.clear()
                        onDownload(all)
                    }
                }
            }
        }
        dialog.setContentView(view)
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }
}

@androidx.compose.runtime.Composable
private fun PicksList(picks: List<VideoItem>, clear: () -> Unit, remove: (VideoItem) -> Unit, download: () -> Unit) {
    val rx = LocalRx.current
    val n = picks.size
    Column(Modifier.fillMaxWidth().background(rx.surface).padding(top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (n == 1) "1 video selected" else "$n videos selected", Modifier.weight(1f), color = rx.text, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
            Text("Clear all", Modifier.clickable(onClick = clear).padding(horizontal = 14.dp, vertical = 12.dp), color = rx.accentText,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
        }
        val maxH = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxH).nestedScroll(rememberNestedScrollInteropConnection())) {
            items(picks.size, key = { i -> picks[i].url }) { i ->
                val item = picks[i]
                Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).rxDrawableBg(R.drawable.bg_song_art)) {
                        RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 240)
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(item.title.ifBlank { item.url }, color = rx.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(item.uploader, YtCatalog.duration(item.seconds)).filter { it.isNotBlank() }.joinToString(" • "),
                            Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(44.dp).clickable { remove(item) }.semantics { contentDescription = "Remove from selection" }.padding(12.dp)) {
                        Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text2))
                    }
                }
            }
        }
        Button(
            onClick = download, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
        ) {
            Image(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.onAccent))
            Text(if (n > 1) "Download $n" else "Download", Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
        }
    }
}
