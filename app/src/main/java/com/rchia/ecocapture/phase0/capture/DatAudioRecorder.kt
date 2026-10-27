package com.rchia.ecocapture.phase0.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

internal data class RecordedDatAudio(val file: File, val firstPresentationTimeUs: Long)

/** Saves only explicitly armed DAT PCM frames, then encodes AAC without opening a phone mic. */
internal class DatAudioRecorder(context: Context) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2 // DAT's decoded PCM is signed 16 bit.
        private const val MAX_PCM_BYTES = SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE * 75
        private const val TAG = "DatAudioRecorder"
    }

    private data class Chunk(val offset: Long, val size: Int, val ptsUs: Long)
    private data class Capture(val file: File, val output: FileOutputStream,
                               val chunks: MutableList<Chunk> = mutableListOf(), var bytes: Long = 0)
    private val directory = File(context.cacheDir, "dat_recording_audio")
    private var capture: Capture? = null
    @Volatile var lastFailure: String? = null
        private set

    @Synchronized fun arm(): Boolean {
        cancel()
        lastFailure = null
        return try {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create audio cache" }
            val file = File(directory, "${UUID.randomUUID()}.pcm")
            capture = Capture(file, FileOutputStream(file))
            true
        } catch (error: Exception) {
            fail("Could not prepare DAT audio recording", error)
            false
        }
    }

    @Synchronized fun offer(buffer: ByteBuffer, presentationTimeUs: Long) {
        val active = capture ?: return
        try {
            val input = buffer.duplicate()
            val size = input.remaining()
            if (size == 0) return
            check(size % (BYTES_PER_SAMPLE * CHANNEL_COUNT) == 0) { "Incomplete PCM sample" }
            check(active.bytes + size <= MAX_PCM_BYTES) { "DAT audio exceeds clip limit" }
            check(active.chunks.lastOrNull()?.let { presentationTimeUs > it.ptsUs } != false) {
                "DAT audio timestamps did not advance"
            }
            val bytes = ByteArray(size)
            input.get(bytes)
            active.output.write(bytes)
            active.chunks.add(Chunk(active.bytes, size, presentationTimeUs))
            active.bytes += size
        } catch (error: Exception) {
            fail("Could not capture DAT microphone frames", error)
            cancel()
        }
    }

    /** Call on IO. Detach the capture before encoding so preview frames cannot enter this clip. */
    fun stop(): RecordedDatAudio? {
        val active = synchronized(this) { capture.also { capture = null } } ?: return null
        val encoded = File(directory, "${UUID.randomUUID()}.m4a")
        return try {
            active.output.close()
            check(active.chunks.isNotEmpty()) { "No DAT microphone audio frames were received" }
            encode(active, encoded)
            RecordedDatAudio(encoded, active.chunks.first().ptsUs)
        } catch (error: Exception) {
            fail("Could not save DAT microphone audio", error)
            encoded.delete()
            null
        } finally {
            runCatching { active.output.close() }
            active.file.delete()
        }
    }

    @Synchronized fun cancel() {
        val active = capture ?: return
        capture = null
        runCatching { active.output.close() }
        active.file.delete()
    }

    private fun fail(message: String, error: Exception) {
        lastFailure = "$message: ${error.message}"
        Log.e(TAG, message, error)
    }

    private fun encode(active: Capture, output: File) {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE, CHANNEL_COUNT).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            var track = -1
            var chunkIndex = 0
            var consumed = 0
            var eosSent = false
            var eosReceived = false
            var samples = 0
            var lastInputPtsUs = 0L
            var lastProgressMs = SystemClock.elapsedRealtime()
            val originUs = active.chunks.first().ptsUs
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(active.file, "r").use { pcm ->
                while (!eosReceived) {
                    check(SystemClock.elapsedRealtime() - lastProgressMs < 5_000) { "AAC encoder timed out" }
                    if (!eosSent) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = checkNotNull(codec.getInputBuffer(index))
                            input.clear()
                            if (chunkIndex == active.chunks.size) {
                                codec.queueInputBuffer(index, 0, 0, lastInputPtsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosSent = true
                            } else {
                                val chunk = active.chunks[chunkIndex]
                                val available = minOf(input.remaining(), chunk.size - consumed)
                                val count = available - available % (BYTES_PER_SAMPLE * CHANNEL_COUNT)
                                check(count > 0) { "AAC input buffer too small" }
                                val bytes = ByteArray(count)
                                pcm.seek(chunk.offset + consumed)
                                pcm.readFully(bytes)
                                input.put(bytes)
                                val ptsUs = chunk.ptsUs - originUs +
                                    consumed.toLong() * 1_000_000 / (SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE)
                                codec.queueInputBuffer(index, 0, count, ptsUs, 0)
                                lastInputPtsUs = ptsUs + count.toLong() * 1_000_000 /
                                    (SAMPLE_RATE * CHANNEL_COUNT * BYTES_PER_SAMPLE)
                                consumed += count
                                if (consumed == chunk.size) { chunkIndex++; consumed = 0 }
                            }
                            lastProgressMs = SystemClock.elapsedRealtime()
                        }
                    }
                    var index = codec.dequeueOutputBuffer(info, 10_000)
                    while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted) { "AAC format changed twice" }
                            track = writer.addTrack(codec.outputFormat)
                            writer.start()
                            muxerStarted = true
                        } else if (index >= 0) {
                            try {
                                if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    check(muxerStarted) { "AAC data before format" }
                                    val data = checkNotNull(codec.getOutputBuffer(index))
                                    data.position(info.offset)
                                    data.limit(info.offset + info.size)
                                    writer.writeSampleData(track, data, info)
                                    samples++
                                }
                                eosReceived = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally { codec.releaseOutputBuffer(index, false) }
                        }
                        lastProgressMs = SystemClock.elapsedRealtime()
                        if (eosReceived) break
                        index = codec.dequeueOutputBuffer(info, 0)
                    }
                }
            }
            check(samples > 0) { "AAC encoder produced no audio samples" }
            writer.stop()
            muxerStarted = false
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
        }
    }
}
