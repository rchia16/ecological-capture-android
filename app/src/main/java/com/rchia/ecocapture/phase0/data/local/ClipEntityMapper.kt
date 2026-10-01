package com.rchia.ecocapture.phase0.data.local

import com.rchia.ecocapture.phase0.domain.ApprovalState
import com.rchia.ecocapture.phase0.domain.ClipRecord
import com.rchia.ecocapture.phase0.domain.ReviewState
import java.io.File
import java.time.Instant

fun ClipEntity.toDomain(): ClipRecord = ClipRecord(
    clipId = clipId,
    videoFile = File(videoPath),
    metadataFile = metadataPath?.let(::File),
    createdAt = Instant.ofEpochMilli(createdAtEpochMs),
    durationMs = durationMs,
    width = width,
    height = height,
    sampleCount = sampleCount,
    reviewState = ReviewState.valueOf(reviewState),
    approvalState = ApprovalState.valueOf(approvalState),
)

fun ClipRecord.toEntity(appVersion: String?, updatedAtEpochMs: Long = System.currentTimeMillis()) = ClipEntity(
    clipId = clipId,
    videoPath = videoFile.absolutePath,
    metadataPath = metadataFile?.absolutePath,
    createdAtEpochMs = createdAt.toEpochMilli(),
    durationMs = durationMs,
    width = width,
    height = height,
    sampleCount = sampleCount,
    reviewState = reviewState.name,
    approvalState = approvalState.name,
    createdByAppVersion = appVersion,
    updatedAtEpochMs = updatedAtEpochMs,
)
