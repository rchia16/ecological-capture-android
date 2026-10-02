package com.rchia.ecocapture.phase0.vlm

sealed interface VlmResult {
    data class Success(
        val clipId: String,
        val rawOutput: String,
        val description: String,
        val modelInfo: VlmModelInfo,
        val promptVersion: String,
        val generatedAtEpochMs: Long,
        val inferenceDurationMs: Long,
        val frameSamplingJson: String,
        val generationConfigJson: String,
    ) : VlmResult

    data class Failure(val reason: VlmFailure) : VlmResult
}
