package com.rchia.ecocapture.phase0.vlm

import android.content.Context
import androidx.room.Room
import androidx.work.WorkerParameters
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.vlm.background.AiPreparationWorker
import com.rchia.ecocapture.phase0.vlm.native.NativeQwen3VlBridge
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** Debug-only, disposable recovery fixtures. Never opens the participant database or official model directory. */
class Checkpoint8RecoveryWorker(context: Context, parameters: WorkerParameters) : AiPreparationWorker(context, parameters) {
    private val token get() = UUID.fromString(requireNotNull(inputData.getString("testToken"))).toString()
    private fun marker(suffix: String) = File(applicationContext.filesDir, "checkpoint8-$token.$suffix")
    override fun openDatabase() = Room.databaseBuilder(applicationContext, EcologicalCaptureDatabase::class.java, "checkpoint8-system-$token.db").build()
    override fun closeDatabase(database: EcologicalCaptureDatabase) { database.close() }
    override fun preparationAllowed() = true // Isolated protocol fixture, independent of participant preferences.
    override fun memoryAvailable() = true // Critical callbacks remain active; deterministic preflight fixtures.
    override fun createEngine(): VlmEngine {
        val counter = marker("invocations")
        counter.writeText(((counter.takeIf { it.exists() }?.readText()?.toInt() ?: 0) + 1).toString())
        val scenario = inputData.getString("scenario") ?: "success"
        if (scenario == "missing" || scenario == "hash") {
            val folder = File(applicationContext.cacheDir, "checkpoint8-model-$token").apply { mkdirs() }
            val manager = if (scenario == "missing") VlmModelManager(folder) else {
                File(folder, "language.gguf").writeBytes(byteArrayOf(1))
                File(folder, "projector.gguf").writeBytes(byteArrayOf(2))
                VlmModelManager(folder, listOf(VlmModelFileSpec("language.gguf", 1, "0".repeat(64)),
                    VlmModelFileSpec("projector.gguf", 1, "0".repeat(64))))
            }
            val bridge = NativeQwen3VlBridge(manager)
            return object : VlmEngine {
                override suspend fun load() = bridge.load()
                override suspend fun generate(request: VlmRequest): VlmResult = error("Unverified files must never reach inference")
                override suspend fun unload() { bridge.unload() }
                override fun modelInfo() = FakeVlmEngine().modelInfo()
            }
        }
        if (scenario == "frames") return Qwen3VlEngine(object : QwenRuntime {
            override suspend fun load() = VlmLoadResult.Ready
            override suspend fun unload() = VlmLoadResult.Ready
            override suspend fun generate(samples: SampledFrames, prompt: VlmPromptDefinition, config: QwenGenerationConfig): NativeGenerationResult =
                error("Invalid disposable MP4 must fail during actual frame extraction")
        })
        val fake = FakeVlmEngine(delayMs = inputData.getLong("delayMs", 1000),
            generationFailure = when (scenario) { "native" -> VlmFailure.NativeFailure(13); "oom" -> VlmFailure.OutOfMemory; else -> null })
        return object : VlmEngine by fake {
            override suspend fun generate(request: VlmRequest): VlmResult {
                marker("running").writeText(id.toString())
                return fake.generate(request)
            }
        }
    }

    override suspend fun ready(clipId: String, database: EcologicalCaptureDatabase) {
        val saved = requireNotNull(database.vlmRunDao().getRun(id.toString()))
        check(saved.rawOutput == FakeVlmEngine.DEFAULT_OUTPUT && saved.description == saved.rawOutput)
        check(saved.firstPresentedAtEpochMs == null && saved.disposition == "NOT_PRESENTED")
        check(database.annotationDao().history(clipId).isEmpty())
        val clip = requireNotNull(database.clipDao().getClip(clipId))
        check(clip.reviewState == "DEFERRED" && clip.approvalState == "UNDECIDED")
        marker("done.json").writeText(JSONObject().put("testToken", token).put("runId", id.toString())
            .put("rawOutputPreserved", true).put("noParticipantAnnotation", true).put("decisionPreserved", true)
            .put("invocations", marker("invocations").readText().toInt()).toString())
    }
}
