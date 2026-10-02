package com.rchia.ecocapture.phase0.vlm

import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.vlm.native.NativeQwenBindings
import com.rchia.ecocapture.phase0.vlm.native.NativeQwen3VlBridge
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in JNI benchmark only. No Room writes, production bridge changes or participant UI. */
@RunWith(AndroidJUnit4::class)
class QwenModelBenchmarkTest {
    @Test fun runIsolatedConfiguration(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("modelBenchmark") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "com.rchia.ecocapture.phase0.benchmark") { "Benchmark must use the isolated package" }
        val runId = requireNotNull(args.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]+")))
        val model = requireNotNull(args.getString("model"))
        require(model in listOf("A", "B"))
        val name = requireNotNull(args.getString("recording"))
        require(name.matches(Regex("clip_[A-Za-z0-9_]+\\.mp4")))
        val count = requireNotNull(args.getString("frameCount")).toInt()
        val edge = requireNotNull(args.getString("maxLongEdge")).toInt()
        val maxOutputTokens = args.getString("maxOutputTokens")?.toInt() ?: 384
        require(maxOutputTokens in 1..1024)
        require((count == 1 && edge == 768) || (count == 3 && edge in listOf(768, 1024)))
        val source = File(context.filesDir, "benchmark/recordings/$name")
        val directory = File(context.filesDir, "benchmark/results/$runId").apply { mkdirs() }
        val reportFile = File(directory, "report.json")
        val report = JSONObject().put("runId", runId).put("model", model).put("recording", name)
            .put("frameCount", count).put("maxLongEdge", edge).put("warmup", args.getString("warmup") == "true")
            .put("repetition", args.getString("repetition")!!.toInt()).put("success", false)
            .put("crash", false).put("oom", false).put("pid", android.os.Process.myPid())
            .put("runtimeCommit", NativeQwen3VlBridge.RUNTIME_COMMIT).put("promptVersion", VlmPrompt.VERSION)
            .put("systemPrompt", VlmPrompt.definition.systemPrompt).put("userPrompt", VlmPrompt.definition.userPrompt)
            .put("sampler", "greedy").put("temperature", 0).put("maxOutputTokens", maxOutputTokens)
            .put("contextSize", 8192).put("threads", 4).put("batchSize", 512).put("backend", "CPU")
        fun checkpoint(stage: String) { report.put("stage", stage); reportFile.writeText(report.toString(2)) }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) { val size = input.read(bytes); if (size < 0) break; digest.update(bytes, 0, size) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        fun memory() = JSONObject().apply {
            File("/proc/self/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }
                .forEach { put(it.substringBefore(':') + "KiB", it.substringAfter(':').trim().substringBefore(' ').toLong()) }
        }
        val power = context.getSystemService(PowerManager::class.java)
        val activity = instrumentation.startActivitySync(Intent(context, VlmEngineeringActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var samples: SampledFrames? = null
        var nativeStarted = false
        var inferenceStarted = false
        val overallStart = SystemClock.elapsedRealtime()
        try {
            val sourceHash = hash(source)
            report.put("sourceSha256", sourceHash).put("thermalBefore", power.currentThermalStatus).put("memoryBefore", memory())
            val specs = if (model == "A") VlmModelManager.OFFICIAL_FILES else listOf(
                VlmModelFileSpec("Qwen3VL-2B-Instruct-Q4_K_M.gguf", 1107409952L, "089d75c52f4b7ffc56ba998ffc50aae89fcafc755f9e7208aacca281dca6c2ae"),
                VlmModelFileSpec("mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf", 445053216L, "f9a68fabba69c3b81e153367b2c7521030b0fa8bb0de400c9599c8e6725f9c82"))
            checkpoint("verification")
            val verifyStart = SystemClock.elapsedRealtime()
            val verified = VlmModelManager(File(context.filesDir, "benchmark/models/$model"), specs).inspect()
            assertEquals(VlmModelStatus.READY, verified.status)
            val bundle = requireNotNull(verified.bundle)
            report.put("verificationMs", SystemClock.elapsedRealtime() - verifyStart)
                .put("languageModelSha256", bundle.language.sha256).put("mmprojSha256", bundle.projector.sha256)
                .put("languageModelFilename", bundle.language.file.name).put("projectorFilename", bundle.projector.file.name)
            assertEquals("llama.cpp/libmtmd|${NativeQwen3VlBridge.RUNTIME_COMMIT}|arm64-v8a|CPU|8192|4", NativeQwenBindings.nativeGetRuntimeInfo())
            checkpoint("loading")
            val totalStart = SystemClock.elapsedRealtime()
            val loadStart = SystemClock.elapsedRealtime()
            nativeStarted = true
            val loaded = withContext(Dispatchers.IO) { NativeQwenBindings.nativeLoadModel(bundle.language.file.path, bundle.projector.file.path) }
            report.put("modelLoadMs", SystemClock.elapsedRealtime() - loadStart).put("loadStatus", loaded)
            assertEquals(0, loaded)
            report.put("memoryLoaded", memory())
            checkpoint("extraction")
            val extractionStart = SystemClock.elapsedRealtime()
            val extracted = VlmFrameSampler().sample(source, FrameSamplingConfig(if (count == 1) listOf(50) else listOf(15, 50, 85), edge))
            report.put("frameExtractionMs", SystemClock.elapsedRealtime() - extractionStart)
            assertTrue("Frame extraction failed: $extracted", extracted is FrameSamplingResult.Success)
            val decoded = (extracted as FrameSamplingResult.Success).samples
            samples = decoded
            report.put("frameSampling", JSONObject(decoded.provenanceJson()))
            checkpoint("inference")
            val ingestionStart = SystemClock.elapsedRealtime()
            val response = withContext(Dispatchers.IO) {
                assertEquals(0, NativeQwenBindings.nativeBeginGeneration())
                inferenceStarted = true
                decoded.frames.forEach { frame -> assertEquals(0, NativeQwenBindings.nativeAddFrame(frame.bitmap)) }
                report.put("rgbIngestionMs", SystemClock.elapsedRealtime() - ingestionStart)
                NativeQwenBindings.nativeGenerate(VlmPrompt.definition.systemPrompt.toByteArray(Charsets.UTF_8),
                    VlmPrompt.definition.userPrompt.toByteArray(Charsets.UTF_8), maxOutputTokens)
            }
            report.put("totalElapsedMs", SystemClock.elapsedRealtime() - totalStart).put("memoryAfterGeneration", memory())
                .put("thermalAfter", power.currentThermalStatus)
            val status = response[0].toString(Charsets.UTF_8).toInt()
            report.put("generationStatus", status).put("oom", status == 4)
            assertEquals(0, status)
            val raw = response[1].toString(Charsets.UTF_8)
            val metrics = JSONObject(response[2].toString(Charsets.UTF_8))
            report.put("nativeMetrics", metrics).put("visionEncodeMs", metrics.getLong("visionEncodingMs"))
                .put("promptEvaluationMs", metrics.getLong("prefillMs")).put("textGenerationMs", metrics.getLong("generationMs"))
                .put("generatedTokens", metrics.getInt("generatedTokens")).put("stopReason", metrics.getString("stopReason"))
                .put("rawOutput", raw).put("formattedPrompt", response[3].toString(Charsets.UTF_8)).put("success", true)
            File(directory, "output.txt").writeText(raw, Charsets.UTF_8)
            // Outside total timing: exact decoded frames allow manual review against model output.
            if (args.getString("warmup") == "true") decoded.frames.forEach { frame ->
                File(directory, "frame-${frame.index}.png").outputStream().use { frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            assertEquals(sourceHash, hash(source))
            report.put("sourceUnchanged", true)
        } catch (failure: Throwable) {
            report.put("errorType", failure.javaClass.simpleName).put("error", failure.message ?: "Unspecified failure")
                .put("oom", failure is OutOfMemoryError || report.optInt("generationStatus") == 4 || report.optInt("loadStatus") == 4)
            throw failure
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (inferenceStarted) NativeQwenBindings.nativeEndGeneration()
                    if (nativeStarted) report.put("unloadStatus", NativeQwenBindings.nativeUnload())
                } finally {
                    samples?.close()
                    report.put("overallElapsedMs", SystemClock.elapsedRealtime() - overallStart)
                        .put("thermalAfterUnload", power.currentThermalStatus).put("memoryAfterUnload", memory())
                    checkpoint("finished")
                    instrumentation.runOnMainSync { activity.finish() }
                }
            }
        }
    }
}
