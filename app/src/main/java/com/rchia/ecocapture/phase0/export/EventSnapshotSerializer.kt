package com.rchia.ecocapture.phase0.export

import com.rchia.ecocapture.phase0.data.local.AnnotationEntity
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import com.rchia.ecocapture.phase0.data.local.VlmRunEntity
import java.util.UUID

data class EventSnapshotSource(
    val clip: ClipEntity,
    val annotations: List<AnnotationEntity>,
    val vlmRuns: List<VlmRunEntity>,
)

data class SnapshotIdentity(
    val snapshotId: String,
    val createdAtEpochMs: Long,
    val appVersion: String,
    val previousSnapshotId: String? = null,
    val approvedStudyParticipantCode: String? = null,
)

/** A restricted deterministic JSON encoder: fixed field order, UTF-8, explicit nulls, no floats. */
internal object SnapshotJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in value) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c.code < 32 || c.isSurrogate()) {
                    append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
            append('"')
        }
        is Boolean, is Long, is Int -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            require(it.key is String)
            encode(it.key) + ":" + encode(it.value)
        }
        is List<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> error("UNSUPPORTED_JSON_VALUE")
    }
    fun bytes(value: Any?): ByteArray = encode(value).toByteArray(Charsets.UTF_8)
}

class EventSnapshotSerializer {
    fun validate(source: EventSnapshotSource, identity: SnapshotIdentity) {
        uuid(source.clip.clipId)
        uuid(identity.snapshotId)
        identity.previousSnapshotId?.let(::uuid)
        require(identity.previousSnapshotId != identity.snapshotId) { "INVALID_PREVIOUS_SNAPSHOT" }
        require(identity.createdAtEpochMs >= 0 && identity.appVersion.isNotBlank())
        identity.approvedStudyParticipantCode?.let {
            require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "INVALID_STUDY_CODE" }
        }
        require(source.clip.approvalState == "APPROVED" && source.clip.reviewState == "REVIEWED") {
            "CLIP_NOT_APPROVED"
        }
        require(source.clip.createdAtEpochMs >= 0 && source.clip.durationMs > 0 &&
            source.clip.width > 0 && source.clip.height > 0 && source.clip.sampleCount > 0)
        val annotations = source.annotations
        val runs = source.vlmRuns
        require(annotations.map { it.annotationId }.distinct().size == annotations.size)
        require(runs.map { it.vlmRunId }.distinct().size == runs.size)
        require(annotations.count { it.isCurrent } == if (annotations.isEmpty()) 0 else 1) {
            "INVALID_CURRENT_ANNOTATION"
        }
        val ordered = annotations.sortedWith(compareBy({ it.createdAtEpochMs }, { it.annotationId }))
        val seen = mutableSetOf<String>()
        for (a in ordered) {
            uuid(a.annotationId)
            require(a.clipId == source.clip.clipId && a.createdAtEpochMs >= 0)
            require(a.source in setOf("PARTICIPANT", "PARTICIPANT_AMENDMENT"))
            // Lineage is checked for existence/cycles independently of phone-clock ordering.
            a.supersedesAnnotationId?.let { parent ->
                require(parent != a.annotationId && annotations.any { it.annotationId == parent })
            }
            require(a.parentVlmRunId == null || runs.any { it.vlmRunId == a.parentVlmRunId })
            require((a.source == "PARTICIPANT_AMENDMENT") == (a.parentVlmRunId != null))
            seen.clear()
            var cursor: AnnotationEntity? = a
            while (cursor != null) {
                require(seen.add(cursor.annotationId)) { "CYCLIC_ANNOTATION_HISTORY" }
                cursor = cursor.supersedesAnnotationId?.let { id -> annotations.single { it.annotationId == id } }
            }
        }
        runs.forEach {
            uuid(it.vlmRunId)
            require(it.clipId == source.clip.clipId)
        }
    }

    fun serialize(source: EventSnapshotSource, identity: SnapshotIdentity): ByteArray {
        validate(source, identity)
        val c = source.clip
        return SnapshotJson.bytes(linkedMapOf(
            "schema" to "ecocapture.event.v1",
            "event_id" to c.clipId,
            "snapshot_id" to identity.snapshotId,
            "previous_snapshot_id" to identity.previousSnapshotId,
            "study_participant_code" to identity.approvedStudyParticipantCode,
            "clip_id" to c.clipId,
            "capture" to linkedMapOf(
                "created_at_epoch_ms" to c.createdAtEpochMs, "duration_ms" to c.durationMs,
                "width" to c.width, "height" to c.height, "sample_count" to c.sampleCount,
                "created_by_app_version" to c.createdByAppVersion,
            ),
            "review" to linkedMapOf(
                "review_state" to c.reviewState, "approval_state" to c.approvalState,
                "approved_at_epoch_ms" to null, "updated_at_epoch_ms" to c.updatedAtEpochMs,
                "snapshot_created_at_epoch_ms" to identity.createdAtEpochMs,
            ),
            "participant_annotations" to linkedMapOf(
                "current_annotation_id" to source.annotations.singleOrNull { it.isCurrent }?.annotationId,
                "revisions" to source.annotations.sortedWith(compareBy({ it.createdAtEpochMs }, { it.annotationId })).map {
                    linkedMapOf("annotation_id" to it.annotationId, "source" to it.source, "text" to it.text,
                        "created_at_epoch_ms" to it.createdAtEpochMs,
                        "supersedes_annotation_id" to it.supersedesAnnotationId,
                        "parent_vlm_run_id" to it.parentVlmRunId, "is_current" to it.isCurrent)
                },
            ),
            "vlm_runs" to source.vlmRuns.sortedWith(compareBy({ it.generatedAtEpochMs }, { it.vlmRunId })).map {
                linkedMapOf("vlm_run_id" to it.vlmRunId, "model_id" to it.modelId, "model_quant" to it.modelQuant,
                    "language_model_sha256" to it.languageModelSha256, "mmproj_sha256" to it.mmprojSha256,
                    "runtime_name" to it.runtimeName, "runtime_commit" to it.runtimeCommit,
                    "prompt_version" to it.promptVersion, "generated_at_epoch_ms" to it.generatedAtEpochMs,
                    "first_presented_at_epoch_ms" to it.firstPresentedAtEpochMs,
                    "inference_duration_ms" to it.inferenceDurationMs,
                    "frame_sampling_json" to it.frameSamplingJson, "generation_config_json" to it.generationConfigJson,
                    "raw_output" to it.rawOutput, "description" to it.description, "disposition" to it.disposition)
            },
            "export" to linkedMapOf("schema_version" to 1, "app_version" to identity.appVersion,
                "exported_at_epoch_ms" to identity.createdAtEpochMs),
        ))
    }

    companion object {
        fun uuid(value: String) {
            require(UUID.fromString(value).toString() == value) { "INVALID_UUID" }
        }
    }
}
