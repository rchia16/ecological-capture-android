package com.rchia.ecocapture.phase0.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.domain.AnnotationSource
import com.rchia.ecocapture.phase0.vlm.VlmPrompt
import com.rchia.ecocapture.phase0.vlm.VlmModelInfo
import com.rchia.ecocapture.phase0.vlm.VlmResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Room/disk tests with a synthetic output fixture, not a model accuracy test. */
@RunWith(AndroidJUnit4::class)
class VlmOutputPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: EcologicalCaptureDatabase
    private val databaseName = "vlm_prompt_v2_${UUID.randomUUID()}.db"

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        open()
    }
    private fun open() {
        database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, databaseName)
            .addMigrations(EcologicalCaptureDatabase.MIGRATION_1_2).build()
    }
    private fun reopen() { database.close(); open() }
    @After fun tearDown() { database.close(); context.deleteDatabase(databaseName) }

    @Test fun uncertaintyAndCoarseLayoutSurvivePersistenceAndReopenVerbatim() = runBlocking {
        database.clipDao().insert(clip("UNREVIEWED", "UNDECIDED"))
        val original = run()
        database.vlmRunDao().insert(original)
        reopen()
        val persisted = requireNotNull(database.vlmRunDao().getRun(original.vlmRunId))
        assertEquals(original, persisted)
        assertTrue(persisted.rawOutput.contains("A path leads to a doorway; stairs are visible to its right."))
        assertTrue(persisted.rawOutput.contains("No clearly legible text relevant to the scene."))
        assertTrue(persisted.rawOutput.contains("The date, price and route numbers are unclear."))
        assertTrue(persisted.rawOutput.contains("The participant's actual problem cannot be determined from the images."))
        assertEquals(VlmPrompt.VERSION, persisted.promptVersion)
        assertNull(persisted.firstPresentedAtEpochMs)
    }

    @Test fun unchangedAndEditedAmendmentsRemainSeparateFromImmutableOriginal() = runBlocking {
        database.clipDao().insert(clip("DEFERRED", "UNDECIDED"))
        val original = run()
        database.vlmRunDao().insert(original)
        val repository = AnnotationRepository(database)
        val endorsement = repository.saveAmendment(original.vlmRunId, original.description, 2000)
        val edit = repository.saveAmendment(original.vlmRunId, "I was unsure which entrance to use.", 3000)
        reopen()
        assertEquals(original.copy(disposition = "AMENDED"), database.vlmRunDao().getRun(original.vlmRunId))
        val history = database.annotationDao().history("clip")
        assertEquals(2, history.size)
        assertFalse(history[0].isCurrent)
        assertEquals(original.description, history[0].text)
        assertEquals(endorsement.annotationId, edit.supersedesAnnotationId)
        assertEquals(edit, database.annotationDao().current("clip"))
        history.forEach {
            assertEquals(AnnotationSource.PARTICIPANT_AMENDMENT.name, it.source)
            assertEquals(original.vlmRunId, it.parentVlmRunId)
        }
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
    }

    @Test fun duplicateRunCannotOverwriteOutputOrProvenanceAndApprovalIsIndependent() = runBlocking {
        database.clipDao().insert(clip("REVIEWED", "APPROVED"))
        val original = run()
        database.vlmRunDao().insert(original)
        var rejected = false
        try { database.vlmRunDao().insert(original.copy(rawOutput = "guessed text", promptVersion = "wrong")) }
        catch (_: android.database.sqlite.SQLiteConstraintException) { rejected = true }
        assertTrue(rejected)
        AnnotationRepository(database).saveAmendment(original.vlmRunId, "Participant edit", 2000)
        assertEquals(original.copy(disposition = "AMENDED"), database.vlmRunDao().history("clip").single())
        assertEquals("APPROVED", database.clipDao().getClip("clip")?.approvalState)
        assertEquals("REVIEWED", database.clipDao().getClip("clip")?.reviewState)
    }

    @Test fun migrationPreservesAllExistingClipFieldsAndDecisionStates() = runBlocking {
        database.close()
        context.deleteDatabase(databaseName)
        val legacy = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null)
        legacy.execSQL("""CREATE TABLE clips (
            clipId TEXT NOT NULL PRIMARY KEY, videoPath TEXT NOT NULL, metadataPath TEXT,
            createdAtEpochMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, width INTEGER NOT NULL,
            height INTEGER NOT NULL, sampleCount INTEGER NOT NULL, reviewState TEXT NOT NULL,
            approvalState TEXT NOT NULL, createdByAppVersion TEXT, updatedAtEpochMs INTEGER NOT NULL
        )""")
        val originals = listOf(
            clip("REVIEWED", "APPROVED").copy(clipId = "approved"),
            clip("DEFERRED", "UNDECIDED").copy(clipId = "deferred"),
            clip("UNREVIEWED", "DELETED").copy(clipId = "deleted"),
        )
        originals.forEach {
            legacy.execSQL("INSERT INTO clips VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(
                it.clipId, it.videoPath, it.metadataPath, it.createdAtEpochMs, it.durationMs,
                it.width, it.height, it.sampleCount, it.reviewState, it.approvalState,
                it.createdByAppVersion, it.updatedAtEpochMs,
            ))
        }
        legacy.version = 1
        legacy.close()
        open()
        originals.forEach { assertEquals(it, database.clipDao().getClip(it.clipId)) }
        assertTrue(database.vlmRunDao().history("approved").isEmpty())
        assertTrue(database.annotationDao().history("approved").isEmpty())
    }

    @Test fun participantInsertAndRevisionPreserveTextHistoryAndCurrentQuery() = runBlocking {
        database.clipDao().insert(clip("DEFERRED", "UNDECIDED"))
        val repository = AnnotationRepository(database)
        assertNull(repository.current("clip"))
        val original = repository.addDescription("clip", "  I was unsure where the entrance was.\n", 1000)
        val revision = repository.reviseDescription("clip", original.annotationId, "I found the doorway on the right.", 2000)
        reopen()
        val reloaded = AnnotationRepository(database)
        assertEquals(revision, reloaded.current("clip"))
        assertEquals(revision, reloaded.observeCurrent("clip").first())
        val history = reloaded.history("clip")
        assertEquals(listOf(original.copy(isCurrent = false), revision), history)
        assertEquals(original.annotationId, revision.supersedesAnnotationId)
        history.forEach {
            assertEquals(AnnotationSource.PARTICIPANT.name, it.source)
            assertNull(it.parentVlmRunId)
        }
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
    }

    @Test fun concurrentEditsCannotSilentlySupersedeTheSameRevisionTwice() = runBlocking {
        database.clipDao().insert(clip("UNREVIEWED", "UNDECIDED"))
        val repository = AnnotationRepository(database)
        val initial = repository.addDescription("clip", "Initial description", 1000)
        val one = async { runCatching { repository.reviseDescription("clip", initial.annotationId, "First edit", 2000) } }
        val two = async { runCatching { repository.reviseDescription("clip", initial.annotationId, "Second edit", 2000) } }
        val edits = listOf(one.await(), two.await())
        assertEquals(1, edits.count { it.isSuccess })
        assertEquals(1, edits.count { it.isFailure })
        assertEquals(2, repository.history("clip").size)
        assertEquals(1, repository.history("clip").count { it.isCurrent })
        assertEquals(edits.single { it.isSuccess }.getOrThrow(), repository.current("clip"))
    }

    @Test fun invalidRevisionLeavesThePriorRowCurrentAndAmendmentParentSurvivesLaterEdit() = runBlocking {
        database.clipDao().insert(clip("UNREVIEWED", "UNDECIDED"))
        database.vlmRunDao().insert(run())
        val repository = AnnotationRepository(database)
        val original = repository.saveAmendment("run", "An unclear doorway", 2000)
        assertTrue(runCatching { repository.reviseDescription("clip", original.annotationId, "  ", 3000) }.isFailure)
        assertEquals(original, repository.current("clip"))
        val revised = repository.reviseDescription("clip", original.annotationId, "The doorway was on my left.", 4000)
        assertEquals(AnnotationSource.PARTICIPANT_AMENDMENT.name, revised.source)
        assertEquals("run", revised.parentVlmRunId)
        assertEquals(run().copy(disposition = "AMENDED"), database.vlmRunDao().getRun("run"))
    }

    @Test fun repositoryPersistsMultipleRunsWithActualProvenanceWithoutRecordingExposure() = runBlocking {
        database.clipDao().insert(clip("REVIEWED", "APPROVED"))
        val repository = VlmRunRepository(database)
        val original = run()
        val result = VlmResult.Success(
            clipId = "clip", rawOutput = original.rawOutput, description = original.description,
            modelInfo = VlmModelInfo(original.modelId, original.modelQuant, original.languageModelSha256,
                original.mmprojSha256, original.runtimeName, original.runtimeCommit),
            promptVersion = original.promptVersion, generatedAtEpochMs = 1000,
            inferenceDurationMs = original.inferenceDurationMs,
            frameSamplingJson = original.frameSamplingJson, generationConfigJson = original.generationConfigJson,
        )
        val first = repository.save(result)
        val second = repository.save(result.copy(generatedAtEpochMs = 2000, rawOutput = "Another uncertain output"))
        assertNotEquals(first.vlmRunId, second.vlmRunId)
        reopen()
        assertEquals(listOf(first, second), VlmRunRepository(database).history("clip"))
        assertEquals(original.copy(vlmRunId = first.vlmRunId), first)
        assertNull(first.firstPresentedAtEpochMs)
        assertEquals("NOT_PRESENTED", second.disposition)
        assertNull(database.annotationDao().current("clip"))
        assertEquals("APPROVED", database.clipDao().getClip("clip")?.approvalState)
        val refused = runCatching { VlmRunRepository(database).save(result.copy(clipId = "absent")) }
        assertTrue(refused.isFailure)
        assertEquals(2, database.vlmRunDao().history("clip").size)
    }

    @Test fun deletedClipsRejectNewDescriptionsAndAmendments() = runBlocking {
        database.clipDao().insert(clip("UNREVIEWED", "DELETED"))
        database.vlmRunDao().insert(run()) // Historical row to exercise the repository's deletion guard.
        val repository = AnnotationRepository(database)
        assertTrue(runCatching { repository.addDescription("clip", "Text", 2000) }.isFailure)
        assertTrue(runCatching { repository.saveAmendment("run", "Text", 2000) }.isFailure)
        assertTrue(repository.history("clip").isEmpty())
        assertEquals("DELETED", database.clipDao().getClip("clip")?.approvalState)
    }

    private fun clip(review: String, approval: String) = ClipEntity(
        "clip", "${context.filesDir}/test-only.mp4", null, 1000, 2000, 720, 1280, 14,
        review, approval, "test", 1000,
    )

    private fun run() = VlmRunEntity(
        vlmRunId = "run", clipId = "clip", modelId = "Qwen/Qwen3-VL-4B-Instruct-GGUF",
        modelQuant = "Q4_K_M",
        languageModelSha256 = "66358cb18bb6b3b1b6675aa412c7a88ef01d228f481184d13668e5201c730a0a",
        mmprojSha256 = "30ba2c7dd3127a4561b6cba9d13d0f711c91bdb38742e2f56d73c8cb596bd06d",
        runtimeName = "llama.cpp/libmtmd", runtimeCommit = "0c1e57098bba43ac29e6e3b677cdceebdd22334f",
        promptVersion = VlmPrompt.VERSION, generatedAtEpochMs = 1000, firstPresentedAtEpochMs = null,
        inferenceDurationMs = 42, frameSamplingJson = "{\"fixture\":true}",
        generationConfigJson = "{\"temperature\":0,\"testOnly\":true}",
        rawOutput = uncertainOutput, description = uncertainOutput, disposition = "NOT_PRESENTED",
    )

    private val uncertainOutput = """
        Scene:
        A path leads to a doorway; stairs are visible to its right.

        Relevant spatial features:
        An object appears near the path; its shape and exact distance are unclear.

        Visible actions:
        Changes across the images cannot be determined confidently.

        Clearly legible text:
        No clearly legible text relevant to the scene.

        Uncertain or unclear details:
        The date, price and route numbers are unclear.
        The participant's actual problem cannot be determined from the images.
    """.trimIndent() + "\n\n"
}
