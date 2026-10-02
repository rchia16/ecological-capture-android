package com.rchia.ecocapture.phase0.vlm

import android.content.Context
import android.os.SystemClock
import com.rchia.ecocapture.phase0.BuildConfig
import com.rchia.ecocapture.phase0.vlm.native.NativeQwen3VlBridge
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class QwenGenerationConfig(val maxOutputTokens: Int = 384) {
    init { require(maxOutputTokens in 1..1024) }
}

/** Checkpoint 6 device comparison: temporal coverage with less cost than five frames. */
object QwenPhase2Defaults {
    val sampling: FrameSamplingConfig get() = FrameSamplingConfig(listOf(15, 50, 85), 1024)
}

sealed interface NativeGenerationResult {
    data class Success(val rawOutput: String, val metricsJson: String, val formattedPrompt: ByteArray) : NativeGenerationResult
    data class Failure(val reason: VlmFailure) : NativeGenerationResult
}

internal interface QwenRuntime {
    suspend fun load(): VlmLoadResult
    suspend fun generate(samples: SampledFrames, prompt: VlmPromptDefinition, config: QwenGenerationConfig): NativeGenerationResult
    suspend fun unload(): VlmLoadResult
}

/** Coarse visual-context assistant. Only saved frames and the fixed ecological prompt enter JNI. */
class Qwen3VlEngine internal constructor(
    private val runtime: QwenRuntime,
    private val sampler: VlmFrameSampler = VlmFrameSampler(),
    private val sampling: FrameSamplingConfig = QwenPhase2Defaults.sampling,
    private val generation: QwenGenerationConfig = QwenGenerationConfig(),
) : VlmEngine {
    constructor(context: Context, sampling: FrameSamplingConfig = QwenPhase2Defaults.sampling,
        generation: QwenGenerationConfig = QwenGenerationConfig()) : this(
        NativeQwen3VlBridge(VlmModelManager(context.applicationContext)), VlmFrameSampler(), sampling, generation)

    private val lifecycle = Mutex()
    private var loaded = false
    private var loadDurationMs = 0L

    override suspend fun load(): VlmLoadResult = lifecycle.withLock {
        if (loaded) return@withLock VlmLoadResult.Ready
        val start = SystemClock.elapsedRealtime()
        val result = runtime.load()
        loadDurationMs = SystemClock.elapsedRealtime() - start
        loaded = result == VlmLoadResult.Ready
        result
    }

    override suspend fun generate(request: VlmRequest): VlmResult = lifecycle.withLock {
        if (!loaded) return@withLock VlmResult.Failure(VlmFailure.ModelNotLoaded)
        if (request.prompt != VlmPrompt.definition) return@withLock VlmResult.Failure(VlmFailure.InvalidPrompt)
        try {
            val extracted = sampler.sample(request.videoFile, sampling)
            if (extracted is FrameSamplingResult.Failure) return@withLock VlmResult.Failure(
                if (extracted.reason == FrameSamplingFailure.OUT_OF_MEMORY) VlmFailure.OutOfMemory else VlmFailure.FrameExtractionFailed)
            (extracted as FrameSamplingResult.Success).samples.use { samples ->
                val frameProvenance = samples.provenanceJson()
                val start = SystemClock.elapsedRealtime()
                when (val result = runtime.generate(samples, request.prompt, generation)) {
                    is NativeGenerationResult.Failure -> VlmResult.Failure(result.reason)
                    is NativeGenerationResult.Success -> {
                        val duration = SystemClock.elapsedRealtime() - start
                        if (result.rawOutput.isBlank()) return@use VlmResult.Failure(VlmFailure.EmptyOutput)
                        val config = JSONObject().apply {
                            put("sampler", "greedy"); put("temperature", 0); put("seed", JSONObject.NULL)
                            put("maxOutputTokens", generation.maxOutputTokens)
                            put("contextSize", 8192); put("batchSize", 512); put("threads", 4)
                            put("backend", "CPU"); put("projectorWarmup", false)
                            put("loadAndVerificationMs", loadDurationMs)
                            put("inferenceIncludes", "RGB ingestion, tokenization, vision encoding, prefill, generation")
                            put("chatFormatter", "llama_chat_apply_template/chatml")
                            put("imagePlacement", "chronological image markers before user text; no temporal merge")
                            put("systemPrompt", request.prompt.systemPrompt); put("userPrompt", request.prompt.userPrompt)
                            put("formattedPromptSha256", sha256(result.formattedPrompt))
                            put("nativeMetrics", JSONObject(result.metricsJson))
                        }.toString()
                        VlmResult.Success(request.clipId, result.rawOutput, result.rawOutput, modelInfo(),
                            request.prompt.version, System.currentTimeMillis(), duration, frameProvenance, config)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { VlmResult.Failure(VlmFailure.OutOfMemory) }
        catch (_: Exception) { VlmResult.Failure(VlmFailure.NativeFailure(6)) }
    }

    override suspend fun unload() = withContext(NonCancellable) { lifecycle.withLock {
        val result = runtime.unload()
        if (result == VlmLoadResult.Ready) loaded = false
        else error("Native unload failed: $result")
    } }

    override fun modelInfo() = VlmModelInfo("Qwen/Qwen3-VL-4B-Instruct-GGUF", "Q4_K_M",
        VlmModelManager.OFFICIAL_FILES[0].sha256, VlmModelManager.OFFICIAL_FILES[1].sha256,
        "llama.cpp/libmtmd", NativeQwen3VlBridge.RUNTIME_COMMIT)

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

enum class VlmRuntimeMode { SIMULATED, QWEN_ENGINEERING, QWEN_PARTICIPANT }

object VlmEngineFactory {
    /** Simulation remains available for isolated workflow checks; participant review selects real mode. */
    fun create(context: Context, mode: VlmRuntimeMode = VlmRuntimeMode.SIMULATED,
        sampling: FrameSamplingConfig = QwenPhase2Defaults.sampling,
        generation: QwenGenerationConfig = QwenGenerationConfig()): VlmEngine = when (mode) {
        VlmRuntimeMode.SIMULATED -> FakeVlmEngine(delayMs = 3000)
        VlmRuntimeMode.QWEN_PARTICIPANT -> Qwen3VlEngine(context, sampling, generation)
        VlmRuntimeMode.QWEN_ENGINEERING -> {
            check(BuildConfig.DEBUG) { "Real VLM engineering mode requires a debug build" }
            Qwen3VlEngine(context, sampling, generation)
        }
    }
}
