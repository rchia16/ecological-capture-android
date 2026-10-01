package com.rchia.ecocapture.phase0.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface FrameWriteResult {
    data object Ignored : FrameWriteResult
    data object Accepted : FrameWriteResult
    data object RecordingBegan : FrameWriteResult
    data class Failed(val message: String) : FrameWriteResult
}

sealed interface RecordingResult {
    data class Completed(
        val clipId: String,
        val videoFile: File,
        val metadataFile: File?,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val samples: Int,
        val createdAtEpochMs: Long = 0L,
    ) : RecordingResult

    data object NoVideo : RecordingResult
    data class Failed(val message: String) : RecordingResult
}

/**
 * Video-only HEVC -> MP4 passthrough recorder for Meta DAT compressed frames.
 *
 * Frames are never re-encoded. The recorder caches VPS/SPS/PPS while the glasses stream is active,
 * opens an MP4 track on a keyframe (or a short fallback), writes to a .partial file, then atomically
 * promotes the file to .mp4 after MediaMuxer finalization succeeds.
 */
class HevcMp4Recorder(context: Context, frameRate: Int = 15) {
    companion object {
        private const val TAG = "HevcMp4Recorder"
    }

    private val appContext = context.applicationContext
    private val maxFramesBeforeStart = frameRate.coerceAtLeast(1) * 2
    private val nominalFrameDurationUs = 1_000_000L / frameRate.coerceAtLeast(1)
    private val lock = Any()
    private val parameterSets = HevcParameterSetCollector()

    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var muxerFailed = false
    private var armed = false
    private var videoStarted = false

    private var partialFile: File? = null
    private var finalFile: File? = null
    private var clipId: String? = null

    private var width = 0
    private var height = 0
    private var firstInputPtsUs = 0L
    private var lastWrittenPtsUs = -1L
    private var framesBeforeStart = 0
    private var sampleCount = 0
    private var recordBeganWallTimeMs = 0L

    /** Always call this for compressed stream frames, even when not recording. */
    fun observeStreamFrame(data: ByteArray) {
        parameterSets.offer(data)
    }

    fun arm(): Result<Unit> = synchronized(lock) {
        if (armed) return Result.failure(IllegalStateException("Recorder already armed"))

        resetMuxerState(keepParameterSets = true)
        val recordingsDir = File(appContext.filesDir, "recordings").apply { mkdirs() }
        if (!recordingsDir.exists() || !recordingsDir.canWrite()) {
            return Result.failure(IllegalStateException("Recordings directory is not writable"))
        }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        partialFile = File(recordingsDir, "clip_${stamp}.partial")
        finalFile = File(recordingsDir, "clip_${stamp}.mp4")
        clipId = UUID.randomUUID().toString()

        return try {
            muxer = MediaMuxer(
                partialFile!!.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            armed = true
            Result.success(Unit)
        } catch (t: Throwable) {
            cleanupFiles()
            resetMuxerState(keepParameterSets = true)
            Result.failure(t)
        }
    }

    fun writeCompressedFrame(
        data: ByteArray,
        presentationTimeUs: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): FrameWriteResult = synchronized(lock) {
        parameterSets.offer(data)
        if (!armed) return FrameWriteResult.Ignored
        if (muxerFailed) return FrameWriteResult.Failed("MP4 muxer is in a failed state")
        if (data.isEmpty()) return FrameWriteResult.Ignored

        if (width <= 0 && frameWidth > 0 && frameHeight > 0) {
            width = frameWidth
            height = frameHeight
        }

        var justStarted = false
        if (!videoStarted) {
            val csd = parameterSets.complete() ?: return FrameWriteResult.Accepted
            if (width <= 0 || height <= 0) return FrameWriteResult.Accepted

            framesBeforeStart += 1
            val keyFrame = HevcNalParser.isKeyFrame(data)
            if (!keyFrame && framesBeforeStart < maxFramesBeforeStart) {
                return FrameWriteResult.Accepted
            }

            try {
                val format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                    width,
                    height,
                ).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                }
                trackIndex = muxer?.addTrack(format) ?: -1
                if (trackIndex < 0) throw IllegalStateException("Could not add HEVC track")
                muxer?.start()
                muxerStarted = true
                videoStarted = true
                firstInputPtsUs = presentationTimeUs
                recordBeganWallTimeMs = System.currentTimeMillis()
                justStarted = true
            } catch (t: Throwable) {
                muxerFailed = true
                Log.e(TAG, "Failed to start muxer", t)
                return FrameWriteResult.Failed(t.message ?: "Failed to start MP4 muxer")
            }
        }

        var outputPtsUs = (presentationTimeUs - firstInputPtsUs).coerceAtLeast(0L)
        if (lastWrittenPtsUs >= 0L && outputPtsUs <= lastWrittenPtsUs) {
            outputPtsUs = lastWrittenPtsUs + nominalFrameDurationUs
        }
        lastWrittenPtsUs = outputPtsUs

