package com.sahachor.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Plain text for the voice: no links, no markdown symbols. Long text is split into chunks so nothing is cut off. */
object SpeechText {
    private val url = Regex("https?://\\S+")
    private val marks = Regex("[*_#`>~|]+")
    private val sentenceEnd = Regex("(?<=[।.?!])\\s+")

    fun forSpeaking(text: String, max: Int = 500): String {
        val t = marks.replace(url.replace(text, ""), " ").replace(Regex("\\s+"), " ").trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = maxOf(cut.lastIndexOf('।'), cut.lastIndexOf('.'), cut.lastIndexOf('?'), cut.lastIndexOf('!'))
        return if (end > max / 2) cut.take(end + 1) else cut
    }

    /** The whole answer, in pieces the speech engine accepts (it refuses very long input), split at sentence ends. */
    fun chunks(text: String, size: Int = 1200): List<String> {
        val clean = forSpeaking(text, Int.MAX_VALUE)
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= size) return listOf(clean)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in clean.split(sentenceEnd)) {
            var sentence = s
            while (sentence.length > size) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                out += sentence.take(size)
                sentence = sentence.drop(size)
            }
            if (cur.isNotEmpty() && cur.length + sentence.length + 1 > size) { out += cur.toString(); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(sentence)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
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

/**
 * Speech to text through the phone's own recognizer (Google's, which understands Bengali). Call from the main thread.
 *
 * The system recognizer stops by itself after a short pause or a few seconds, which cut long sentences off.
 * So this listens continuously: every time a segment ends it starts the next one and joins the text, until the user
 * taps stop, nothing is said for about two rounds, or two minutes have passed.
 */
class VoiceInput(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var userStopped = false
    private var startedAt = 0L
    private var silentRounds = 0
    private val acc = StringBuilder()
    private var lang = "bn-BD"
    private var onText: (String) -> Unit = {}
    private var onFinalText: (String) -> Unit = {}
    private var onProblem: (String) -> Unit = {}
    private var onStopped: () -> Unit = {}

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!t.isNullOrBlank()) onText((acc.toString() + " " + t).trim())
        }

        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
            if (t.isNotEmpty()) {
                if (acc.isNotEmpty()) acc.append(' ')
                acc.append(t)
                silentRounds = 0
                onText(acc.toString())
            } else {
                silentRounds++
            }
            continueOrFinish()
        }

        override fun onError(error: Int) {
            if (!active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    silentRounds++
                    if (silentRounds >= MAX_SILENT_ROUNDS) finish() else continueOrFinish()
                }
                else -> {
                    if (acc.isNotEmpty()) finish() else { active = false; shutdown(); onProblem(problemText(error)); onStopped() }
                }
            }
        }
    }

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(lang: String, onText: (String) -> Unit, onFinalText: (String) -> Unit, onProblem: (String) -> Unit, onStopped: () -> Unit) {
        cancel()
        this.lang = lang; this.onText = onText; this.onFinalText = onFinalText; this.onProblem = onProblem; this.onStopped = onStopped
        acc.setLength(0)
        active = true; userStopped = false; silentRounds = 0
        startedAt = SystemClock.elapsedRealtime()
        beginSegment()
    }

    /** The user pressed stop: finish what was said so far and deliver it. */
    fun stop() {
        if (!active) return
        userStopped = true
        try { recognizer?.stopListening() } catch (e: Exception) { /* finish below */ }
        main.postDelayed({ finish() }, 1200) // in case the engine reports nothing
    }

    /** Leave without delivering anything (screen closed). */
    fun cancel() {
        active = false
        shutdown()
    }

    private fun shutdown() {
        main.removeCallbacksAndMessages(null)
        recognizer?.let { try { it.destroy() } catch (e: Exception) { /* already gone */ } }
        recognizer = null
    }

    private fun beginSegment() {
        if (!active) return
        recognizer?.let { try { it.destroy() } catch (e: Exception) { /* ignore */ } }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L) // some engines honour longer pauses
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000L)
        r.startListening(intent)
    }

    private fun continueOrFinish() {
        if (!active) return
        if (userStopped || SystemClock.elapsedRealtime() - startedAt > MAX_SESSION_MS) finish()
        else if (silentRounds >= MAX_SILENT_ROUNDS) finish()
        else main.postDelayed({ beginSegment() }, 300)
    }

    private fun finish() {
        if (!active) return
        active = false
        shutdown()
        val text = acc.toString().trim()
        if (text.isNotEmpty()) onFinalText(text) else onProblem(problemText(SpeechRecognizer.ERROR_NO_MATCH))
        onStopped()
    }

    private companion object {
        const val MAX_SILENT_ROUNDS = 2
        const val MAX_SESSION_MS = 120_000L
    }
}

/** Reads answers aloud with the phone's text-to-speech (Bengali voice if installed, else the phone's default language). */
class Speaker(context: Context) {
    private val appCtx = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: List<String>? = null

    private fun play(t: TextToSpeech, chunks: List<String>) {
        chunks.forEachIndexed { i, c -> t.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "sahachor-$i") }
    }

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(appCtx) { status ->
            val t = tts
            if (status == TextToSpeech.SUCCESS && t != null) {
                if (t.setLanguage(Locale("bn", "BD")) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.getDefault())
                ready = true
                val p = pending
                if (p != null) { pending = null; play(t, p) }
            }
        }
    }

    /** Reads the whole text (in pieces), not only the first part. */
    fun speak(text: String) {
        val chunks = SpeechText.chunks(text)
        if (chunks.isEmpty()) return
        ensure()
        val t = tts
        if (ready && t != null) play(t, chunks) else pending = chunks
    }

    fun stop() { pending = null; tts?.stop() }
}
