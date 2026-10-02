package com.rchia.ecocapture.phase0.data

import androidx.room.withTransaction
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.data.local.VlmRunEntity
import com.rchia.ecocapture.phase0.domain.VlmDisposition
import com.rchia.ecocapture.phase0.vlm.VlmResult
import java.util.UUID

class VlmRunRepository(private val database: EcologicalCaptureDatabase) {
    suspend fun save(result: VlmResult.Success, runId: String = UUID.randomUUID().toString()): VlmRunEntity = database.withTransaction {
        database.vlmRunDao().getRun(runId)?.let {
            require(it.clipId == result.clipId) { "VLM request ID belongs to another recording" }
            return@withTransaction it
        }
        val clip = requireNotNull(database.clipDao().getClip(result.clipId)) { "Clip not found" }
        require(clip.approvalState != "DELETED") { "Clip is deleted" }
        require(result.rawOutput.isNotBlank() && result.description.isNotBlank()) { "Empty VLM output" }
        require(result.promptVersion.isNotBlank() && result.modelInfo.modelId.isNotBlank()) { "Missing VLM provenance" }
        require(result.inferenceDurationMs >= 0) { "Invalid inference duration" }
        val model = result.modelInfo
        val run = VlmRunEntity(
            vlmRunId = runId, clipId = result.clipId,
            modelId = model.modelId, modelQuant = model.modelQuant,
            languageModelSha256 = model.languageModelSha256, mmprojSha256 = model.mmprojSha256,
            runtimeName = model.runtimeName, runtimeCommit = model.runtimeCommit,
            promptVersion = result.promptVersion, generatedAtEpochMs = result.generatedAtEpochMs,
            firstPresentedAtEpochMs = null, inferenceDurationMs = result.inferenceDurationMs,
            frameSamplingJson = result.frameSamplingJson, generationConfigJson = result.generationConfigJson,
            rawOutput = result.rawOutput, description = result.description,
            disposition = VlmDisposition.NOT_PRESENTED.name,
        )
        database.vlmRunDao().insert(run)
        run
    }

    suspend fun history(clipId: String): List<VlmRunEntity> = database.vlmRunDao().history(clipId)

    fun observeLatest(clipId: String) = database.vlmRunDao().observeLatest(clipId)

    suspend fun markPresented(runId: String, time: Long) = database.withTransaction {
        requireActiveRun(runId)
        database.vlmRunDao().markPresented(runId, time)
    }

    suspend fun useAsStartingPoint(runId: String) = database.withTransaction {
        val run = requireActiveRun(runId)
        require(run.firstPresentedAtEpochMs != null) { "Output has not been presented" }
        require(run.disposition != VlmDisposition.IGNORED.name) { "Output was ignored" }
        if (run.disposition != VlmDisposition.AMENDED.name) {
            database.vlmRunDao().setDisposition(runId, VlmDisposition.USED_AS_STARTING_POINT.name)
        }
    }

    suspend fun ignore(runId: String) = database.withTransaction {
        val run = requireActiveRun(runId)
        require(run.firstPresentedAtEpochMs != null) { "Output has not been presented" }
        database.vlmRunDao().setDisposition(runId, VlmDisposition.IGNORED.name)
    }

    private suspend fun requireActiveRun(runId: String): VlmRunEntity {
        val run = requireNotNull(database.vlmRunDao().getRun(runId)) { "VLM run not found" }
        val clip = requireNotNull(database.clipDao().getClip(run.clipId)) { "Clip not found" }
        require(clip.approvalState != "DELETED") { "Clip is deleted" }
        return run
    }
}
