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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/*
 * Small sheets drawn with Jetpack Compose (they replaced sheet_account.xml, sheet_description.xml,
 * item_desc_chapter.xml and popup_theme.xml): your YouTube account, a video's description, and the
 * theme / accent menu on the top bar.
 */

// ---------- account ----------

@Composable
internal fun AccountSheet(name: String, handle: String?, avatar: String?, signOut: () -> Unit) {
    val rx = LocalRx.current
    Column(
        Modifier.fillMaxWidth().background(rx.surface).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
            RxNetImage(avatar, Modifier.fillMaxSize(), circle = true, widthPx = 200)
        }
        Text(name, Modifier.padding(top = 12.dp), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
        if (!handle.isNullOrBlank()) {
            Text(handle, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
        }
        Text(
            "Your recommendations, subscriptions, history and liked videos are on Home. Your sign-in stays on this phone.",
            Modifier.padding(top = 16.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center
        )
        Button(
            onClick = signOut,
            modifier = Modifier.padding(top = 20.dp).fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = rx.accentContainer, contentColor = rx.accentText)
        ) { Text("Sign out", fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
    }
}

// ---------- description ----------

class DescChapter(val time: String, val title: String, val now: Boolean, val ms: Long)

@Composable
internal fun DescriptionSheet(
    title: String, likes: String, views: String, date: String,
    chapters: List<DescChapter>, text: AnnotatedString, onChapter: (Long) -> Unit
) {
    val rx = LocalRx.current
    Column(
        Modifier.fillMaxWidth().background(rx.surface).nestedScroll(rememberNestedScrollInteropConnection())
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp)
    ) {
        SheetHandle()
        Label("Description", Modifier.padding(top = 16.dp))
        Text(title, Modifier.padding(top = 6.dp), color = rx.text, fontSize = 20.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 16.dp).fillMaxWidth().rxDrawableBg(R.drawable.bg_desc_card).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Stat(likes, "Likes", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(32.dp).background(rx.outline))
            Stat(views, "Views", Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(32.dp).background(rx.outline))
            Stat(date, "Uploaded", Modifier.weight(1f))
        }
        if (chapters.size >= 2) {
            Label("Chapters", Modifier.padding(top = 20.dp))
            Column(Modifier.padding(top = 8.dp).fillMaxWidth().rxDrawableBg(R.drawable.bg_desc_card).padding(vertical = 4.dp)) {
                chapters.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChapter(c.ms) }
                            .semantics { contentDescription = "${c.time} ${c.title}" }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(c.time, Modifier.rxDrawableBg(R.drawable.bg_time_chip).padding(horizontal = 8.dp, vertical = 3.dp),
                            color = rx.accentText, fontSize = 14.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold)
                        Text(c.title, Modifier.weight(1f).padding(start = 12.dp), color = rx.text, fontSize = 14.sp, fontFamily = RxFonts.body,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (c.now) Box(Modifier.padding(start = 8.dp).size(8.dp).background(rx.accent, CircleShape))
                    }
                }
            }
        }
        Label("About this video", Modifier.padding(top = 20.dp))
        Text(text, Modifier.padding(top = 8.dp).fillMaxWidth().rxDrawableBg(R.drawable.bg_desc_card).padding(14.dp),
            color = rx.text, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = RxFonts.body)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(text, modifier, color = LocalRx.current.text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = RxFonts.body)
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    val rx = LocalRx.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(label, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 12.sp, fontFamily = RxFonts.body)
    }
}

// ---------- theme / accent menu ----------

@Composable
internal fun ThemeMenu(act: MainActivity, mode: Int, accentName: String, pick: (Int) -> Unit, accent: (Int) -> Unit) {
    val rx = LocalRx.current
    Column(Modifier.width(240.dp).rxDrawableBg(R.drawable.bg_theme_popup).padding(vertical = 8.dp)) {
        run {
            Text("Appearance", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp), color = rx.text2, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
            ModeRow(R.drawable.ic_theme_system, "System", mode == 0) { pick(0) }
            ModeRow(R.drawable.ic_brightness, "Light", mode == 1) { pick(1) }
            ModeRow(R.drawable.ic_moon, "Dark", mode == 2 || mode == 3) { pick(2) }
        }
    }
}

@Composable
private fun ModeRow(icon: Int, label: String, chosen: Boolean, onClick: () -> Unit) {
    val rx = LocalRx.current
    Row(
        Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick)
            .semantics { contentDescription = label + if (chosen) ", chosen" else "" }.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(painterResource(icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.text))
        Text(label, Modifier.weight(1f).padding(start = 14.dp), color = rx.text, fontSize = 15.sp, fontFamily = RxFonts.body)
        if (chosen) Image(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(rx.accent))
    }
}
