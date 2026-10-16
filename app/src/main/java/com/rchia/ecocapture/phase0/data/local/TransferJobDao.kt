package com.rchia.ecocapture.phase0.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferJobDao {
    @Insert suspend fun insert(job: TransferJobEntity)
    @Query("SELECT * FROM transfer_jobs WHERE jobId = :id") suspend fun get(id: String): TransferJobEntity?
    @Query("SELECT * FROM transfer_jobs WHERE clipId = :clipId ORDER BY createdAtEpochMs DESC, rowid DESC LIMIT 1")
    suspend fun latest(clipId: String): TransferJobEntity?
    @Query("SELECT * FROM transfer_jobs ORDER BY createdAtEpochMs, rowid") fun observeAll(): Flow<List<TransferJobEntity>>
    @Query("UPDATE transfer_jobs SET state = :next, progressPercent = :progress, updatedAtEpochMs = :time, lastSafeErrorCode = :error WHERE jobId = :id AND state = :expected AND EXISTS (SELECT 1 FROM clips WHERE clips.clipId = transfer_jobs.clipId AND approvalState = 'APPROVED')")
    suspend fun transition(id: String, expected: String, next: String, progress: Int, time: Long, error: String?): Int
    @Query("UPDATE transfer_jobs SET state = 'CANCELLED', updatedAtEpochMs = :time, lastSafeErrorCode = NULL WHERE jobId = :id AND state NOT IN ('CANCELLED', 'SIMULATED_COMPLETE')")
    suspend fun cancel(id: String, time: Long)
    @Query("UPDATE transfer_jobs SET retryCount = retryCount + 1 WHERE jobId = :id") suspend fun countRetry(id: String)
}
