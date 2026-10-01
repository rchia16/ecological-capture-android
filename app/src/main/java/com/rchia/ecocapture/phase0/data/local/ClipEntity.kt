package com.rchia.ecocapture.phase0.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey val clipId: String,
    val videoPath: String,
    val metadataPath: String?,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sampleCount: Long,
    val reviewState: String,
    val approvalState: String,
    val createdByAppVersion: String?,
    val updatedAtEpochMs: Long,
)
