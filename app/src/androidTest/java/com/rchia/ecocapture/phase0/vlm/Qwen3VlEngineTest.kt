package com.rchia.ecocapture.phase0.vlm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Qwen3VlEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val files = mutableListOf<File>()
    private fun fixture() = File(instrumentation.targetContext.cacheDir, "qwen-engine-${UUID.randomUUID()}.mp4").also {
        files += it
        instrumentation.context.assets.open("checkpoint5/short.mp4").use { input -> it.outputStream().use { output -> input.copyTo(output) } }
    }
    @After fun cleanup() { files.forEach { it.delete() } }
    private class Runtime : QwenRuntime {
        var loadResult: VlmLoadResult = VlmLoadResult.Ready
        var generated: NativeGenerationResult = NativeGenerationResult.Success(
            "Scene:\nA corridor and doorway.\nUncertain or unclear details:\nSmall text, dates and prices are unclear. € — 😀\n",
            "{\"stopReason\":\"eog\",\"generatedTokens\":20}", "formatted prompt".toByteArray())
        var cancelled = false
        var unloaded = false
        var samples: SampledFrames? = null
        var calls = 0
        override suspend fun load() = loadResult
        override suspend fun unload(): VlmLoadResult { unloaded = true; return VlmLoadResult.Ready }
        override suspend fun generate(samples: SampledFrames, prompt: VlmPromptDefinition, config: QwenGenerationConfig): NativeGenerationResult {
            this.samples = samples; calls++
            assertEquals(VlmPrompt.definition, prompt)
            assertTrue(samples.frames.none { it.bitmap.isRecycled })
            if (cancelled) throw CancellationException("test cancellation")
            return generated
        }
    }
    @Test fun generationRequiresSuccessfulLoad() = runBlocking {
        val runtime = Runtime().apply { loadResult = VlmLoadResult.Failure(VlmFailure.ModelMissing) }
        val engine = Qwen3VlEngine(runtime)
        assertEquals(runtime.loadResult, engine.load())
        assertEquals(VlmResult.Failure(VlmFailure.ModelNotLoaded), engine.generate(VlmRequest("test", fixture())))
        assertEquals(0, runtime.calls)
        engine.unload(); assertTrue(runtime.unloaded)
    }
    @Test fun rawUncertaintyUnicodeAndAllProvenanceRemainUnmodified() = runBlocking {
        val runtime = Runtime()
        val engine = Qwen3VlEngine(runtime)
        val source = fixture()
        val original = source.readBytes()
        try {
            engine.load()
            val result = engine.generate(VlmRequest("test", source)) as VlmResult.Success
            assertEquals((runtime.generated as NativeGenerationResult.Success).rawOutput, result.rawOutput)
            assertEquals(result.rawOutput, result.description)
            assertEquals("ecological_scene_description_v2", result.promptVersion)
            assertEquals(VlmModelManager.OFFICIAL_FILES[0].sha256, result.modelInfo.languageModelSha256)
            assertEquals(VlmModelManager.OFFICIAL_FILES[1].sha256, result.modelInfo.mmprojSha256)
            val config = JSONObject(result.generationConfigJson)
            assertEquals(384, config.getInt("maxOutputTokens"))
            assertEquals(VlmPrompt.definition.systemPrompt, config.getString("systemPrompt"))
            assertEquals(VlmPrompt.definition.userPrompt, config.getString("userPrompt"))
            assertEquals(64, config.getString("formattedPromptSha256").length)
            assertEquals(3, JSONObject(result.frameSamplingJson).getJSONArray("frames").length())
            assertEquals(listOf(15, 50, 85), QwenPhase2Defaults.sampling.percentages)
            assertTrue(runtime.samples!!.frames.all { it.bitmap.isRecycled })
            assertArrayEquals(original, source.readBytes())
        } finally { engine.unload() }
    }
    @Test fun alteredPromptIsRejectedBeforeNativeGeneration() = runBlocking {
        val runtime = Runtime(); val engine = Qwen3VlEngine(runtime)
        try {
            engine.load()
            assertEquals(VlmResult.Failure(VlmFailure.InvalidPrompt), engine.generate(VlmRequest("test", fixture(),
                VlmPrompt.definition.copy(systemPrompt = "Infer participant problems"))))
            assertEquals(0, runtime.calls)
        } finally { engine.unload() }
    }
    @Test fun failedAndBlankNativeOutputsRecycleFramesWithoutSuccess() = runBlocking {
        val runtime = Runtime(); val engine = Qwen3VlEngine(runtime)
        try {
            engine.load()
            runtime.generated = NativeGenerationResult.Failure(VlmFailure.NativeFailure(13))
            assertEquals(VlmResult.Failure(VlmFailure.NativeFailure(13)), engine.generate(VlmRequest("test", fixture())))
            assertTrue(runtime.samples!!.frames.all { it.bitmap.isRecycled })
            runtime.generated = NativeGenerationResult.Success(" \n", "{}", byteArrayOf())
            assertEquals(VlmResult.Failure(VlmFailure.EmptyOutput), engine.generate(VlmRequest("test", fixture())))
            assertTrue(runtime.samples!!.frames.all { it.bitmap.isRecycled })
        } finally { engine.unload() }
    }
    @Test fun cancelledGenerationRecyclesFramesAndNeverReturnsSuccess() = runBlocking {
        val runtime = Runtime().apply { cancelled = true }; val engine = Qwen3VlEngine(runtime)
        try {
            engine.load()
            try { engine.generate(VlmRequest("test", fixture())); fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            assertTrue(runtime.samples!!.frames.all { it.bitmap.isRecycled })
        } finally { engine.unload() }
        assertTrue(runtime.unloaded)
    }
    @Test fun runtimeChoiceDefaultsToSimulationAndDoesNotLoadAtConstruction() {
        assertTrue(VlmEngineFactory.create(instrumentation.targetContext) is FakeVlmEngine)
        assertTrue(VlmEngineFactory.create(instrumentation.targetContext, VlmRuntimeMode.QWEN_ENGINEERING) is Qwen3VlEngine)
    }
}
