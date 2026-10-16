package com.rchia.ecocapture.phase0.export

import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

data class SnapshotFileDigest(val sizeBytes: Long, val sha256: String)

object EventManifest {
    fun digest(file: File, checkActive: () -> Unit = {}): SnapshotFileDigest {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "INVALID_FILE" }
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
                size += count
            }
        }
        return SnapshotFileDigest(size, hash.digest().joinToString("") { "%02x".format(it) })
    }

    fun serialize(eventId: String, identity: SnapshotIdentity,
                  video: SnapshotFileDigest, event: SnapshotFileDigest): ByteArray = SnapshotJson.bytes(linkedMapOf(
        "schema" to "ecocapture.manifest.v1", "event_id" to eventId,
        "snapshot_id" to identity.snapshotId, "created_at_epoch_ms" to identity.createdAtEpochMs,
        "files" to listOf(entry("video.mp4", video), entry("event.json", event)),
        "hash_algorithm" to "SHA-256", "manifest_version" to 1,
    ))

    internal fun entry(path: String, digest: SnapshotFileDigest) = linkedMapOf(
        "path" to path, "size_bytes" to digest.sizeBytes, "sha256" to digest.sha256,
    )
}
