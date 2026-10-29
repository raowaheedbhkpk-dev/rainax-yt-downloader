package com.rainax.ytdownloader

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Grey placeholders while lists load, drawn with Jetpack Compose (they replaced item_skeleton*.xml,
 * item_music_skeleton*.xml and the RxShimmer view): the shapes of what is coming, gently pulsing.
 */

/** The gentle pulse every placeholder on screen shares. */
@Composable
private fun pulse(): Float {
    val t = rememberInfiniteTransition(label = "skeleton")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeleton")
    return a
}

@Composable
private fun Block(modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(modifier.background(LocalRx.current.surfaceAlt, shape))
}

@Composable
private fun Line(width: Dp?, height: Dp, modifier: Modifier = Modifier) =
    Block((if (width == null) modifier.fillMaxWidth() else modifier.width(width)).height(height))

/** Placeholders for Home: a list of big white video cards. */
@Composable
internal fun FeedSkeleton() {
    // the shape of Home's white video cards (v20.0)
    val a = pulse()
    val rx = LocalRx.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxSize().alpha(a).padding(top = 4.dp)) {
        repeat(4) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth().clip(shape).background(rx.surface)) {
                Block(Modifier.fillMaxWidth().aspectRatio(16f / 9f), androidx.compose.ui.graphics.RectangleShape)
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, end = 56.dp, bottom = 14.dp)) {
                    Block(Modifier.size(38.dp), CircleShape)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Line(null, 14.dp)
                        Line(160.dp, 12.dp, Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

/** Placeholders for the Music tab: a shelf title and song rows. */
@Composable
internal fun MusicSkeleton() {
    val a = pulse()
    Column(Modifier.fillMaxWidth().alpha(a).padding(top = 20.dp, bottom = 8.dp)) {
        Line(180.dp, 22.dp, Modifier.padding(start = 16.dp, bottom = 12.dp))
        repeat(4) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 8.dp, end = 16.dp)) {
                Block(Modifier.size(52.dp).padding(0.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp, top = 12.dp)) {
                    Line(null, 14.dp)
                    Line(120.dp, 12.dp, Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
