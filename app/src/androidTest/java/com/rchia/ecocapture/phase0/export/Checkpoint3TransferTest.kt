package com.rchia.ecocapture.phase0.export

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.AnnotationRepository
import com.rchia.ecocapture.phase0.data.RoomClipRepository
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.transfer.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Checkpoint3TransferTest {
    private suspend fun fixture(block: suspend (EcologicalCaptureDatabase, File, ClipEntity, String) -> Unit) {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val id = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "checkpoint3-$id").apply { check(mkdir()) }
        val recordings = File(root, "recordings").apply { mkdir() }
        val video = File(recordings, "synthetic.mp4")
        i.context.assets.open("checkpoint5/short.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val name = "checkpoint3-$id.db"
        val db = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
        val clip = ClipEntity(id, video.path, null, 1000, 1000, 320, 180, 2, "REVIEWED", "APPROVED", "synthetic", 2000)
        try { db.clipDao().insert(clip); block(db, root, clip, name) }
        finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
    private fun repo(db: EcologicalCaptureDatabase, root: File, enabled: () -> Boolean = { true },
        fail: () -> Boolean = { false }, delayMs: Long = 0, onAttempt: suspend (String) -> Unit = {}) = TransferRepository(db,
        File(root, "event-exports"), File(root, "recordings"), object : TransferEligibility {
            override fun permitsSimulation(clipId: String) = enabled()
        }, FakeTransferTransport(delayMs, fail), "checkpoint3-test", onAttempt = onAttempt)
    private suspend fun expectRejected(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
    }

    @Test fun approvalAndUnprovisionedInstallNeverQueue(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            assertTrue(db.transferJobDao().observeAll().first().isEmpty())
            assertTrue(db.exportSnapshotDao().observeAll().first().isEmpty())
            expectRejected { repo(db, root, enabled = { false }).send(clip.clipId) }
            assertTrue(db.transferJobDao().observeAll().first().isEmpty())
            assertEquals(clip, db.clipDao().getClip(clip.clipId))
        }
    }
    @Test fun missingUnapprovedDeletedOrCorruptClipCannotCreateJob(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            val repository = repo(db, root)
            expectRejected { repository.send(UUID.randomUUID().toString()) }
            db.clipDao().updateApprovalState(clip.clipId, "UNDECIDED", 3000)
            expectRejected { repository.send(clip.clipId) }
            db.clipDao().updateApprovalState(clip.clipId, "APPROVED", 4000)
            val original = File(clip.videoPath).readBytes()
            File(clip.videoPath).writeText("corrupt fixture")
            expectRejected { repository.send(clip.clipId) }
            File(clip.videoPath).writeBytes(original)
            assertTrue(File(clip.videoPath).delete())
            expectRejected { repository.send(clip.clipId) }
            db.clipDao().markDeletedAndEraseText(clip.clipId, 5000)
            expectRejected { repository.send(clip.clipId) }
            assertTrue(db.transferJobDao().observeAll().first().isEmpty())
        }
    }
    @Test fun concurrentTapsCreateOneSnapshotAndOneSimulatedJob(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            val repository = repo(db, root)
            val before = EventManifest.digest(File(clip.videoPath))
            val results = listOf(async(Dispatchers.IO) { repository.send(clip.clipId) },
                async(Dispatchers.IO) { repository.send(clip.clipId) }).awaitAll()
            assertEquals(results[0].jobId, results[1].jobId)
            assertEquals("SIMULATED_COMPLETE", results[0].state)
            assertEquals("SIMULATION", results[0].transportMode)
            assertEquals(1, db.exportSnapshotDao().observeAll().first().size)
            assertEquals(1, db.transferJobDao().observeAll().first().size)
            assertEquals(clip, db.clipDao().getClip(clip.clipId))
            assertEquals(before, EventManifest.digest(File(clip.videoPath)))
        }
    }
    @Test fun fakeFailureRetainsFrozenBytesAndRetryUsesSameJob(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            val failure = AtomicBoolean(true)
            val repository = repo(db, root, fail = { failure.getAndSet(false) })
            val before = EventManifest.digest(File(clip.videoPath))
            val job = repository.send(clip.clipId)
            assertEquals("FAILED_RETRYABLE", job.state)
            val frozen = db.exportSnapshotDao().get(job.snapshotId)!!
            val completed = repository.retry(job.jobId)
            assertEquals(job.jobId, completed.jobId)
            assertEquals("SIMULATED_COMPLETE", completed.state)
            assertEquals(1, completed.retryCount)
            assertEquals(frozen, db.exportSnapshotDao().get(job.snapshotId))
            assertEquals(before, EventManifest.digest(File(clip.videoPath)))
            assertEquals(1, db.transferJobDao().observeAll().first().size)
        }
    }
    @Test fun newLocalEditProducesExplicitLinkedVersionAndPreservesOldBytes(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            val annotations = AnnotationRepository(db)
            val first = annotations.addDescription(clip.clipId, "Original synthetic description", 2500)
            val repository = repo(db, root)
            val job = repository.send(clip.clipId)
            val oldJson = File(root, "event-exports/${job.snapshotId}/event_${clip.clipId}_snapshot_${job.snapshotId}/event.json")
            val frozen = oldJson.readBytes()
            annotations.reviseDescription(clip.clipId, first.annotationId, "New synthetic description", 3000)
            val current = RoomEventSnapshotStore(db).read(clip.clipId)
            assertNotEquals(job.sourceVersionSha256, ExportSnapshotRepository.version(current))
            assertEquals(1, db.transferJobDao().observeAll().first().size) // Editing does not send.
            val updated = repository.send(clip.clipId)
            assertNotEquals(job.snapshotId, updated.snapshotId)
            assertEquals(job.snapshotId, db.exportSnapshotDao().get(updated.snapshotId)!!.previousSnapshotId)
            assertEquals("SUPERSEDED", db.exportSnapshotDao().get(job.snapshotId)!!.snapshotState)
            assertArrayEquals(frozen, oldJson.readBytes())
        }
    }
    @Test fun deleteCancelsJobsPurgesExportsAndKeepsTombstone(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            AnnotationRepository(db).addDescription(clip.clipId, "Disposable text", 2500)
            val job = repo(db, root).send(clip.clipId)
            assertTrue(File(root, "event-exports/${job.snapshotId}").exists())
            RoomClipRepository(db.clipDao()).deleteClip(clip.clipId)
            assertEquals("CANCELLED", db.transferJobDao().get(job.jobId)!!.state)
            assertEquals(db.clipDao().getClip(clip.clipId)!!.updatedAtEpochMs, db.transferJobDao().get(job.jobId)!!.updatedAtEpochMs)
            assertEquals("PURGED", db.exportSnapshotDao().get(job.snapshotId)!!.snapshotState)
            assertFalse(File(root, "event-exports/${job.snapshotId}").exists())
            assertFalse(File(clip.videoPath).exists())
            assertEquals("DELETED", db.clipDao().getClip(clip.clipId)!!.approvalState)
            assertTrue(db.annotationDao().history(clip.clipId).isEmpty())
            assertTrue(db.clipDao().observeApprovedSources().first().isEmpty())
            expectRejected { repo(db, root).send(clip.clipId) }
        }
    }
    @Test fun cancelDuringPreparationPublishesNoSnapshot(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            val job = repo(db, root, onAttempt = { db.transferJobDao().cancel(it, 3000) }).send(clip.clipId)
            assertEquals("CANCELLED", job.state)
            assertTrue(db.exportSnapshotDao().observeAll().first().isEmpty())
            assertTrue(File(root, "event-exports").list().orEmpty().isEmpty())
            assertTrue(File(clip.videoPath).exists())
        }
    }
    @Test fun interruptedJobReopensAsRetryableWithoutAutomaticSimulation(): Unit = runBlocking {
        fixture { db, root, clip, name ->
            try { repo(db, root, onAttempt = { throw CancellationException("simulated death") }).send(clip.clipId); fail("Expected interruption") }
            catch (_: CancellationException) { }
            val created = db.transferJobDao().latest(clip.clipId)!!
            assertEquals("PREPARING", created.state)
            db.close()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val reopened = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
            try {
                val repository = repo(reopened, root)
                repository.recoverInterrupted()
                assertEquals("FAILED_RETRYABLE", reopened.transferJobDao().get(created.jobId)!!.state)
                assertTrue(reopened.exportSnapshotDao().observeAll().first().isEmpty())
                assertEquals("SIMULATED_COMPLETE", repository.retry(created.jobId).state)
                assertEquals(1, reopened.transferJobDao().observeAll().first().size)
            } finally { reopened.close() }
        }
    }
    @Test fun cancellationDuringSimulationKeepsSnapshotAndOriginalRetryable(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            val original = EventManifest.digest(File(clip.videoPath))
            val repository = repo(db, root, delayMs = 1000)
            val attempt = async(Dispatchers.IO) { repository.send(clip.clipId) }
            val job = withTimeout(5000) { db.transferJobDao().observeAll().first { rows -> rows.any { it.state == "SIMULATING" } }.single() }
            attempt.cancelAndJoin()
            assertEquals("FAILED_RETRYABLE", db.transferJobDao().get(job.jobId)!!.state)
            assertNotNull(db.exportSnapshotDao().get(job.snapshotId))
            assertEquals(original, EventManifest.digest(File(clip.videoPath)))
            assertTrue(File(root, "event-exports/${job.snapshotId}").exists())
        }
    }

    @Test fun versionTwoMigrationRetainsEveryOldRowAndStartsWithNoJobs(): Unit = runBlocking {
        fixture { db, _, clip, name ->
            val annotations = AnnotationRepository(db)
            annotations.addDescription(clip.clipId, "Original annotation", 2500)
            val oldRun = VlmRunEntity(UUID.randomUUID().toString(), clip.clipId, "simulated", "fixture", "lm", "projector",
                "local", "commit", "prompt", 3000, null, 30, "{}", "{}", "Exact raw\ntext", "Exact description", "NOT_PRESENTED")
            db.vlmRunDao().insert(oldRun)
            val others = listOf(clip.copy(clipId = UUID.randomUUID().toString(), approvalState = "UNDECIDED", reviewState = "DEFERRED"),
                clip.copy(clipId = UUID.randomUUID().toString(), approvalState = "DELETED", reviewState = "UNREVIEWED"))
            others.forEach { db.clipDao().insert(it) }
            val expected = RoomEventSnapshotStore(db).read(clip.clipId)
            db.close()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Removing ONLY the new tables reconstructs the exact unchanged v2 entity schema and rows.
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { legacy ->
                legacy.execSQL("DROP TABLE export_snapshots")
                legacy.execSQL("DROP TABLE transfer_jobs")
                legacy.execSQL("DELETE FROM room_master_table")
                legacy.version = 2
            }
            val migrated = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name)
                .addMigrations(EcologicalCaptureDatabase.MIGRATION_2_3, EcologicalCaptureDatabase.MIGRATION_3_4).build()
            try {
                assertEquals(expected, RoomEventSnapshotStore(migrated).read(clip.clipId))
                others.forEach { assertEquals(it, migrated.clipDao().getClip(it.clipId)) }
                assertTrue(migrated.transferJobDao().observeAll().first().isEmpty())
                assertTrue(migrated.exportSnapshotDao().observeAll().first().isEmpty())
                assertEquals(4, migrated.openHelper.writableDatabase.version)
            } finally { migrated.close() }
        }
    }
}
