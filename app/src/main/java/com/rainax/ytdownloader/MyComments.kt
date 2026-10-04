package com.rainax.ytdownloader

import android.content.Context
import android.text.format.DateUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * Comments the user wrote from RAINAX. YouTube shows your own comments at the top only to you (signed in);
 * the app's comment list is read without the account, so it keeps them here and always shows them first.
 */
object MyComments {

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("my_comments", Context.MODE_PRIVATE)

    fun add(c: Context, videoId: String, text: String) {
        val arr = all(c)
        arr.put(JSONObject().put("v", videoId).put("t", text).put("at", System.currentTimeMillis()))
        // keep the newest 300
        val kept = JSONArray()
        val from = (arr.length() - 300).coerceAtLeast(0)
        for (i in from until arr.length()) kept.put(arr.get(i))
        prefs(c).edit().putString("list", kept.toString()).apply()
    }

    /** Your comments on this video, newest first, as list rows. */
    fun forVideo(c: Context, videoId: String): List<Comment> {
        val me = YtAccount.profile(c)
        val arr = all(c)
        val out = mutableListOf<Comment>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("v") != videoId) continue
            val at = o.optLong("at")
            out += Comment(
                author = me?.handle ?: me?.name ?: "You",
                avatar = me?.avatar,
                text = o.optString("t"),
                html = false,
                likes = null,
                date = DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            )
        }
        return out
    }

    private fun all(c: Context): JSONArray =
        runCatching { JSONArray(prefs(c).getString("list", "[]")) }.getOrDefault(JSONArray())
}
