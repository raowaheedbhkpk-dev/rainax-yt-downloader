package com.rainax.ytdownloader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * The Private folder screen drawn with Jetpack Compose (it replaced activity_private.xml): the lock (PIN, pattern
 * or password) and then the hidden videos and music. PrivateActivity keeps the logic.
 */

class PrivateUi {
    var unlocked by mutableStateOf(false)
    var title by mutableStateOf("")
    var sub by mutableStateOf("")
    /** PrivateVault.PIN / PATTERN / PASSWORD */
    var type by mutableStateOf<String>(PrivateVault.PIN)
    var typesShown by mutableStateOf(false)
    var forgotShown by mutableStateOf(false)
    var dots by mutableIntStateOf(0)
    var password by mutableStateOf("")
    var okText by mutableStateOf("Continue")
    var busy by mutableStateOf(false)
    var items by mutableStateOf<List<PrivateVault.Item>>(emptyList())
    var count by mutableStateOf("")
    var loaded by mutableStateOf(false)
    internal var shake by mutableIntStateOf(0)
    fun shakeSub() { shake++ }
}

class PrivateActions(
    val close: () -> Unit,
    val pickType: (String) -> Unit,
    val key: (String) -> Unit,
    val submitPassword: () -> Unit,
    val forgot: () -> Unit,
    val vaultMenu: () -> Unit,
    val play: (PrivateVault.Item) -> Unit,
    val itemMenu: (PrivateVault.Item) -> Unit
)

@Composable
internal fun PrivateScreen(ui: PrivateUi, pattern: PatternView, a: PrivateActions) {
    val rx = LocalRx.current
    Box(Modifier.fillMaxSize().background(rx.bg).systemBarsPadding()) {
        if (ui.unlocked) Vault(ui, a) else Lock(ui, pattern, a)
    }
}

@Composable
private fun Lock(ui: PrivateUi, pattern: PatternView, a: PrivateActions) {
    val rx = LocalRx.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(ui.shake) {
        if (ui.shake > 0) for (x in listOf(16f, -16f, 0f)) shake.animateTo(x, androidx.compose.animation.core.tween(50))
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.align(Alignment.Start).size(44.dp).clickable { a.close() }.semantics { contentDescription = "Close" }.padding(10.dp)) {
            Image(painterResource(R.drawable.ic_back), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
        }
        Box(Modifier.padding(top = 12.dp).size(72.dp).rxDrawableBg(R.drawable.bg_private_badge), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_lock), null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(Color.White))
        }
        Text(ui.title, Modifier.padding(top = 16.dp), color = rx.text, fontSize = 24.sp, fontFamily = RxFonts.body,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(ui.sub, Modifier.padding(top = 6.dp).offset { IntOffset(shake.value.dp.roundToPx(), 0) }, color = rx.text2, fontSize = 14.sp,
            fontFamily = RxFonts.body, textAlign = TextAlign.Center)

        // first time: which kind of lock
        if (ui.typesShown) {
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PrivateVault.PIN to "PIN", PrivateVault.PATTERN to "Pattern", PrivateVault.PASSWORD to "Password").forEach { (t, label) ->
                    RxChip(label, ui.type == t) { if (ui.type != t) a.pickType(t) }
                }
            }
        }

        when (ui.type) {
            PrivateVault.PATTERN -> AndroidView(
                factory = { (pattern.parent as? android.view.ViewGroup)?.removeView(pattern); pattern },
                update = { it.isEnabled = !ui.busy },
                modifier = Modifier.padding(top = 18.dp).fillMaxWidth().wrapContentHeight()
            )
            PrivateVault.PASSWORD -> PasswordBox(ui, a)
            else -> PinPad(ui, a)
        }

        Box(Modifier.padding(top = 16.dp).size(28.dp).alpha(if (ui.busy) 1f else 0f)) {
            CircularProgressIndicator(Modifier.fillMaxSize(), color = rx.accent, strokeWidth = 3.dp)
        }
        if (ui.forgotShown) {
            Text("Forgot?", Modifier.height(44.dp).clickable { a.forgot() }.padding(horizontal = 14.dp, vertical = 12.dp),
                color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
        }
    }
}

