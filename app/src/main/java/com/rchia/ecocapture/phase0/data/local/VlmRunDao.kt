package com.rchia.ecocapture.phase0.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VlmRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: VlmRunEntity)

    @Query("SELECT * FROM vlm_runs WHERE vlmRunId = :id")
    suspend fun getRun(id: String): VlmRunEntity?

    @Query("SELECT * FROM vlm_runs WHERE clipId = :clipId ORDER BY generatedAtEpochMs, vlmRunId")
    suspend fun history(clipId: String): List<VlmRunEntity>

    @Query("SELECT * FROM vlm_runs WHERE clipId = :clipId ORDER BY generatedAtEpochMs DESC, rowid DESC LIMIT 1")
    fun observeLatest(clipId: String): Flow<VlmRunEntity?>

    // Only exposure/disposition metadata is mutable; output and provenance have no update API.
    @Query("UPDATE vlm_runs SET firstPresentedAtEpochMs = :time, disposition = CASE WHEN disposition = 'NOT_PRESENTED' THEN 'PRESENTED' ELSE disposition END WHERE vlmRunId = :id AND firstPresentedAtEpochMs IS NULL")
    suspend fun markPresented(id: String, time: Long)

    @Query("UPDATE vlm_runs SET disposition = :disposition WHERE vlmRunId = :id")
    suspend fun setDisposition(id: String, disposition: String)
}
