package com.rainax.ytdownloader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * One colour in the accent picker: a thick ring of the colour with a hole in the middle (the page's own
 * background shows through). The chosen one gets a second, thin ring around it with a small gap.
 */
class AccentSwatch(context: Context, val color: Int, private val hole: Int) : View(context) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }

    var chosen = false
        set(value) {
            field = value
            isSelected = value
            invalidate()
        }

    init {
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((color and 0x00FFFFFF) or 0x33000000), null,
            android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape())
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f
        val r = outer - 6 * d                      // room for the "chosen" ring
        if (chosen) {
            ring.color = color
            canvas.drawCircle(cx, cy, outer - ring.strokeWidth / 2f, ring)
        }
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
        fill.color = hole
        canvas.drawCircle(cx, cy, r * 0.44f, fill)
    }

    companion object {
        /**
         * Fills [box] (a vertical LinearLayout) with the sixteen accents in rows of four, ticks [current],
         * and calls [onPick] with the accent's number when one is tapped.
         */
        fun fill(box: LinearLayout, current: Int, onPick: (Int) -> Unit) {
            val c = box.context
            val d = c.resources.displayMetrics.density
            box.removeAllViews()
            val tv = android.util.TypedValue()
            val hole = if (c.theme.resolveAttribute(R.attr.rxBg, tv, true)) {
                if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(c, tv.resourceId) else tv.data
            } else android.graphics.Color.WHITE
            val swatches = ArrayList<AccentSwatch>()
            Accents.GRID.chunked(4).forEach { rowIds ->
                val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                rowIds.forEach { id ->
                    val acc = Accents.of(id)
                    val sw = AccentSwatch(c, androidx.core.content.ContextCompat.getColor(c, acc.color), hole)
                    sw.chosen = id == current
                    sw.contentDescription = acc.name + if (id == current) ", chosen" else ""
                    sw.tooltipText = acc.name
                    sw.setOnClickListener {
                        swatches.forEach { it.chosen = it === sw }
                        Haptics.tick(sw)
                        onPick(id)
                    }
                    swatches += sw
                    row.addView(sw, LinearLayout.LayoutParams(0, (60 * d).toInt(), 1f))
                }
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
    }
}
