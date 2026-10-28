package com.rainax.ytdownloader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/*
 * Progressive blur (iOS style): at the bottom of a screen the content behind the floating tab bar gets more and
 * more blurred towards the edge (about 20 px at the very bottom, nothing 80 px up), with a soft tint so the
 * bar stays readable. Not one blur with a hard edge: three blurred copies (light, medium, strong), each faded in
 * further down by a gradient mask, stacked into one smooth fade.
 *
 * The screen's content is drawn into a graphics layer once ([source]); the blurred copies draw that same layer,
 * so they follow the list as it scrolls. Blur needs Android 12+; older phones get the soft tint only.
 */

class ProgressiveBlurLayers(val source: GraphicsLayer, val blurs: List<GraphicsLayer>, val masks: List<GraphicsLayer>)

@Composable
fun rememberProgressiveBlur(): ProgressiveBlurLayers {
    val source = rememberGraphicsLayer()
    val b1 = rememberGraphicsLayer(); val b2 = rememberGraphicsLayer(); val b3 = rememberGraphicsLayer()
    val m1 = rememberGraphicsLayer(); val m2 = rememberGraphicsLayer(); val m3 = rememberGraphicsLayer()
    return remember(source) { ProgressiveBlurLayers(source, listOf(b1, b2, b3), listOf(m1, m2, m3)) }
}

private val RADII = listOf(4.dp, 10.dp, 20.dp)
/** Where each copy starts to show (0 = top of the blur zone, 1 = bottom edge). */
private val FADE_FROM = listOf(0f, 0.3f, 0.6f)

/**
 * Inside a drawWithContent: draws the content (kept in [p].source) and over its bottom [heightPx] the progressive
 * blur and [tint].
 */
fun DrawScope.drawWithProgressiveBlur(p: ProgressiveBlurLayers, heightPx: Float, tint: Color, content: DrawScope.() -> Unit) {
    p.source.record { content() }
    drawLayer(p.source)
    val w = size.width.toInt()
    val h = heightPx.toInt().coerceAtMost(size.height.toInt())
    if (w <= 0 || h <= 0) return
    val top = size.height - h
    val zone = IntSize(w, h)
    for (i in 0 until 3) {
        val blur = p.blurs[i]
        val r = RADII[i].toPx()
        blur.renderEffect = BlurEffect(r, r, TileMode.Clamp)
        blur.record(size = zone) { translate(top = -top) { drawLayer(p.source) } }
        val mask = p.masks[i]
        mask.compositingStrategy = CompositingStrategy.Offscreen
        val from = FADE_FROM[i]
        mask.record(size = zone) {
            drawLayer(blur)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, from to Color.Transparent, (from + 0.35f).coerceAtMost(1f) to Color.Black, 1f to Color.Black),
                blendMode = BlendMode.DstIn
            )
        }
        translate(top = top) { drawLayer(mask) }
    }
    // a soft tint of the page colour so the bar's icons stay readable over busy pictures
    translate(top = top) {
        drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = 0f), tint.copy(alpha = 0.55f))), size = Size(size.width, h.toFloat()))
    }
}
