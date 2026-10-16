package com.rchia.ecocapture.phase0.export

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface EventSnapshotStore {
    suspend fun read(clipId: String): EventSnapshotSource
    /** Compare and publish while holding the same database transaction; never mutate scientific records. */
    suspend fun publishIfUnchanged(expected: EventSnapshotSource, publish: () -> Unit)
}

data class CompletedEventSnapshot(
    val bundle: File, val record: File, val video: SnapshotFileDigest,
    val event: SnapshotFileDigest, val manifest: SnapshotFileDigest,
)

/** Local-only, one-shot build. Only an atomic rename makes a complete snapshot visible. */
class ApprovedEventSnapshotBuilder(
    private val store: EventSnapshotStore,
    private val exportRoot: File,
    private val recordingRoot: File,
    private val validateVideo: (File) -> Unit = ::validateMp4Boxes,
) {
    /** Explicit maintenance; never remove completed snapshots or recent/in-progress staging. */
    suspend fun cleanAbandonedStaging(nowEpochMs: Long): Int = mutex.withLock {
        require(!Files.isSymbolicLink(exportRoot.toPath())) { "INVALID_EXPORT_ROOT" }
        val root = exportRoot.canonicalFile
        if (!root.exists()) return@withLock 0
        require(root.isDirectory) { "INVALID_EXPORT_ROOT" }
        var count = 0
        root.listFiles()?.forEach { child ->
            if (child.name.matches(Regex("[0-9a-f-]{36}\\.pending")) && child.isDirectory &&
                child.canonicalFile == child.absoluteFile && nowEpochMs - child.lastModified() >= 24L * 60 * 60 * 1000) {
                EventSnapshotSerializer.uuid(child.name.removeSuffix(".pending"))
                check(child.walkTopDown().all { it.canonicalFile == it.absoluteFile }) { "UNSAFE_STAGING_PATH" }
                check(child.deleteRecursively()) { "STAGING_CLEANUP_FAILED" }
                count++
            }
        }
        count
    }

    suspend fun build(clipId: String, identity: SnapshotIdentity): CompletedEventSnapshot = mutex.withLock {
        EventSnapshotSerializer.uuid(clipId)
        EventSnapshotSerializer.uuid(identity.snapshotId)
        val context = currentCoroutineContext()
        val checkActive = { context.ensureActive() }
        val source = store.read(clipId)
        val serializer = EventSnapshotSerializer()
        serializer.validate(source, identity)
        require(!Files.isSymbolicLink(exportRoot.toPath())) { "INVALID_EXPORT_ROOT" }
        val root = exportRoot.canonicalFile
        check(root.mkdirs() || root.isDirectory) { "EXPORT_STORAGE_UNAVAILABLE" }
        val requestedMedia = File(source.clip.videoPath)
        require(!Files.isSymbolicLink(requestedMedia.toPath())) { "SOURCE_MEDIA_UNAVAILABLE" }
        val media = requestedMedia.canonicalFile
        val mediaRoot = recordingRoot.canonicalFile
        require(media.parentFile == mediaRoot &&
            media.extension == "mp4" && media.isFile) { "SOURCE_MEDIA_UNAVAILABLE" }
        val finalJob = File(root, identity.snapshotId)
        val pending = File(root, "${identity.snapshotId}.pending")
        check(!finalJob.exists() && !pending.exists()) { "SNAPSHOT_ALREADY_EXISTS" }
        check(pending.mkdir()) { "EXPORT_STORAGE_UNAVAILABLE" }
        try {
            checkActive()
            validateVideo(media)
            val original = EventManifest.digest(media, checkActive)
            val bundleName = "event_${clipId}_snapshot_${identity.snapshotId}"
            val bundle = File(pending, bundleName).apply { check(mkdir()) }
            val copy = File(bundle, "video.mp4")
            media.inputStream().buffered().use { input ->
                FileOutputStream(copy).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(EventManifest.digest(copy, checkActive) == original) { "SOURCE_MEDIA_CHANGED" }
            validateVideo(copy)
            val eventFile = File(bundle, "event.json")
            writeSynced(eventFile, serializer.serialize(source, identity))
            val event = EventManifest.digest(eventFile, checkActive)
            val manifestFile = File(bundle, "manifest.json")
            writeSynced(manifestFile, EventManifest.serialize(clipId, identity, original, event))
            val manifest = EventManifest.digest(manifestFile, checkActive)
            // App-private engineering record outside the three-file bundle. Room migration is Checkpoint 3.
            val record = File(pending, "snapshot-record.json")
            writeSynced(record, SnapshotJson.bytes(linkedMapOf(
                "schema" to "ecocapture.local-snapshot.v1", "snapshot_id" to identity.snapshotId,
                "clip_id" to clipId, "created_at_epoch_ms" to identity.createdAtEpochMs,
                "state" to "LOCAL_ONLY", "files" to listOf(EventManifest.entry("video.mp4", original),
                    EventManifest.entry("event.json", event), EventManifest.entry("manifest.json", manifest)),
            )))
            check(EventManifest.digest(media, checkActive) == original) { "SOURCE_MEDIA_CHANGED" }
            checkActive()
            store.publishIfUnchanged(source) {
                checkActive()
                check(EventManifest.digest(media, checkActive) == original) { "SOURCE_MEDIA_CHANGED" }
                check(pending.renameTo(finalJob)) { "SNAPSHOT_PUBLICATION_FAILED" }
            }
            CompletedEventSnapshot(File(finalJob, bundleName), File(finalJob, record.name), original, event, manifest)
        } finally {
            // The only deletions are our newly allocated pending tree; source/complete snapshots are untouched.
            if (pending.exists()) pending.deleteRecursively()
        }
    }

    companion object {
        private val mutex = Mutex()
        internal fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { it.write(bytes); it.fd.sync() }
        }

        /** Bounded ISO-BMFF box walk; Android adapter additionally validates an actual video sample. */
        fun validateMp4Boxes(file: File) {
            var ftyp = false
            var moov = false
            var mdat = false
            RandomAccessFile(file, "r").use { input ->
                val length = input.length()
                require(length >= 24) { "INVALID_MP4" }
                var offset = 0L
                while (offset < length) {
                    require(length - offset >= 8) { "INVALID_MP4" }
                    input.seek(offset)
                    val shortSize = input.readInt().toLong() and 0xffffffffL
                    val typeBytes = ByteArray(4).also { input.readFully(it) }
                    val type = String(typeBytes, Charsets.US_ASCII)
                    val header = if (shortSize == 1L) 16L else 8L
                    val size = when (shortSize) { 0L -> length - offset; 1L -> input.readLong(); else -> shortSize }
                    require(size >= header && size <= length - offset) { "INVALID_MP4" }
                    if (type == "ftyp") { require(offset == 0L && size >= 16); ftyp = true }
                    if (type == "moov") moov = size > header
                    if (type == "mdat") mdat = size > header
                    offset += size
                }
            }
            require(ftyp && moov && mdat) { "INVALID_MP4" }
        }
    }
}
