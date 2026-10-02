package com.rchia.ecocapture.phase0.data

import com.rchia.ecocapture.phase0.data.local.ClipDao
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RoomClipRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun reviewLaterUpdatesTheSelectedClipAndPreservesMedia() = runBlocking {
        val video = folder.newFile("clip.mp4").apply { writeText("original video") }
        val sidecar = folder.newFile("clip.json").apply { writeText("original metadata") }
        val dao = DeferDao()

        RoomClipRepository(dao).deferClip("clip-id")

        assertEquals("clip-id", dao.updatedClipId)
        assertTrue(dao.updatedAt > 0L)
        assertEquals("original video", video.readText())
        assertEquals("original metadata", sidecar.readText())
    }

    @Test
    fun aMissingOrIneligibleRowIsNotReportedAsSuccess() = runBlocking {
        try {
            RoomClipRepository(DeferDao(rowsUpdated = 0)).deferClip("missing")
            fail("Expected a failed decision when no row was updated")
        } catch (_: IllegalStateException) {
            // The UI must remain open and report this failed decision.
        }
    }

    @Test
    fun databaseFailurePropagatesAndPreservesMedia() = runBlocking {
        val video = folder.newFile("clip.mp4").apply { writeText("original video") }
        val sidecar = folder.newFile("clip.json").apply { writeText("original metadata") }
        try {
            RoomClipRepository(DeferDao(failure = IOException("Database unavailable"))).deferClip("clip-id")
            fail("Expected database failure")
        } catch (_: IOException) {
            assertEquals("original video", video.readText())
            assertEquals("original metadata", sidecar.readText())
        }
    }

    @Test
    fun approvalUsesOneDecisionUpdateAndPreservesMedia() = runBlocking {
        val video = folder.newFile("clip.mp4").apply { writeText("original video") }
        val sidecar = folder.newFile("clip.json").apply { writeText("original metadata") }
        val dao = DeferDao()

        RoomClipRepository(dao).approveClip("clip-id")

        assertEquals("clip-id", dao.approvedClipId)
        assertEquals(1, dao.approvalCalls)
        assertTrue(dao.updatedAt > 0L)
        assertEquals("original video", video.readText())
        assertEquals("original metadata", sidecar.readText())
    }

    @Test
    fun approvalRejectsMissingOrIneligibleRows() = runBlocking {
        try {
            RoomClipRepository(DeferDao(rowsUpdated = 0)).approveClip("missing")
            fail("Expected a failed approval when no row was updated")
        } catch (_: IllegalStateException) {
            // Success feedback is only appropriate after an actual database update.
        }
    }

    @Test
    fun approvalDatabaseFailurePropagatesAndPreservesMedia() = runBlocking {
        val video = folder.newFile("clip.mp4").apply { writeText("original video") }
        val sidecar = folder.newFile("clip.json").apply { writeText("original metadata") }
        try {
            RoomClipRepository(DeferDao(failure = IOException("Database unavailable"))).approveClip("clip-id")
            fail("Expected database failure")
        } catch (_: IOException) {
            assertEquals("original video", video.readText())
            assertEquals("original metadata", sidecar.readText())
        }
    }

    private class DeferDao(
        private val rowsUpdated: Int = 1,
        private val failure: Exception? = null,
    ) : ClipDao {
        var updatedClipId: String? = null
        var updatedAt: Long = 0
        var approvedClipId: String? = null
        var approvalCalls: Int = 0

        override suspend fun approveClip(clipId: String, updatedAtEpochMs: Long): Int {
            failure?.let { throw it }
            approvedClipId = clipId
            updatedAt = updatedAtEpochMs
            approvalCalls += 1
            return rowsUpdated
        }

        override suspend fun deferClip(clipId: String, updatedAtEpochMs: Long): Int {
            failure?.let { throw it }
            updatedClipId = clipId
            updatedAt = updatedAtEpochMs
            return rowsUpdated
        }

        override fun observeActiveClips(): Flow<List<ClipEntity>> = error("Unused in decision tests")
        override fun observeReviewQueue(): Flow<List<ClipEntity>> = error("Unused in decision tests")
        override fun observeClip(clipId: String): Flow<ClipEntity?> = error("Unused in decision tests")
        override suspend fun insert(clip: ClipEntity): Unit = error("Unused in decision tests")
        override suspend fun findClipIdByVideoPath(videoPath: String): String? = error("Unused in decision tests")
        override suspend fun getClip(clipId: String): ClipEntity? = error("Unused in decision tests")
        override suspend fun markDeleted(clipId: String, updatedAtEpochMs: Long): Int = error("Unused in decision tests")
        override suspend fun eraseAnnotations(clipId: String) = Unit
        override suspend fun eraseVlmRuns(clipId: String) = Unit
        override suspend fun deletedClips(): List<ClipEntity> = emptyList()
        override suspend fun updateReviewState(clipId: String, reviewState: String, updatedAtEpochMs: Long): Unit = error("Unused in decision tests")
        override suspend fun updateApprovalState(clipId: String, approvalState: String, updatedAtEpochMs: Long): Unit = error("Unused in decision tests")
    }
}
