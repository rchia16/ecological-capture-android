package com.rchia.ecocapture.phase0.vlm

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.rchia.ecocapture.phase0.domain.ClipRecord
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class FrameVideoMetadata(val durationMs: Long?, val width: Int?, val height: Int?, val rotation: Int)
internal interface SavedVideoDecoder : Closeable {
    fun open(file: File)
    fun metadata(): FrameVideoMetadata
    fun frame(timestampMs: Long, bound: Int): Bitmap?
}

private class AndroidSavedVideoDecoder : SavedVideoDecoder {
    private val retriever = MediaMetadataRetriever()
    override fun open(file: File) = retriever.setDataSource(file.absolutePath)
    override fun metadata() = FrameVideoMetadata(
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
    )
    override fun frame(timestampMs: Long, bound: Int): Bitmap? = retriever.getScaledFrameAtTime(
        timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, bound, bound,
        MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 },
    )
    override fun close() = retriever.release()
}

/** Saved MP4 only. No live DAT frames, model loading, profile inputs or participant UI integration. */
class VlmFrameSampler internal constructor(private val decoderFactory: () -> SavedVideoDecoder) {
    constructor() : this({ AndroidSavedVideoDecoder() })

    suspend fun sample(clip: ClipRecord, config: FrameSamplingConfig = FrameSamplingConfig()) =
        sample(clip.videoFile, config)

    suspend fun sample(videoFile: File, config: FrameSamplingConfig = FrameSamplingConfig()): FrameSamplingResult {
        val frames = mutableListOf<FrameSample>()
        try {
            // Keep ownership outside withContext: cancellation during dispatcher return also frees frames.
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                if (!videoFile.isFile) return@withContext FrameSamplingResult.Failure(FrameSamplingFailure.MISSING_VIDEO)
                if (videoFile.length() == 0L || !videoFile.canRead() || videoFile.extension.lowercase() != "mp4") {
                    return@withContext FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_VIDEO)
                }
                val started = SystemClock.elapsedRealtime()
                decoderFactory().use { decoder ->
                    decoder.open(videoFile)
                    val metadata = decoder.metadata()
                    val duration = metadata.durationMs
                    if (duration == null || duration <= 0 || duration > Long.MAX_VALUE / 1000) {
                        return@withContext FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_DURATION)
                    }
                    val width = metadata.width ?: 0
                    val height = metadata.height ?: 0
                    if (width <= 0 || height <= 0 || metadata.rotation !in listOf(0, 90, 180, 270)) {
                        return@withContext FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_DIMENSIONS)
                    }
                    // Square bounds let the decoder preserve aspect ratio and apply rotation once.
                    val bound = minOf(config.maxLongEdge, maxOf(width, height))
                    config.timestampsMs(duration).forEachIndexed { index, timestamp ->
                        currentCoroutineContext().ensureActive()
                        val bitmap = decoder.frame(timestamp, bound)
                            ?: throw FrameUnavailableException()
                        // Register ownership before checking cancellation or bitmap geometry.
                        try {
                            frames += FrameSample(index, timestamp, width, height, metadata.rotation, bitmap)
                        } catch (failure: Throwable) {
                            bitmap.recycle()
                            throw failure
                        }
                        currentCoroutineContext().ensureActive()
                        val displayWidth = if (metadata.rotation % 180 == 0) width else height
                        val displayHeight = if (metadata.rotation % 180 == 0) height else width
                        val ratioError = kotlin.math.abs(bitmap.width.toDouble() / bitmap.height - displayWidth.toDouble() / displayHeight)
                        if (bitmap.width > bound || bitmap.height > bound || bitmap.width > displayWidth ||
                            bitmap.height > displayHeight || ratioError > 2.0 / bitmap.height) {
                            throw FrameUnavailableException()
                        }
                    }
                    FrameSamplingResult.Success(SampledFrames(frames.toList(), duration, config,
                        SystemClock.elapsedRealtime() - started))
                }
            }
        } catch (cancelled: CancellationException) {
            frames.forEach { it.close() }
            throw cancelled
        } catch (_: OutOfMemoryError) {
            frames.forEach { it.close() }
            return FrameSamplingResult.Failure(FrameSamplingFailure.OUT_OF_MEMORY)
        } catch (_: FrameUnavailableException) {
            frames.forEach { it.close() }
            return FrameSamplingResult.Failure(FrameSamplingFailure.FRAME_UNAVAILABLE)
        } catch (_: Exception) {
            frames.forEach { it.close() }
            return FrameSamplingResult.Failure(FrameSamplingFailure.INVALID_VIDEO)
        }
    }

    private class FrameUnavailableException : Exception()
}
