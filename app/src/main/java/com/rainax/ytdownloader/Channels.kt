package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** The signed-in account's subscribed channels (round pictures on Home and Subscriptions), kept for 30 minutes. */
object SubsChannels {
    @Volatile private var list: List<VideoItem> = emptyList()
    @Volatile private var at = 0L

    fun cached(): List<VideoItem> = list

    /** Blocking. The channels (from memory when fresh, unless [fresh]). */
    fun load(fresh: Boolean = false): List<VideoItem> {
        if (!fresh && list.isNotEmpty() && System.currentTimeMillis() - at < 30 * 60_000L) return list
        val l = YtAccount.browse("FEchannels", null).items.filter { it.isChannel }
        list = l
        at = System.currentTimeMillis()
        return l
    }

    fun clear() {
        list = emptyList()
        at = 0
    }
}

/** Row of round channel pictures with names (tap: the channel's page). */
class ChannelAvatarAdapter(private val onOpen: (VideoItem) -> Unit) : RecyclerView.Adapter<ChannelAvatarAdapter.VH>() {
    private var items: List<VideoItem> = emptyList()

    fun submit(list: List<VideoItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel_avatar, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        Img.load(holder.avatar, c.thumb, circle = true, widthPx = 160)
        holder.name.text = c.title
        holder.itemView.contentDescription = c.title
        holder.itemView.setOnClickListener { onOpen(c) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.caAvatar)
        val name: TextView = v.findViewById(R.id.caName)
    }
}
