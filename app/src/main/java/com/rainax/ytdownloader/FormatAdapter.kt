package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rainax.ytdownloader.databinding.ItemFormatBinding
import com.rainax.ytdownloader.databinding.ItemNavBinding
import com.rainax.ytdownloader.databinding.ItemSectionBinding

sealed class FormatRow {
    data class Section(val text: String) : FormatRow()
    data class Choice(val choice: FormatChoice, val selected: Boolean, val detailed: Boolean) : FormatRow()
    data class Nav(val id: String, val title: String, val value: String, val iconRes: Int?) : FormatRow()
}

/** Rows of the download sheet: section headers, radio choices and "More formats" style links. */
class FormatAdapter(
    private val onChoice: (FormatChoice) -> Unit,
    private val onNav: (String) -> Unit
) : ListAdapter<FormatRow, RecyclerView.ViewHolder>(Diff) {

    private class SectionVH(val b: ItemSectionBinding) : RecyclerView.ViewHolder(b.root)
    private class ChoiceVH(val b: ItemFormatBinding) : RecyclerView.ViewHolder(b.root)
    private class NavVH(val b: ItemNavBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is FormatRow.Section -> 0
        is FormatRow.Choice -> 1
        is FormatRow.Nav -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> SectionVH(ItemSectionBinding.inflate(inflater, parent, false))
            1 -> ChoiceVH(ItemFormatBinding.inflate(inflater, parent, false))
            else -> NavVH(ItemNavBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is FormatRow.Section -> (holder as SectionVH).b.sectionText.text = row.text
            is FormatRow.Choice -> {
                val b = (holder as ChoiceVH).b
                val c = row.choice
                when (c.kind) {
                    KIND_AUDIO -> { b.fIcon.isVisible = true; b.fIcon.setImageResource(R.drawable.ic_audio) }
                    KIND_VIDEO -> { b.fIcon.isVisible = true; b.fIcon.setImageResource(R.drawable.ic_play_box) }
                    else -> b.fIcon.isVisible = false
                }
                b.fTitle.text = c.title
                b.fBadge.isVisible = row.detailed && c.badge != null
                b.fBadge.text = c.badge.orEmpty()
                b.fDesc.isVisible = row.detailed && c.desc.isNotBlank()
                b.fDesc.text = c.desc
                b.fSize.text = c.size
                b.fRadio.isChecked = row.selected
                b.root.setOnClickListener { onChoice(c) }
            }
            is FormatRow.Nav -> {
                val b = (holder as NavVH).b
                b.navTitle.text = row.title
                b.navValue.text = row.value
                if (row.iconRes != null) {
                    b.navIcon.isVisible = true
                    b.navIcon.setImageResource(row.iconRes)
                } else {
                    b.navIcon.isVisible = false
                }
                b.root.setOnClickListener { onNav(row.id) }
            }
        }
    }

    private object Diff : DiffUtil.ItemCallback<FormatRow>() {
        override fun areItemsTheSame(a: FormatRow, b: FormatRow): Boolean = when {
            a is FormatRow.Section && b is FormatRow.Section -> a.text == b.text
            a is FormatRow.Choice && b is FormatRow.Choice -> a.choice.spec == b.choice.spec
            a is FormatRow.Nav && b is FormatRow.Nav -> a.id == b.id
            else -> false
        }

        override fun areContentsTheSame(a: FormatRow, b: FormatRow) = a == b
    }
}
