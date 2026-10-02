package com.rchia.ecocapture.phase0.vlm

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FakeVlmEngineTest {
    private val video = File.createTempFile("fake-vlm-", ".mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    private val request = VlmRequest("clip", video)
    @After fun cleanUp() { video.delete() }

    @Test fun generationRequiresAnExplicitLoadAndUnloadingReleasesState() = runBlocking {
        val engine = FakeVlmEngine(delayMs = 0)
        assertEquals(VlmResult.Failure(VlmFailure.ModelNotLoaded), engine.generate(request))
        assertEquals(VlmLoadResult.Ready, engine.load())
        assertTrue(engine.generate(request) is VlmResult.Success)
        engine.unload()
        engine.unload()
        assertEquals(VlmResult.Failure(VlmFailure.ModelNotLoaded), engine.generate(request))
        assertEquals(VlmLoadResult.Ready, engine.load())
        assertTrue(engine.generate(request) is VlmResult.Success)
        engine.unload()
    }

    @Test fun deterministicOutputPreservesUncertaintyAndHonestFakeProvenance() = runBlocking {
        val output = "Scene:\nA simulated path.\nUncertain or unclear details:\nThe sign is unclear.\n\n"
        val engine = FakeVlmEngine(delayMs = 0, output = output, clock = { 1234 })
        engine.load()
        val first = engine.generate(request) as VlmResult.Success
        assertEquals(first, engine.generate(request))
        assertEquals(output, first.rawOutput)
        assertEquals(output, first.description)
        assertEquals(VlmPrompt.VERSION, first.promptVersion)
        assertEquals(VlmPrompt.systemPrompt, request.prompt.systemPrompt)
        assertEquals(VlmPrompt.userPrompt, request.prompt.userPrompt)
        assertEquals("fake/ecological-scene-v1", first.modelInfo.modelId)
        assertEquals("FakeVlmEngine", first.modelInfo.runtimeName)
        assertEquals("", first.modelInfo.languageModelSha256)
        assertEquals("", first.modelInfo.mmprojSha256)
        assertEquals("{\"simulated\":true,\"frames\":[]}", first.frameSamplingJson)
        assertEquals(1234L, first.generatedAtEpochMs)
        assertEquals(0L, first.inferenceDurationMs)
        engine.unload()
    }

    @Test fun loadFailureAndGenerationFailureRemainTypedFailures() = runBlocking {
        val loading = FakeVlmEngine(delayMs = 0, loadFailure = VlmFailure.ModelLoadFailed)
        assertEquals(VlmLoadResult.Failure(VlmFailure.ModelLoadFailed), loading.load())
        assertEquals(VlmResult.Failure(VlmFailure.ModelNotLoaded), loading.generate(request))
        val generating = FakeVlmEngine(delayMs = 0, generationFailure = VlmFailure.OutOfMemory)
        generating.load()
        assertEquals(VlmResult.Failure(VlmFailure.OutOfMemory), generating.generate(request))
        generating.unload()
        assertArrayEquals(byteArrayOf(1, 2, 3), video.readBytes())
    }

    @Test fun cancellationProducesNoSuccessAndLeavesLifecycleUsable() = runBlocking {
        val engine = FakeVlmEngine(delayMs = 60_000)
        engine.load()
        var result: VlmResult? = null
        val generation = launch(start = CoroutineStart.UNDISPATCHED) { result = engine.generate(request) }
        generation.cancelAndJoin()
        assertTrue(generation.isCancelled)
        assertNull(result)
        engine.unload() // Must not deadlock after cancellation releases the mutex.
        assertEquals(VlmResult.Failure(VlmFailure.ModelNotLoaded), engine.generate(request))
        assertEquals(VlmLoadResult.Ready, engine.load())
        engine.unload()
        assertArrayEquals(byteArrayOf(1, 2, 3), video.readBytes())
    }

    @Test fun missingOrEmptySavedMediaFailsWithoutCreatingOrChangingIt() = runBlocking {
        val engine = FakeVlmEngine(delayMs = 0)
        engine.load()
        video.writeBytes(byteArrayOf())
        assertEquals(VlmResult.Failure(VlmFailure.FrameExtractionFailed), engine.generate(request))
        assertEquals(0L, video.length())
        video.delete()
        assertEquals(VlmResult.Failure(VlmFailure.FrameExtractionFailed), engine.generate(request))
        assertFalse(video.exists())
        engine.unload()
    }
}
