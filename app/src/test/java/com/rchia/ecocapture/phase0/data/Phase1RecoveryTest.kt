package com.rchia.ecocapture.phase0.data

import com.rchia.ecocapture.phase0.capture.RecordingResult
import com.rchia.ecocapture.phase0.data.local.ClipDao
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import com.rchia.ecocapture.phase0.domain.ApprovalState
import com.rchia.ecocapture.phase0.domain.ReviewState
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests of repository/reconciliation logic; these do not simulate Android process death. */
class Phase1RecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun completedRecordingMapsMetadataAndStartsUndecided() = runBlocking {
        val dao = MemoryDao()
        val repository = RoomClipRepository(dao, "test-version")
        val result = completed()
        repository.addCompletedRecording(result)
        val row = checkNotNull(dao.getClip(result.clipId))
        assertEquals(result.videoFile.absolutePath, row.videoPath)
        assertEquals(result.metadataFile?.absolutePath, row.metadataPath)
        assertEquals(result.createdAtEpochMs, row.createdAtEpochMs)
        assertEquals(720, row.width)
        assertEquals(1280, row.height)
        assertEquals(14L, row.sampleCount)
        assertEquals("UNREVIEWED", row.reviewState)
        assertEquals("UNDECIDED", row.approvalState)
        assertEquals("test-version", row.createdByAppVersion)
    }

    @Test
    fun insertionFailurePreservesMediaAndReconciliationRecoversIdentity() = runBlocking {
        val dao = MemoryDao()
        val result = completed()
        val videoBytes = result.videoFile.readBytes()
        val metadataBytes = checkNotNull(result.metadataFile).readBytes()
        dao.failInserts = true
        try {
            RoomClipRepository(dao).addCompletedRecording(result)
            fail("Expected insertion failure")
        } catch (_: IOException) {
            assertArrayEquals(videoBytes, result.videoFile.readBytes())
            assertArrayEquals(metadataBytes, result.metadataFile.readBytes())
        }
        dao.failInserts = false
        LegacyClipReconciler(RoomClipRepository(dao), folder.root).reconcile()
        assertEquals(1, dao.rows.value.size)
        assertEquals(result.clipId, dao.rows.value.single().clipId)
        assertEquals(result.createdAtEpochMs, dao.rows.value.single().createdAtEpochMs)
    }

    @Test
    fun emptyMissingAndPartialFilesDoNotGetCompletedRows() = runBlocking {
        val dao = MemoryDao()
        val repository = RoomClipRepository(dao)
        val invalidFiles = listOf(
            folder.newFile("empty.mp4"),
            File(folder.root, "missing.mp4"),
            folder.newFile("clip.partial").apply { writeText("unfinished") },
        )
        invalidFiles.forEach { video ->
            try {
                repository.addCompletedRecording(RecordingResult.Completed("invalid", video, null, 0, 0, 0, 0))
                fail("Expected invalid completed media to be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun legacyScanIncludesMp4WithAndWithoutSidecarAndIgnoresPartial() = runBlocking {
        val dao = MemoryDao()
        val withMetadata = completed()
        folder.newFile("legacy.mp4").writeText("legacy video")
        folder.newFile("unfinished.partial").writeText("partial")
        val reconciler = LegacyClipReconciler(RoomClipRepository(dao), folder.root)
        reconciler.reconcile()
        reconciler.reconcile()
        assertEquals(2, dao.rows.value.size)
        assertEquals(2000L, checkNotNull(dao.getClip(withMetadata.clipId)).durationMs)
        val legacy = dao.rows.value.single { it.videoPath.endsWith("legacy.mp4") }
        assertEquals(0L, legacy.durationMs)
        assertEquals(0, legacy.width)
        assertEquals(0L, legacy.sampleCount)
        assertNull(legacy.metadataPath)
    }

    @Test
    fun scanDoesNotDuplicateAnExistingRecordingOrResetApproval() = runBlocking {
        val dao = MemoryDao()
        val result = completed()
        val repository = RoomClipRepository(dao)
        repository.addCompletedRecording(result)
        repository.approveClip(result.clipId)
        LegacyClipReconciler(repository, folder.root).reconcile()
        repository.addCompletedRecording(result.copy(clipId = UUID.randomUUID().toString()))
        assertEquals(1, dao.rows.value.size)
        assertEquals("APPROVED", dao.rows.value.single().approvalState)
    }

    @Test
    fun malformedAndOverflowingMetadataFallsBackSafely() = runBlocking {
        val dao = MemoryDao()
        folder.newFile("legacy.mp4").writeText("video")
        folder.newFile("legacy.json").writeText("""{"width":999999999999,"height":-1,"samples":"unknown","duration_ms":-5}""")
        LegacyClipReconciler(RoomClipRepository(dao), folder.root).reconcile()
        val row = dao.rows.value.single()
        assertEquals(0, row.width)
        assertEquals(0, row.height)
        assertEquals(0L, row.durationMs)
        assertEquals(0L, row.sampleCount)
    }

    @Test
    fun repositoryFlowAReadsDefersAndApprovesWithoutChangingMedia() = runBlocking {
        val dao = MemoryDao()
        val result = completed()
        RoomClipRepository(dao).addCompletedRecording(result)
        val original = result.videoFile.readBytes()
        var repository = RoomClipRepository(dao)
        assertEquals(ReviewState.UNREVIEWED, repository.observeClip(result.clipId).first()?.reviewState)
        // Reading a clip or its queue never changes its decision.
        assertEquals(ApprovalState.UNDECIDED, repository.observeReviewQueue().first().single().approvalState)
        repository.deferClip(result.clipId)
        repository = RoomClipRepository(dao)
        assertEquals(ReviewState.DEFERRED, repository.observeReviewQueue().first().single().reviewState)
        repository.approveClip(result.clipId)
        repository = RoomClipRepository(dao)
        assertTrue(repository.observeReviewQueue().first().isEmpty())
        assertEquals(ApprovalState.APPROVED, repository.observeClip(result.clipId).first()?.approvalState)
        assertArrayEquals(original, result.videoFile.readBytes())
    }

    @Test
    fun repositoryFlowBDeletesAndReconciliationDoesNotRestoreClip() = runBlocking {
        val dao = MemoryDao()
        val result = completed()
        RoomClipRepository(dao).addCompletedRecording(result)
        RoomClipRepository(dao).deleteClip(result.clipId)
        val repository = RoomClipRepository(dao)
        LegacyClipReconciler(repository, folder.root).reconcile()
        assertFalse(result.videoFile.exists())
        assertFalse(checkNotNull(result.metadataFile).exists())
        assertTrue(repository.observeReviewQueue().first().isEmpty())
        assertEquals(1, dao.rows.value.size)
        assertEquals(ApprovalState.DELETED, repository.observeClip(result.clipId).first()?.approvalState)
        assertEquals(0, dao.approveClip(result.clipId, 10000))
        assertEquals(0, dao.deferClip(result.clipId, 10000))
    }

    private fun completed(): RecordingResult.Completed {
        val id = UUID.randomUUID().toString()
        val video = folder.newFile("clip.mp4").apply { writeText("disposable test media") }
        val metadata = folder.newFile("clip.json").apply {
            writeText("""{"clip_id":"$id","created_at_epoch_ms":1700000000000,"duration_ms":2000,"width":720,"height":1280,"samples":14}""")
        }
        return RecordingResult.Completed(id, video, metadata, 2000, 720, 1280, 14, 1700000000000)
    }

    private class MemoryDao : ClipDao {
        override suspend fun eraseAnnotations(clipId: String) = Unit
        override suspend fun eraseVlmRuns(clipId: String) = Unit
        override suspend fun deletedClips(): List<ClipEntity> = rows.value.filter { it.approvalState == "DELETED" }
        val rows = MutableStateFlow<List<ClipEntity>>(emptyList())
        var failInserts = false
        override fun observeActiveClips(): Flow<List<ClipEntity>> = rows.map { list -> list.filter { it.approvalState != "DELETED" } }
        override fun observeReviewQueue(): Flow<List<ClipEntity>> = rows.map { list ->
            list.filter { it.approvalState != "DELETED" && it.reviewState in listOf("UNREVIEWED", "DEFERRED") }
        }
        override fun observeClip(clipId: String): Flow<ClipEntity?> = rows.map { list -> list.find { it.clipId == clipId } }
        override suspend fun getClip(clipId: String): ClipEntity? = rows.value.find { it.clipId == clipId }
        override suspend fun findClipIdByVideoPath(videoPath: String): String? = rows.value.find { it.videoPath == videoPath }?.clipId
        override suspend fun insert(clip: ClipEntity) {
            if (failInserts) throw IOException("Database unavailable")
            if (getClip(clip.clipId) == null) rows.value = rows.value + clip
        }
        override suspend fun deferClip(clipId: String, updatedAtEpochMs: Long): Int = decide(clipId, "DEFERRED", "UNDECIDED", updatedAtEpochMs)
        override suspend fun approveClip(clipId: String, updatedAtEpochMs: Long): Int = decide(clipId, "REVIEWED", "APPROVED", updatedAtEpochMs)
        private suspend fun decide(id: String, review: String, approval: String, timestamp: Long): Int {
            val clip = getClip(id) ?: return 0
            if (clip.approvalState != "UNDECIDED" || clip.reviewState !in listOf("UNREVIEWED", "DEFERRED")) return 0
            rows.value = rows.value.map { if (it.clipId == id) it.copy(reviewState = review, approvalState = approval, updatedAtEpochMs = timestamp) else it }
            return 1
        }
        override suspend fun markDeleted(clipId: String, updatedAtEpochMs: Long): Int {
            if (getClip(clipId) == null) return 0
            rows.value = rows.value.map { if (it.clipId == clipId) it.copy(approvalState = "DELETED", updatedAtEpochMs = updatedAtEpochMs) else it }
            return 1
        }
        override suspend fun updateReviewState(clipId: String, reviewState: String, updatedAtEpochMs: Long): Unit = error("Unused")
        override suspend fun updateApprovalState(clipId: String, approvalState: String, updatedAtEpochMs: Long): Unit = error("Unused")
    }
}
