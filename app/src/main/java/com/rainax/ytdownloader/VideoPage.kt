package com.rainax.ytdownloader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The part of the video page under the player, drawn with Jetpack Compose (it replaced item_video_header.xml and
 * the Up next adapter): title and views, the channel with Subscribe, the button row (Download, Chapters, Like,
 * Dislike, Save, Share), the chapter film strip, the first comment, the description and the Up next videos.
 * VideoScreen keeps the logic and fills [VideoPageUi].
 */

class VideoPageUi {
    var title by mutableStateOf("")
    var meta by mutableStateOf("")
    var uploader by mutableStateOf("")
    var subs by mutableStateOf("")
    var avatar by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    var subscribeShown by mutableStateOf(false)
    var subscribed by mutableStateOf(false)
    var rateShown by mutableStateOf(false)
    var likeText by mutableStateOf("Like")
    var dislikeText by mutableStateOf("Dislike")
    var liked by mutableStateOf(false)
    var disliked by mutableStateOf(false)
    var saveShown by mutableStateOf(false)
    var saved by mutableStateOf(false)

    var chaptersShown by mutableStateOf(false)
    /** The chapter film strip: (time, name, start ms). */
    var chapters by mutableStateOf<List<Triple<String, String, Long>>>(emptyList())
    var markedChapter by mutableIntStateOf(-1)

    var commentsShown by mutableStateOf(false)
    var commentsTitle by mutableStateOf("Comments")
    var commentAvatar by mutableStateOf<String?>(null)
    var commentAvatarShown by mutableStateOf(true)
    var commentText by mutableStateOf("")

    var desc by mutableStateOf<String?>(null)
    var upNextShown by mutableStateOf(false)

    /** Cinema mode: everything under the player fades almost to black. */
    var dim by mutableStateOf(false)
    internal var toTop by mutableIntStateOf(0)
    internal var list: LazyListState? = null

    fun scrollToTop() { toTop++ }
    fun canScrollUp() = list?.canScrollBackward ?: false
}

class VideoPageActions(
    val channel: () -> Unit,
    val subscribe: () -> Unit,
    val download: () -> Unit,
    val chapters: () -> Unit,
    val like: () -> Unit,
    val dislike: () -> Unit,
    val save: () -> Unit,
    val saveHold: () -> Unit,
    val share: () -> Unit,
    val chapter: (Int) -> Unit,
    val comments: () -> Unit,
    val description: () -> Unit,
    val error: () -> Unit,
    /** A touch or a scroll that ended: the lights come back up. */
    val wake: () -> Unit
)

@Composable
internal fun VideoPageScreen(ui: VideoPageUi, related: FeedModel, a: VideoPageActions) {
    val rx = LocalRx.current
    val state = rememberLazyListState()
    ui.list = state
    LaunchedEffect(ui.toTop) { if (ui.toTop > 0) state.animateScrollToItem(0) }
    // reading the description or comments: the lights stay on; when the list rests they may go down again
    LaunchedEffect(state) { snapshotFlow { state.isScrollInProgress }.collect { moving -> if (!moving) a.wake() } }
    @Suppress("UNUSED_VARIABLE") val v = related.version
    val videos = related.all()
    LazyColumn(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.any { it.pressed && !it.previousPressed }) a.wake()
                    }
                }
            },
        state = state, contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(key = "header") { Header(ui, a) }
        items(videos.size, key = { i -> videos[i].url + "#" + i }) { i ->
            VideoCard(videos[i], related.onOpen, related.onDownload, related.onMore, null, related.onPick, related.isPicked)
        }
    }
}

