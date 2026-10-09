package com.rainax.ytdownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * Video list with a Download button on every row. [big] = large pictures (Home, related),
 * otherwise picture on the left (search). An optional [header] view sits on top (video page details).
 */
class VideoAdapter(
    private val big: Boolean,
    private val onOpen: (VideoItem) -> Unit,
    private val onDownload: (VideoItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<VideoItem>()

    var header: View? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Home feed only: an ad card after the first 3 videos, then after every 8 (AdMob native ads). */
    var feedAds = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val adsOn get() = feedAds && Ads.feedAdsEnabled
    private fun adCount(n: Int) = if (!adsOn || n < AD_FIRST) 0 else 1 + (n - AD_FIRST) / AD_EVERY
    private fun isAdPos(i: Int) = adsOn && (i == AD_FIRST || (i > AD_FIRST && (i - AD_FIRST) % (AD_EVERY + 1) == 0))
    /** How many ad cards come before list position [i] (also the ad's own number when [i] is an ad). */
    private fun adsBefore(i: Int) = if (!adsOn || i <= AD_FIRST) 0 else (i - AD_FIRST - 1) / (AD_EVERY + 1) + 1
    private fun itemAt(i: Int) = items[i - adsBefore(i)]
    private val contentCount get() = items.size + adCount(items.size)

    var loadingMore = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    val count get() = items.size

    fun submit(list: List<VideoItem>) {
        if (adsOn) Ads.resetNative()              // a new list gets fresh ads
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Adds the next page (skipping videos already shown). Returns how many were new. */
    fun append(list: List<VideoItem>): Int {
        val known = items.mapTo(HashSet()) { it.url }
        val fresh = list.filter { known.add(it.url) }
        items.addAll(fresh)
        notifyDataSetChanged()
        return fresh.size
    }

    fun all(): List<VideoItem> = items.toList()

    private val headerCount get() = if (header != null) 1 else 0

    override fun getItemCount() = headerCount + contentCount + if (loadingMore) 1 else 0

    override fun getItemViewType(position: Int): Int = when {
        header != null && position == 0 -> HEADER
        position >= headerCount + contentCount -> FOOTER
        isAdPos(position - headerCount) -> AD
        itemAt(position - headerCount).isChannel -> CHANNEL
        else -> ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            HEADER -> object : RecyclerView.ViewHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}
            FOOTER -> object : RecyclerView.ViewHolder(inf.inflate(R.layout.item_loading, parent, false)) {}
            CHANNEL -> ChannelRow(inf.inflate(R.layout.item_channel_row, parent, false))
            AD -> AdRow(inf.inflate(R.layout.item_native_ad, parent, false))
            else -> Row(inf.inflate(if (big) R.layout.item_video_big else R.layout.item_video_small, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is Row -> holder.bind(itemAt(position - headerCount))
            is ChannelRow -> holder.bind(itemAt(position - headerCount))
            is AdRow -> holder.bind(adsBefore(position - headerCount))
            else -> if (getItemViewType(position) == HEADER) {
                val box = holder.itemView as FrameLayout
                val h = header ?: return
                if (h.parent !== box) {
                    (h.parent as? ViewGroup)?.removeView(h)
                    box.removeAllViews()
                    box.addView(h, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
    }

    inner class Row(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.thumb)
        private val duration: TextView = v.findViewById(R.id.duration)
        private val title: TextView = v.findViewById(R.id.title)
        private val meta: TextView = v.findViewById(R.id.meta)
        private val download: ImageButton = v.findViewById(R.id.download)
        private val avatar: ImageView? = v.findViewById(R.id.avatar)      // big rows only
        private val watchBar: android.widget.ProgressBar? = v.findViewById(R.id.watchBar)

        init {
            (thumb.parent as View).clipToOutline = true      // rounded corners from the background shape
        }

        fun bind(item: VideoItem) {
            Img.load(thumb, item.thumb, widthPx = if (big) 720 else 360)
            title.text = item.title
            avatar?.let { Img.load(it, item.avatar, circle = true, widthPx = 96) }
            val parts = mutableListOf<String>()
            if (item.uploader.isNotBlank()) parts += item.uploader
            if (item.isPlaylist) {
                if (item.count > 0) parts += "${item.count} videos"
            } else {
                if (item.views >= 0) parts += YtCatalog.count(item.views) + if (item.seconds < 0) " watching" else " views"
                item.uploaded?.takeIf { it.isNotBlank() }?.let { parts += it }
            }
            meta.text = parts.joinToString(" • ")
            val badge = when {
                item.isPlaylist -> if (item.count > 0) "${item.count} videos" else "Playlist"
                item.isShort -> "SHORTS"
                else -> YtCatalog.duration(item.seconds)
            }
            // how much of it you watched (red bar, like YouTube)
            val watched = if (item.isPlaylist || item.isChannel) -1f else WatchHistory.progress(itemView.context, item.url)
            watchBar?.isVisible = watched > 0f
            if (watched > 0f) watchBar?.progress = (watched * 1000).toInt()
            duration.text = badge
            duration.isVisible = badge.isNotEmpty()
            duration.setBackgroundResource(if (item.seconds < 0) R.drawable.bg_live else R.drawable.bg_duration)
            itemView.setOnClickListener { onOpen(item) }
            download.setOnClickListener { onDownload(item) }
        }
    }

    /** An ad card (native ad). While no ad is loaded the card takes no space. */
    inner class AdRow(v: View) : RecyclerView.ViewHolder(v) {
        private val adView = v as com.google.android.gms.ads.nativead.NativeAdView
        private val headline: TextView = v.findViewById(R.id.adHeadline)
        private val body: TextView = v.findViewById(R.id.adBody)
        private val icon: ImageView = v.findViewById(R.id.adIcon)
        private val media: com.google.android.gms.ads.nativead.MediaView = v.findViewById(R.id.adMedia)
        private val cta: TextView = v.findViewById(R.id.adCta)

        fun bind(slot: Int) {
            val ad = Ads.nativeFor(itemView.context, slot)
            val lp = itemView.layoutParams
            if (ad == null) {
                lp.height = 0
                itemView.layoutParams = lp
                itemView.isVisible = false
                return
            }
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            itemView.layoutParams = lp
            itemView.isVisible = true
            headline.text = ad.headline
            adView.headlineView = headline
            body.text = ad.body.orEmpty()
            body.isVisible = !ad.body.isNullOrBlank()
            adView.bodyView = body
            val drawable = ad.icon?.drawable
            icon.setImageDrawable(drawable)
            icon.isVisible = drawable != null
            adView.iconView = icon
            ad.mediaContent?.let { media.mediaContent = it }
            adView.mediaView = media
            cta.text = ad.callToAction ?: "Open"
            cta.isVisible = !ad.callToAction.isNullOrBlank()
            adView.callToActionView = cta
            adView.setNativeAd(ad)
        }
    }

    /** A channel (search results, subscriptions): picture, name, subscribers. */
    inner class ChannelRow(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: ImageView = v.findViewById(R.id.chAvatar)
        private val name: TextView = v.findViewById(R.id.chName)
        private val meta: TextView = v.findViewById(R.id.chMeta)

        fun bind(item: VideoItem) {
            Img.load(avatar, item.thumb, circle = true, widthPx = 160)
            name.text = item.title
            meta.text = item.uploaded ?: listOfNotNull(
                item.views.takeIf { it >= 0 }?.let { YtCatalog.count(it) + " subscribers" },
                item.count.takeIf { it > 0 }?.let { "$it videos" }
            ).joinToString(" • ")
            itemView.setOnClickListener { onOpen(item) }
        }
    }

    companion object {
        private const val CHANNEL = 3
        private const val AD = 4
        private const val AD_FIRST = 3
        private const val AD_EVERY = 8
        private const val HEADER = 0
        private const val ITEM = 1
        private const val FOOTER = 2
    }
}
