package com.rchia.ecocapture.phase0.feedback

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice

/** Prefer Australian English; never substitute a voice in another language. */
internal fun TextToSpeech.offlineEnglishVoice(): Voice? {
    val english = voices.orEmpty().filter { voice ->
        voice.locale.language.equals("en", ignoreCase = true) && !voice.isNetworkConnectionRequired
    }
    return english.firstOrNull { it.locale.country.equals("AU", ignoreCase = true) }
        ?: english.firstOrNull()
}
