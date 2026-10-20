package com.rchia.ecocapture.phase0.export

import com.rchia.ecocapture.phase0.data.local.ExportSnapshotEntity
import java.security.MessageDigest

object ExportSnapshotRepository {
    fun version(source: EventSnapshotSource): String {
        val bytes = EventSnapshotSerializer().serialize(source, SnapshotIdentity(
            "00000000-0000-0000-0000-000000000000", 0, "source-fingerprint-v1"))
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    fun record(job: com.rchia.ecocapture.phase0.data.local.TransferJobEntity,
               snapshot: CompletedEventSnapshot, participantCode: String? = null) = ExportSnapshotEntity(job.snapshotId, job.clipId,
        job.previousSnapshotId, job.createdAtEpochMs, snapshot.event.sha256, snapshot.manifest.sha256,
        snapshot.video.sha256, "READY", job.appVersion, participantCode)
}
