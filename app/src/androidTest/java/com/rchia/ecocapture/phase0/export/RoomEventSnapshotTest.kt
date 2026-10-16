package com.rchia.ecocapture.phase0.export

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.AnnotationRepository
import com.rchia.ecocapture.phase0.data.local.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual MediaExtractor and isolated Room; no research records/media used as fixtures. */
@RunWith(AndroidJUnit4::class)
class RoomEventSnapshotTest {
    private suspend fun fixture(block: suspend (EcologicalCaptureDatabase, File, ClipEntity, String) -> Unit) {
        val i = InstrumentationRegistry.getInstrumentation()
        val context = i.targetContext
        val token = UUID.randomUUID().toString()
        val root = File(context.cacheDir, "checkpoint2-$token").apply { check(mkdir()) }
        val video = File(root, "synthetic.mp4")
        i.context.assets.open("checkpoint5/short.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        val name = "checkpoint2-$token.db"
        val db = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
        val clip = ClipEntity(token, video.path, null, 1000, 1000, 320, 180, 2,
            "REVIEWED", "APPROVED", "synthetic-fixture", 2000)
        try { db.clipDao().insert(clip); block(db, root, clip, name) }
        finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
    private fun builder(db: EcologicalCaptureDatabase, root: File, store: EventSnapshotStore = RoomEventSnapshotStore(db)) =
        ApprovedEventSnapshotBuilder(store, File(root, "exports"), root, RoomEventSnapshotStore::validateVideo)
    private fun identity() = SnapshotIdentity(UUID.randomUUID().toString(), 5000, "checkpoint2-test")
    private fun run(clip: ClipEntity) = VlmRunEntity(UUID.randomUUID().toString(), clip.clipId,
        "SIMULATED", "fixture", "test-lm-hash", "test-projector-hash", "test-runtime", "test-commit",
        "test-prompt", 3000, null, 20, "{\"frame_times\":[1,2,3]}", "{\"test\":true}",
        "Exact fixture output\nUncertainty retained.", "Unedited fixture description", "NOT_PRESENTED")

    @Test fun fiveScientificStatesExportWithoutChangingRoomOrMedia(): Unit = runBlocking {
        for (state in listOf("neither", "participant", "vlm", "both", "amendment")) fixture { db, root, clip, _ ->
            val annotations = AnnotationRepository(db)
            if (state in listOf("participant", "both", "amendment")) annotations.addDescription(clip.clipId, "Exact first text", 2500)
            if (state in listOf("vlm", "both", "amendment")) {
                val r = run(clip)
                db.vlmRunDao().insert(r)
                if (state == "amendment") {
                    db.vlmRunDao().markPresented(r.vlmRunId, 3200)
                    val a = annotations.saveAmendment(r.vlmRunId, "Exact amendment", 3500)
                    annotations.reviseDescription(clip.clipId, a.annotationId, "Exact revised amendment", 4000)
                }
            }
            val store = RoomEventSnapshotStore(db)
            val before = store.read(clip.clipId)
            val digest = EventManifest.digest(File(clip.videoPath))
            val id = identity()
            val result = builder(db, root).build(clip.clipId, id)
            assertEquals(before, store.read(clip.clipId))
            assertEquals(digest, EventManifest.digest(File(clip.videoPath)))
            assertEquals(digest, EventManifest.digest(File(result.bundle, "video.mp4")))
            assertArrayEquals(EventSnapshotSerializer().serialize(before, id), File(result.bundle, "event.json").readBytes())
            assertEquals(setOf("video.mp4", "event.json", "manifest.json"), result.bundle.list()!!.toSet())
            assertTrue(result.record.readText().contains(result.manifest.sha256))
        }
    }
    @Test fun concurrentEditAndTombstoneRejectStaleSnapshots(): Unit = runBlocking {
        for (delete in listOf(false, true)) fixture { db, root, clip, _ ->
            val delegate = RoomEventSnapshotStore(db)
            val store = object : EventSnapshotStore {
                override suspend fun read(clipId: String) = delegate.read(clipId)
                override suspend fun publishIfUnchanged(expected: EventSnapshotSource, publish: () -> Unit) {
                    if (delete) db.clipDao().markDeletedAndEraseText(clip.clipId, 4000)
                    else AnnotationRepository(db).addDescription(clip.clipId, "Saved during export", 3000)
                    delegate.publishIfUnchanged(expected, publish)
                }
            }
            try { builder(db, root, store).build(clip.clipId, identity()); fail("Stale snapshot published") }
            catch (_: IllegalStateException) { }
            assertTrue(File(clip.videoPath).exists())
            assertTrue(File(root, "exports").list()!!.isEmpty())
            if (delete) {
                assertEquals("DELETED", db.clipDao().getClip(clip.clipId)!!.approvalState)
                assertTrue(db.annotationDao().history(clip.clipId).isEmpty())
            } else assertEquals("Saved during export", db.annotationDao().current(clip.clipId)!!.text)
        }
    }
    @Test fun corruptMediaDoesNotRemoveOriginal(): Unit = runBlocking {
        fixture { db, root, clip, _ ->
            File(clip.videoPath).writeText("Corrupt disposable fixture")
            try { builder(db, root).build(clip.clipId, identity()); fail("Corrupt video accepted") }
            catch (_: IllegalArgumentException) { }
            assertEquals("Corrupt disposable fixture", File(clip.videoPath).readText())
        }
    }
    @Test fun completeSnapshotAndOriginalSurviveDatabaseReopen(): Unit = runBlocking {
        fixture { db, root, clip, name ->
            val result = builder(db, root).build(clip.clipId, identity())
            db.close()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val reopened = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, name).build()
            try {
                assertEquals(clip, reopened.clipDao().getClip(clip.clipId))
                assertEquals(result.manifest, EventManifest.digest(File(result.bundle, "manifest.json")))
                assertTrue(result.record.readText().contains(result.manifest.sha256))
            } finally { reopened.close() }
        }
    }
}
