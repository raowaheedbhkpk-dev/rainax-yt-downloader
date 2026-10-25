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
        val lists = mutableListOf<JSONArray>()
        allArrays(json, "mutations", lists)
        for (mutations in lists) for (i in 0 until mutations.length()) {
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
        // YouTube keeps the like/dislike keys in a few places that move between versions, so look everywhere
        val acts = HashMap<String, String>()
        surface?.let { collectActions(it, "", acts) }
        collectActions(vm, "", acts)
        collectActions(entity, "", acts)
        vm.keys().forEach { k -> vm.optString(k).takeIf { it.isNotBlank() }?.let { entities[it] }?.let { collectActions(it, "", acts) } }
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
            likeAction = acts["like"],
            unlikeAction = acts["unlike"],
            dislikeAction = acts["dislike"],
            undislikeAction = acts["undislike"],
            replyParams = (surface?.optJSONObject("replyCommand")?.let { YtAccount.findValue(it, "createReplyParams") as? String })
                ?: (surface?.let { YtAccount.findValue(it, "createReplyParams") as? String })
                ?: (YtAccount.findValue(vm, "createReplyParams") as? String),
            isReply = reply || props.optInt("replyLevel", 0) > 0,
            byCreator = author?.optBoolean("isCreator") == true
        )
    }

    /** Older answer format (fewer actions). */
    private fun fromRenderer(r: JSONObject, repliesToken: String?, reply: Boolean): Item? {
        val id = r.optString("commentId").takeIf { it.isNotBlank() } ?: return null
        val acts = HashMap<String, String>()
        collectActions(r.optJSONObject("actionButtons"), "", acts)
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
            likeAction = acts["like"], unlikeAction = acts["unlike"],
            dislikeAction = acts["dislike"], undislikeAction = acts["undislike"],
            replyParams = YtAccount.findValue(r.optJSONObject("actionButtons"), "createReplyParams") as? String,
            isReply = reply,
            byCreator = false
        )
    }

    /**
     * Finds every comment action key under [node] and files it as like / unlike / dislike / undislike
     * by the nearest name above it ("likeCommand", "dislikeButton", a toggled state, ...).
     */
    private fun collectActions(node: Any?, path: String, out: MutableMap<String, String>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("performCommentActionEndpoint")?.optString("action")?.takeIf { it.isNotBlank() }
                    ?.let { a -> classify(path)?.let { k -> out.putIfAbsent(k, a) } }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val lk = k.lowercase()
                    val p = when {
                        lk.startsWith("toggled") || lk.startsWith("ondeselect") -> (if (lk.contains("like")) lk else path) + "|un"
                        lk.contains("like") -> lk
                        else -> path
                    }
                    collectActions(node.opt(k), p, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collectActions(node.opt(i), path, out)
        }
    }

    private fun classify(path: String): String? {
        val toggled = path.endsWith("|un")
        val p = path.removeSuffix("|un")
        return when {
            p.contains("undislike") || p.contains("removedislike") -> "undislike"
            p.contains("dislike") -> if (toggled) "undislike" else "dislike"
            p.contains("unlike") || p.contains("removelike") -> "unlike"
            p.contains("like") -> if (toggled) "unlike" else "like"
            else -> null
        }
    }

    /** Every array stored under [key] anywhere in [node]. */
    private fun allArrays(node: Any?, key: String, out: MutableList<JSONArray>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONArray) out += v else allArrays(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) allArrays(node.opt(i), key, out)
        }
    }
}
