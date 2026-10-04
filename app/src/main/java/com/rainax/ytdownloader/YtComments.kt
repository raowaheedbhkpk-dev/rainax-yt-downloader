package com.rainax.ytdownloader

import org.json.JSONArray
import org.json.JSONObject

/**
 * Comments read the way YouTube's own site reads them: Top / Newest sorting, like and dislike,
 * replies and replying. Signed in, YouTube also returns the user's own comments first and their likes.
 * (The app falls back to the simple reader in [YtCatalog] when this one can't read a video.)
 */
object YtComments {

    /** One comment with what is needed to act on it. */
    class Item(
        val id: String,
        val author: String,
        val avatar: String?,
        val text: String,
        val date: String?,
        var likeCount: String?,
        var likeState: String,          // "LIKE", "DISLIKE", "NONE"
        val replyCount: Int,
        val repliesToken: String?,
        val likeAction: String?,
        val unlikeAction: String?,
        val dislikeAction: String?,
        val undislikeAction: String?,
        val replyParams: String?,
        val isReply: Boolean,
        val byCreator: Boolean,
        val mine: Boolean = false
    )

    class Result(
        val items: List<Item>,
        val next: String?,
        val count: String?,
        val sorts: List<Pair<String, String>>     // ("Top", token), ("Newest", token)
    )

    /** Blocking. First page of a video's comments. */
    fun first(videoId: String): Result {
        val page = YtAccount.request("next", JSONObject().put("context", YtAccount.context()).put("videoId", videoId), false)
        val section = YtAccount.findSection(page, "comment-item-section") ?: error("Comments are turned off")
        val token = (YtAccount.findValue(section, "continuationCommand") as? JSONObject)?.optString("token")
            ?.takeIf { it.isNotBlank() } ?: error("Comments are turned off")
        return more(token, false)
    }

    /** Blocking. The page behind a token: next page, another sort order, or a comment's replies. */
    fun more(token: String, replies: Boolean): Result {
        val json = YtAccount.request("next", JSONObject().put("context", YtAccount.context()).put("continuation", token), false)
        return parse(json, replies)
    }

    /** Blocking. Like / unlike / dislike / undislike with the comment's own action key (signed in). */
    fun act(action: String) {
        YtAccount.request(
            "comment/perform_comment_action",
            JSONObject().put("context", YtAccount.context()).put("actions", JSONArray().put(action)), true
        )
    }

    /** Blocking. Replies to a comment (signed in). */
    fun reply(replyParams: String, text: String) {
        YtAccount.request(
            "comment/create_comment_reply",
            JSONObject().put("context", YtAccount.context()).put("createReplyParams", replyParams).put("commentText", text), true
        )
    }

    // ---------- reading YouTube's answer ----------

    private fun parse(json: JSONObject, replies: Boolean): Result {
        // details of each comment live in "mutations", keyed by entityKey
        val entities = HashMap<String, JSONObject>()
        val mutations = (YtAccount.findValue(json, "mutations") as? JSONArray)
        if (mutations != null) for (i in 0 until mutations.length()) {
            val m = mutations.optJSONObject(i) ?: continue
            val key = m.optString("entityKey").takeIf { it.isNotBlank() } ?: continue
            m.optJSONObject("payload")?.let { entities[key] = it }
        }

        val items = mutableListOf<Item>()
        var next: String? = null
        var count: String? = null
        val sorts = mutableListOf<Pair<String, String>>()

        val actions = json.optJSONArray("onResponseReceivedEndpoints") ?: JSONArray()
        for (a in 0 until actions.length()) {
            val ep = actions.optJSONObject(a) ?: continue
            val list = ep.optJSONObject("reloadContinuationItemsCommand")?.optJSONArray("continuationItems")
                ?: ep.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems")
                ?: continue
            for (i in 0 until list.length()) {
                val node = list.optJSONObject(i) ?: continue
                node.optJSONObject("commentsHeaderRenderer")?.let { h ->
                    count = YtAccount.text(h.optJSONObject("countText"))?.let { t -> Regex("[\\d.,]+[KMB]?").find(t)?.value } ?: count
                    val menu = YtAccount.find(h, "sortFilterSubMenuRenderer")?.optJSONArray("subMenuItems")
                    if (menu != null) for (k in 0 until menu.length()) {
                        val it = menu.optJSONObject(k) ?: continue
                        val title = it.optString("title")
                        val tok = (YtAccount.findValue(it, "continuationCommand") as? JSONObject)?.optString("token")
                        if (title.isNotBlank() && !tok.isNullOrBlank()) {
                            sorts += (if (k == 0) "Top" else "Newest") to tok
                        }
                    }
                }
                node.optJSONObject("commentThreadRenderer")?.let { t ->
                    val vm = t.optJSONObject("commentViewModel")?.optJSONObject("commentViewModel")
                    val repToken = t.optJSONObject("replies")?.let { r ->
                        (YtAccount.findValue(r, "continuationCommand") as? JSONObject)?.optString("token")
                    }
                    val item = vm?.let { fromViewModel(it, entities, repToken, replies) }
                        ?: t.optJSONObject("comment")?.optJSONObject("commentRenderer")?.let { fromRenderer(it, repToken, false) }
                    item?.let { items += it }
                }
                // replies arrive as plain comments
                node.optJSONObject("commentViewModel")?.let { vm -> fromViewModel(vm, entities, null, true)?.let { items += it } }
                node.optJSONObject("commentRenderer")?.let { r -> fromRenderer(r, null, true)?.let { items += it } }
                node.optJSONObject("continuationItemRenderer")?.let { c ->
                    (YtAccount.findValue(c, "continuationCommand") as? JSONObject)?.optString("token")?.takeIf { it.isNotBlank() }?.let { next = it }
                }
            }
        }
        return Result(items, next, count, sorts)
    }

