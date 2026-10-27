package com.rchia.ecocapture.phase0.capture

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Copies compressed tracks into one MP4. The original video is retained on every failure. */
internal object AudioVideoMuxer {
    fun merge(video: File, audio: File, audioOffsetUs: Long, durationUs: Long): Boolean {
        if (!video.isFile || !audio.isFile || audio.length() == 0L) return false
        val output = File(video.parentFile, "${video.nameWithoutExtension}.audio-merge.partial")
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var finished = false
        return try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoSource = findTrack(videoExtractor, "video/")
            val audioSource = findTrack(audioExtractor, "audio/")
            if (videoSource < 0 || audioSource < 0) {
                Log.e("AudioVideoMuxer", "Missing source track: video=$videoSource audio=$audioSource")
                return false
            }
            videoExtractor.selectTrack(videoSource)
            audioExtractor.selectTrack(audioSource)
            // AAC encoders may emit a priming sample with a negative timestamp.
            // sampleSize, unlike sampleTime, distinguishes that from end of stream.
            if (videoExtractor.sampleSize <= 0 || audioExtractor.sampleSize <= 0) {
                Log.e("AudioVideoMuxer", "Missing source samples")
                return false
            }
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTarget = muxer.addTrack(videoExtractor.getTrackFormat(videoSource))
            val audioTarget = muxer.addTrack(audioExtractor.getTrackFormat(audioSource))
            muxer.start()
            started = true
            var videoSamples = 0
            var audioSamples = 0
            var buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val offset = audioOffsetUs
            while (true) {
                val videoTime = videoExtractor.sampleTime
                val audioTime = audioExtractor.sampleTime
                val audioAvailable = audioExtractor.sampleSize > 0
                if (audioAvailable && audioTime + offset < 0) {
                    audioExtractor.advance()
                    continue
                }
                val eligibleAudio = audioAvailable && audioTime + offset <= durationUs
                if (videoTime < 0 && !eligibleAudio) break
                val useVideo = videoTime >= 0 && (!eligibleAudio || videoTime <= audioTime + offset)
                val extractor = if (useVideo) videoExtractor else audioExtractor
                val size = extractor.sampleSize
                check(size in 1..(32L * 1024 * 1024)) { "Invalid MP4 sample size" }
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect(size.toInt())
                buffer.clear()
                val bytes = extractor.readSampleData(buffer, 0)
                check(bytes == size.toInt()) { "Could not copy MP4 sample" }
                info.set(0, bytes,
                    if (useVideo) videoTime else audioTime + offset,
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                buffer.position(0)
                buffer.limit(bytes)
                muxer.writeSampleData(if (useVideo) videoTarget else audioTarget, buffer, info)
                if (useVideo) videoSamples++ else audioSamples++
                extractor.advance()
            }
            check(videoSamples > 0 && audioSamples > 0) { "Missing video or audio samples" }
            muxer.stop()
            started = false
            muxer.release()
            muxer = null
            check(output.isFile && output.length() > 0L) { "Merged MP4 is empty" }
            try {
                Files.move(output.toPath(), video.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(output.toPath(), video.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            finished = true
            true
        } catch (error: Exception) {
            Log.e("AudioVideoMuxer", "Could not add microphone audio; original video retained", error)
            false
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            videoExtractor.release()
            audioExtractor.release()
            if (!finished) output.delete()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: -1
}
