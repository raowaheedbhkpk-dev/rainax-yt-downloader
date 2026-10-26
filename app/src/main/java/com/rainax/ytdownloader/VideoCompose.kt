package com.rainax.ytdownloader

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/*
 * Video pieces for Compose screens (Subscriptions now, Home later): the big video card like YouTube's and the
 * round channel picture. Pictures come through RAINAX's own loader (Img), the same cache as the rest of the app.
 */

/** A picture from the internet through Img (round for channel pictures). */
@Composable
internal fun RxNetImage(url: String?, modifier: Modifier, circle: Boolean = false, widthPx: Int = 640) {
    AndroidView(
        factory = { c -> ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { v -> if (v.getTag(R.id.img_url) != url) Img.load(v, url, circle = circle, widthPx = widthPx) },
        modifier = modifier
    )
}

/** "LIVE", "SHORTS", "12 videos" or "12:34" for the picture's corner. */
internal fun videoBadge(item: VideoItem): String = when {
    item.isPlaylist -> if (item.count > 0) "${item.count} videos" else "Playlist"
    item.isShort -> "SHORTS"
    item.seconds < 0 -> "LIVE"
    else -> YtCatalog.duration(item.seconds)
}

/** "Channel • 1.2M views • 3 days ago" */
internal fun videoMeta(item: VideoItem): String {
    val parts = mutableListOf<String>()
    if (item.uploader.isNotBlank()) parts += item.uploader
    if (item.isPlaylist) {
        if (item.count > 0) parts += "${item.count} videos"
    } else {
        if (item.views >= 0) parts += YtCatalog.count(item.views) + if (item.seconds < 0) " watching" else " views"
        item.uploaded?.takeIf { it.isNotBlank() }?.let { parts += it }
    }
    return parts.joinToString(" • ")
}

/**
 * A big video card: the picture (duration in the corner, a red bar for how much you watched), the channel
 * picture, title, channel and views, and the round Download button. Tap opens; ⋮ / hold shows [onMore].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(
    item: VideoItem,
    onOpen: (VideoItem) -> Unit,
    onDownload: (VideoItem) -> Unit,
    onMore: ((VideoItem, android.view.View) -> Unit)? = null,
    /** Autoplay in feed: the list this card can play its video in (null = no preview). */
    preview: ComposePreviewList? = null,
    /** + button: pick several videos, then download them together (null = no + button). */
    onPick: ((VideoItem) -> Unit)? = null,
    isPicked: (String) -> Boolean = { false }
) {
    val rx = LocalRx.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val menu = if (item.isChannel) null else onMore
    // sizes ready before Download is tapped (normal videos and Shorts; not live, playlists or channels)
    androidx.compose.runtime.LaunchedEffect(item.url) {
        if (!item.isPlaylist && !item.isChannel && item.seconds >= 0) FastExtractor.lookAhead(item.url)
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 18.dp)
            .combinedClickable(onClick = { onOpen(item) }, onLongClick = menu?.let { m -> { m(item, view) } })
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).rxDrawableBg(R.drawable.bg_thumb_big)) {
            val card = if (preview != null) androidx.compose.runtime.remember(item.url) { ComposeCard(ctx, item) } else null
            if (card != null && preview != null) {
                card.item = item
                // the picture inside a real frame, so the playing preview can be put over it
                AndroidView(
                    factory = { (card.box.parent as? android.view.ViewGroup)?.removeView(card.box); card.box },
                    update = { Img.load(card.image, item.thumb, widthPx = 720) },
                    modifier = Modifier.fillMaxSize()
                )
                androidx.compose.runtime.DisposableEffect(card, preview) {
                    preview.add(card)
                    onDispose { preview.remove(card) }
                }
            } else {
                RxNetImage(item.thumb, Modifier.fillMaxSize(), widthPx = 720)
            }
            val badge = card?.badge?.toString() ?: videoBadge(item)
            if (badge.isNotEmpty()) {
                Text(
                    badge,
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .rxDrawableBg(if (item.seconds < 0) R.drawable.bg_live else R.drawable.bg_duration)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
            }
            val watched = if (item.isPlaylist || item.isChannel) -1f else WatchHistory.progress(ctx, item.url)
            if (watched > 0f) {
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.weight(watched.coerceIn(0.01f, 1f)).height(3.dp).background(rx.accent))
                    if (watched < 1f) Box(Modifier.weight(1f - watched.coerceIn(0.01f, 0.99f)).height(3.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.align(Alignment.Top).size(36.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                RxNetImage(item.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 96)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.title, color = rx.text, fontSize = 16.sp, lineHeight = 21.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(videoMeta(item), Modifier.padding(top = 3.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onPick != null && !item.isPlaylist && !item.isChannel && item.seconds >= 0) {
                val on = isPicked(item.url)
                Box(
                    Modifier.padding(start = 4.dp).size(40.dp).rxDrawableBg(R.drawable.bg_circle_btn).clickable { onPick(item) }
                        .semantics { contentDescription = if (on) "Selected. Tap to unselect" else "Select for download" }.padding(10.dp)
                ) {
                    Image(painterResource(if (on) R.drawable.ic_check else R.drawable.ic_add), null, Modifier.fillMaxSize(),
                        colorFilter = ColorFilter.tint(if (on) rx.accent else rx.text))
                }
            }
            Box(
                Modifier.padding(start = 6.dp).size(42.dp).rxDrawableBg(R.drawable.bg_circle_btn).clickable { onDownload(item) }
                    .semantics { contentDescription = "Download" }.padding(10.dp)
            ) {
                Image(painterResource(R.drawable.ic_download), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.accent))
            }
            if (menu != null) {
                Box(
                    Modifier.padding(start = 2.dp).size(28.dp, 42.dp).clickable { menu(item, view) }.semantics { contentDescription = "More" },
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.ic_more), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(rx.text2))
                }
            }
        }
    }
}

/** A channel's round picture and name (the strip on top of Subscriptions). */
@Composable
internal fun ChannelAvatar(item: VideoItem, onOpen: (VideoItem) -> Unit) {
    val rx = LocalRx.current
    Column(
        Modifier.width(80.dp).clickable { onOpen(item) }.semantics { contentDescription = item.title }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
            RxNetImage(item.thumb, Modifier.fillMaxSize(), circle = true, widthPx = 160)
        }
        Text(item.title, Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp), color = rx.text, fontSize = 12.sp,
            fontFamily = RxFonts.body, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A big card that feed autoplay can play in: a real picture frame and the duration badge's text. */
internal class ComposeCard(ctx: android.content.Context, first: VideoItem) : PreviewCard {
    val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val box = android.widget.FrameLayout(ctx).apply {
        addView(image, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
    }
    override val thumbBox: android.view.ViewGroup get() = box
    override var item: VideoItem? = first
    override val canPreview: Boolean
        get() = item?.let { !it.isPlaylist && !it.isChannel && !it.isShort && it.seconds > 0 } == true
    var badge by androidx.compose.runtime.mutableStateOf<CharSequence>(videoBadge(first))
    override var badgeText: CharSequence
        get() = badge
        set(v) { badge = v }
}
