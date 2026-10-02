package com.rchia.ecocapture.phase0.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.ui.review.*
import com.rchia.ecocapture.phase0.vlm.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Disposable Room/media fixtures only; never edits participant recordings or model files. */
@RunWith(AndroidJUnit4::class)
class Checkpoint8RecoveryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val name = "checkpoint8-$token.db"
    private val directory = File(context.cacheDir, "checkpoint8-$token")
    private lateinit var database: EcologicalCaptureDatabase
    private lateinit var video: File
    private lateinit var sidecar: File
    private var scope: CoroutineScope? = null
    @Before fun setup(): Unit = runBlocking {
        directory.mkdirs()
        video = File(directory, "fixture.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        sidecar = File(directory, "fixture.json").apply { writeText("{}") }
        open()
        database.clipDao().insert(clip("clip"))
    }
    private fun clip(id: String) = ClipEntity(id, video.path, sidecar.path, 1000, 2000, 640, 480, 14,
        "DEFERRED", "UNDECIDED", "checkpoint8-test", 1000)
    private fun open() { database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build() }
    private fun reopen() { database.close(); open() }
    private suspend fun result() = FakeVlmEngine(delayMs = 0, output = "Scene:\nA corridor leads to a doorway.\nUncertain or unclear details:\nSmall text and route numbers are unclear.").let {
        it.load(); try { it.generate(VlmRequest("clip", video)) as VlmResult.Success } finally { it.unload() }
    }
    @After fun cleanup(): Unit = runBlocking {
        scope?.coroutineContext?.get(Job)?.cancelAndJoin()
        VlmExecutionGate.releaseCapture()
        database.close(); context.deleteDatabase(name)
        directory.listFiles()?.forEach { it.delete() }; directory.delete()
    }

    @Test fun completedRequestReopensExactlyAndRetryDoesNotOverwriteIt(): Unit = runBlocking {
        val before = database.clipDao().getClip("clip")
        val generated = result()
        val saved = VlmRunRepository(database).save(generated, "stable-request")
        reopen()
        assertEquals(saved, database.vlmRunDao().getRun("stable-request"))
        assertEquals(saved, VlmRunRepository(database).save(generated.copy(rawOutput = "Changed", description = "Changed"), "stable-request"))
        assertEquals(1, database.vlmRunDao().history("clip").size)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertNull(saved.firstPresentedAtEpochMs)
        assertTrue(saved.rawOutput.contains("are unclear"))
        assertEquals(before, database.clipDao().getClip("clip"))
    }

    @Test fun deletionErasesAllTextAndRestartFinishesPartialMediaRemoval(): Unit = runBlocking {
        val generated = VlmRunRepository(database).save(result())
        AnnotationRepository(database).saveAmendment(generated.vlmRunId, "My edited account", 2000)
        database.clipDao().insert(clip("other").copy(videoPath = File(directory, "other.mp4").path,
            approvalState = "APPROVED", reviewState = "REVIEWED"))
        val other = database.clipDao().getClip("other")
        val failing = RoomClipRepository(database.clipDao(), deleteFile = { false })
        assertTrue(runCatching { failing.deleteClip("clip") }.isFailure)
        assertTrue(video.exists())
        assertEquals("DELETED", database.clipDao().getClip("clip")?.approvalState)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        reopen()
        LegacyClipReconciler(RoomClipRepository(database.clipDao()), directory).reconcile()
        assertFalse(video.exists()); assertFalse(sidecar.exists())
        assertTrue(database.clipDao().observeReviewQueue().first().isEmpty())
        assertEquals(other, database.clipDao().getClip("other"))
        assertTrue(RoomClipRepository(database.clipDao()).containsVideo(video))
    }

    @Test fun committedDeletionBlocksLateResultsAndAmendments(): Unit = runBlocking {
        val generated = result()
        val saved = VlmRunRepository(database).save(generated, "request")
        database.clipDao().markDeletedAndEraseText("clip", 3000)
        database.clipDao().updateApprovalState("clip", "APPROVED", 4000)
        database.clipDao().updateReviewState("clip", "REVIEWED", 4000)
        database.clipDao().markDeletedAndEraseText("clip", 5000)
        val tombstone = database.clipDao().getClip("clip")!!
        assertEquals("DELETED", tombstone.approvalState)
        assertEquals("DEFERRED", tombstone.reviewState)
        assertEquals(3000L, tombstone.updatedAtEpochMs)
        assertTrue(runCatching { VlmRunRepository(database).save(generated, "request") }.isFailure)
        assertTrue(runCatching { AnnotationRepository(database).saveAmendment(saved.vlmRunId, "Late edit", 4000) }.isFailure)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        assertTrue(database.annotationDao().history("clip").isEmpty())
    }

    @Test fun databaseFailureBeforeTombstonePreservesMedia(): Unit = runBlocking {
        val repository = RoomClipRepository(database.clipDao())
        database.close()
        assertTrue(runCatching { repository.deleteClip("clip") }.isFailure)
        assertTrue(video.exists()); assertTrue(sidecar.exists())
        open()
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
    }

    @Test fun typedFailuresNeverBecomeSuccessOrParticipantText(): Unit = runBlocking {
        val before = database.clipDao().getClip("clip")
        for (failure in listOf(VlmFailure.ModelMissing, VlmFailure.HashMismatch, VlmFailure.FrameExtractionFailed,
            VlmFailure.NativeFailure(13), VlmFailure.OutOfMemory)) {
            val fake = if (failure == VlmFailure.ModelMissing || failure == VlmFailure.HashMismatch)
                FakeVlmEngine(loadFailure = failure) else FakeVlmEngine(generationFailure = failure)
            var loadCalled = false
            var unloaded = false
            val tracked = object : VlmEngine by fake {
                override suspend fun load(): VlmLoadResult { loadCalled = true; return fake.load() }
                override suspend fun unload() { fake.unload(); unloaded = true }
            }
            val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            scope = activeScope
            val editor = DescriptionEditor(AnnotationRepository(database), activeScope)
            val workflow = VlmReviewWorkflow(VlmRunRepository(database), tracked, editor, activeScope)
            withContext(Dispatchers.Main) { workflow.selectClip("clip"); editor.selectClip("clip") }
            withTimeout(5000) { workflow.state.first { !it.isLoading } }
            withContext(Dispatchers.Main) { workflow.generate(video) }
            val failed = withTimeoutOrNull(5000) { workflow.state.first { it.phase == VlmReviewPhase.ERROR } }
            assertNotNull("Failure $failure left workflow in ${workflow.state.value}; load=$loadCalled unload=$unloaded", failed)
            assertTrue(database.vlmRunDao().history("clip").isEmpty())
            assertTrue(database.annotationDao().history("clip").isEmpty())
            assertEquals(before, database.clipDao().getClip("clip"))
            activeScope.coroutineContext[Job]!!.cancelAndJoin()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), video.readBytes())
    }

    @Test fun leavingForegroundCancelsAndUnloadsBeforeReuse(): Unit = runBlocking {
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activeScope
        val editor = DescriptionEditor(AnnotationRepository(database), activeScope)
        val workflow = VlmReviewWorkflow(VlmRunRepository(database), FakeVlmEngine(delayMs = 60000), editor, activeScope)
        withContext(Dispatchers.Main) { workflow.selectClip("clip") }
        withTimeout(5000) { workflow.state.first { !it.isLoading } }
        withContext(Dispatchers.Main) { workflow.generate(video) }
        withTimeout(5000) { workflow.state.first { it.phase == VlmReviewPhase.RUNNING } }
        withContext(Dispatchers.Main) { workflow.cancelForeground() }
        withTimeout(5000) { workflow.state.first { it.phase == VlmReviewPhase.CANCELLED } }
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertNotNull(VlmExecutionGate.execute(wait = false) { "reusable" })
    }

    @Test fun recordingWaitsForCancelledInferenceCleanup(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        var released = false
        val engine = launch(Dispatchers.Default) {
            VlmExecutionGate.execute {
                try { started.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(50); released = true } }
            }
        }
        withTimeout(5000) { started.await() }
        withTimeout(5000) { VlmExecutionGate.reserveForCapture() }
        assertTrue(released)
        assertTrue(VlmExecutionGate.captureBusy)
        assertNull(VlmExecutionGate.execute(wait = false) { "must not run during recording" })
        VlmExecutionGate.releaseCapture()
        engine.join()
        assertEquals("ready", VlmExecutionGate.execute(wait = false) { "ready" })
    }
}
