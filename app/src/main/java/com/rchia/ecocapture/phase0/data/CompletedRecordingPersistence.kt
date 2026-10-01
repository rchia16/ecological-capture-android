package com.rchia.ecocapture.phase0.data

import com.rchia.ecocapture.phase0.capture.RecordingResult
import com.rchia.ecocapture.phase0.domain.ApprovalState
import com.rchia.ecocapture.phase0.domain.ClipRecord
import com.rchia.ecocapture.phase0.domain.ReviewState
import java.time.Instant

/** This operation never removes media, including when database insertion fails. */
suspend fun ClipRepository.addCompletedRecording(result: RecordingResult.Completed) {
    require(result.videoFile.isFile && result.videoFile.length() > 0L &&
        result.videoFile.extension.equals("mp4", ignoreCase = true)) {
        "Completed recording must reference a nonempty finalized MP4."
    }
    insert(ClipRecord(
        clipId = result.clipId,
        videoFile = result.videoFile,
        metadataFile = result.metadataFile,
        createdAt = Instant.ofEpochMilli(result.createdAtEpochMs.takeIf { it > 0L }
            ?: result.videoFile.lastModified().coerceAtLeast(0L)),
        durationMs = result.durationMs,
        width = result.width,
        height = result.height,
        sampleCount = result.samples.toLong(),
        reviewState = ReviewState.UNREVIEWED,
        approvalState = ApprovalState.UNDECIDED,
    ))
}