@Composable
private fun Header(ui: VideoPageUi, a: VideoPageActions) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(ui.title, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), color = rx.text, fontSize = 20.sp, lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (ui.meta.isNotEmpty()) {
            Text(ui.meta, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).clickable { a.description() }, color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
        }
        if (ui.loading) {
            LinearProgressIndicator(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(), color = rx.accent, trackColor = rx.surfaceAlt)
        }
        val err = ui.error
        if (err != null) {
            Text(err, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).clickable { a.error() }, color = colorResource(R.color.rx_error), fontSize = 14.sp, fontFamily = RxFonts.body)
        }
        // the channel
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().clickable { a.channel() }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                RxNetImage(ui.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 120)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(ui.uploader, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.subs.isNotEmpty()) Text(ui.subs, color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
            }
            if (ui.subscribeShown) {
                val on = ui.subscribed
                Box(
                    Modifier.padding(start = 8.dp).height(36.dp).background(if (on) rx.surfaceAlt else rx.text, CircleShape).clip(CircleShape)
                        .clickable { a.subscribe() }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (on) "Subscribed" else "Subscribe", color = if (on) rx.text else rx.bg, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                }
            }
        }
        // the buttons
        Row(
            Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.height(40.dp).background(rx.accent, CircleShape).clip(CircleShape).clickable { a.download() }.padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_download), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.onAccent))
                Text("Download", Modifier.padding(start = 8.dp), color = rx.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
            }
            if (ui.chaptersShown) PillButton(R.drawable.ic_chapters, "Chapters", false) { a.chapters() }
            if (ui.rateShown) {
                PillButton(R.drawable.ic_thumb_up, ui.likeText, ui.liked) { a.like() }
                PillButton(R.drawable.ic_thumb_down, ui.dislikeText, ui.disliked) { a.dislike() }
            }
            if (ui.saveShown) PillButton(R.drawable.ic_watch_later, "Save", ui.saved, onHold = { a.saveHold() }) { a.save() }
            PillButton(R.drawable.ic_share, "Share", false) { a.share() }
        }
        // the chapter film strip
        if (ui.chapters.size >= 2) {
            val strip = rememberLazyListState()
            LaunchedEffect(ui.markedChapter) {
                if (ui.markedChapter >= 0) strip.animateScrollToItem((ui.markedChapter - 1).coerceAtLeast(0))
            }
            LazyRow(Modifier.padding(top = 12.dp).fillMaxWidth(), state = strip, contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(ui.chapters.size) { i ->
                    val (time, name, _) = ui.chapters[i]
                    Column(
                        Modifier.padding(horizontal = 2.dp).size(140.dp, 86.dp)
                            .rxDrawableBg(if (i == ui.markedChapter) R.drawable.bg_film_frame_on else R.drawable.bg_film_frame)
                            .clickable { a.chapter(i) }
                            .semantics { contentDescription = "Chapter ${i + 1}: $name" }
                            .padding(horizontal = 10.dp, vertical = 14.dp)
                    ) {
                        Text(time, color = rx.gold, fontSize = 14.sp, fontFamily = RxFonts.display)
                        Text(name, Modifier.padding(top = 2.dp), color = colorResource(R.color.film_text), fontSize = 12.sp, fontFamily = RxFonts.body,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // the first comment
        if (ui.commentsShown) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().background(rx.surfaceAlt, RoundedCornerShape(12.dp))
                    .clickable { a.comments() }.padding(14.dp)
            ) {
                Text(ui.commentsTitle, color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ui.commentAvatarShown) {
                        Box(Modifier.padding(end = 12.dp).size(28.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                            RxNetImage(ui.commentAvatar, Modifier.fillMaxSize(), circle = true, widthPx = 80)
                        }
                    }
                    Text(ui.commentText, Modifier.weight(1f), color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val desc = ui.desc
        if (desc != null) {
            Text(desc, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clickable { a.description() },
                color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (ui.upNextShown) {
            Text("Up next", Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp), color = rx.text, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
        }
    }
}

/** A round-ended button of the row (Like, Save, Share...): the icon turns the accent when it is on. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PillButton(icon: Int, label: String, active: Boolean, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    val rx = LocalRx.current
    Row(
        Modifier.height(40.dp).background(rx.surfaceAlt, CircleShape).border(1.dp, rx.outline, CircleShape).clip(CircleShape)
            .combinedClickable(onClick = onClick, onLongClick = onHold).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(icon), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(if (active) rx.accent else rx.text))
        if (label.isNotEmpty()) Text(label, Modifier.padding(start = 6.dp), color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body)
    }
}
