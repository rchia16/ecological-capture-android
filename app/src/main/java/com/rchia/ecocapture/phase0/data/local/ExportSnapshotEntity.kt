package com.rchia.ecocapture.phase0.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "export_snapshots", indices = [Index("clipId")])
data class ExportSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val clipId: String,
    val previousSnapshotId: String?,
    val createdAtEpochMs: Long,
    val eventJsonSha256: String,
    val manifestSha256: String,
    val mp4Sha256: String,
    val snapshotState: String,
    val appVersion: String,
    val studyParticipantCode: String?,
)
