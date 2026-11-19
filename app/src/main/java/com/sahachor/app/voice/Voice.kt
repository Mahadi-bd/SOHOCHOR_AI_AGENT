package com.sahachor.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Plain text for the voice: no links, no markdown symbols, a sensible length. */
object SpeechText {
    private val url = Regex("https?://\\S+")
    private val marks = Regex("[*_#`>~|]+")

    fun forSpeaking(text: String, max: Int = 500): String {
        val t = marks.replace(url.replace(text, ""), " ").replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = maxOf(cut.lastIndexOf('।'), cut.lastIndexOf('.'), cut.lastIndexOf('?'), cut.lastIndexOf('!'))
        return if (end > max / 2) cut.take(end + 1) else cut
    }
}

private fun problemText(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কিছু শুনতে পাইনি। মাইক্রোফোনের কাছে এসে আবার বলুন।"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ভয়েস চেনার জন্য ইন্টারনেট লাগছে। নেট ঠিক আছে কি না দেখুন।"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোনের অনুমতি দিন।"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ভয়েস সেবা ব্যস্ত আছে, একটু পরে চেষ্টা করুন।"
    SpeechRecognizer.ERROR_AUDIO -> "মাইক্রোফোন ব্যবহার করা যাচ্ছে না।"
    else -> "ভয়েস বুঝতে পারিনি। (কোড $code)"
}

/** Speech to text through the phone's own recognizer (Google's, which understands Bengali). Call from the main thread. */
class VoiceInput(private val ctx: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(lang: String, onText: (String) -> Unit, onFinalText: (String) -> Unit, onProblem: (String) -> Unit, onStopped: () -> Unit) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { onProblem(problemText(error)); onStopped() }
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) onText(t)
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (t.isNotEmpty()) onFinalText(t) else onProblem(problemText(SpeechRecognizer.ERROR_NO_MATCH))
                onStopped()
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.let { try { it.stopListening(); it.destroy() } catch (e: Exception) { /* already gone */ } }
        recognizer = null
    }
}

/** Reads answers aloud with the phone's text-to-speech (Bengali voice if installed, else the phone's default language). */
class Speaker(context: Context) {
    private val appCtx = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(appCtx) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                if (t.setLanguage(Locale("bn", "BD")) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.getDefault())
                ready = true
                val p = pending
                if (p != null) { pending = null; t.speak(p, TextToSpeech.QUEUE_FLUSH, null, "sahachor") }
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        ensure()
        val t = tts
        if (ready && t != null) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sahachor") else pending = text
    }

    fun stop() { pending = null; tts?.stop() }
}
