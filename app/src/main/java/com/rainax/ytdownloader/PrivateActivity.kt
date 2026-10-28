package com.rainax.ytdownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

    /** What the screen shows (Compose, PrivateScreen.kt). */
    private val ui = PrivateUi()
    private lateinit var patternView: PatternView
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private enum class Step { CREATE, CONFIRM, UNLOCK, CHECK_OLD }

    private var step = Step.UNLOCK
    private var type = PrivateVault.PIN
    private var firstEntry: String? = null
    private var pin = StringBuilder()
    private var busy = false
    private var unlocked = false
    private var playing = false           // opened the player from here: don't lock on return

    override fun onCreate(savedInstanceState: Bundle?) {
        if (AppPrefs.themeMode(this) == 3) setTheme(R.style.Theme_Rainax_Amoled)
        Ui.applyAccent(this)
        super.onCreate(savedInstanceState)
        Ui.applyAccent(this)      // again: for a chosen Light/Dark theme AppCompat re-applies the base theme here, which dropped the accent
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        patternView = PatternView(this)
        patternView.onPattern = { dots ->
            if (dots.size < 4 && (step == Step.CREATE)) {
                ui.sub = "Connect at least 4 dots"
                patternView.showWrong()
            } else submit(dots.joinToString("-"))
        }
        val actions = PrivateActions(
            close = { finish() },
            pickType = { t ->
                type = t
                firstEntry = null
                showInput()
            },
            key = { onKey(it) },
            submitPassword = { submit(ui.password) },
            forgot = { confirmReset() },
            vaultMenu = { vaultMenu() },
            play = { item -> play(ui.items.indexOf(item).coerceAtLeast(0)) },
            itemMenu = { itemMenu(it) }
        )
        setContent { RxTheme { PrivateScreen(ui, patternView, actions) } }
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
        ui.title = "Set up Private folder"
        ui.typesShown = true
        ui.forgotShown = false
        showInput()
    }

    private fun startUnlock() {
        step = Step.UNLOCK
        type = PrivateVault.lockType(this)
        ui.title = "Private folder"
        ui.typesShown = false
        ui.forgotShown = true
        showInput()
        val wait = PrivateVault.waitMs(this)
        if (wait > 0) tooManyTries(wait)
    }

    /** Shows the right input for [type] and the current step. */
    private fun showInput() {
        ui.unlocked = false
        ui.type = type
        pin.clear()
        updateDots()
        patternView.clear()
        ui.password = ""
        val what = when (type) { PrivateVault.PATTERN -> "pattern"; PrivateVault.PASSWORD -> "password"; else -> "PIN" }
        ui.sub = when (step) {
            Step.CREATE -> when (type) {
                PrivateVault.PATTERN -> "Draw a pattern (at least 4 dots)"
                PrivateVault.PASSWORD -> "Choose a password (at least 4 characters)"
                else -> "Choose a PIN (4 to 8 numbers)"
            }
            Step.CONFIRM -> "Enter the same $what again"
            Step.CHECK_OLD -> "Enter your current $what"
            Step.UNLOCK -> "Enter your $what"
        }
        ui.okText = if (step == Step.UNLOCK || step == Step.CHECK_OLD) "Unlock" else "Continue"
    }

    private fun onKey(k: String) {
        if (busy) return
        when (k) {
            "⌫" -> if (pin.isNotEmpty()) pin.setLength(pin.length - 1)
            "✓" -> {
                if (pin.length < 4) { ui.sub = "At least 4 numbers"; return }
                submit(pin.toString())
                return
            }
            else -> if (pin.length < 8) pin.append(k)
        }
        updateDots()
    }

    private fun updateDots() {
        ui.dots = pin.length
    }

    private fun submit(secret: String) {
        if (busy) return
        when (step) {
            Step.CREATE -> {
                if (type == PrivateVault.PASSWORD && secret.length < 4) { ui.sub = "At least 4 characters"; return }
                firstEntry = secret
                step = Step.CONFIRM
                ui.typesShown = false
                showInput()
            }
            Step.CONFIRM -> {
                if (secret != firstEntry) {
                    wrongInput("Didn't match. Start again")
                    step = Step.CREATE
                    ui.typesShown = true
                    firstEntry = null
                    handler.postDelayed({ if (step == Step.CREATE) showInput() }, 900)
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
        ui.sub = text
        patternView.showWrong()
        pin.clear()
        updateDots()
        ui.password = ""
        ui.shakeSub()
    }

    private fun tooManyTries(ms: Long) {
        ui.sub = "Too many tries. Wait ${(ms / 1000) + 1} seconds"
        patternView.isEnabled = false
        handler.postDelayed({
            patternView.isEnabled = true
            if (!unlocked) showInput()
        }, ms + 200)
    }

    private fun setBusy(on: Boolean) {
        busy = on
        ui.busy = on
        patternView.isEnabled = !on
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
        ui.unlocked = true
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { PrivateVault.items(this@PrivateActivity) }
            ui.items = list
            ui.loaded = true
            val videos = list.count { !it.isAudio }
            val songs = list.size - videos
            ui.count = if (list.isEmpty()) "" else listOfNotNull(
                videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
                songs.takeIf { it > 0 }?.let { if (it == 1) "1 song" else "$it songs" },
                formatSize(list.sumOf { it.size })
            ).joinToString(" • ")
        }
    }

    private fun vaultMenu() {
        RxOptions.sheet(this, "Private folder", listOf(
            RxOption("Change lock", R.drawable.ic_lock) {
                unlocked = false
                step = Step.CHECK_OLD
                type = PrivateVault.lockType(this)
                ui.title = "Change lock"
                ui.typesShown = false
                ui.forgotShown = false
                showInput()
            },
            RxOption("Lock now", R.drawable.ic_lock_open) { unlocked = false; startUnlock() }
        ))
    }

    private fun play(index: Int) {
        val list = ui.items
        playing = true
        PlayerActivity.open(
            this,
            list.map { android.net.Uri.fromFile(PrivateVault.file(this, it)).toString() },
            list.map { it.title },
            index
        )
    }

    private fun itemMenu(item: PrivateVault.Item) {
        RxOptions.sheet(this, item.title, listOf(
            RxOption("Play", R.drawable.ic_play) { play(ui.items.indexOf(item).coerceAtLeast(0)) },
            RxOption("Move back to Rainax Tube", R.drawable.ic_lock_open) {
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { PrivateVault.moveOut(this@PrivateActivity, item) }.isSuccess }
                    Notify.show(this@PrivateActivity, if (ok) "Moved back to Rainax Tube" else "Couldn't move it back")
                    refresh()
                }
            },
            RxOption("Delete for good", R.drawable.ic_delete) {
                RxDialogBuilder(this, R.style.ThemeOverlay_Rainax_Dialog)
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
        ))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, PrivateActivity::class.java))
    }
}
