package com.rchia.ecocapture.phase0.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.vlm.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Scientific state matrix using isolated Room and explicitly simulated output. */
@RunWith(AndroidJUnit4::class)
class Checkpoint9ProvenanceTest {
    @Test fun allSevenStatesPreserveHistoryExposureAndDecisionsAcrossReopen(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val token = UUID.randomUUID().toString()
        val name = "checkpoint9-$token.db"
        val media = File(context.cacheDir, "checkpoint9-$token.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
        try {
            for (scenario in listOf("neither", "participant-only", "vlm-only", "participant-then-vlm",
                "vlm-then-participant", "vlm-amendment", "participant-vlm-amendment")) {
                val clip = ClipEntity(scenario, media.path, null, 1000, 2000, 640, 480, 14,
                    "DEFERRED", "UNDECIDED", "checkpoint9-test", 1000)
                database.clipDao().insert(clip)
                val annotations = AnnotationRepository(database)
                val runs = VlmRunRepository(database)
                val before = scenario in setOf("participant-only", "participant-then-vlm", "participant-vlm-amendment")
                val hasVlm = scenario !in setOf("neither", "participant-only")
                val amendment = scenario in setOf("vlm-amendment", "participant-vlm-amendment")
                if (before) annotations.addDescription(scenario, "My original account", 2000)
                val generated = if (hasVlm) {
                    val engine = FakeVlmEngine(delayMs = 0, clock = { 3000 })
                    engine.load()
                    try { runs.save(engine.generate(VlmRequest(scenario, media)) as VlmResult.Success) }
                    finally { engine.unload() }
                } else null
                // Generation and saving alone are never evidence of participant exposure.
                generated?.let { assertNull(database.vlmRunDao().getRun(it.vlmRunId)?.firstPresentedAtEpochMs) }
                if (scenario == "vlm-then-participant" || amendment) {
                    runs.markPresented(generated!!.vlmRunId, 4000)
                    runs.markPresented(generated.vlmRunId, 9000) // Preserve the first exposure.
                }
                if (scenario == "vlm-then-participant") annotations.addDescription(scenario, "My independent account", 4500)
                if (amendment) {
                    val previous = annotations.current(scenario)
                    runs.useAsStartingPoint(generated!!.vlmRunId)
                    assertEquals(previous, annotations.current(scenario)) // Use alone does not save.
                    val saved = annotations.saveAmendmentFromDraft(generated.vlmRunId,
                        "A doorway is visible; small text is unclear.", 5000, previous?.annotationId)
                    val revised = annotations.reviseDescription(scenario, saved.annotationId, "My revised account", 6000)
                    assertEquals(saved.annotationId, revised.supersedesAnnotationId)
                    assertEquals("PARTICIPANT_AMENDMENT", revised.source)
                    assertEquals(generated.vlmRunId, revised.parentVlmRunId)
                }
                val expectedAnnotations = annotations.history(scenario)
                val expectedRuns = runs.history(scenario)
                assertEquals(clip, database.clipDao().getClip(scenario))
                if (generated != null) {
                    val persisted = expectedRuns.single()
                    assertEquals(generated.rawOutput, persisted.rawOutput)
                    assertEquals(generated.description, persisted.description)
                    assertEquals(generated.modelId, persisted.modelId)
                    assertEquals(generated.generationConfigJson, persisted.generationConfigJson)
                    assertEquals(generated.frameSamplingJson, persisted.frameSamplingJson)
                    assertEquals("ecological_scene_description_v2", persisted.promptVersion)
                    assertEquals(3000L, persisted.generatedAtEpochMs)
                    assertTrue(persisted.rawOutput.contains("cannot be determined"))
                    if (scenario == "vlm-then-participant" || amendment) {
                        assertEquals(4000L, persisted.firstPresentedAtEpochMs)
                        if (before) assertTrue(expectedAnnotations.first().createdAtEpochMs < persisted.firstPresentedAtEpochMs!!)
                        assertTrue(expectedAnnotations.last().createdAtEpochMs > persisted.firstPresentedAtEpochMs!!)
                    } else assertNull(persisted.firstPresentedAtEpochMs)
                }
                database.close()
                database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
                assertEquals(expectedAnnotations, AnnotationRepository(database).history(scenario))
                assertEquals(expectedRuns, VlmRunRepository(database).history(scenario))
                assertEquals(clip, database.clipDao().getClip(scenario))
                assertEquals(if (expectedAnnotations.isEmpty()) 0 else 1, expectedAnnotations.count { it.isCurrent })
                assertArrayEquals(byteArrayOf(1, 2, 3), media.readBytes())
            }
        } finally { database.close(); context.deleteDatabase(name); media.delete() }
    }
}
