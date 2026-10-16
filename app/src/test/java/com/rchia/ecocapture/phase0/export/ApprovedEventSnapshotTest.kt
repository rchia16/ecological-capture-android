package com.rchia.ecocapture.phase0.export

import com.rchia.ecocapture.phase0.data.local.*
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApprovedEventSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private val clipId = "00000000-0000-0000-0000-000000000001"
    private val runId = "00000000-0000-0000-0000-000000000002"
    private val annotationId = "00000000-0000-0000-0000-000000000003"
    private val identity = SnapshotIdentity("00000000-0000-0000-0000-000000000004", 5000, "synthetic-test")
    private fun media(): File = temp.newFile("synthetic.mp4").apply {
        fun box(type: String, content: ByteArray) = ByteBuffer.allocate(8 + content.size)
            .putInt(8 + content.size).put(type.toByteArray()).put(content).array()
        writeBytes(box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf(1)) + box("mdat", byteArrayOf(2)))
    }
    private fun source(video: File): EventSnapshotSource = EventSnapshotSource(
        ClipEntity(clipId, video.absolutePath, "/private/not-exported.json", 1000, 2000, 320, 180, 4,
            "REVIEWED", "APPROVED", null, 4000), emptyList(), emptyList())
    private fun run() = VlmRunEntity(runId, clipId, "simulated", "fixture", "hash-a", "hash-b",
        "test-runtime", "commit", "prompt-test", 2000, null, 100,
        "{\"frame_times\":[1,2,3]}", "{\"max_tokens\":384}", "Exact uncertain output\n\"café\" 😀",
        "Unedited description", "NOT_PRESENTED")
    private fun annotation() = AnnotationEntity(annotationId, clipId, "PARTICIPANT", "Exact \t participant\ntext 😀", 3000, null, null, true)
    private class Store(var source: EventSnapshotSource) : EventSnapshotStore {
        var beforePublish: () -> Unit = {}
        override suspend fun read(clipId: String) = source
        override suspend fun publishIfUnchanged(expected: EventSnapshotSource, publish: () -> Unit) {
            beforePublish()
            check(source == expected) { "SNAPSHOT_SOURCE_CHANGED_RETRY" }
            publish()
        }
    }
    private fun builder(store: Store, validator: (File) -> Unit = ApprovedEventSnapshotBuilder::validateMp4Boxes) =
        ApprovedEventSnapshotBuilder(store, File(temp.root, "exports"), temp.root, validator)
    private fun fails(block: suspend () -> Unit): Unit = runBlocking {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) { }
    }

    @Test fun allAnnotationStatesExportAndPersistExactHashes(): Unit = runBlocking {
        val video = media()
        val before = video.readBytes()
        val base = source(video)
        val r = run()
        val a = annotation()
        for ((index, state) in listOf(base, base.copy(annotations = listOf(a)), base.copy(vlmRuns = listOf(r)),
            base.copy(annotations = listOf(a), vlmRuns = listOf(r)),
            base.copy(annotations = listOf(a.copy(source = "PARTICIPANT_AMENDMENT", parentVlmRunId = runId)), vlmRuns = listOf(r))).withIndex()) {
            val id = identity.copy(snapshotId = UUID(0, 100L + index).toString())
            val result = builder(Store(state)).build(clipId, id)
            assertEquals(setOf("video.mp4", "event.json", "manifest.json"), result.bundle.list()!!.toSet())
            assertEquals(result.video, EventManifest.digest(video))
            assertEquals(result.manifest, EventManifest.digest(File(result.bundle, "manifest.json")))
            assertTrue(result.record.readText().contains(result.manifest.sha256))
            val event = File(result.bundle, "event.json").readText()
            assertFalse(event.contains("/private/"))
            assertFalse(event.contains(video.absolutePath))
            assertTrue(event.contains("\"approved_at_epoch_ms\":null"))
            if (state.vlmRuns.isNotEmpty()) assertTrue(event.contains("\"first_presented_at_epoch_ms\":null"))
        }
        assertArrayEquals(before, video.readBytes())
    }
    @Test fun deterministicBytesPreserveExactTextAndProvenance() {
        val base = source(media())
        val r = run()
        val a = annotation()
        val revised = a.copy(annotationId = "00000000-0000-0000-0000-000000000005", createdAtEpochMs = 2500,
            supersedesAnnotationId = a.annotationId, isCurrent = true)
        val s = base.copy(annotations = listOf(revised, a.copy(isCurrent = false)), vlmRuns = listOf(r))
        val serializer = EventSnapshotSerializer()
        val bytes = serializer.serialize(s, identity)
        assertArrayEquals(bytes, serializer.serialize(s.copy(annotations = s.annotations.reversed()), identity))
        val text = String(bytes, Charsets.UTF_8)
        for (exact in listOf(r.rawOutput, r.description, r.frameSamplingJson, r.generationConfigJson, a.text)) {
            assertTrue(text.contains(SnapshotJson.encode(exact)))
        }
        assertTrue(text.contains("\"current_annotation_id\":\"${revised.annotationId}\""))
        assertFalse(text.contains("approved_at_epoch_ms\":4000"))
    }
    @Test fun editDuringCopyAbortsAndKeepsSource() {
        val video = media()
        val store = Store(source(video))
        store.beforePublish = { store.source = store.source.copy(annotations = listOf(annotation())) }
        fails { builder(store).build(clipId, identity) }
        assertTrue(video.exists())
        assertTrue(File(temp.root, "exports").list()!!.isEmpty())
    }
    @Test fun deletionBeforePublicationCannotResurrect() {
        val video = media()
        val store = Store(source(video))
        store.beforePublish = { store.source = store.source.copy(clip = store.source.clip.copy(approvalState = "DELETED")) }
        fails { builder(store).build(clipId, identity) }
        assertTrue(video.exists())
        assertTrue(File(temp.root, "exports").list()!!.isEmpty())
    }
    @Test fun changedSourceBytesRejectPublication() {
        val video = media()
        val store = Store(source(video))
        var validations = 0
        val validate: (File) -> Unit = {
            ApprovedEventSnapshotBuilder.validateMp4Boxes(it)
            if (++validations == 2) video.appendBytes(byteArrayOf(9))
        }
        fails { builder(store, validate).build(clipId, identity) }
        assertTrue(video.exists())
        assertTrue(File(temp.root, "exports").list()!!.isEmpty())
    }
    @Test fun corruptAndMissingMediaFailWithoutDeletion() {
        val video = media()
        video.writeText("not an MP4")
        fails { builder(Store(source(video))).build(clipId, identity) }
        assertEquals("not an MP4", video.readText())
        val absent = File(temp.root, "absent.mp4")
        fails { builder(Store(source(absent))).build(clipId, identity) }
        assertFalse(absent.exists())
    }
    @Test fun approvedOnlyAndSafePathsAndLinkage() {
        val s = source(media())
        for (state in listOf("UNDECIDED", "DELETED")) fails {
            builder(Store(s.copy(clip = s.clip.copy(approvalState = state)))).build(clipId, identity)
        }
        fails { builder(Store(s)).build(clipId, identity.copy(snapshotId = "../escape")) }
        fails { builder(Store(s)).build(clipId, identity.copy(approvedStudyParticipantCode = "person@example.com")) }
        fails { builder(Store(s.copy(annotations = listOf(annotation().copy(parentVlmRunId = runId))))).build(clipId, identity) }
    }
    @Test fun completedSnapshotRemainsFrozenAfterEditAndDuplicateBuildIsRejected(): Unit = runBlocking {
        val store = Store(source(media()))
        val b = builder(store)
        val result = b.build(clipId, identity)
        val bytes = File(result.bundle, "event.json").readBytes()
        store.source = store.source.copy(annotations = listOf(annotation()))
        try { b.build(clipId, identity); fail("Duplicate") } catch (_: IllegalStateException) { }
        assertArrayEquals(bytes, File(result.bundle, "event.json").readBytes())
    }
    @Test fun interruptedBuildCleansOnlyItsOwnStaging(): Unit = runBlocking {
        val video = media()
        val b = builder(Store(source(video))) { throw CancellationException("synthetic interruption") }
        try { b.build(clipId, identity); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(video.exists())
        assertTrue(File(temp.root, "exports").list()!!.isEmpty())
    }
    @Test fun lineageCyclesAndDuplicateCurrentRowsFail() {
        val s = source(media())
        val a = annotation()
        fails { EventSnapshotSerializer().serialize(s.copy(annotations = listOf(a.copy(supersedesAnnotationId = a.annotationId))), identity) }
        fails { EventSnapshotSerializer().serialize(s.copy(annotations = listOf(a, a.copy(annotationId = UUID.randomUUID().toString()))), identity) }
    }
    @Test fun crossLanguageSyntheticFixtures(): Unit = runBlocking {
        // Test outputs contain only structural box fixtures and synthetic text, never actual phone data.
        val video = media()
        val root = File("build/c2/${UUID.randomUUID().toString().take(8)}").absoluteFile
        val states = listOf(source(video), source(video).copy(annotations = listOf(annotation())),
            source(video).copy(annotations = listOf(annotation().copy(source = "PARTICIPANT_AMENDMENT", parentVlmRunId = runId)), vlmRuns = listOf(run())))
        for (state in states) ApprovedEventSnapshotBuilder(Store(state), root, temp.root).build(clipId,
            identity.copy(snapshotId = UUID.randomUUID().toString()))
        assertEquals(3, root.list()!!.size)
    }

    @Test fun abandonedStagingCleanupPreservesCompletedAndRecentSnapshots(): Unit = runBlocking {
        val store = Store(source(media()))
        val b = builder(store)
        val complete = b.build(clipId, identity)
        val root = complete.record.parentFile!!.parentFile!!
        val stale = File(root, "00000000-0000-0000-0000-000000000009.pending").apply { mkdir() }
        File(stale, "partial").writeText("synthetic")
        assertTrue(stale.setLastModified(1000))
        val recent = File(root, "00000000-0000-0000-0000-000000000008.pending").apply { mkdir() }
        val now = System.currentTimeMillis()
        assertEquals(1, b.cleanAbandonedStaging(now))
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(complete.record.exists())
        assertEquals(complete.video, EventManifest.digest(File(complete.bundle, "video.mp4")))
    }

    @Test fun sourceMutationImmediatelyBeforePublicationIsRejected() {
        val video = media()
        val store = Store(source(video))
        store.beforePublish = { val bytes = video.readBytes(); bytes[bytes.lastIndex] = 7; video.writeBytes(bytes) }
        fails { builder(store).build(clipId, identity) }
        assertTrue(video.exists())
        assertTrue(File(temp.root, "exports").list()!!.isEmpty())
    }
}
