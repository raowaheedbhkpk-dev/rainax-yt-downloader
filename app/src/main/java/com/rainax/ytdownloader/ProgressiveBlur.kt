package com.rainax.ytdownloader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.dp

/*
 * Progressive blur (iOS style): at the bottom of a screen the content behind the floating tab bar gets more and
 * more blurred towards the edge (about 20 px at the very bottom, nothing 80 px up), with a soft tint so the
 * bar stays readable. Not one blur with a hard edge: three blurred copies (light, medium, strong), each faded in
 * further down by a gradient mask, stacked into one smooth fade.
 *
 * The screen draws its content into [source] (a graphics layer); each copy here draws that layer again through
 * a blur. They redraw whenever [tick] changes (the list scrolled). Blur needs Android 12+; older phones get the
 * soft tint only.
 */

private val RADII = listOf(4.dp, 10.dp, 20.dp)
/** Where each copy starts to show (0 = top of the blur zone, 1 = bottom edge). */
private val FADE_FROM = listOf(0f, 0.3f, 0.6f)

@Composable
fun ProgressiveBlurZone(source: GraphicsLayer, zoneTop: () -> Float, tick: () -> Any?, tint: Color, modifier: Modifier) {
    Box(modifier) {
        for (i in 0 until 3) {
            val from = FADE_FROM[i]
            val mask = Brush.verticalGradient(
                0f to Color.Transparent, from to Color.Transparent, (from + 0.35f).coerceAtMost(1f) to Color.Black, 1f to Color.Black
            )
            // the mask: this copy shows only from [from] down
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
            ) {
                // the blurred copy of what is under this zone
                Box(
                    Modifier.matchParentSize()
                        .graphicsLayer {
                            val r = RADII[i].toPx()
                            renderEffect = BlurEffect(r, r, TileMode.Clamp)
                            clip = true
                        }
                        .drawBehind {
                            tick()
                            translate(top = -zoneTop()) { drawLayer(source) }
                        }
                )
            }
        }
        // a soft tint of the page colour so the bar's icons stay readable over busy pictures
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0f), tint.copy(alpha = 0.5f)))))
    }
}
