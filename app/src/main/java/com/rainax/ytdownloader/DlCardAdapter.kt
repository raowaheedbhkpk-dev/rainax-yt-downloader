package com.rainax.ytdownloader

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/** One quality card of the download sheet, or the "More Formats" card. */
sealed class DlCard {
    data class Format(
        val choice: FormatChoice,
        val tag: String,
        val quality: String,
        val size: String,
        val recommended: Boolean,
        val selected: Boolean
    ) : DlCard()

    data class More(val sub: String) : DlCard()
}

/** The download sheet's 2-column cards (Video or Audio): tap a card to select it. */
class DlCardAdapter(
    private val onPick: (FormatChoice) -> Unit,
    private val onMore: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<DlCard> = emptyList()

    fun submit(list: List<DlCard>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = if (items[position] is DlCard.More) MORE else CARD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == MORE) MoreVH(inf.inflate(R.layout.item_dl_more, parent, false))
        else CardVH(inf.inflate(R.layout.item_dl_card, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DlCard.More -> (holder as MoreVH).bind(item)
            is DlCard.Format -> (holder as CardVH).bind(item)
        }
    }

    private inner class MoreVH(v: View) : RecyclerView.ViewHolder(v) {
        private val box: View = v.findViewById(R.id.more)
        private val sub: TextView = v.findViewById(R.id.moreSub)
        fun bind(item: DlCard.More) {
            sub.text = item.sub
            box.setOnClickListener { onMore() }
        }
    }

    private inner class CardVH(v: View) : RecyclerView.ViewHolder(v) {
        private val card: View = v.findViewById(R.id.card)
        private val tag: TextView = v.findViewById(R.id.cardTag)
        private val quality: TextView = v.findViewById(R.id.cardQuality)
        private val size: TextView = v.findViewById(R.id.cardSize)
        private val check: ImageView = v.findViewById(R.id.cardCheck)
        private val badge: TextView = v.findViewById(R.id.cardBadge)

        fun bind(item: DlCard.Format) {
            val c = card.context
            fun col(id: Int) = ContextCompat.getColor(c, id)
            val d = c.resources.displayMetrics.density
            tag.text = item.tag
            quality.text = item.quality
            size.text = item.size
            size.setTextColor(if (item.selected) Ui.accentText(c) else col(R.color.dl_text2))
            check.isVisible = item.selected
            badge.isVisible = item.recommended
            // a ticket: the size sits on the tear-off stub. Selected: red edge on a soft card; recommended: gold edge
            val shape = when {
                item.selected -> TicketDrawable(androidx.core.graphics.ColorUtils.compositeColors(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(Ui.accent(c), 0x14), col(R.color.dl_card_bg)), Ui.accent(c), 2 * d, d, 40 * d)
                item.recommended -> TicketDrawable(col(R.color.dl_card_bg), col(R.color.dl_card_recommended), 2 * d, d, 40 * d)
                else -> TicketDrawable(col(R.color.dl_card_bg), col(R.color.dl_card_stroke), 1.5f * d, d, 40 * d)
            }
            val mask = GradientDrawable().apply { cornerRadius = 2 * d; setColor(0xFFFFFFFF.toInt()) }
            card.background = RippleDrawable(ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(Ui.accent(c), 0x22)), shape, mask)
            card.tag = if (item.selected) SELECTED_TAG else null
            card.elevation = 0f                       // (a ticket has notches: no square shadow behind it)
            card.contentDescription = listOf(item.quality, item.tag, item.size, if (item.recommended) "Recommended" else "",
                if (item.selected) "Selected" else "").filter { it.isNotBlank() }.joinToString(", ")
            card.setOnClickListener { onPick(item.choice) }
        }
    }

    companion object {
        private const val CARD = 0
        private const val MORE = 1
        /** The chosen card's tag: Download tears it off (see MainActivity / QuickSheet). */
        const val SELECTED_TAG = "rx_selected_ticket"

        /** Download pressed: the chosen ticket lifts, tilts and fades like a stub torn off, then [then] runs. */
        fun tear(list: android.view.View, then: () -> Unit) {
            Haptics.confirm(list)
            val t = list.findViewWithTag<android.view.View>(SELECTED_TAG)
            if (t == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) { then(); return }
            val d = t.resources.displayMetrics.density
            t.animate().rotation(-5f).translationY(-26 * d).alpha(0f).setDuration(240)
                .withEndAction { then() }.start()
        }
    }
}
