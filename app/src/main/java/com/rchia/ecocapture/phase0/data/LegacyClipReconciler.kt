package com.rchia.ecocapture.phase0.data

import android.util.Log
import com.rchia.ecocapture.phase0.domain.ApprovalState
import com.rchia.ecocapture.phase0.domain.ClipRecord
import com.rchia.ecocapture.phase0.domain.ReviewState
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

class LegacyClipReconciler(
    private val repository: ClipRepository,
    private val recordingsDirectory: File,
) {
    suspend fun reconcile() {
        val files = recordingsDirectory.listFiles { file ->
            file.isFile && file.extension.equals("mp4", ignoreCase = true)
        }.orEmpty()

        var firstFailure: Exception? = null
        files.forEach { videoFile ->
            try {
            if (repository.containsVideo(videoFile)) return@forEach

            val metadataFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.json")
                .takeIf(File::isFile)
            val metadata = metadataFile?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
            repository.insert(
                ClipRecord(
                    clipId = metadata.clipIdOrNull() ?: UUID.randomUUID().toString(),
                    videoFile = videoFile,
                    metadataFile = metadataFile,
                    createdAt = Instant.ofEpochMilli(
                        metadata.longValue("created_at_epoch_ms").takeIf { it > 0L }
                            ?: videoFile.lastModified().coerceAtLeast(0L)
                    ),
                    durationMs = metadata.longValue("duration_ms"),
                    width = metadata.intValue("width"),
                    height = metadata.intValue("height"),
                    sampleCount = metadata.longValue("samples"),
                    reviewState = ReviewState.UNREVIEWED,
                    approvalState = ApprovalState.UNDECIDED,
                )
            )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
            }
        }
        firstFailure?.let { throw it }
    }

    companion object {
        private const val TAG = "LegacyClipReconciler"

        suspend fun runSafely(reconciler: LegacyClipReconciler) {
            runCatching { reconciler.reconcile() }
                .onFailure { Log.e(TAG, "Legacy clip reconciliation failed", it) }
        }
    }
}

private fun String.longValue(key: String): Long =
    (Regex("\\\"$key\\\"\\s*:\\s*(-?\\d+)").find(this)?.groupValues?.get(1)?.toLongOrNull() ?: 0L).coerceAtLeast(0L)

private fun String.intValue(key: String): Int = longValue(key).takeIf { it <= Int.MAX_VALUE }?.toInt() ?: 0

private fun String.clipIdOrNull(): String? {
    val candidate = Regex("\"clip_id\"\\s*:\\s*\"([^\"]+)\"").find(this)?.groupValues?.get(1) ?: return null
    return runCatching { UUID.fromString(candidate).toString() }.getOrNull()
}
