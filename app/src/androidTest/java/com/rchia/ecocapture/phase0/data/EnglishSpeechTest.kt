package com.rchia.ecocapture.phase0.data

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.feedback.offlineEnglishVoice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnglishSpeechTest {
    @Test fun deviceHasAnOfflineEnglishVoiceForClipReminders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        val status = AtomicInteger(TextToSpeech.ERROR)
        val tts = TextToSpeech(context) { result -> status.set(result); latch.countDown() }
        try {
            assertTrue("Text-to-speech did not initialise", latch.await(10, TimeUnit.SECONDS))
            assertEquals(TextToSpeech.SUCCESS, status.get())
            val voice = tts.offlineEnglishVoice()
            assertNotNull("Install an English offline voice on this phone", voice)
            assertEquals("en", voice!!.locale.language)
            assertFalse(voice.isNetworkConnectionRequired)
        } finally { tts.shutdown() }
    }
}
