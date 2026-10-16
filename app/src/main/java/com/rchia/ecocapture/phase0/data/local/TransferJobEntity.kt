package com.rchia.ecocapture.phase0.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "transfer_jobs", indices = [Index("clipId"), Index(value = ["snapshotId"], unique = true)])
data class TransferJobEntity(
    @PrimaryKey val jobId: String,
    val snapshotId: String,
    val clipId: String,
    val state: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val retryCount: Int,
    val progressPercent: Int,
    val lastSafeErrorCode: String?,
    val transportMode: String,
    val sourceVersionSha256: String,
    val previousSnapshotId: String?,
    val appVersion: String,
)
