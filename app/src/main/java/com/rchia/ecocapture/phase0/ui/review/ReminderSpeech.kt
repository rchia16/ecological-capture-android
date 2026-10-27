package com.rchia.ecocapture.phase0.ui.review

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.rchia.ecocapture.phase0.feedback.offlineEnglishVoice
import java.util.UUID

/** User-initiated speech only. No reminder text is logged or used as an utterance ID. */
class ReminderSpeech(context: Context, private val onAiStarted: () -> Unit,
    private val onAvailability: (Boolean) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { if (it <= 0) stop() }.build()
    private var ready = false
    private var closed = false
    private var activePrefix: String? = null
    private lateinit var tts: TextToSpeech
    init {
        tts = TextToSpeech(context) { status ->
            Handler(Looper.getMainLooper()).post {
                if (closed) return@post
                val voice = if (status == TextToSpeech.SUCCESS) tts.offlineEnglishVoice() else null
                ready = voice != null && runCatching { tts.voice = voice }.isSuccess
                onAvailability(ready)
            }
        }
    }

    fun speak(intro: String, aiText: String?) {
        if (!ready) return
        val voice = tts.offlineEnglishVoice()
        if (voice == null) { onAvailability(false); return }
        tts.voice = voice
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        stopSpeechOnly()
        val prefix = UUID.randomUUID().toString()
        activePrefix = prefix
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (activePrefix == prefix && utteranceId?.startsWith("$prefix-ai-") == true) onAiStarted()
            }
            override fun onDone(utteranceId: String?) = Unit
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = Unit
        })
        val parts = chunk(intro).mapIndexed { index, part -> Triple(part, "$prefix-intro-$index", false) } +
            (aiText?.let { chunk(it).mapIndexed { index, part -> Triple(part, "$prefix-ai-$index", true) } } ?: emptyList())
        parts.forEachIndexed { index, (part, id, _) ->
            tts.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                Bundle(), id)
        }
    }

    fun stop() {
        stopSpeechOnly()
        audio.abandonAudioFocusRequest(focus)
    }

    private fun stopSpeechOnly() { activePrefix = null; tts.stop() }
    fun close() { closed = true; stop(); tts.shutdown() }

    private fun chunk(text: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            if (remaining.length <= 3500) { result += remaining; break }
            val boundary = remaining.lastIndexOf(' ', 3500).takeIf { it >= 1800 } ?: 3500
            result += remaining.take(boundary).trim()
            remaining = remaining.drop(boundary).trimStart()
        }
        return result
    }
}
