package com.rainax.ytdownloader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unlocks YouTube stream addresses ("n" and signature challenges) by running YouTube's own player code,
 * the way yt-dlp does today: its public-domain solver (yt-dlp-ejs, in assets/ejs) runs inside an invisible
 * WebView (Chrome's JavaScript engine, already on every phone). Needed for the embedded/TV players that
 * "made for kids" videos use; the old pattern-matching way no longer finds YouTube's functions.
 */
object JsSolver {

    class Player(val id: String, val sts: Int?)

    private class Prepared(val code: String, val preprocessed: Boolean, val sts: Int?)

    private val players = ConcurrentHashMap<String, Prepared>()
    private const val TIMEOUT_S = 40L

    private fun ctx(): Context = RainaxApp.app ?: error("App not ready")

    /** The player code's version number YouTube wants in player requests (loads the player code once). */
    @Synchronized
    fun prepare(playerId: String): Player {
        players[playerId]?.let { return Player(playerId, it.sts) }
        val js = get("https://www.youtube.com/s/player/$playerId/player_ias.vflset/en_US/base.js")
        val sts = Regex("(?:signatureTimestamp|sts)\\s*:\\s*([0-9]{5})").find(js)?.groupValues?.get(1)?.toIntOrNull()
        if (players.size > 3) players.clear()
        players[playerId] = Prepared(js, false, sts)
        return Player(playerId, sts)
    }

    /**
     * Blocking (not on the main thread). Solves the given challenges with player [playerId]
     * (after [prepare]). Returns n -> solved and signature -> solved.
     */
    fun solve(playerId: String, n: Collection<String>, sig: Collection<String>): Pair<Map<String, String>, Map<String, String>> {
        if (n.isEmpty() && sig.isEmpty()) return emptyMap<String, String>() to emptyMap()
        val p = players[playerId] ?: run { prepare(playerId); players.getValue(playerId) }
        val requests = JSONArray()
        if (n.isNotEmpty()) requests.put(JSONObject().put("type", "n").put("challenges", JSONArray(n.toList())))
        if (sig.isNotEmpty()) requests.put(JSONObject().put("type", "sig").put("challenges", JSONArray(sig.toList())))
        val data = if (p.preprocessed) {
            JSONObject().put("type", "preprocessed").put("preprocessed_player", p.code).put("requests", requests)
        } else {
            JSONObject().put("type", "player").put("player", p.code).put("requests", requests).put("output_preprocessed", true)
        }
        val out = JSONObject(run(data.toString()))
        if (out.optString("type") == "error") error("Solver: " + out.optString("error").take(200))
        // the trimmed player code makes the next solve faster
        out.optString("preprocessed_player").takeIf { it.isNotEmpty() }?.let { players[playerId] = Prepared(it, true, p.sts) }
        val responses = out.optJSONArray("responses") ?: error("Solver: no answer")
        val nOut = HashMap<String, String>()
        val sigOut = HashMap<String, String>()
        var i = 0
        if (n.isNotEmpty()) collect(responses.optJSONObject(i++), nOut)
        if (sig.isNotEmpty()) collect(responses.optJSONObject(i), sigOut)
        return nOut to sigOut
    }

    private fun collect(r: JSONObject?, into: MutableMap<String, String>) {
        if (r == null || r.optString("type") != "result") return
        val d = r.optJSONObject("data") ?: return
        d.keys().forEach { k -> into[k] = d.optString(k) }
    }

    private class Bridge(val input: String, val latch: CountDownLatch) {
        @Volatile var result: String? = null

        @JavascriptInterface
        fun data(): String = input

        @JavascriptInterface
        fun done(value: String) {
            result = value
            latch.countDown()
        }
    }

    /** Runs the solver on [input] (JSON) in an invisible WebView and returns its JSON answer. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun run(input: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) error("Solver can't run on the main thread")
        val c = ctx()
        val lib = c.assets.open("ejs/lib.min.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val core = c.assets.open("ejs/core.min.js").use { it.readBytes().toString(Charsets.UTF_8) }
        val latch = CountDownLatch(1)
        val bridge = Bridge(input, latch)
        val script = lib + "\n;Object.assign(globalThis, lib);\n" + core + """
            ;(function(){
              try { RX.done(JSON.stringify(jsc(JSON.parse(RX.data())))); }
              catch (e) { RX.done(JSON.stringify({type: "error", error: String(e && e.stack || e)})); }
            })();
        """.trimIndent()
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null
        main.post {
            try {
                val w = WebView(c.applicationContext)
                web = w
                w.settings.javaScriptEnabled = true
                w.addJavascriptInterface(bridge, "RX")
                w.webViewClient = object : WebViewClient() {
                    private var started = false
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (started) return
                        started = true
                        view.evaluateJavascript(script, null)
                    }
                }
                w.loadDataWithBaseURL("https://www.youtube.com/", "<html><body></body></html>", "text/html", "utf-8", null)
            } catch (e: Throwable) {
                bridge.result = JSONObject().put("type", "error").put("error", "WebView: ${e.message}").toString()
                latch.countDown()
            }
        }
        try {
            if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) error("Solver took too long")
            return bridge.result ?: error("Solver gave no answer")
        } finally {
            main.post { runCatching { web?.destroy() } }
        }
    }

    private fun get(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 15_000
            con.readTimeout = 30_000
            con.setRequestProperty("User-Agent", FastExtractor.UA)
            if (con.responseCode != 200) error("Player code: HTTP ${con.responseCode}")
            return con.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            con.disconnect()
        }
    }

}