    private fun fromViewModel(vm: JSONObject, entities: Map<String, JSONObject>, repliesToken: String?, reply: Boolean): Item? {
        val entity = entities[vm.optString("commentKey")]?.optJSONObject("commentEntityPayload") ?: return null
        val props = entity.optJSONObject("properties") ?: return null
        val author = entity.optJSONObject("author")
        val toolbar = entity.optJSONObject("toolbar")
        val surface = entities[vm.optString("toolbarSurfaceKey")]?.optJSONObject("engagementToolbarSurfaceEntityPayload")
        val state = entities[vm.optString("toolbarStateKey")]?.optJSONObject("engagementToolbarStateEntityPayload")
        fun action(cmd: String): String? = surface?.optJSONObject(cmd)?.let {
            (YtAccount.findValue(it, "performCommentActionEndpoint") as? JSONObject)?.optString("action")?.takeIf { a -> a.isNotBlank() }
        }
        val like = when (state?.optString("likeState")) {
            "TOOLBAR_LIKE_STATE_LIKED" -> "LIKE"
            "TOOLBAR_LIKE_STATE_DISLIKED" -> "DISLIKE"
            else -> "NONE"
        }
        val likes = toolbar?.optString(if (like == "LIKE") "likeCountLiked" else "likeCountNotliked")?.trim()?.takeIf { it.isNotBlank() && it != "0" }
        val avatar = author?.optString("avatarThumbnailUrl")?.takeIf { it.isNotBlank() }
            ?: entity.optJSONObject("avatar")?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        return Item(
            id = props.optString("commentId"),
            author = author?.optString("displayName").orEmpty(),
            avatar = avatar,
            text = props.optJSONObject("content")?.optString("content").orEmpty(),
            date = props.optString("publishedTime").takeIf { it.isNotBlank() },
            likeCount = likes,
            likeState = like,
            replyCount = toolbar?.optString("replyCount")?.filter { it.isDigit() }?.toIntOrNull() ?: 0,
            repliesToken = repliesToken,
            likeAction = action("likeCommand"),
            unlikeAction = action("unlikeCommand"),
            dislikeAction = action("dislikeCommand"),
            undislikeAction = action("undislikeCommand"),
            replyParams = surface?.optJSONObject("replyCommand")?.let { YtAccount.findValue(it, "createReplyParams") as? String },
            isReply = reply || props.optInt("replyLevel", 0) > 0,
            byCreator = author?.optBoolean("isCreator") == true
        )
    }

    /** Older answer format (fewer actions). */
    private fun fromRenderer(r: JSONObject, repliesToken: String?, reply: Boolean): Item? {
        val id = r.optString("commentId").takeIf { it.isNotBlank() } ?: return null
        return Item(
            id = id,
            author = YtAccount.text(r.optJSONObject("authorText")).orEmpty(),
            avatar = r.optJSONObject("authorThumbnail")?.optJSONArray("thumbnails")?.let { it.optJSONObject(it.length() - 1)?.optString("url") },
            text = YtAccount.text(r.optJSONObject("contentText")).orEmpty(),
            date = YtAccount.text(r.optJSONObject("publishedTimeText")),
            likeCount = YtAccount.text(r.optJSONObject("voteCount")),
            likeState = if (r.optBoolean("isLiked")) "LIKE" else "NONE",
            replyCount = r.optInt("replyCount", 0),
            repliesToken = repliesToken,
            likeAction = null, unlikeAction = null, dislikeAction = null, undislikeAction = null,
            replyParams = null,
            isReply = reply,
            byCreator = false
        )
    }
}
