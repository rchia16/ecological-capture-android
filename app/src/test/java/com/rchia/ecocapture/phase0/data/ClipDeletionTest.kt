package com.rchia.ecocapture.phase0.data

import com.rchia.ecocapture.phase0.data.local.ClipDao
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClipDeletionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun deletionRemovesMediaBeforeTombstoneAndKeepsPathIdentity() = runBlocking {
        val dao = fixture()
        val video = File(dao.clip.videoPath)
        val metadata = File(checkNotNull(dao.clip.metadataPath))
        dao.beforeTombstone = {
            assertFalse(video.exists())
            assertFalse(metadata.exists())
        }

        RoomClipRepository(dao).deleteClip("clip-id")

        assertEquals("DELETED", dao.clip.approvalState)
        assertTrue(dao.clip.updatedAtEpochMs > 0)
        val restartedRepository = RoomClipRepository(dao)
        assertTrue(restartedRepository.observeReviewQueue().first().isEmpty())
        assertTrue(restartedRepository.containsVideo(video))
    }

    @Test
    fun mp4DeletionFailurePreservesMetadataAndDatabaseState() = runBlocking {
        val dao = fixture()
        val repository = RoomClipRepository(dao, deleteFile = { false })

        expectFailure { repository.deleteClip("clip-id") }

        assertTrue(File(dao.clip.videoPath).exists())
        assertTrue(File(checkNotNull(dao.clip.metadataPath)).exists())
        assertEquals("UNDECIDED", dao.clip.approvalState)
        assertEquals(0, dao.tombstoneCalls)
    }

    @Test
    fun verifiesFileDisappearanceEvenWhenDeleteReturnsTrue() = runBlocking {
        val dao = fixture()
        expectFailure { RoomClipRepository(dao, deleteFile = { true }).deleteClip("clip-id") }
        assertEquals(0, dao.tombstoneCalls)
        assertTrue(File(dao.clip.videoPath).exists())
    }

    @Test
    fun sidecarFailureIsReportedAndCanBeRetried() = runBlocking {
        val dao = fixture()
        val repository = RoomClipRepository(dao, deleteFile = {
            if (it.extension == "json") false else it.delete()
        })

        expectFailure { repository.deleteClip("clip-id") }

        assertFalse(File(dao.clip.videoPath).exists())
        assertTrue(File(checkNotNull(dao.clip.metadataPath)).exists())
        assertEquals("UNDECIDED", dao.clip.approvalState)
        RoomClipRepository(dao).deleteClip("clip-id")
        assertEquals("DELETED", dao.clip.approvalState)
        assertFalse(File(checkNotNull(dao.clip.metadataPath)).exists())
    }

    @Test
    fun missingMediaCanStillBeRemovedFromQueue() = runBlocking {
        val dao = fixture(withSidecar = false)
        assertTrue(File(dao.clip.videoPath).delete())
        RoomClipRepository(dao).deleteClip("clip-id")
        assertEquals("DELETED", dao.clip.approvalState)
    }

    @Test
    fun removesMatchingSidecarEvenWhenPathWasNotPersisted() = runBlocking {
        val dao = fixture(withSidecar = false)
        val sidecar = File(folder.root, "clip.json").apply { writeText("metadata") }
        RoomClipRepository(dao).deleteClip("clip-id")
        assertFalse(sidecar.exists())
        assertEquals("DELETED", dao.clip.approvalState)
    }

    @Test
    fun tombstoneFailureDoesNotReportSuccessAndRetryCompletes() = runBlocking {
        val dao = fixture()
        dao.failTombstone = true
        expectFailure { RoomClipRepository(dao).deleteClip("clip-id") }
        assertEquals("UNDECIDED", dao.clip.approvalState)
        assertFalse(File(dao.clip.videoPath).exists())
        dao.failTombstone = false
        RoomClipRepository(dao).deleteClip("clip-id")
        assertEquals("DELETED", dao.clip.approvalState)
    }

    private fun fixture(withSidecar: Boolean = true): DeletionDao {
        val video = folder.newFile("clip.mp4").apply { writeText("disposable test media") }
        val metadata = if (withSidecar) folder.newFile("clip.json").apply { writeText("metadata") } else null
        return DeletionDao(ClipEntity(
            clipId = "clip-id",
            videoPath = video.absolutePath,
            metadataPath = metadata?.absolutePath,
            createdAtEpochMs = 1000,
            durationMs = 1000,
            width = 720,
            height = 1280,
            sampleCount = 7,
            reviewState = "UNREVIEWED",
            approvalState = "UNDECIDED",
            createdByAppVersion = "test",
            updatedAtEpochMs = 1000,
        ))
    }

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("Deletion must not report success on failure")
        } catch (_: IOException) {
            // Expected filesystem or persistence failure.
        }
    }

    private class DeletionDao(var clip: ClipEntity) : ClipDao {
        var tombstoneCalls = 0
        var failTombstone = false
        var beforeTombstone: () -> Unit = { }

        override suspend fun getClip(clipId: String): ClipEntity? = clip.takeIf { it.clipId == clipId }
        override suspend fun markDeleted(clipId: String, updatedAtEpochMs: Long): Int {
            beforeTombstone()
            if (failTombstone) throw IOException("Database unavailable")
            if (clip.clipId != clipId) return 0
            tombstoneCalls++
            clip = clip.copy(approvalState = "DELETED", updatedAtEpochMs = updatedAtEpochMs)
            return 1
        }
        override suspend fun findClipIdByVideoPath(videoPath: String): String? =
            clip.clipId.takeIf { clip.videoPath == videoPath }
        override fun observeReviewQueue(): Flow<List<ClipEntity>> =
            flowOf(listOf(clip).filter { it.approvalState != "DELETED" && it.reviewState in listOf("UNREVIEWED", "DEFERRED") })
        override fun observeActiveClips(): Flow<List<ClipEntity>> = error("Unused")
        override fun observeClip(clipId: String): Flow<ClipEntity?> = error("Unused")
        override suspend fun insert(clip: ClipEntity): Unit = error("Unused")
        override suspend fun approveClip(clipId: String, updatedAtEpochMs: Long): Int = error("Unused")
        override suspend fun deferClip(clipId: String, updatedAtEpochMs: Long): Int = error("Unused")
        override suspend fun updateReviewState(clipId: String, reviewState: String, updatedAtEpochMs: Long): Unit = error("Unused")
        override suspend fun updateApprovalState(clipId: String, approvalState: String, updatedAtEpochMs: Long): Unit = error("Unused")
    }
}
