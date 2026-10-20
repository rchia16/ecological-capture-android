package com.rchia.ecocapture.phase0.vlm

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.vlm.native.NativeVlmBindings
import com.rchia.ecocapture.phase0.vlm.native.NativeVlmBridge
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real model test using generated pixels only; never opens participant recordings or Room. */
@RunWith(AndroidJUnit4::class)
class SmolVlm2NativeTest {
    @Test fun generateFromThreeFramesWithModelSpecificTemplate() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smolVlm2Native") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, VlmEngineeringActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val bridge = NativeVlmBridge(VlmModelManager(context))
        val start = SystemClock.elapsedRealtime()
        var generating = false
        try {
            assertEquals(VlmLoadResult.Ready, bridge.load())
            val loadMs = SystemClock.elapsedRealtime() - start
            withContext(Dispatchers.IO) {
                assertEquals(0, NativeVlmBindings.nativeBeginGeneration()); generating = true
                for (index in 1..3) {
                    val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                        val paint = Paint().apply { color = Color.RED }
                        canvas.drawRect(60f + index * 40, 140f, 180f + index * 40, 320f, paint)
                        paint.color = Color.BLACK; paint.textSize = 42f
                        canvas.drawText("Frame $index", 50f, 70f, paint)
                        assertEquals(0, NativeVlmBindings.nativeAddFrame(bitmap))
                    } finally { bitmap.recycle() }
                }
                val response = NativeVlmBindings.nativeGenerate(
                    "Describe only visible image content. Do not invent details.".toByteArray(),
                    "These three images are in chronological order. Briefly describe what is visible.".toByteArray(), 48)
                assertEquals(4, response.size)
                assertEquals("0", response[0].toString(Charsets.UTF_8))
                assertTrue(response[1].toString(Charsets.UTF_8).isNotBlank())
                val formatted = response[3].toString(Charsets.UTF_8)
                assertTrue(formatted.startsWith("<|im_start|>"))
                assertTrue(formatted.contains("User:"))
                assertTrue(formatted.endsWith("<end_of_utterance>\nAssistant:"))
                assertFalse(formatted.contains("<|im_end|>"))
                assertEquals(3, Regex(Regex.escape("<__media__>")).findAll(formatted).count())
                val report = JSONObject().put("modelId", VlmModelManager.MODEL_ID)
                    .put("revision", VlmModelManager.MODEL_REVISION).put("frames", 3).put("success", true)
                    .put("loadMs", loadMs).put("elapsedMs", SystemClock.elapsedRealtime() - start)
                    .put("nativeMetrics", JSONObject(response[2].toString(Charsets.UTF_8)))
                File(context.cacheDir, "smolvlm2-synthetic-report.json").writeText(report.toString(2))
            }
        } finally {
            if (generating) NativeVlmBindings.nativeEndGeneration()
            assertEquals(VlmLoadResult.Ready, bridge.unload())
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
