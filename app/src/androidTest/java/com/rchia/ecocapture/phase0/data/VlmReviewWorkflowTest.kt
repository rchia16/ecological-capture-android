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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room with a simulated engine; UI visibility callbacks are driven explicitly by tests. */
@RunWith(AndroidJUnit4::class)
class VlmReviewWorkflowTest {
    private lateinit var context: Context
    private lateinit var database: EcologicalCaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var editor: DescriptionEditor
    private lateinit var workflow: VlmReviewWorkflow
    private lateinit var media: File
    private lateinit var engine: CountingEngine
    private val databaseName = "vlm_workflow_${UUID.randomUUID()}.db"
    private val preferenceName = "ai_preferences_test_${UUID.randomUUID()}"
    private var time = 4000L

    @Before fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        open()
        media = File(context.cacheDir, "fake_vlm_${UUID.randomUUID()}.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        database.clipDao().insert(ClipEntity("clip", media.path, null, 1000, 10000, 640, 480, 20,
            "DEFERRED", "UNDECIDED", "test", 1000))
        newWorkflow()
    }
    private fun open() {
        database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, databaseName)
            .addMigrations(EcologicalCaptureDatabase.MIGRATION_1_2).build()
    }
    private suspend fun newWorkflow(fake: VlmEngine = FakeVlmEngine(delayMs = 50)) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        editor = DescriptionEditor(AnnotationRepository(database), scope) { time }
        engine = CountingEngine(fake)
        workflow = VlmReviewWorkflow(VlmRunRepository(database), engine, editor, scope) { time }
        withContext(Dispatchers.Main) { editor.selectClip("clip"); workflow.selectClip("clip") }
        withTimeout(5000) {
            editor.state.first { !it.isLoading }
            workflow.state.first { !it.isLoading }
        }
    }
    private suspend fun await(predicate: (VlmReviewState) -> Boolean) =
        withTimeout(5000) { workflow.state.first(predicate) }
    private suspend fun generate(): VlmRunEntity {
        withContext(Dispatchers.Main) { workflow.generate(media) }
        return requireNotNull(await { it.phase == VlmReviewPhase.SUCCESS }.run)
    }
    private suspend fun present(run: VlmRunEntity): VlmRunEntity {
        withContext(Dispatchers.Main) { workflow.onOutputVisible(run.vlmRunId) }
        return requireNotNull(await { it.run?.firstPresentedAtEpochMs != null }.run)
    }
    private suspend fun use(run: VlmRunEntity) {
        present(run)
        withContext(Dispatchers.Main) { workflow.useAsStartingPoint() }
        withTimeout(5000) { editor.state.first { it.draft != null } }
        await { !it.isUpdating }
    }
    @After fun tearDown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        context.deleteDatabase(databaseName)
        context.deleteSharedPreferences(preferenceName)
        media.delete()
        Unit
    }

    @Test fun generateIsOptionalAndPersistsUnpresentedOutputWithFakeProvenance() = runBlocking {
        assertEquals(VlmReviewPhase.IDLE, workflow.state.value.phase)
        assertEquals(0, engine.loads)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        val run = generate()
        assertNull(run.firstPresentedAtEpochMs)
        assertEquals("NOT_PRESENTED", run.disposition)
        assertEquals(FakeVlmEngine.DEFAULT_OUTPUT, run.rawOutput)
        assertEquals(run.rawOutput, run.description)
        assertEquals("FakeVlmEngine", run.runtimeName)
        assertEquals(VlmPrompt.VERSION, run.promptVersion)
        assertEquals(1, engine.loads)
        assertEquals(1, engine.unloads)
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), media.readBytes())
    }

    @Test fun firstPresentationIsSetOnlyByVisibleCallbackAndNeverReplaced() = runBlocking {
        val original = generate()
        withContext(Dispatchers.Main) { workflow.onOutputVisible("wrong-run") }
        assertNull(database.vlmRunDao().getRun(original.vlmRunId)?.firstPresentedAtEpochMs)
        val shown = present(original)
        assertEquals(4000L, shown.firstPresentedAtEpochMs)
        assertEquals(original.copy(firstPresentedAtEpochMs = 4000L, disposition = "PRESENTED"), shown)
        time = 9000
        withContext(Dispatchers.Main) { workflow.onOutputVisible(original.vlmRunId) }
        VlmRunRepository(database).markPresented(original.vlmRunId, time)
        assertEquals(shown, database.vlmRunDao().getRun(original.vlmRunId))
    }

    @Test fun startingPointUnchangedSaveAndEditedSaveAreSeparateAmendments() = runBlocking {
        val original = generate()
        use(original)
        assertEquals(original.description, editor.state.value.draft?.text)
        assertEquals(original.vlmRunId, editor.state.value.draft?.parentVlmRunId)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        withContext(Dispatchers.Main) { editor.save() }
        val first = requireNotNull(withTimeout(5000) {
            editor.state.first { it.draft == null && it.annotation != null }.annotation
        })
        assertEquals(original.description, first.text)
        await { it.run?.disposition == "AMENDED" }
        time = 5000
        assertTrue(workflow.state.value.isSuggestionResolved)
        withContext(Dispatchers.Main) { editor.startEditing() }
        withContext(Dispatchers.Main) { editor.changeText("I could not determine which doorway to use."); editor.save() }
        val edited = requireNotNull(withTimeout(5000) {
            editor.state.first { it.draft == null && it.annotation?.annotationId != first.annotationId }.annotation
        })
        assertEquals(first.annotationId, edited.supersedesAnnotationId)
        assertEquals("PARTICIPANT_AMENDMENT", edited.source)
        assertEquals(original.vlmRunId, edited.parentVlmRunId)
        assertEquals(2, database.annotationDao().history("clip").size)
        assertEquals(original.copy(firstPresentedAtEpochMs = 4000L, disposition = "AMENDED"),
            database.vlmRunDao().getRun(original.vlmRunId))
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
    }

    @Test fun cancelAmendmentDoesNotCreateAnnotationOrChangeOriginalOutput() = runBlocking {
        val run = generate()
        use(run)
        withContext(Dispatchers.Main) { editor.changeText("Discard me"); editor.cancelEditing() }
        assertTrue(database.annotationDao().history("clip").isEmpty())
        val persisted = requireNotNull(database.vlmRunDao().getRun(run.vlmRunId))
        assertEquals(run.rawOutput, persisted.rawOutput)
        assertEquals("USED_AS_STARTING_POINT", persisted.disposition)
    }

    @Test fun ignoreAndRestartRetainOutputProvenanceAndExposureWithoutAnAnnotation() = runBlocking {
        val run = generate()
        present(run)
        withContext(Dispatchers.Main) { workflow.ignore() }
        val ignored = requireNotNull(await { !it.isUpdating && it.run?.disposition == "IGNORED" }.run)
        assertEquals(run.copy(firstPresentedAtEpochMs = 4000L, disposition = "IGNORED"), ignored)
        VlmRunRepository(database).markPresented(run.vlmRunId, 9000)
        assertEquals(ignored, database.vlmRunDao().getRun(run.vlmRunId))
        assertTrue(database.annotationDao().history("clip").isEmpty())
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close(); open(); newWorkflow()
        assertEquals(ignored, workflow.state.value.run)
        assertEquals(0, engine.loads)
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertTrue(workflow.state.value.isSuggestionResolved)
        withContext(Dispatchers.Main) { workflow.useAsStartingPoint() }
        assertEquals(0, engine.loads)
        assertNull(editor.state.value.draft)
        assertTrue(database.annotationDao().history("clip").isEmpty())
    }

    @Test fun acceptingSuggestionStoresDescriptionAndHidesItAcrossReload() = runBlocking {
        val original = generate()
        present(original)
        withContext(Dispatchers.Main) { editor.startEditing() }
        use(original)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertEquals(original.description, editor.state.value.draft?.text)
        withContext(Dispatchers.Main) { editor.save() }
        val saved = requireNotNull(withTimeout(5000) {
            editor.state.first { it.annotation != null && !it.isSaving }.annotation
        })
        await { it.isSuggestionResolved }
        assertEquals(original.description, saved.text)
        assertEquals("PARTICIPANT_AMENDMENT", saved.source)
        assertEquals(original.vlmRunId, saved.parentVlmRunId)
        assertEquals(original.copy(firstPresentedAtEpochMs = 4000L, disposition = "AMENDED"),
            database.vlmRunDao().getRun(original.vlmRunId))
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close(); open(); newWorkflow()
        assertTrue(workflow.state.value.isSuggestionResolved)
        assertEquals(saved, editor.state.value.annotation)
        withContext(Dispatchers.Main) { workflow.generate(media); workflow.useAsStartingPoint() }
        assertEquals(0, engine.loads)
        assertEquals(1, database.annotationDao().history("clip").size)
    }

    @Test fun rejectionKeepsExistingParticipantDescription() = runBlocking {
        val originalDescription = AnnotationRepository(database).addDescription("clip", "My own account", 2000)
        val run = generate()
        present(run)
        withContext(Dispatchers.Main) { workflow.ignore() }
        await { !it.isUpdating && it.isSuggestionResolved }
        assertEquals(originalDescription, database.annotationDao().current("clip"))
        assertEquals(1, database.annotationDao().history("clip").size)
        assertEquals(run.rawOutput, database.vlmRunDao().getRun(run.vlmRunId)?.rawOutput)
    }

    @Test fun regenerationAfterRejectionPreservesDraftAndAllOriginalRuns() = runBlocking {
        withContext(Dispatchers.Main) { editor.startEditing(); editor.changeText("My unsaved account") }
        val first = generate()
        present(first)
        withContext(Dispatchers.Main) { workflow.ignore() }
        val rejected = requireNotNull(await { !it.isUpdating && it.isSuggestionResolved }.run)
        assertEquals("My unsaved account", editor.state.value.draft?.text)
        val second = generate()
        assertNotEquals(first.vlmRunId, second.vlmRunId)
        assertNull(second.firstPresentedAtEpochMs)
        assertEquals("My unsaved account", editor.state.value.draft?.text)
        assertEquals(rejected, database.vlmRunDao().getRun(first.vlmRunId))
        assertEquals(2, database.vlmRunDao().history("clip").size)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        use(second)
        assertEquals(second.vlmRunId, editor.state.value.draft?.parentVlmRunId)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        withContext(Dispatchers.Main) { editor.save() }
        withTimeout(5000) { editor.state.first { it.draft == null && it.annotation != null } }
        assertEquals(second.vlmRunId, database.annotationDao().current("clip")?.parentVlmRunId)
        assertEquals(rejected, database.vlmRunDao().getRun(first.vlmRunId))
    }

    @Test fun failedRegenerationKeepsUnsavedDraftAndRejectedRun() = runBlocking {
        val first = generate()
        present(first)
        withContext(Dispatchers.Main) { workflow.ignore() }
        val rejected = requireNotNull(await { !it.isUpdating && it.isSuggestionResolved }.run)
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(delayMs = 0, generationFailure = VlmFailure.FrameExtractionFailed))
        withContext(Dispatchers.Main) {
            editor.startEditing(); editor.changeText("Keep this draft"); workflow.generate(media)
        }
        await { it.phase == VlmReviewPhase.ERROR }
        assertEquals("Keep this draft", editor.state.value.draft?.text)
        assertEquals(rejected, database.vlmRunDao().getRun(first.vlmRunId))
        assertEquals(1, database.vlmRunDao().history("clip").size)
        assertTrue(database.annotationDao().history("clip").isEmpty())
    }

    @Test fun copyingSuggestionRetainsDraftRevisionEvenIfCurrentDescriptionChanged() = runBlocking {
        withContext(Dispatchers.Main) { editor.startEditing(); editor.changeText("Started without a saved description") }
        val original = AnnotationRepository(database).addDescription("clip", "Concurrent saved text", 2000)
        withTimeout(5000) { editor.state.first { it.annotation != null } }
        val run = generate()
        use(run)
        assertNull(editor.state.value.draft?.expectedCurrentId)
        withContext(Dispatchers.Main) { editor.save() }
        withTimeout(5000) { editor.state.first { !it.isSaving && it.error != null } }
        assertEquals(original, database.annotationDao().current("clip"))
        assertEquals(run.description, editor.state.value.draft?.text)
    }

    @Test fun automaticPreferenceDefaultsOffAndPersistsAcrossRecreation() = runBlocking {
        val preferences = AiSuggestionPreferences(context, preferenceName)
        assertFalse(preferences.enabled.value)
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { preferences.claimFirstPreparation("clip") }
        }
        assertEquals(0, engine.loads)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        preferences.setEnabled(true)
        assertTrue(AiSuggestionPreferences(context, preferenceName).enabled.value)
        preferences.setEnabled(false)
        assertFalse(AiSuggestionPreferences(context, preferenceName).enabled.value)
    }

    @Test fun automaticPreparationDoesNotExposeTextOrChangeDraftAndDecisions() = runBlocking {
        val preferences = AiSuggestionPreferences(context, preferenceName)
        preferences.setEnabled(true)
        withContext(Dispatchers.Main) {
            editor.startEditing(); editor.changeText("Independent draft before seeing AI")
            workflow.prepareAutomatically(media) { preferences.claimFirstPreparation("clip") }
        }
        val run = requireNotNull(await { it.phase == VlmReviewPhase.SUCCESS }.run)
        assertNull(run.firstPresentedAtEpochMs)
        assertEquals("NOT_PRESENTED", run.disposition)
        assertEquals("Independent draft before seeing AI", editor.state.value.draft?.text)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { preferences.claimFirstPreparation("clip") }
        }
        assertEquals(1, engine.loads)
        assertFalse(AiSuggestionPreferences(context, preferenceName).claimFirstPreparation("clip"))
    }

    @Test fun automaticFailureDoesNotRetryOnReopenButManualRetryWorks() = runBlocking {
        val preferences = AiSuggestionPreferences(context, preferenceName)
        preferences.setEnabled(true)
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(loadFailure = VlmFailure.ModelLoadFailed))
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { preferences.claimFirstPreparation("clip") }
        }
        await { it.phase == VlmReviewPhase.ERROR }
        assertEquals(1, engine.loads)
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow()
        val reloaded = AiSuggestionPreferences(context, preferenceName)
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { reloaded.claimFirstPreparation("clip") }
        }
        assertEquals(0, engine.loads)
        assertNotNull(generate())
    }

    @Test fun cancellationAndRejectionNeverAutomaticallyRegenerate() = runBlocking {
        val preferences = AiSuggestionPreferences(context, preferenceName)
        preferences.setEnabled(true)
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(delayMs = 5000))
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { preferences.claimFirstPreparation("clip") }
        }
        await { it.phase == VlmReviewPhase.RUNNING && it.isAutomaticGeneration }
        withContext(Dispatchers.Main) { workflow.cancelAndJoin() }
        assertEquals(VlmReviewPhase.CANCELLED, workflow.state.value.phase)
        assertFalse(workflow.state.value.isAutomaticGeneration)
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow()
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { AiSuggestionPreferences(context, preferenceName).claimFirstPreparation("clip") }
        }
        assertEquals(0, engine.loads)
        val run = generate()
        present(run)
        withContext(Dispatchers.Main) { workflow.ignore() }
        await { !it.isUpdating && it.isSuggestionResolved }
        withContext(Dispatchers.Main) {
            workflow.prepareAutomatically(media) { preferences.claimFirstPreparation("clip") }
        }
        assertEquals(1, engine.loads)
        assertEquals(1, database.vlmRunDao().history("clip").size)
    }

    @Test fun cancellationUnloadsAndCreatesNoOutputThenAllowsRetry() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(delayMs = 5000))
        withContext(Dispatchers.Main) { workflow.generate(media); workflow.generate(media) }
        await { it.phase == VlmReviewPhase.RUNNING }
        withContext(Dispatchers.Main) { workflow.cancelAndJoin() }
        assertEquals(VlmReviewPhase.CANCELLED, workflow.state.value.phase)
        assertEquals(1, engine.loads)
        assertEquals(1, engine.unloads)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow()
        assertNotNull(generate())
    }

    @Test fun fakeGenerationFailureLeavesMediaAndDecisionsUntouched() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(delayMs = 0, generationFailure = VlmFailure.NativeFailure(123)))
        withContext(Dispatchers.Main) { workflow.generate(media) }
        val failed = await { it.phase == VlmReviewPhase.ERROR }
        assertNotNull(failed.error)
        assertFalse(requireNotNull(failed.error).contains("123"))
        assertEquals(1, engine.unloads)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), media.readBytes())
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
    }

    @Test fun fakeLoadFailureUnloadsAndDoesNotPersistOutput() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(loadFailure = VlmFailure.ModelLoadFailed))
        withContext(Dispatchers.Main) { workflow.generate(media) }
        await { it.phase == VlmReviewPhase.ERROR }
        assertEquals(0, engine.generations)
        assertEquals(1, engine.unloads)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
    }

    @Test fun selectingAnotherClipCancelsWorkAndCannotPresentOldOutputThere() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        newWorkflow(FakeVlmEngine(delayMs = 5000))
        database.clipDao().insert(requireNotNull(database.clipDao().getClip("clip")).copy(clipId = "other"))
        withContext(Dispatchers.Main) { workflow.generate(media) }
        await { it.phase == VlmReviewPhase.RUNNING }
        withContext(Dispatchers.Main) { editor.selectClip("other"); workflow.selectClip("other"); workflow.cancelAndJoin() }
        await { !it.isLoading }
        assertNull(workflow.state.value.run)
        assertEquals(VlmReviewPhase.IDLE, workflow.state.value.phase)
        assertTrue(database.vlmRunDao().history("clip").isEmpty())
        assertEquals(1, engine.unloads)
    }

    @Test fun amendmentDraftCannotOverwriteConcurrentParticipantRevision() = runBlocking {
        val run = generate()
        use(run)
        val participant = AnnotationRepository(database).addDescription("clip", "Independent participant text", 3000)
        withTimeout(5000) { editor.state.first { it.annotation != null } }
        withContext(Dispatchers.Main) { editor.save() }
        withTimeout(5000) { editor.state.first { !it.isSaving && it.error != null } }
        assertEquals(run.description, editor.state.value.draft?.text)
        assertEquals(participant, database.annotationDao().current("clip"))
        assertEquals("USED_AS_STARTING_POINT", database.vlmRunDao().getRun(run.vlmRunId)?.disposition)
    }

    private class CountingEngine(private val delegate: VlmEngine) : VlmEngine {
        var loads = 0
        var generations = 0
        var unloads = 0
        override suspend fun load(): VlmLoadResult { loads++; return delegate.load() }
        override suspend fun generate(request: VlmRequest): VlmResult { generations++; return delegate.generate(request) }
        override suspend fun unload() { unloads++; delegate.unload() }
        override fun modelInfo() = delegate.modelInfo()
    }
}
