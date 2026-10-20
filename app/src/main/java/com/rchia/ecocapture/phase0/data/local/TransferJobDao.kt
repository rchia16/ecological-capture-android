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
    @Query("UPDATE transfer_jobs SET state = 'CANCELLED', updatedAtEpochMs = :time, lastSafeErrorCode = NULL WHERE jobId = :id AND state NOT IN ('CANCELLED', 'SIMULATED_COMPLETE', 'AWAITING_GB10', 'VERIFIED_GB10', 'NEEDS_ATTENTION')")
    suspend fun cancel(id: String, time: Long)
    @Query("UPDATE transfer_jobs SET retryCount = retryCount + 1 WHERE jobId = :id") suspend fun countRetry(id: String)
    @Query("UPDATE transfer_jobs SET encryptedArtifactPath=:path, ciphertextSha256=:sha, encryptionKeyVersions=:versions WHERE jobId=:id AND state='ENCRYPTING'")
    suspend fun artifact(id: String, path: String, sha: String, versions: String): Int
    @Query("UPDATE transfer_jobs SET state=:state, remoteReceiptJson=:receipt, updatedAtEpochMs=:time WHERE jobId=:id AND state IN ('UPLOADING','AWAITING_GB10','NEEDS_ATTENTION')")
    suspend fun receipt(id: String, state: String, receipt: String, time: Long): Int
}
