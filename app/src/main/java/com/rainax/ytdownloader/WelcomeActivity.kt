package com.rainax.ytdownloader

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.rainax.ytdownloader.databinding.ActivityWelcomeBinding

/**
 * The welcome guide after the first install: a few pages on what RAINAX Tube does, swiped or with Next,
 * then "Get started" (or Skip). Shown once; the first-run questions (storage, updates) come right after.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }

    private class Page(val icon: Int, val tint: Boolean, val title: String, val text: String)

    private val pages = listOf(
        Page(R.drawable.ic_logo, false, "Welcome to RAINAX Tube",
            "Watch YouTube and save any video to your phone, in every quality up to 8K."),
        Page(R.drawable.ic_download, true, "One tap to download",
            "Tap Download under any video, choose Video or Audio and a quality. Sizes are ready at once."),
        Page(R.drawable.ic_link, true, "TikTok, Instagram and Facebook",
            "Share a link to RAINAX Tube or paste it on the Social page. Links you copy are found by themselves."),
        Page(R.drawable.ic_headphones, true, "Music and background play",
            "Save songs as MP3 and keep listening with the screen off. Search with your voice from the search box."),
        Page(R.drawable.ic_lock, true, "Private folder and WhatsApp",
            "Hide videos behind a PIN, pattern or password, and save WhatsApp statuses in one tap.")
    )

    private lateinit var b: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        Ui.applyAccent(this)
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        b = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.welcomePager.adapter = PagesAdapter()
        // swiping: the page going away shrinks and fades a little, the picture moves slower than the text
        b.welcomePager.setPageTransformer { page, pos ->
            val p = pos.coerceIn(-1f, 1f)
            val a = kotlin.math.abs(p)
            page.alpha = 1f - a * 0.6f
            val scale = 1f - a * 0.08f
            page.scaleX = scale
            page.scaleY = scale
            page.findViewById<View>(R.id.pageIcon)?.let { (it.parent as View).translationX = -p * page.width * 0.25f }
        }
        val d = resources.displayMetrics.density
        val dots = pages.indices.map {
            View(this).also { v ->
                b.welcomeDots.addView(v, LinearLayout.LayoutParams((8 * d).toInt(), (8 * d).toInt()).apply {
                    marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
                })
            }
        }
        fun show(pos: Int) {
            dots.forEachIndexed { i, v ->
                v.setBackgroundResource(if (i == pos) R.drawable.bg_welcome_dot_on else R.drawable.bg_welcome_dot)
                v.alpha = if (i == pos) 1f else 0.35f
                v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply { width = ((if (i == pos) 22 else 8) * d).toInt() }
            }
            val last = pos == pages.lastIndex
            b.welcomeNext.text = if (last) "Get started" else "Next"
            b.welcomeSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        }
        b.welcomePager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = show(position)
        })
        show(0)

        b.welcomeNext.setOnClickListener {
            val pos = b.welcomePager.currentItem
            if (pos < pages.lastIndex) b.welcomePager.currentItem = pos + 1 else finishGuide()
        }
        b.welcomeSkip.setOnClickListener { finishGuide() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val pos = b.welcomePager.currentItem
                if (pos > 0) b.welcomePager.currentItem = pos - 1 else finishGuide()
            }
        })
    }

    private fun finishGuide() {
        AppPrefs.setGuideSeen(this)
        setResult(RESULT_OK)
        finish()
    }

    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.pageIcon)
            val title: TextView = v.findViewById(R.id.pageTitle)
            val text: TextView = v.findViewById(R.id.pageText)
        }

        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_welcome_page, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = pages[position]
            holder.icon.setImageResource(p.icon)
            holder.icon.imageTintList = if (p.tint) ColorStateList.valueOf(Ui.accent(this@WelcomeActivity)) else null
            holder.title.text = p.title
            holder.text.text = p.text
        }
    }
}
