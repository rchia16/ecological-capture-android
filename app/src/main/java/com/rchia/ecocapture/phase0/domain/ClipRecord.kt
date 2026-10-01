package com.rchia.ecocapture.phase0.domain

import java.io.File
import java.time.Instant

data class ClipRecord(
    val clipId: String,
    val videoFile: File,
    val metadataFile: File?,
    val createdAt: Instant,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sampleCount: Long,
    val reviewState: ReviewState,
    val approvalState: ApprovalState,
)
