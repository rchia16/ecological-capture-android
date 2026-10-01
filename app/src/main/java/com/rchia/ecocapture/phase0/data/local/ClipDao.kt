package com.rchia.ecocapture.phase0.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips WHERE approvalState != 'DELETED' ORDER BY createdAtEpochMs DESC")
    fun observeActiveClips(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE approvalState != 'DELETED' AND reviewState IN ('UNREVIEWED', 'DEFERRED') ORDER BY createdAtEpochMs DESC")
    fun observeReviewQueue(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE clipId = :clipId")
    fun observeClip(clipId: String): Flow<ClipEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(clip: ClipEntity)

    @Transaction
    suspend fun insertIfVideoAbsent(clip: ClipEntity) {
        if (findClipIdByVideoPath(clip.videoPath) == null) insert(clip)
    }

    @Query("SELECT clipId FROM clips WHERE videoPath = :videoPath LIMIT 1")
    suspend fun findClipIdByVideoPath(videoPath: String): String?

    @Query("SELECT * FROM clips WHERE clipId = :clipId")
    suspend fun getClip(clipId: String): ClipEntity?

    @Query("UPDATE clips SET approvalState = 'DELETED', updatedAtEpochMs = :updatedAtEpochMs WHERE clipId = :clipId")
    suspend fun markDeleted(clipId: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE clips SET reviewState = 'DEFERRED', approvalState = 'UNDECIDED', updatedAtEpochMs = :updatedAtEpochMs WHERE clipId = :clipId AND approvalState = 'UNDECIDED' AND reviewState IN ('UNREVIEWED', 'DEFERRED')")
    suspend fun deferClip(clipId: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE clips SET reviewState = 'REVIEWED', approvalState = 'APPROVED', updatedAtEpochMs = :updatedAtEpochMs WHERE clipId = :clipId AND approvalState = 'UNDECIDED' AND reviewState IN ('UNREVIEWED', 'DEFERRED')")
    suspend fun approveClip(clipId: String, updatedAtEpochMs: Long): Int

    @Query("UPDATE clips SET reviewState = :reviewState, updatedAtEpochMs = :updatedAtEpochMs WHERE clipId = :clipId")
    suspend fun updateReviewState(clipId: String, reviewState: String, updatedAtEpochMs: Long)

    @Query("UPDATE clips SET approvalState = :approvalState, updatedAtEpochMs = :updatedAtEpochMs WHERE clipId = :clipId")
    suspend fun updateApprovalState(clipId: String, approvalState: String, updatedAtEpochMs: Long)
}
