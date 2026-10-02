package com.rchia.ecocapture.phase0.data

import com.rchia.ecocapture.phase0.data.local.ClipDao
import com.rchia.ecocapture.phase0.data.local.toDomain
import com.rchia.ecocapture.phase0.data.local.toEntity
import com.rchia.ecocapture.phase0.domain.ClipRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomClipRepository(
    private val dao: ClipDao,
    private val appVersion: String? = null,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) : ClipRepository {
    companion object {
        // Shared across repository instances so review decisions cannot race deletion.
        private val mutationLocks = ConcurrentHashMap<String, Mutex>()
    }

    private fun mutationLock(clipId: String): Mutex = mutationLocks.getOrPut(clipId) { Mutex() }
    override fun observeActiveClips(): Flow<List<ClipRecord>> = dao.observeActiveClips().map { it.map { clip -> clip.toDomain() } }
    override fun observeReviewQueue(): Flow<List<ClipRecord>> = dao.observeReviewQueue().map { it.map { clip -> clip.toDomain() } }
    override fun observeClip(clipId: String): Flow<ClipRecord?> = dao.observeClip(clipId).map { it?.toDomain() }
    override suspend fun insert(clip: ClipRecord) = dao.insertIfVideoAbsent(clip.toEntity(appVersion))
    override suspend fun containsVideo(videoFile: File): Boolean =
        dao.findClipIdByVideoPath(videoFile.absolutePath) != null
    override suspend fun deferClip(clipId: String): Unit = mutationLock(clipId).withLock {
        check(dao.deferClip(clipId, System.currentTimeMillis()) == 1) {
            "Recording is unavailable or no longer waiting for a decision."
        }
    }
    override suspend fun approveClip(clipId: String): Unit = mutationLock(clipId).withLock {
        check(dao.approveClip(clipId, System.currentTimeMillis()) == 1) {
            "Recording is unavailable or no longer waiting for a decision."
        }
    }
    override suspend fun deleteClip(clipId: String): Unit = mutationLock(clipId).withLock {
        // Once files start being removed, finish the tombstone even if the screen is disposed.
        withContext(Dispatchers.IO + NonCancellable) {
            val clip = dao.getClip(clipId) ?: throw IOException("Recording is unavailable.")
            // A process death during file removal must never leave an active event or resurrect media.
            dao.markDeletedAndEraseText(clipId, System.currentTimeMillis())
            val video = File(clip.videoPath)
            val metadata = clip.metadataPath?.let(::File)
                ?: File(video.parentFile, "${video.nameWithoutExtension}.json").takeIf { it.exists() }

            removeAndVerify(video)
            metadata?.let(::removeAndVerify)
            check(!video.exists() && (metadata == null || !metadata.exists())) {
                "Recording files could not be removed."
            }
        }
    }

    /** Resume incomplete file removals and purge any historical deleted-event text. */
    suspend fun resumePendingDeletions() {
        var firstFailure: Exception? = null
        dao.deletedClips().forEach { clip ->
            try { deleteClip(clip.clipId) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { if (firstFailure == null) firstFailure = failure }
        }
        firstFailure?.let { throw it }
    }

    private fun removeAndVerify(file: File) {
        if (!file.exists()) return // Allows retry after partial deletion or a missing recording.
        if (!file.isFile || !deleteFile(file) || file.exists()) {
            throw IOException("Could not remove ${file.name}.")
        }
    }
    override suspend fun updateReviewState(clipId: String, reviewState: String) =
        dao.updateReviewState(clipId, reviewState, System.currentTimeMillis())
    override suspend fun updateApprovalState(clipId: String, approvalState: String) =
        dao.updateApprovalState(clipId, approvalState, System.currentTimeMillis())
}
