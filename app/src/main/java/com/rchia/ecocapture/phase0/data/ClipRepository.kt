package com.rchia.ecocapture.phase0.data

import com.rchia.ecocapture.phase0.domain.ClipRecord
import java.io.File
import kotlinx.coroutines.flow.Flow

interface ClipRepository {
    fun observeActiveClips(): Flow<List<ClipRecord>>
    fun observeReviewQueue(): Flow<List<ClipRecord>>
    fun observeClip(clipId: String): Flow<ClipRecord?>
    suspend fun insert(clip: ClipRecord)
    suspend fun containsVideo(videoFile: File): Boolean
    suspend fun deferClip(clipId: String)
    suspend fun approveClip(clipId: String)
    suspend fun deleteClip(clipId: String)
    suspend fun updateReviewState(clipId: String, reviewState: String)
    suspend fun updateApprovalState(clipId: String, approvalState: String)
}
