package com.rainax.ytdownloader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The Social page, built with Jetpack Compose (it replaced page_social.xml, item_social_tag.xml and item_status.xml):
 * the headline, the app tags, the link box with Paste / Download, the copied-link ticket, the WhatsApp status saver
 * and "How it works". The work (links, the download sheet, reading and saving statuses) stays in MainSocial.kt and
 * StatusSaver.kt.
 */

/** What the Social page shows (the status saver part is [StatusUi]). */
class SocialState {
    var input by mutableStateOf("")
    /** The copied link offered in the ticket strip, or null. */
    var clipUrl by mutableStateOf<String?>(null)
    /** Bumped to scroll to the status saver (the "statuses saved" notification was tapped). */
    var showStatus by mutableIntStateOf(0)
}

/** An app's real icon from the phone, else its coloured tag with the short name. */
@Composable
private fun AppBadge(app: SocialApps.App, size: Dp, textSize: Int) {
    val ctx = LocalContext.current
    val real = remember(app.name) { SocialApps.icon(ctx, app) }
    val drawable = remember(app.name, real) { real ?: SocialApps.badge(app, ctx) }
    Box(
        Modifier.size(size).drawBehind {
            drawIntoCanvas { cv ->
                drawable.setBounds(0, 0, this.size.width.toInt(), this.size.height.toInt())
                drawable.draw(cv.nativeCanvas)
            }
        },
        contentAlignment = Alignment.Center
    ) {
        if (real == null) Text(app.short, color = Color.White, fontSize = textSize.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SocialScreen(act: MainActivity, s: SocialState, st: StatusUi) {
    val rx = LocalRx.current
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    var statusTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.showStatus) { if (s.showStatus > 0) scroll.animateScrollTo(statusTop) }

    val go = {
        val urls = act.extractUrls(s.input)
        if (urls.isEmpty()) act.message("Paste a video link first")
        else {
            focus.clearFocus()
            AppPrefs.setClipSeen(act, urls.first())
            act.showDownloadSheet(urls)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 120.dp)) {
        Text("All-in-one downloader", color = rx.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("Save any video", Modifier.padding(top = 2.dp), color = rx.text, fontSize = 32.sp, lineHeight = 38.sp, fontFamily = RxFonts.display)

        // the apps: tap one to open it (to copy a link) or its website
        Row(Modifier.padding(top = 14.dp).fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("TikTok", "Instagram", "Facebook", "YouTube").mapNotNull { n -> SocialApps.ALL.firstOrNull { it.name == n } }.forEach { app ->
                Row(
                    Modifier.padding(end = 8.dp).height(44.dp).clip(RoundedCornerShape(50)).background(rx.surface)
                        .clickable { if (!SocialApps.open(act, app)) act.message("Couldn't open ${app.name}") }
                        .semantics { contentDescription = "Open ${app.name}" }
                        .padding(start = 6.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppBadge(app, 24.dp, 10)
                    Text(app.name, Modifier.padding(start = 8.dp), color = rx.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // the link box
        Text("Paste a link", Modifier.padding(start = 4.dp, top = 20.dp), color = rx.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val link = act.extractUrls(s.input).firstOrNull()
        val linkApp = link?.let { SocialApps.of(it) }
        Row(
            Modifier.padding(top = 6.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(rx.surface)
                .padding(start = 8.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (linkApp != null) AppBadge(linkApp, 30.dp, 12)
                else Image(painterResource(R.drawable.ic_link), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text2))
            }
            Box(Modifier.weight(1f).padding(start = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (s.input.isEmpty()) Text("https://…", color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
                BasicTextField(
                    value = s.input,
                    onValueChange = { s.input = it; act.refreshClipStrip() },
                    singleLine = true,
                    textStyle = TextStyle(color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body),
                    cursorBrush = SolidColor(rx.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (s.input.isNotEmpty()) {
                IconButton(onClick = { s.input = ""; act.refreshClipStrip() }, modifier = Modifier.size(40.dp)) {
                    Image(painterResource(R.drawable.ic_close), "Clear", Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text2))
                }
            }
        }
        if (link != null) {
            Text(
                if (linkApp != null) "${linkApp.name} link  ·  ${linkApp.what}"
                else "Link from ${android.net.Uri.parse(link).host?.removePrefix("www.").orEmpty()}  ·  RAINAX will look for the video",
                Modifier.padding(start = 4.dp, top = 8.dp), color = rx.text2, fontSize = 12.sp, fontWeight = FontWeight.Medium
            )
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth()) {
            Row(
                Modifier.padding(end = 8.dp).weight(1f).height(52.dp).clip(RoundedCornerShape(50)).background(rx.surface)
                    .clickable {
                        val url = act.clipText()?.let { act.extractUrls(it).firstOrNull() }
                        if (url == null) act.message("Copy a video link first, then tap Paste") else { s.input = url; act.refreshClipStrip() }
                    },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_paste), null, Modifier.size(17.dp), colorFilter = ColorFilter.tint(rx.text))
                Text("Paste", Modifier.padding(start = 8.dp), color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em)
            }
            Row(
                Modifier.weight(1.6f).height(52.dp).clip(RoundedCornerShape(50)).background(RxRed).clickable { go() },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Image(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
                Text("Download", Modifier.padding(start = 8.dp), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.em)
            }
        }

        // a video link is on the clipboard: "Copied · TikTok" with Get it
        val clip = s.clipUrl
        if (clip != null) {
            val app = SocialApps.of(clip)
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(60.dp).clip(RoundedCornerShape(16.dp)).background(rx.surface).padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (app != null) AppBadge(app, 30.dp, 12)
                else Box(Modifier.size(30.dp).background(rx.accent, CircleShape), contentAlignment = Alignment.Center) {
                    Text("↓", color = rx.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Copied · ${app?.name ?: "video link"}", Modifier.weight(1f).padding(horizontal = 12.dp),
                    color = rx.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = {
                        act.clipStripDone = clip
                        AppPrefs.setClipSeen(act, clip)
                        s.clipUrl = null
                        act.showDownloadSheet(listOf(clip))
                    },
                    modifier = Modifier.height(40.dp), shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.textButtonColors(containerColor = rx.accent, contentColor = rx.onAccent)
                ) { Text("Get it", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }

        Column(Modifier.padding(top = 24.dp).fillMaxWidth().onGloballyPositioned { statusTop = it.positionInParent().y.toInt() }
            .clip(RoundedCornerShape(16.dp)).background(rx.surface).padding(16.dp)) {
            StatusSaverCard(act, st)
        }

        Text("How it works", Modifier.padding(start = 4.dp, top = 22.dp), color = rx.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        listOf(
            "01" to "Copy the link in TikTok, Instagram, Facebook or YouTube (Share › Copy link)",
            "02" to "Paste it here, or share it straight to RAINAX",
            "03" to "Pick HD or MP3 and download"
        ).forEach { (n, line) ->
            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(rx.surface, CircleShape), contentAlignment = Alignment.Center) {
                    Text(n.trimStart('0'), color = rx.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(line, Modifier.weight(1f).padding(start = 12.dp), color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body)
            }
        }
        Text(
            "Private accounts and paid videos can't be saved. If a link fails, copy it again: some links expire. Only save what you have the right to, and respect the creators.",
            Modifier.padding(top = 18.dp), color = rx.text2, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = RxFonts.body
        )
    }
}

@Composable
private fun StatusSaverCard(act: MainActivity, st: StatusUi) {
    val rx = LocalRx.current
    val saver = act.statusSaver
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp)) {
            val ctx = LocalContext.current
            val wa = remember { SocialApps.icon(ctx, SocialApps.WHATSAPP) }
            if (wa != null) {
                Box(Modifier.fillMaxSize().drawBehind {
                    drawIntoCanvas { cv -> wa.setBounds(0, 0, size.width.toInt(), size.height.toInt()); wa.draw(cv.nativeCanvas) }
                })
            } else {
                Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_status_wa).padding(8.dp)) {
                    Image(painterResource(R.drawable.ic_status_ring), null, Modifier.fillMaxSize())
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("WhatsApp status saver", color = rx.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
            Text(st.sub, Modifier.padding(top = 1.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
        }
        if (st.refreshShown) {
            IconButton(onClick = { saver.refresh() }, modifier = Modifier.size(40.dp)) {
                Image(painterResource(R.drawable.ic_refresh), "Refresh", Modifier.size(22.dp), colorFilter = ColorFilter.tint(rx.text))
            }
        }
    }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("WhatsApp", "WA Business", "Saved").forEachIndexed { i, label ->
            RxChip(label, selected = st.tab == i) { saver.selectTab(i) }
        }
    }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clickable { saver.setAuto(!st.auto) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Auto-save new statuses", color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body)
            Text("Every status you view is saved before it disappears", Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
        }
        Switch(
            checked = st.auto, onCheckedChange = { saver.setAuto(it) }, modifier = Modifier.padding(start = 12.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = rx.onAccent, checkedTrackColor = rx.accent,
                uncheckedThumbColor = rx.text2, uncheckedTrackColor = rx.surface, uncheckedBorderColor = rx.outline
            )
        )
    }
    if (st.ask) {
        Column(Modifier.padding(top = 10.dp).fillMaxWidth()) {
            Text(st.askText, color = rx.text2, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = RxFonts.body)
            Button(
                onClick = { saver.allowAccess() }, modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366), contentColor = Color.White)
            ) {
                Image(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
                Text("Allow access", Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
            }
            Text(
                "Or choose only the status folder",
                Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp).height(40.dp).clickable { saver.pickStatusFolder() }.padding(horizontal = 12.dp, vertical = 10.dp),
                color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body
            )
        }
    }
    val empty = st.emptyText
    if (empty != null) {
        Text(empty, Modifier.padding(top = 12.dp).fillMaxWidth().padding(vertical = 18.dp), color = rx.text2, fontSize = 14.sp,
            fontFamily = RxFonts.body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (st.loading) {
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), color = rx.accent, strokeWidth = 3.dp)
        }
    }
    if (st.items.isNotEmpty()) {
        @Suppress("UNUSED_VARIABLE") val tick = st.savedTick        // ticks redraw after a save
        Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
            st.items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { StatusTile(saver, item, st.tab != 2) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (st.saveAllShown) {
        Button(
            onClick = { saver.saveAll() }, enabled = st.saveAllEnabled,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = rx.accentContainer, contentColor = rx.accentText)
        ) {
            Image(painterResource(R.drawable.ic_download), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(rx.text))
            Text(st.saveAllText, Modifier.padding(start = 8.dp), fontFamily = RxFonts.body, fontWeight = FontWeight.Bold)
        }
    }
}

/** One status: its picture (a play mark on videos) and a Save / Saved button. */
@Composable
private fun StatusTile(saver: StatusSaver, item: StatusSaver.Item, showSave: Boolean) {
    val bmp by produceState<Bitmap?>(saver.cachedThumb(item), item.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { saver.thumb(item) }
    }
    val saved = saver.isSaved(item)
    Box(
        Modifier.padding(4.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).rxDrawableBg(R.drawable.bg_status_thumb)
            .clickable { saver.open(item) }
    ) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (item.isVideo) {
            Box(Modifier.align(Alignment.Center).size(30.dp).rxDrawableBg(R.drawable.bg_status_save).padding(6.dp)) {
                Image(painterResource(R.drawable.ic_p_play), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Color.White))
            }
        }
        if (showSave) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(34.dp).rxDrawableBg(R.drawable.bg_status_save)
                    .clickable { if (!saved) saver.save(item) }
                    .semantics { contentDescription = if (saved) "Saved" else "Save" }
                    .padding(8.dp)
            ) {
                Image(painterResource(if (saved) R.drawable.ic_check else R.drawable.ic_download), null, Modifier.fillMaxSize(),
                    colorFilter = ColorFilter.tint(Color.White))
            }
        }
    }
}
