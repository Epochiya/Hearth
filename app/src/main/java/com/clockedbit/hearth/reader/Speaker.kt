package com.clockedbit.hearth.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Read-aloud using the tablet's built-in text-to-speech engine. */
class Speaker(ctx: Context) {
    private var ready = false
    private val main = Handler(Looper.getMainLooper())
    var onDone: () -> Unit = {}

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { onDone() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    fun speak(text: String, rate: Float): Boolean {
        if (!ready) return false
        val arabic = text.any { it in '؀'..'ۿ' }
        runCatching { tts.language = if (arabic) Locale("ar") else Locale.getDefault() }
        tts.setSpeechRate(rate)
        val max = TextToSpeech.getMaxSpeechInputLength() - 10
        tts.speak(if (text.length > max) text.substring(0, max) else text, TextToSpeech.QUEUE_FLUSH, null, "page")
        return true
    }

    fun stop() { runCatching { tts.stop() } }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
