package com.rainax.ytdownloader

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rainax.ytdownloader.databinding.ActivityPrivateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Private folder screen. First time: choose PIN, pattern or password and enter it twice. Then: unlock, and see
 * the hidden videos and music (play, put back, delete). Locks again when you leave the app.
 * Screenshots and the recent-apps preview are blocked here.
 */
class PrivateActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        Ui.saneScale(newBase)?.let { runCatching { applyOverrideConfiguration(it) } }    // same clean sizes on every phone
    }

    private lateinit var b: ActivityPrivateBinding

    private enum class Step { CREATE, CONFIRM, UNLOCK, CHECK_OLD }

    private var step = Step.UNLOCK
    private var type = PrivateVault.PIN
    private var firstEntry: String? = null
    private var pin = StringBuilder()
    private var busy = false
    private var unlocked = false
    private var playing = false           // opened the player from here: don't lock on return
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        Ui.applyAccent(this)
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        b = ActivityPrivateBinding.inflate(layoutInflater)
        setContentView(b.root)

        buildPinPad()
        b.lockClose.setOnClickListener { finish() }
        b.vaultBack.setOnClickListener { finish() }
        b.vaultMenu.setOnClickListener { vaultMenu(it) }
        b.lockTypes.setOnCheckedStateChangeListener { _, ids ->
            type = when (ids.firstOrNull()) { R.id.typePattern -> PrivateVault.PATTERN; R.id.typePassword -> PrivateVault.PASSWORD; else -> PrivateVault.PIN }
            firstEntry = null
            showInput()
        }
        b.patternView.onPattern = { dots ->
            if (dots.size < 4 && (step == Step.CREATE)) {
                b.lockSub.text = "Connect at least 4 dots"
                b.patternView.showWrong()
            } else submit(dots.joinToString("-"))
        }
        b.passwordOk.setOnClickListener { submit(b.passwordInput.text.toString()) }
        b.passwordInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { submit(b.passwordInput.text.toString()); true } else false
        }
        b.lockForgot.setOnClickListener { confirmReset() }
        b.vaultList.layoutManager = LinearLayoutManager(this)
        b.vaultList.adapter = adapter
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        if (PrivateVault.hasLock(this)) startUnlock() else startCreate()
    }

    override fun onStart() {
        super.onStart()
        if (playing) { playing = false; return }
        // back from another app: lock again
        if (unlocked && PrivateVault.hasLock(this)) { unlocked = false; startUnlock() }
    }

    // ---------- lock steps ----------

    private fun startCreate() {
        step = Step.CREATE
        firstEntry = null
        b.lockTitle.text = "Set up Private folder"
        b.lockTypes.isVisible = true
        b.lockForgot.isVisible = false
        showInput()
    }

    private fun startUnlock() {
        step = Step.UNLOCK
        type = PrivateVault.lockType(this)
        b.lockTitle.text = "Private folder"
        b.lockTypes.isVisible = false
        b.lockForgot.isVisible = true
        showInput()
        val wait = PrivateVault.waitMs(this)
        if (wait > 0) tooManyTries(wait)
    }

    /** Shows the right input for [type] and the current step. */
    private fun showInput() {
        b.lockView.isVisible = true
        b.vaultView.isVisible = false
        pin.clear()
        updateDots()
        b.patternView.clear()
        b.passwordInput.setText("")
        val isPin = type == PrivateVault.PIN
        val isPattern = type == PrivateVault.PATTERN
        b.pinDots.isVisible = isPin
        b.pinPad.isVisible = isPin
        b.patternView.isVisible = isPattern
        b.passwordBox.isVisible = type == PrivateVault.PASSWORD
        val what = when (type) { PrivateVault.PATTERN -> "pattern"; PrivateVault.PASSWORD -> "password"; else -> "PIN" }
        b.lockSub.text = when (step) {
            Step.CREATE -> when (type) {
                PrivateVault.PATTERN -> "Draw a pattern (at least 4 dots)"
                PrivateVault.PASSWORD -> "Choose a password (at least 4 characters)"
                else -> "Choose a PIN (4 to 8 numbers)"
            }
            Step.CONFIRM -> "Enter the same $what again"
            Step.CHECK_OLD -> "Enter your current $what"
            Step.UNLOCK -> "Enter your $what"
        }
        b.passwordOk.text = if (step == Step.UNLOCK || step == Step.CHECK_OLD) "Unlock" else "Continue"
    }

    private fun buildPinPad() {
        val d = resources.displayMetrics.density
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        keys.forEach { k ->
            val key = TextView(this).apply {
                text = k
                gravity = Gravity.CENTER
                textSize = if (k.length == 1 && k[0].isDigit()) 26f else 22f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@PrivateActivity, R.color.rx_text))
                setBackgroundResource(R.drawable.bg_pin_key)
                contentDescription = when (k) { "⌫" -> "Delete"; "✓" -> "OK"; else -> k }
                setOnClickListener { onKey(k) }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = (72 * d).toInt(); height = (72 * d).toInt()
                val m = (9 * d).toInt()
                setMargins(m, m, m, m)
            }
            b.pinPad.addView(key, lp)
        }
    }

    private fun onKey(k: String) {
        if (busy) return
        when (k) {
            "⌫" -> if (pin.isNotEmpty()) pin.setLength(pin.length - 1)
            "✓" -> {
                if (pin.length < 4) { b.lockSub.text = "At least 4 numbers"; return }
                submit(pin.toString())
                return
            }
            else -> if (pin.length < 8) pin.append(k)
        }
        updateDots()
    }

    private fun updateDots() {
        b.pinDots.text = if (pin.isEmpty()) " " else "●".repeat(pin.length)
    }

    private fun submit(secret: String) {
        if (busy) return
        when (step) {
            Step.CREATE -> {
                if (type == PrivateVault.PASSWORD && secret.length < 4) { b.lockSub.text = "At least 4 characters"; return }
                firstEntry = secret
                step = Step.CONFIRM
                b.lockTypes.isVisible = false
                showInput()
            }
            Step.CONFIRM -> {
                if (secret != firstEntry) {
                    wrongInput("Didn't match. Start again")
                    step = Step.CREATE
                    b.lockTypes.isVisible = true
                    firstEntry = null
                    b.root.postDelayed({ if (step == Step.CREATE) showInput() }, 900)
                    return
                }
                setBusy(true)
                lifecycleScope.launch {
                    withContext(Dispatchers.Default) { PrivateVault.setLock(this@PrivateActivity, type, secret) }
                    setBusy(false)
                    Notify.show(this@PrivateActivity, "Private folder is ready")
                    openVault()
                }
            }
            Step.UNLOCK, Step.CHECK_OLD -> {
                val wait = PrivateVault.waitMs(this)
                if (wait > 0) { tooManyTries(wait); return }
                setBusy(true)
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.Default) { runCatching { PrivateVault.check(this@PrivateActivity, secret) }.getOrDefault(false) }
                    setBusy(false)
                    when {
                        ok && step == Step.CHECK_OLD -> startCreate()        // changing the lock: now the new one
                        ok -> openVault()
                        PrivateVault.waitMs(this@PrivateActivity) > 0 -> tooManyTries(PrivateVault.waitMs(this@PrivateActivity))
                        else -> wrongInput("Wrong. Try again")
                    }
                }
            }
        }
    }

    private fun wrongInput(text: String) {
        b.lockSub.text = text
        b.patternView.showWrong()
        pin.clear()
        updateDots()
        b.passwordInput.setText("")
        b.lockSub.animate().translationX(16f).setDuration(50).withEndAction {
            b.lockSub.animate().translationX(-16f).setDuration(50).withEndAction { b.lockSub.animate().translationX(0f).setDuration(50).start() }.start()
        }.start()
    }

    private fun tooManyTries(ms: Long) {
        b.lockSub.text = "Too many tries. Wait ${(ms / 1000) + 1} seconds"
        b.patternView.isEnabled = false
        b.root.postDelayed({
            b.patternView.isEnabled = true
            if (!unlocked) showInput()
        }, ms + 200)
    }

    private fun setBusy(on: Boolean) {
        busy = on
        b.lockBusy.visibility = if (on) View.VISIBLE else View.INVISIBLE
        b.passwordOk.isEnabled = !on
        b.patternView.isEnabled = !on
    }

    private fun confirmReset() {
        RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
            .setTitle("Forgot your lock?")
            .setMessage("For your privacy it can't be recovered. You can reset the Private folder, but everything in it is deleted for good.")
            .setPositiveButton("Delete all and reset") { _, _ ->
                PrivateVault.reset(this)
                Notify.show(this, "Private folder reset")
                startCreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- the folder ----------

    private fun openVault() {
        unlocked = true
        currentFocus?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        b.lockView.isVisible = false
        b.vaultView.isVisible = true
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { PrivateVault.items(this@PrivateActivity) }
            adapter.items = list
            adapter.notifyDataSetChanged()
            b.vaultEmpty.isVisible = list.isEmpty()
            val videos = list.count { !it.isAudio }
            val songs = list.size - videos
            b.vaultCount.text = if (list.isEmpty()) "" else listOfNotNull(
                videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
                songs.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
                formatSize(list.sumOf { it.size })
            ).joinToString(" • ")
        }
    }

    private fun vaultMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Change lock")
        m.menu.add(0, 2, 1, "Lock now")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> { unlocked = false; step = Step.CHECK_OLD; type = PrivateVault.lockType(this); b.lockTitle.text = "Change lock"; b.lockTypes.isVisible = false; b.lockForgot.isVisible = false; showInput() }
                2 -> { unlocked = false; startUnlock() }
            }
            true
        }
        m.show()
    }

    private fun play(index: Int) {
        val list = adapter.items
        playing = true
        PlayerActivity.open(
            this,
            list.map { android.net.Uri.fromFile(PrivateVault.file(this, it)).toString() },
            list.map { it.title },
            index
        )
    }

    private fun itemMenu(item: PrivateVault.Item, anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Play")
        m.menu.add(0, 2, 1, "Move back to Rainax Tube")
        m.menu.add(0, 3, 2, "Delete for good")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> play(adapter.items.indexOf(item).coerceAtLeast(0))
                2 -> lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { PrivateVault.moveOut(this@PrivateActivity, item) }.isSuccess }
                    Notify.show(this@PrivateActivity, if (ok) "Moved back to Rainax Tube" else "Couldn't move it back")
                    refresh()
                }
                3 -> RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
                    .setTitle("Delete for good?")
                    .setMessage("\"${item.title}\" is deleted from your phone. This can't be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { runCatching { PrivateVault.remove(this@PrivateActivity, item) } }
                            refresh()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        m.show()
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<PrivateVault.Item> = emptyList()
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_song_row, parent, false)
            v.findViewById<View>(R.id.songArtBox).clipToOutline = true
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val v = holder.itemView
            val art = v.findViewById<ImageView>(R.id.songArt)
            val thumb = PrivateVault.thumb(this@PrivateActivity, item)
            art.tag = item.id
            art.scaleType = ImageView.ScaleType.CENTER
            art.setImageResource(if (item.isAudio) R.drawable.ic_audio else R.drawable.ic_video)
            if (thumb.exists()) {
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeFile(thumb.path, BitmapFactory.Options().apply { inSampleSize = 2 }) }.getOrNull()
                    }
                    if (bmp != null && art.tag == item.id) { art.scaleType = ImageView.ScaleType.CENTER_CROP; art.setImageBitmap(bmp) }
                }
            }
            v.findViewById<TextView>(R.id.songTitle).text = item.title
            v.findViewById<TextView>(R.id.songSub).text = listOf(if (item.isAudio) "Music" else "Video", formatSize(item.size)).joinToString(" • ")
            v.findViewById<ImageButton>(R.id.songMore).setOnClickListener { itemMenu(item, it) }
            v.setOnClickListener { play(items.indexOf(item).coerceAtLeast(0)) }
        }
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, PrivateActivity::class.java))
    }
}
