package com.rchia.ecocapture.phase0.vlm

import android.graphics.Bitmap
import java.io.Closeable
import org.json.JSONArray
import org.json.JSONObject

/** Caller owns this bitmap until close. Never label a requested time as a decoded frame time. */
class FrameSample internal constructor(
    val index: Int,
    val requestedTimestampMs: Long,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val sourceRotationDegrees: Int,
    val bitmap: Bitmap,
) : Closeable {
    val actualTimestampMs: Long? = null // MediaMetadataRetriever does not expose this.
    val inferenceWidth = bitmap.width
    val inferenceHeight = bitmap.height
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

/** Use with .use { ... }; on failure/cancellation the sampler releases all partial frames itself. */
class SampledFrames internal constructor(
    val frames: List<FrameSample>,
    val durationMs: Long,
    val config: FrameSamplingConfig,
    val extractionDurationMs: Long,
) : Closeable {
    override fun close() { frames.forEach { it.close() } }

    fun provenanceJson(): String = JSONObject().apply {
        put("policyVersion", "saved_video_temporal_v1")
        put("durationMs", durationMs)
        put("percentages", JSONArray(config.percentages))
        put("maxLongEdge", config.maxLongEdge)
        put("upscaling", false)
        put("decoder", "MediaMetadataRetriever")
        put("selection", "OPTION_CLOSEST")
        put("orientation", "decoder_applies_container_rotation")
        put("extractionDurationMs", extractionDurationMs)
        put("frames", JSONArray().apply {
            frames.forEach { frame -> put(JSONObject().apply {
                put("index", frame.index)
                put("requestedTimestampMs", frame.requestedTimestampMs)
                put("actualTimestampMs", JSONObject.NULL)
                put("sourceWidth", frame.sourceWidth)
                put("sourceHeight", frame.sourceHeight)
                put("sourceRotationDegrees", frame.sourceRotationDegrees)
                put("inferenceWidth", frame.inferenceWidth)
                put("inferenceHeight", frame.inferenceHeight)
            }) }
        })
    }.toString()
}

enum class FrameSamplingFailure {
    MISSING_VIDEO, INVALID_VIDEO, INVALID_DURATION, INVALID_DIMENSIONS, FRAME_UNAVAILABLE, OUT_OF_MEMORY
}

sealed interface FrameSamplingResult {
    data class Success(val samples: SampledFrames) : FrameSamplingResult
    data class Failure(val reason: FrameSamplingFailure) : FrameSamplingResult
}
