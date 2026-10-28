package com.rainax.ytdownloader

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsControllerCompat

/**
 * RAINAX's own voice search screen: a dark page with a status pill (Listening / Searching / Try again),
 * your words appearing as you speak, a changing "Try saying" hint, and a glowing orb that moves with your
 * voice. When you stop talking, the words are searched.
 */
class VoicePage(private val act: AppCompatActivity, private val onResult: (String) -> Unit) {

    /** Drawn with Jetpack Compose (it replaced page_voice.xml); the glowing orb stays its own drawn view. */
    private val dialog = androidx.activity.ComponentDialog(act, R.style.Theme_Rainax_Page)
    private val orb = VoiceOrb(act)
    private var status by mutableStateOf("LISTENING")
    private var words by mutableStateOf<String?>(null)
    private var wordsColor by mutableIntStateOf(R.color.voice_hint)
    private var hint by mutableStateOf("")
    private var lang by mutableStateOf("")
    private var dotBlink by mutableStateOf(true)
    private var dotDim by mutableStateOf(false)
    private var bump by mutableIntStateOf(0)
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
            hintIndex = (hintIndex + 1) % hints.size
            hint = "Try saying  “${hints[hintIndex]}”"
            orb.postDelayed(this, 2800)
        }
    }

    fun show() {
        val view = ComposeView(act)
        view.setContent { RxTheme { Page() } }
        dialog.setContentView(view)
        dialog.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // the page's own colour (night or warm paper), with dark icons on the light one
            val bg = androidx.core.content.ContextCompat.getColor(act, R.color.voice_bg)
            w.statusBarColor = bg
            w.navigationBarColor = bg
            val night = Ui.isDark(act)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }
        hintIndex = (hints.indices).random()
        hint = "Try saying  “${hints[hintIndex]}”"
        lang = java.util.Locale.getDefault().displayLanguage.takeIf { it.isNotBlank() }?.let { "Listening in $it" }.orEmpty()
        dialog.setOnDismissListener { release() }
        dialog.show()
        orb.postDelayed(hintTick, 2800)
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
        override fun onRmsChanged(rmsdB: Float) { orb.setLevel(rmsdB) }
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
            orb.postDelayed({
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

    private fun setText(text: String?) {
        if (text.isNullOrBlank()) {
            words = null
            wordsColor = R.color.voice_hint
        } else {
            words = text
            wordsColor = R.color.voice_text
        }
    }

    private fun state(s: Int, error: String? = null) {
        listening = s == STATE_LISTENING
        when (s) {
            STATE_LISTENING -> {
                status = "LISTENING"
                orb.mode = VoiceOrb.MODE_LISTENING
                dotBlink = true
                dotDim = false
            }
            STATE_THINKING -> {
                status = "SEARCHING"
                orb.mode = VoiceOrb.MODE_THINKING
                dotBlink = false
                dotDim = false
            }
            else -> {
                status = "TAP THE MIC TO TRY AGAIN"
                orb.mode = VoiceOrb.MODE_IDLE
                dotBlink = false
                dotDim = true
                words = error ?: "Didn't catch that"
                wordsColor = R.color.voice_text2
                bump++
            }
        }
    }

    // ---------- drawing ----------

    @Composable
    private fun Page() {
        val text = colorResource(R.color.voice_text)
        Box(Modifier.fillMaxSize().rxDrawableBg(R.drawable.bg_voice_page).systemBarsPadding()) {
            Box(Modifier.padding(8.dp).size(48.dp).clickable { dialog.dismiss() }.semantics { contentDescription = "Close" }.padding(12.dp)) {
                Image(painterResource(R.drawable.ic_close), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(text))
            }
            // ● Listening / Searching / Tap to try again
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 14.dp).height(36.dp).rxDrawableBg(R.drawable.bg_voice_status)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val blink = remember { Animatable(1f) }
                LaunchedEffect(dotBlink, dotDim) {
                    if (dotBlink) while (true) {
                        blink.animateTo(0.25f, tween(520)); blink.animateTo(1f, tween(520))
                    } else blink.snapTo(if (dotDim) 0.4f else 1f)
                }
                Box(Modifier.size(8.dp).alpha(blink.value).rxDrawableBg(R.drawable.bg_voice_dot))
                Text(status, Modifier.padding(start = 8.dp), color = text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.06.em, fontFamily = RxFonts.body)
            }
            // your words, live
            Text(
                (words ?: "Speak now…").uppercase(),
                Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 120.dp),
                color = colorResource(wordsColor), fontSize = 32.sp, lineHeight = 36.sp, fontFamily = RxFonts.display,
                textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis
            )
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(hint, Modifier.padding(horizontal = 32.dp), color = colorResource(R.color.voice_text2), fontSize = 14.sp,
                    fontFamily = RxFonts.body, textAlign = TextAlign.Center)
                Box(Modifier.padding(top = 8.dp).size(280.dp), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { (orb.parent as? android.view.ViewGroup)?.removeView(orb); orb }, modifier = Modifier.fillMaxSize())
                    val press = remember { Animatable(1f) }
                    LaunchedEffect(bump) { if (bump > 0) { press.animateTo(1.12f, tween(120)); press.animateTo(1f, tween(220)) } }
                    Box(
                        Modifier.size(84.dp).scale(press.value).clip(CircleShape).rxDrawableBg(R.drawable.bg_nav_fab)
                            .clickable { if (listening) recognizer?.stopListening() else start() }
                            .semantics { contentDescription = "Microphone" },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(painterResource(R.drawable.ic_mic), null, Modifier.size(34.dp), colorFilter = ColorFilter.tint(Color.White))
                    }
                }
                if (lang.isNotEmpty()) Text(lang, Modifier.padding(top = 4.dp), color = colorResource(R.color.voice_muted), fontSize = 12.sp,
                    fontFamily = RxFonts.body)
            }
        }
    }

    private fun release() {
        orb.removeCallbacks(hintTick)
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
