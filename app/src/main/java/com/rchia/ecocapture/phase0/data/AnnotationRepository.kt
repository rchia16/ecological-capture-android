package com.rchia.ecocapture.phase0.data

import androidx.room.withTransaction
import com.rchia.ecocapture.phase0.data.local.AnnotationEntity
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.domain.AnnotationSource
import com.rchia.ecocapture.phase0.domain.VlmDisposition
import java.util.UUID

class AnnotationRepository(private val database: EcologicalCaptureDatabase) {
    fun observeCurrent(clipId: String) = database.annotationDao().observeCurrent(clipId)
    suspend fun current(clipId: String) = database.annotationDao().current(clipId)
    suspend fun history(clipId: String) = database.annotationDao().history(clipId)

    suspend fun addDescription(clipId: String, text: String, createdAtEpochMs: Long): AnnotationEntity =
        database.withTransaction {
            requireActiveClip(clipId)
            require(database.annotationDao().current(clipId) == null) { "Description already exists; create a revision" }
            insertRevision(clipId, text, createdAtEpochMs, AnnotationSource.PARTICIPANT.name, null, null)
        }

    /** Keep an amendment's VLM parent when the participant revises it later. */
    suspend fun reviseDescription(clipId: String, expectedCurrentId: String, text: String, createdAtEpochMs: Long): AnnotationEntity =
        database.withTransaction {
            requireActiveClip(clipId)
            val previous = requireNotNull(database.annotationDao().current(clipId)) { "Description not found" }
            require(previous.annotationId == expectedCurrentId) { "Description has changed; reload before saving" }
            insertRevision(clipId, text, createdAtEpochMs, previous.source, previous.parentVlmRunId, previous)
        }

    /** Even an unchanged endorsement is a separate participant record. */
    suspend fun saveAmendment(runId: String, text: String, createdAtEpochMs: Long): AnnotationEntity =
        saveAmendmentInternal(runId, text, createdAtEpochMs, false, null)

    suspend fun saveAmendmentFromDraft(runId: String, text: String, createdAtEpochMs: Long,
        expectedCurrentId: String?): AnnotationEntity =
        saveAmendmentInternal(runId, text, createdAtEpochMs, true, expectedCurrentId)

    private suspend fun saveAmendmentInternal(runId: String, text: String, createdAtEpochMs: Long,
        checkRevision: Boolean, expectedCurrentId: String?): AnnotationEntity =
        database.withTransaction {
            val run = requireNotNull(database.vlmRunDao().getRun(runId)) { "VLM run not found" }
            requireActiveClip(run.clipId)
            val previous = database.annotationDao().current(run.clipId)
            if (checkRevision) require(previous?.annotationId == expectedCurrentId) { "Description has changed; reload before saving" }
            val saved = insertRevision(run.clipId, text, createdAtEpochMs,
                AnnotationSource.PARTICIPANT_AMENDMENT.name, runId, previous)
            database.vlmRunDao().setDisposition(runId, VlmDisposition.AMENDED.name)
            saved
        }

    private suspend fun requireActiveClip(clipId: String) {
        val clip = requireNotNull(database.clipDao().getClip(clipId)) { "Clip not found" }
        require(clip.approvalState != "DELETED") { "Clip is deleted" }
    }

    private suspend fun insertRevision(
        clipId: String, text: String, createdAtEpochMs: Long, source: String,
        parentVlmRunId: String?, previous: AnnotationEntity?,
    ): AnnotationEntity {
        require(text.isNotBlank()) { "Description is empty" }
        val annotation = AnnotationEntity(
            UUID.randomUUID().toString(), clipId, source, text, createdAtEpochMs,
            parentVlmRunId, previous?.annotationId, true,
        )
        previous?.let { database.annotationDao().supersede(it.annotationId) }
        database.annotationDao().insert(annotation)
        return annotation
    }
}
