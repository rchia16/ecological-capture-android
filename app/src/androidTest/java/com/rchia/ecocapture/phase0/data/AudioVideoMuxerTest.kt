package com.rchia.ecocapture.phase0.capture

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioVideoMuxerTest {
    @Test fun syntheticAudioAndVideoBecomeOnePlayableMp4() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = File(context.cacheDir, "audio_mux_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            val video = File(directory, "clip.mp4")
            val audio = File(directory, "tone.m4a")
            video.outputStream().use { output ->
                assets.open("checkpoint5/short.mp4").use { it.copyTo(output) }
            }
            audio.outputStream().use { output ->
                assets.open("checkpoint5/test-tone.m4a").use { it.copyTo(output) }
            }
            assertTrue(AudioVideoMuxer.merge(video, audio, 50_000, 1_000_000))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(video.absolutePath)
                val formats = (0 until extractor.trackCount).map {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                }
                assertEquals(2, formats.size)
                assertTrue(formats.any { it.startsWith("video/") })
                assertTrue(formats.any { it.startsWith("audio/") })
                for (track in 0 until extractor.trackCount) {
                    extractor.selectTrack(track)
                    assertTrue("Track has no samples", extractor.sampleTime >= 0)
                    extractor.unselectTrack(track)
                }
            } finally { extractor.release() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingAudioLeavesOriginalVideoUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val directory = File(context.cacheDir, "audio_mux_test_${System.nanoTime()}").apply { mkdirs() }
        try {
            val video = File(directory, "clip.mp4")
            video.outputStream().use { output ->
                assets.open("checkpoint5/short.mp4").use { it.copyTo(output) }
            }
            val before = MessageDigest.getInstance("SHA-256").digest(video.readBytes())
            assertFalse(AudioVideoMuxer.merge(video, File(directory, "missing.m4a"), 0, 1_000_000))
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(video.readBytes()))
        } finally { directory.deleteRecursively() }
    }
}
