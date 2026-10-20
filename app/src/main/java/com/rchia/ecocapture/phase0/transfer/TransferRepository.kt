package com.rchia.ecocapture.phase0.transfer

import androidx.room.withTransaction
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.export.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first

/** Snapshot creation follows an explicit Send. Optional live transport persists that request. */
class TransferRepository(
    private val db: EcologicalCaptureDatabase,
    private val exportRoot: File,
    private val recordingRoot: File,
    private val eligibility: TransferEligibility,
    private val transport: TransferTransport,
    private val appVersion: String,
    private val validateVideo: (File) -> Unit = RoomEventSnapshotStore::validateVideo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onAttempt: suspend (String) -> Unit = {},
) {
    private val sourceStore = RoomEventSnapshotStore(db)
    private val jobs get() = db.transferJobDao()
    private val snapshots get() = db.exportSnapshotDao()
    private fun lock(id: String) = locks.getOrPut(id) { Mutex() }

    private suspend fun eligibleSource(id: String): EventSnapshotSource {
        check(eligibility.permitsSimulation(id)) { "INSTALLATION_NOT_ENABLED" }
        val source = sourceStore.read(id)
        EventSnapshotSerializer().validate(source, SnapshotIdentity(UUID.randomUUID().toString(), clock(), appVersion))
        val media = File(source.clip.videoPath)
        check(media.isFile && media.canonicalFile.parentFile == recordingRoot.canonicalFile &&
            !java.nio.file.Files.isSymbolicLink(media.toPath())) { "SOURCE_MEDIA_UNAVAILABLE" }
        validateVideo(media)
        return source
    }

    suspend fun send(clipId: String): TransferJobEntity = lock(clipId).withLock {
        val source = eligibleSource(clipId)
        val version = ExportSnapshotRepository.version(source)
        val latest = jobs.latest(clipId)
        if (latest != null && latest.transportMode == transport.mode && latest.sourceVersionSha256 == version && latest.state != "CANCELLED") return@withLock latest
        val time = clock()
        val job = TransferJobEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), clipId,
            "PREPARING", time, time, 0, 0, null, transport.mode, version, snapshots.latest(clipId)?.snapshotId, appVersion)
        db.withTransaction {
            check(ExportSnapshotRepository.version(eligibleSource(clipId)) == version) { "SOURCE_CHANGED_RETRY" }
            jobs.insert(job)
        }
        onAttempt(job.jobId)
        prepareAndSimulate(job)
        checkNotNull(jobs.get(job.jobId))
    }

    suspend fun retry(jobId: String): TransferJobEntity {
        val job = checkNotNull(jobs.get(jobId)) { "JOB_UNAVAILABLE" }
        return lock(job.clipId).withLock {
            val latest = checkNotNull(jobs.get(jobId))
            if (latest.state != "FAILED_RETRYABLE") return@withLock latest
            check(ExportSnapshotRepository.version(eligibleSource(job.clipId)) == job.sourceVersionSha256) { "SOURCE_CHANGED_RETRY" }
            val ready = snapshots.get(job.snapshotId) != null
            check(latest.transportMode == transport.mode) { "TRANSPORT_CHANGED" }
            val next = if (ready) transport.activeState else "PREPARING"
            check(jobs.transition(jobId, "FAILED_RETRYABLE", next, 0, clock(), null) == 1)
            jobs.countRetry(jobId)
            onAttempt(jobId)
            if (ready) simulate(job) else {
                LocalSnapshotFiles.purge(exportRoot, job.snapshotId)
                prepareAndSimulate(job)
            }
            checkNotNull(jobs.get(jobId))
        }
    }

    private suspend fun prepareAndSimulate(job: TransferJobEntity) {
        var published = false
        try {
            val guardedStore = object : EventSnapshotStore {
                override suspend fun read(clipId: String) = sourceStore.read(clipId)
                override suspend fun publishIfUnchanged(expected: EventSnapshotSource, publish: () -> Unit) {
                    db.withTransaction {
                        check(sourceStore.read(job.clipId) == expected && eligibility.permitsSimulation(job.clipId)) { "SOURCE_CHANGED_RETRY" }
                        check(jobs.get(job.jobId)?.state == "PREPARING") { "JOB_CANCELLED" }
                        publish()
                    }
                }
            }
            val completed = ApprovedEventSnapshotBuilder(guardedStore, exportRoot, recordingRoot, validateVideo)
                .build(job.clipId, SnapshotIdentity(job.snapshotId, job.createdAtEpochMs, job.appVersion, job.previousSnapshotId,
                    eligibility.participantCode()))
            db.withTransaction {
                check(eligibility.permitsSimulation(job.clipId) && jobs.get(job.jobId)?.state == "PREPARING") { "JOB_CANCELLED" }
                check(ExportSnapshotRepository.version(sourceStore.read(job.clipId)) == job.sourceVersionSha256) { "SOURCE_CHANGED_RETRY" }
                snapshots.supersede(job.clipId)
                snapshots.insert(ExportSnapshotRepository.record(job, completed, eligibility.participantCode()))
                check(jobs.transition(job.jobId, "PREPARING", transport.activeState, 0, clock(), null) == 1)
            }
            published = true
            simulate(job)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { jobs.transition(job.jobId, "PREPARING", "FAILED_RETRYABLE", 0, clock(), "INTERRUPTED") }
            throw cancelled
        } catch (_: Exception) {
            jobs.transition(job.jobId, "PREPARING", "FAILED_RETRYABLE", 0, clock(), "PREPARATION_FAILED")
        } finally {
            if (!published) withContext(NonCancellable) { LocalSnapshotFiles.purge(exportRoot, job.snapshotId) }
        }
    }

    private suspend fun simulate(job: TransferJobEntity) {
        try {
            val context = currentCoroutineContext()
            val checkActive = { context.ensureActive() }
            // Verify the persisted snapshot bytes on each explicit retry rather than silently rebuilding it.
            val row = checkNotNull(snapshots.get(job.snapshotId))
            val bundle = File(File(exportRoot, job.snapshotId), "event_${job.clipId}_snapshot_${job.snapshotId}")
            check(EventManifest.digest(File(bundle, "video.mp4"), checkActive).sha256 == row.mp4Sha256 &&
                EventManifest.digest(File(bundle, "event.json"), checkActive).sha256 == row.eventJsonSha256 &&
                EventManifest.digest(File(bundle, "manifest.json"), checkActive).sha256 == row.manifestSha256) { "SNAPSHOT_INTEGRITY_FAILED" }
            val completedState = transport.deliver(job, bundle) { percent ->
                check(eligibility.permitsSimulation(job.clipId)) { "INSTALLATION_NOT_ENABLED" }
                check(jobs.transition(job.jobId, transport.activeState, transport.activeState, percent, clock(), null) == 1) { "JOB_CANCELLED" }
            }
            if (completedState != transport.activeState)
                jobs.transition(job.jobId, transport.activeState, completedState, 100, clock(), null)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { jobs.transition(job.jobId, transport.activeState, "FAILED_RETRYABLE", 0, clock(), "INTERRUPTED") }
            throw cancelled
        } catch (_: Exception) {
            jobs.transition(job.jobId, transport.activeState, "FAILED_RETRYABLE", 0, clock(), "TRANSFER_FAILED")
        }
    }

    suspend fun cancel(jobId: String) { jobs.cancel(jobId, clock()) }

    /** Interrupted attempts become explicitly retryable; never enqueue a job or simulate automatically. */
    suspend fun recoverInterrupted() {
        val all = db.transferJobDao().observeAll().first()
        for (job in all.filter { it.state in setOf("PREPARING", "SIMULATING") }) {
            jobs.transition(job.jobId, job.state, "FAILED_RETRYABLE", 0, clock(), "INTERRUPTED")
        }
    }

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
