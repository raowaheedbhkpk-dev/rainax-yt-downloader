package com.rainax.ytdownloader


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
