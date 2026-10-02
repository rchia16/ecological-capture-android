package com.rchia.ecocapture.phase0.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.ui.review.DescriptionEditor
import com.rchia.ecocapture.phase0.ui.review.DescriptionEditorState
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Editor integration with actual Room, isolated from the participant's database and media. */
@RunWith(AndroidJUnit4::class)
class DescriptionEditorTest {
    private lateinit var context: Context
    private lateinit var database: EcologicalCaptureDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var editor: DescriptionEditor
    private val databaseName = "description_editor_${UUID.randomUUID()}.db"

    @Before fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        open()
        database.clipDao().insert(ClipEntity("clip", "/synthetic/not-media.mp4", null,
            1000, 10000, 640, 480, 20, "DEFERRED", "UNDECIDED", "test", 1000))
        newEditor()
        select("clip")
    }

    private fun open() {
        database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, databaseName)
            .addMigrations(EcologicalCaptureDatabase.MIGRATION_1_2).build()
    }
    private fun newEditor() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        editor = DescriptionEditor(AnnotationRepository(database), scope)
    }
    private suspend fun select(id: String) {
        withContext(Dispatchers.Main) { editor.selectClip(id) }
        await { !it.isLoading }
    }
    private suspend fun await(predicate: (DescriptionEditorState) -> Boolean): DescriptionEditorState =
        withTimeout(5000) { editor.state.first(predicate) }

    @After fun tearDown() = runBlocking {
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        context.deleteDatabase(databaseName)
        Unit
    }

    @Test fun noDescriptionAndCancelAddDoNotCreateRowsOrChangeDecisions() = runBlocking {
        assertNull(editor.state.value.annotation)
        withContext(Dispatchers.Main) {
            editor.startEditing()
            editor.changeText("Discard this draft")
            editor.cancelEditing()
        }
        assertNull(editor.state.value.draft)
        assertTrue(database.annotationDao().history("clip").isEmpty())
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
    }

    @Test fun addEditAndReloadPreserveExactTextAndRevisionHistory() = runBlocking {
        val originalText = "  I was unsure where to cross.\nThe sign was unclear.  "
        withContext(Dispatchers.Main) {
            editor.startEditing(); editor.changeText(originalText); editor.save()
        }
        val original = requireNotNull(await { it.draft == null && it.annotation != null }.annotation)
        assertEquals(originalText, original.text)
        assertEquals("PARTICIPANT", original.source)
        assertNull(original.parentVlmRunId)
        withContext(Dispatchers.Main) {
            editor.startEditing(); editor.changeText("I waited beside the crossing."); editor.save()
        }
        val revised = requireNotNull(await {
            it.draft == null && it.annotation?.annotationId != original.annotationId
        }.annotation)
        assertEquals(original.annotationId, revised.supersedesAnnotationId)
        assertEquals(2, database.annotationDao().history("clip").size)
        assertFalse(database.annotationDao().history("clip").first().isCurrent)
        assertEquals(originalText, database.annotationDao().history("clip").first().text)
        assertEquals("DEFERRED", database.clipDao().getClip("clip")?.reviewState)
        assertEquals("UNDECIDED", database.clipDao().getClip("clip")?.approvalState)
        scope.coroutineContext[Job]?.cancelAndJoin()
        database.close(); open(); newEditor(); select("clip")
        assertEquals(revised, editor.state.value.annotation)
        assertNull(editor.state.value.draft)
    }

    @Test fun cancelEditKeepsOriginalAndBlankSaveCreatesNothing() = runBlocking {
        val original = AnnotationRepository(database).addDescription("clip", "Original", 2000)
        await { it.annotation != null }
        withContext(Dispatchers.Main) {
            editor.startEditing(); editor.changeText(" \n "); editor.save()
        }
        assertFalse(editor.state.value.isSaving)
        assertFalse(requireNotNull(editor.state.value.draft).canSave)
        withContext(Dispatchers.Main) { editor.changeText("Unsaved revision"); editor.cancelEditing() }
        assertEquals(original, database.annotationDao().current("clip"))
        assertEquals(1, database.annotationDao().history("clip").size)
    }

    @Test fun staleSaveRetainsDraftAndCannotOverwriteAnotherRevision() = runBlocking {
        val repository = AnnotationRepository(database)
        val original = repository.addDescription("clip", "Original", 2000)
        await { it.annotation != null }
        withContext(Dispatchers.Main) { editor.startEditing(); editor.changeText("My draft") }
        val other = repository.reviseDescription("clip", original.annotationId, "Another revision", 3000)
        await { it.annotation?.annotationId == other.annotationId }
        withContext(Dispatchers.Main) { editor.save(); editor.save() }
        val failed = await { it.error != null && !it.isSaving }
        assertEquals("My draft", failed.draft?.text)
        assertEquals(original.annotationId, failed.draft?.expectedCurrentId)
        assertEquals(other, database.annotationDao().current("clip"))
        assertEquals(2, database.annotationDao().history("clip").size)
        withContext(Dispatchers.Main) { editor.cancelEditing(); editor.startEditing() }
        assertEquals(other.text, editor.state.value.draft?.text)
        assertEquals(other.annotationId, editor.state.value.draft?.expectedCurrentId)
    }

    @Test fun changingClipClearsUnsavedDraftAndLoadsOnlySelectedDescription() = runBlocking {
        database.clipDao().insert(requireNotNull(database.clipDao().getClip("clip")).copy(clipId = "other"))
        val other = AnnotationRepository(database).addDescription("other", "Other clip description", 2000)
        withContext(Dispatchers.Main) { editor.startEditing(); editor.changeText("Unsaved on first clip") }
        select("other")
        assertNull(editor.state.value.draft)
        assertEquals(other, editor.state.value.annotation)
        assertNull(database.annotationDao().current("clip"))
    }
}
