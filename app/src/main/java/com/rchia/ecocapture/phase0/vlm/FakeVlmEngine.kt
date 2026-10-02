package com.rchia.ecocapture.phase0.vlm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deterministic workflow simulation. Does not decode video, sample frames, or load Qwen. */
class FakeVlmEngine(
    private val delayMs: Long = 300,
    private val output: String = DEFAULT_OUTPUT,
    private val loadFailure: VlmFailure? = null,
    private val generationFailure: VlmFailure? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : VlmEngine {
    init { require(delayMs >= 0) }
    private val lifecycle = Mutex()
    private var loaded = false

    override suspend fun load(): VlmLoadResult = lifecycle.withLock {
        loaded = loadFailure == null
        loadFailure?.let { VlmLoadResult.Failure(it) } ?: VlmLoadResult.Ready
    }

    override suspend fun generate(request: VlmRequest): VlmResult = lifecycle.withLock {
        if (!loaded) return@withLock VlmResult.Failure(VlmFailure.ModelNotLoaded)
        if (!request.videoFile.isFile || request.videoFile.length() == 0L || request.videoFile.extension.lowercase() != "mp4") {
            return@withLock VlmResult.Failure(VlmFailure.FrameExtractionFailed)
        }
        val start = clock()
        delay(delayMs) // Cooperative coroutine cancellation; never persist cancelled work as success.
        generationFailure?.let { return@withLock VlmResult.Failure(it) }
        val end = clock()
        VlmResult.Success(
            clipId = request.clipId, rawOutput = output, description = output,
            modelInfo = modelInfo(), promptVersion = request.prompt.version,
            generatedAtEpochMs = end, inferenceDurationMs = (end - start).coerceAtLeast(0),
            frameSamplingJson = "{\"simulated\":true,\"frames\":[]}",
            generationConfigJson = "{\"simulated\":true,\"delayMs\":$delayMs}",
        )
    }

    override suspend fun unload() { lifecycle.withLock { loaded = false } }

    override fun modelInfo() = VlmModelInfo(
        modelId = "fake/ecological-scene-v1", modelQuant = "NONE",
        languageModelSha256 = "", mmprojSha256 = "",
        runtimeName = "FakeVlmEngine", runtimeCommit = "fake_v1",
    )

    companion object {
        val DEFAULT_OUTPUT = """
            Scene:
            This is a simulated scene description for testing the review workflow.

            Relevant spatial features:
            A path, doorway and nearby obstacle are simulated examples, not observations of this recording.

            Visible actions:
            Actual actions cannot be determined by the fake engine.

            Clearly legible text:
            No clearly legible text relevant to the scene.

            Uncertain or unclear details:
            The recording is not analyzed. Text, numbers and the participant's experience cannot be determined.
        """.trimIndent()
    }
}
