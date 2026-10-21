package com.rainax.ytdownloader

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.rainax.ytdownloader.databinding.PageVoiceBinding

/**
 * RAINAX's own voice search screen: a dark page with a status pill (Listening / Searching / Try again),
 * your words appearing as you speak, a changing "Try saying" hint, and a glowing orb that moves with your
 * voice. When you stop talking, the words are searched.
 */
class VoicePage(private val act: AppCompatActivity, private val onResult: (String) -> Unit) {

    private val b = PageVoiceBinding.inflate(act.layoutInflater)
    private val dialog = Dialog(act, R.style.Theme_Rainax_Page)
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var done = false
    private var hintIndex = 0
    private val hints = listOf(
        "Nusrat Fateh Ali Khan qawwali", "latest Punjabi songs", "cricket highlights today",
        "funny cat videos", "naat sharif", "how to cook biryani", "new Pakistani drama"
    )
    private val hintTick = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            b.vHint.animate().alpha(0f).setDuration(220).withEndAction {
                hintIndex = (hintIndex + 1) % hints.size
                b.vHint.text = "Try saying  “${hints[hintIndex]}”"
                b.vHint.animate().alpha(1f).setDuration(260).start()
            }.start()
            b.vHint.postDelayed(this, 2800)
        }
    }

    fun show() {
        dialog.setContentView(b.root)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // the page's own colour (night or warm paper), with dark icons on the light one
            val bg = androidx.core.content.ContextCompat.getColor(act, R.color.voice_bg)
            w.statusBarColor = bg
            w.navigationBarColor = bg
            val night = (act.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(w, w.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }
        b.vClose.setOnClickListener { dialog.dismiss() }
        b.vMic.setOnClickListener { if (listening) recognizer?.stopListening() else start() }
        hintIndex = (hints.indices).random()
        b.vHint.text = "Try saying  “${hints[hintIndex]}”"
        b.vLang.text = java.util.Locale.getDefault().displayLanguage.takeIf { it.isNotBlank() }?.let { "Listening in $it" }.orEmpty()
        dialog.setOnDismissListener { release() }
        dialog.show()
        b.vHint.postDelayed(hintTick, 2800)
        start()
    }

    private fun start() {
        done = false
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(act)
        recognizer = r
        r.setRecognitionListener(listener)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
        setText(null)
        state(STATE_LISTENING)
        runCatching { r.startListening(i) }.onFailure { state(STATE_ERROR, "Voice search isn't available on this phone") }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { state(STATE_LISTENING) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { b.vOrb.setLevel(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { state(STATE_THINKING) }
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let { setText(it) }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (text.isNullOrBlank()) { state(STATE_ERROR, "Didn't catch that"); return }
            done = true
            setText(text)
            state(STATE_THINKING)
            // a short look at what was heard, then search it
            b.root.postDelayed({
                if (dialog.isShowing) { dialog.dismiss(); onResult(text) }
            }, 450)
        }

        override fun onError(error: Int) {
            if (done) return
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "No internet for voice search"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to search by voice"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Try again"
                else -> "Something went wrong"
            }
            state(STATE_ERROR, msg)
        }
    }

    private fun color(id: Int) = androidx.core.content.ContextCompat.getColor(act, id)

    private fun setText(text: String?) {
        if (text.isNullOrBlank()) {
            b.vText.text = "Speak now…"
            b.vText.setTextColor(color(R.color.voice_hint))
        } else {
            b.vText.text = text
            b.vText.setTextColor(color(R.color.voice_text))
        }
    }

    private fun state(s: Int, error: String? = null) {
        listening = s == STATE_LISTENING
        b.vDot.animate().cancel()
        when (s) {
            STATE_LISTENING -> {
                b.vStatus.text = "LISTENING"
                b.vOrb.mode = VoiceOrb.MODE_LISTENING
                blinkDot()
            }
            STATE_THINKING -> {
                b.vStatus.text = "SEARCHING"
                b.vOrb.mode = VoiceOrb.MODE_THINKING
                b.vDot.alpha = 1f
            }
            else -> {
                b.vStatus.text = "TAP THE MIC TO TRY AGAIN"
                b.vOrb.mode = VoiceOrb.MODE_IDLE
                b.vDot.alpha = 0.4f
                b.vText.text = error ?: "Didn't catch that"
                b.vText.setTextColor(color(R.color.voice_text2))
                Motion.bounce(b.vMic)
            }
        }
    }

    private fun blinkDot() {
        b.vDot.animate().alpha(if (b.vDot.alpha > 0.6f) 0.25f else 1f).setDuration(520)
            .withEndAction { if (listening && dialog.isShowing) blinkDot() }.start()
    }

    private fun release() {
        b.vHint.removeCallbacks(hintTick)
        listening = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private companion object {
        const val STATE_LISTENING = 0
        const val STATE_THINKING = 1
        const val STATE_ERROR = 2
    }
}
