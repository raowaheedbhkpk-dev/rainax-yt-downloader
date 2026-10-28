package com.rainax.ytdownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The top of a channel's page drawn with Jetpack Compose (it replaced item_channel_header.xml and item_chip.xml):
 * banner, picture, name, subscribers, description, Subscribe, and Videos / Shorts / Live / Playlists.
 */

class ChannelHeaderUi {
    var name by mutableStateOf("")
    var meta by mutableStateOf("")
    var desc by mutableStateOf("")
    var avatar by mutableStateOf<String?>(null)
    var banner by mutableStateOf<String?>(null)
    var subscribeShown by mutableStateOf(true)
    var subscribed by mutableStateOf(false)
    var tabs by mutableStateOf<List<String>>(emptyList())
    var tab by mutableStateOf<String?>(null)
}

@Composable
internal fun ChannelHeader(ui: ChannelHeaderUi, subscribe: () -> Unit, pickTab: (String) -> Unit) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Box(Modifier.fillMaxWidth().height(96.dp).background(rx.surfaceAlt)) {
            if (ui.banner != null) RxNetImage(ui.banner, Modifier.fillMaxSize(), widthPx = 1080)
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).rxDrawableBg(R.drawable.bg_avatar)) {
                RxNetImage(ui.avatar, Modifier.fillMaxSize(), circle = true, widthPx = 200)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(ui.name.uppercase(), color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.display, letterSpacing = 0.02.em,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ui.meta.isNotEmpty()) Text(ui.meta, Modifier.padding(top = 2.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
            }
        }
        if (ui.desc.isNotBlank()) {
            Text(ui.desc, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), color = rx.text2, fontSize = 14.sp,
                fontFamily = RxFonts.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (ui.subscribeShown) {
            val on = ui.subscribed
            Box(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().height(44.dp)
                    .background(if (on) rx.surfaceAlt else rx.text, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = subscribe),
                contentAlignment = Alignment.Center
            ) {
                Text(if (on) "Subscribed" else "Subscribe", color = if (on) rx.text else rx.bg, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, fontFamily = RxFonts.body)
            }
        }
        // Videos / Shorts / Live / Playlists, like YouTube's channel tabs
        if (ui.tabs.size > 1) {
            Row(
                Modifier.padding(top = 14.dp).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ui.tabs.forEach { label -> RxChip(label, ui.tab == label) { if (ui.tab != label) pickTab(label) } }
            }
        }
    }
}