        val flags = if (justStarted || HevcNalParser.isKeyFrame(data)) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
            0
        }

        val info = MediaCodec.BufferInfo().apply {
            set(0, data.size, outputPtsUs, flags)
        }

        try {
            muxer?.writeSampleData(trackIndex, ByteBuffer.wrap(data), info)
            sampleCount += 1
        } catch (t: Throwable) {
            muxerFailed = true
            Log.e(TAG, "MP4 sample write failed", t)
            return FrameWriteResult.Failed(t.message ?: "MP4 sample write failed")
        }

        if (justStarted) FrameWriteResult.RecordingBegan else FrameWriteResult.Accepted
    }

    fun stop(): RecordingResult = synchronized(lock) {
        if (!armed) return RecordingResult.NoVideo
        armed = false

        val hadVideo = muxerStarted && !muxerFailed && trackIndex >= 0 && sampleCount > 0
        var finalizeError: Throwable? = null

        if (muxerStarted) {
            try {
                muxer?.stop()
            } catch (t: Throwable) {
                finalizeError = t
                Log.e(TAG, "Failed to stop muxer", t)
            }
        }

        try {
            muxer?.release()
        } catch (t: Throwable) {
            if (finalizeError == null) finalizeError = t
            Log.e(TAG, "Failed to release muxer", t)
        }
        muxer = null

        val source = partialFile
        val target = finalFile
        val durationMs = if (recordBeganWallTimeMs > 0L) {
            System.currentTimeMillis() - recordBeganWallTimeMs
        } else {
            0L
        }

        val result = when {
            !hadVideo -> {
                source?.delete()
                RecordingResult.NoVideo
            }
            finalizeError != null -> {
                source?.delete()
                RecordingResult.Failed(finalizeError.message ?: "MP4 finalization failed")
            }
            source == null || target == null || source.length() <= 0L -> {
                source?.delete()
                RecordingResult.Failed("Finalized MP4 is empty")
            }
            else -> {
                val moved = source.renameTo(target)
                if (!moved) {
                    try {
                        source.copyTo(target, overwrite = true)
                        source.delete()
                    } catch (t: Throwable) {
                        target.delete()
                        resetMuxerState(keepParameterSets = true)
                        return RecordingResult.Failed(
                            t.message ?: "Could not promote partial recording"
                        )
                    }
                }

                val metadataFile = writeMetadataSidecar(target, durationMs)
                if (target.length() <= 0L) {
                    target.delete()
                    resetMuxerState(keepParameterSets = true)
                    return RecordingResult.Failed("Promoted MP4 is empty")
                }
                RecordingResult.Completed(
                    clipId = checkNotNull(clipId),
                    videoFile = target,
                    metadataFile = metadataFile,
                    durationMs = durationMs,
                    width = width,
                    height = height,
                    samples = sampleCount,
                    createdAtEpochMs = recordBeganWallTimeMs,
                )
            }
        }

        resetMuxerState(keepParameterSets = true)
        result
    }

    fun abort() = synchronized(lock) {
        armed = false
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        cleanupFiles()
        resetMuxerState(keepParameterSets = true)
    }

    fun resetStreamCodecState() {
        parameterSets.clear()
    }

    private fun writeMetadataSidecar(videoFile: File, durationMs: Long): File? {
        val metadata = File(videoFile.parentFile, videoFile.nameWithoutExtension + ".json")
        val json = """
            {
              "clip_id": "$clipId",
              "created_at_epoch_ms": $recordBeganWallTimeMs,
              "video_file": "${videoFile.name}",
              "duration_ms": $durationMs,
              "width": $width,
              "height": $height,
              "samples": $sampleCount,
              "codec": "video/hevc",
              "container": "mp4"
            }
        """.trimIndent()
        return runCatching {
            metadata.writeText(json)
            metadata
        }.onFailure { Log.w(TAG, "Could not write metadata sidecar", it) }.getOrNull()
    }

    private fun cleanupFiles() {
        partialFile?.delete()
        finalFile?.delete()
        partialFile = null
        finalFile = null
        clipId = null
    }

    private fun resetMuxerState(keepParameterSets: Boolean) {
        clipId = null
        muxer = null
        trackIndex = -1
        muxerStarted = false
        muxerFailed = false
        armed = false
        videoStarted = false
        partialFile = null
        finalFile = null
        width = 0
        height = 0
        firstInputPtsUs = 0L
        lastWrittenPtsUs = -1L
        framesBeforeStart = 0
        sampleCount = 0
        recordBeganWallTimeMs = 0L
        if (!keepParameterSets) parameterSets.clear()
    }
}
