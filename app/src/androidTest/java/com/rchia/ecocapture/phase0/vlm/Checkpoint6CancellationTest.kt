package com.rchia.ecocapture.phase0.vlm

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Checkpoint6CancellationTest {
    @Test fun realGenerationCancellationUnloadsAndReloads(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkpoint6Cancel") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.cacheDir, "checkpoint6-cancel-fixture.mp4")
        instrumentation.context.assets.open("checkpoint5/short.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val engine = Qwen3VlEngine(context, FrameSamplingConfig(listOf(50), 320))
        var returned: VlmResult? = null
        try {
            assertEquals(VlmLoadResult.Ready, engine.load())
            val job = launch(Dispatchers.Default) { returned = engine.generate(VlmRequest("engineering-cancellation", fixture)) }
            delay(1500)
            assertFalse("Generation unexpectedly completed before cancellation test", job.isCompleted)
            val start = SystemClock.elapsedRealtime()
            withTimeout(90000) { job.cancelAndJoin() }
            val cancellationDuration = SystemClock.elapsedRealtime() - start
            assertTrue(job.isCancelled)
            assertNull("Cancelled inference must not return a persistable result", returned)
            engine.unload()
            assertEquals(VlmLoadResult.Ready, engine.load())
            File(context.filesDir, "checkpoint6-cancellation-report.txt").writeText(
                "cancelAndJoinMs=$cancellationDuration\nnoResultReturned=PASS\nreloadAfterCancellation=PASS\n")
        } finally { engine.unload(); fixture.delete() }
    }
}
