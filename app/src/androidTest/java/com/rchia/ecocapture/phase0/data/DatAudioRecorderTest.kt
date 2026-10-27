package com.rchia.ecocapture.phase0.capture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatAudioRecorderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun timestampedDatPcmBecomesAudibleAacInVideo() {
        val recorder = DatAudioRecorder(context)
        val directory = File(context.cacheDir, "dat_audio_test_${System.nanoTime()}").apply { mkdirs() }
        var encoded: RecordedDatAudio? = null
        try {
            assertTrue(recorder.arm())
            val epochUs = 123_000_000L
            repeat(50) { frame ->
                val pcm = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN)
                repeat(320) { sample ->
                    pcm.putShort((10_000 * sin(2 * PI * 440 * (frame * 320 + sample) / 16_000)).toInt().toShort())
                }
                pcm.flip()
                recorder.offer(pcm, epochUs + frame * 20_000L)
                assertEquals("Capture must not consume SDK buffer", 0, pcm.position())
            }
            encoded = recorder.stop()
            assertNotNull(recorder.lastFailure, encoded)
            val audio = checkNotNull(encoded)
            assertEquals(epochUs, audio.firstPresentationTimeUs)
            assertTrue("Encoded AAC must decode to a non-silent signal", decodedPeak(audio.file) > 1_000)
            val video = File(directory, "clip.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("checkpoint5/short.mp4").use { input ->
                video.outputStream().use { input.copyTo(it) }
            }
            // Audio begins before the first video keyframe on the shared DAT timeline.
            assertTrue(AudioVideoMuxer.merge(video, audio.file,
                audio.firstPresentationTimeUs - (epochUs + 100_000), 900_000))
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(video.absolutePath)
                val audioTrack = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                extractor.selectTrack(audioTrack)
                assertTrue(extractor.sampleTime in 0L..100_000L)
            } finally { extractor.release() }
        } finally {
            recorder.cancel()
            encoded?.file?.delete()
            directory.deleteRecursively()
        }
    }

    @Test fun noFramesReportsTheActualFailureAndRemovesTemporaryPcm() {
        val recorder = DatAudioRecorder(context)
        val directory = File(context.cacheDir, "dat_recording_audio")
        val before = directory.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertTrue(recorder.arm())
        assertNull(recorder.stop())
        assertTrue(recorder.lastFailure.orEmpty().contains("No DAT microphone audio frames"))
        assertEquals(before, directory.listFiles()?.map { it.name }?.toSet().orEmpty())
    }

    @Test fun invalidTimestampsDiscardAudioRatherThanMisalignIt() {
        val recorder = DatAudioRecorder(context)
        try {
            assertTrue(recorder.arm())
            recorder.offer(ByteBuffer.allocate(640), 100_000)
            recorder.offer(ByteBuffer.allocate(640), 90_000)
            assertNull(recorder.stop())
            assertTrue(recorder.lastFailure.orEmpty().contains("timestamps did not advance"))
        } finally { recorder.cancel() }
    }

    private fun decodedPeak(file: File): Int {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            }
            val format = extractor.getTrackFormat(track)
            assertEquals(16_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var peak = 0
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (!outputDone) {
                check(SystemClock.elapsedRealtime() < deadline) { "AAC decode timeout" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = checkNotNull(codec.getInputBuffer(index))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    try {
                        val pcm = checkNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        pcm.position(info.offset)
                        pcm.limit(info.offset + info.size)
                        while (pcm.remaining() >= 2) peak = maxOf(peak, abs(pcm.short.toInt()))
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
            return peak
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }
}
