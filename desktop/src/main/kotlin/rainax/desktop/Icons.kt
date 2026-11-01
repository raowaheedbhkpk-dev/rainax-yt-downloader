package rainax.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Simple line icons on a 24 grid (the same shapes as the phone app's). Tint them with Icon(tint = ...). */
object Ic {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} $cy a$r $r 0 1 0 ${2 * r} 0 a$r $r 0 1 0 ${-2 * r} 0"

    private fun line(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round
                )
            }
        }.build()

    private fun solid(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) }
        }.build()

    val Home by lazy { line("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    val Music by lazy { line("music", "M9 18V5l11-2v13", circle(6f, 18f, 3f), circle(17f, 16f, 3f)) }
    val Download by lazy { line("download", "M12 4v11m0 0-4-4m4 4 4-4M5 20h14") }
    val Settings by lazy {
        line("settings", circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    }
    val Search by lazy { line("search", circle(11f, 11f, 7f), "M20 20l-3.5-3.5") }
    val Back by lazy { line("back", "M15 5l-7 7 7 7") }
    val Play by lazy { solid("play", "M8 5v14l11-7z") }
    val Pause by lazy { solid("pause", "M7 5h4v14H7zM13 5h4v14h-4z") }
    val Folder by lazy { line("folder", "M3 7h6l2 2h10v10H3z") }
    val Retry by lazy { line("retry", "M4 12a8 8 0 1 0 2.3-5.7", "M4 4v4h4") }
    val Close by lazy { line("close", "M6 6l12 12M18 6 6 18") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7") }
    val Paste by lazy { line("paste", "M9 4h6v3H9z", "M8 5H6v15h12V5h-2") }
    val Link by lazy { line("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val Video by lazy { line("video", "M3 7h18v10H3z", "M10 9.5v5l4-2.5z") }
    val Open by lazy { line("open", "M14 4h6v6", "M20 4l-9 9", "M18 14v6H4V6h6") }
}
