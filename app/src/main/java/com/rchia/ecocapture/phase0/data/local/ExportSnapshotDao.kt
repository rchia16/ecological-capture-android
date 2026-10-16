package com.rchia.ecocapture.phase0.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExportSnapshotDao {
    @Insert suspend fun insert(snapshot: ExportSnapshotEntity)
    @Query("SELECT * FROM export_snapshots WHERE snapshotId = :id")
    suspend fun get(id: String): ExportSnapshotEntity?
    @Query("SELECT * FROM export_snapshots ORDER BY createdAtEpochMs, rowid")
    fun observeAll(): Flow<List<ExportSnapshotEntity>>
    @Query("SELECT * FROM export_snapshots WHERE clipId = :clipId ORDER BY createdAtEpochMs DESC, rowid DESC LIMIT 1")
    suspend fun latest(clipId: String): ExportSnapshotEntity?
    @Query("UPDATE export_snapshots SET snapshotState = 'SUPERSEDED' WHERE clipId = :clipId AND snapshotState = 'READY'")
    suspend fun supersede(clipId: String)
}
