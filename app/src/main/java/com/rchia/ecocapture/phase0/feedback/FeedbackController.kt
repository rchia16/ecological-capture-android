package com.rchia.ecocapture.phase0.feedback

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.io.Closeable
import java.util.Locale

class FeedbackController(context: Context) : Closeable, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator =
        appContext.getSystemService(VibratorManager::class.java).defaultVibrator

    private val tts = TextToSpeech(appContext, this)
    @Volatile private var ttsReady = false

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.language = Locale.getDefault()
            tts.setSpeechRate(0.95f)
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
    }

    fun cameraReady() = announce(
        "Glasses camera ready.",
        longArrayOf(0, 80),
    )

    fun recordingStarted() = announce(
        "Recording started.",
        longArrayOf(0, 180),
    )

    fun recordingStopped() = announce(
        "Recording stopped. Saved on this phone.",
        longArrayOf(0, 110, 90, 110),
    )

    fun attention(message: String) = announce(
        message,
        longArrayOf(0, 150, 90, 150, 90, 150),
    )

    fun clipDeferred() = announce(
        "Recording saved for review later. It is still saved on this phone.",
        longArrayOf(0, 110, 90, 110),
    )

    fun clipApproved() = announce(
        "Recording approved. It is still saved on this phone.",
        longArrayOf(0, 110, 90, 110),
    )

    fun clipDeleted() = announce(
        "Recording deleted from this phone.",
        longArrayOf(0, 110, 90, 110),
    )

    fun playbackChanged(playing: Boolean) = announce(
        if (playing) "Playback started." else "Playback paused.",
        longArrayOf(0, 60),
    )

    private fun announce(message: String, pattern: LongArray) {
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build(),
            )
        }
        if (ttsReady) {
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, message.hashCode().toString())
        }
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
        vibrator.cancel()
    }
}
