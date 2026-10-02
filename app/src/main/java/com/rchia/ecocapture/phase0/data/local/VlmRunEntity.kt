package com.rchia.ecocapture.phase0.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Original output and provenance are insert-only; participant edits live in annotations. */
@Entity(tableName = "vlm_runs")
data class VlmRunEntity(
    @PrimaryKey val vlmRunId: String,
    val clipId: String,
    val modelId: String,
    val modelQuant: String,
    val languageModelSha256: String,
    val mmprojSha256: String,
    val runtimeName: String,
    val runtimeCommit: String,
    val promptVersion: String,
    val generatedAtEpochMs: Long,
    val firstPresentedAtEpochMs: Long?,
    val inferenceDurationMs: Long,
    val frameSamplingJson: String,
    val generationConfigJson: String,
    val rawOutput: String,
    val description: String,
    val disposition: String,
)