@Composable
private fun PinPad(ui: PrivateUi, a: PrivateActions) {
    val rx = LocalRx.current
    Text(if (ui.dots == 0) " " else "●".repeat(ui.dots), Modifier.padding(top = 22.dp).height(40.dp), color = rx.text,
        fontSize = 24.sp, letterSpacing = 0.5.em)
    Column(Modifier.padding(top = 10.dp)) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓")).forEach { row ->
            Row {
                row.forEach { k ->
                    Box(
                        Modifier.padding(9.dp).size(72.dp).rxDrawableBg(R.drawable.bg_pin_key).clip(RoundedCornerShape(36.dp))
                            .clickable { a.key(k) }
                            .semantics { contentDescription = when (k) { "⌫" -> "Delete"; "✓" -> "OK"; else -> k } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(k, color = rx.text, fontSize = if (k[0].isDigit()) 26.sp else 22.sp, fontFamily = RxFonts.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordBox(ui: PrivateUi, a: PrivateActions) {
    val rx = LocalRx.current
    Column(Modifier.padding(top = 24.dp).fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().height(52.dp).rxDrawableBg(R.drawable.bg_search).padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (ui.password.isEmpty()) Text("Password", color = rx.text2, fontSize = 16.sp, fontFamily = RxFonts.body)
            BasicTextField(
                value = ui.password,
                onValueChange = { ui.password = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(color = rx.text, fontSize = 16.sp, fontFamily = RxFonts.body),
                cursorBrush = SolidColor(rx.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { a.submitPassword() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Button(
            onClick = { a.submitPassword() }, enabled = !ui.busy,
            modifier = Modifier.padding(top = 14.dp).fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = rx.accent, contentColor = rx.onAccent)
        ) { Text(ui.okText, fontFamily = RxFonts.body, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Vault(ui: PrivateUi, a: PrivateActions) {
    val rx = LocalRx.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { a.close() }.semantics { contentDescription = "Back" }.padding(10.dp)) {
                Image(painterResource(R.drawable.ic_back), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
            }
            Text("Private folder", Modifier.weight(1f).padding(start = 6.dp), color = rx.text, fontSize = 24.sp,
                fontFamily = RxFonts.display, letterSpacing = 0.02.em)
            Box(Modifier.size(44.dp).clickable { a.vaultMenu() }.semantics { contentDescription = "More" }.padding(10.dp)) {
                Image(painterResource(R.drawable.ic_more), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text))
            }
        }
        if (ui.count.isNotEmpty()) {
            Text(ui.count, Modifier.padding(start = 18.dp, bottom = 6.dp), color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val items = ui.items
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items.size, key = { i -> items[i].id }) { i -> VaultRow(items[i], a) }
            }
            if (ui.loaded && items.isEmpty()) {
                Text(
                    "Nothing here yet.\n\nIn Downloads or Library, tap ⋮ on a video or song, then “Move to Private folder”. It disappears from your gallery and other apps.",
                    Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 36.dp), color = rx.text2, fontSize = 14.sp,
                    lineHeight = 20.sp, fontFamily = RxFonts.body, textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun VaultRow(item: PrivateVault.Item, a: PrivateActions) {
    val rx = LocalRx.current
    val ctx = LocalContext.current
    var thumb by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.id) {
        val f = PrivateVault.thumb(ctx, item)
        thumb = withContext(Dispatchers.IO) {
            if (!f.exists()) null
            else runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).clickable { a.play(item) }.semantics { contentDescription = item.title }.padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).rxDrawableBg(R.drawable.bg_song_art), contentAlignment = Alignment.Center) {
            val bmp = thumb
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Image(painterResource(if (item.isAudio) R.drawable.ic_audio else R.drawable.ic_video), null, Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(rx.text2))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(item.title, color = rx.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = RxFonts.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(if (item.isAudio) "Music" else "Video", formatSize(item.size)).joinToString(" • "), Modifier.padding(top = 2.dp),
                color = rx.text2, fontSize = 14.sp, fontFamily = RxFonts.body, maxLines = 1)
        }
        Box(Modifier.size(44.dp).clickable { a.itemMenu(item) }.semantics { contentDescription = "More" }.padding(10.dp)) {
            Image(painterResource(R.drawable.ic_more), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(rx.text2))
        }
    }
}
