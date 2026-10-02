package com.rchia.ecocapture.phase0.vlm

import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rchia.ecocapture.phase0.data.VlmRunRepository
import com.rchia.ecocapture.phase0.data.local.ClipEntity
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real inference, isolated engineering database, readonly source recordings. */
@RunWith(AndroidJUnit4::class)
class Checkpoint6InferenceTest {
    @Test fun compareAndPersistRealInference(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("checkpoint6Recording") != null)
        val name = requireNotNull(args.getString("checkpoint6Recording"))
        require(name.matches(Regex("clip_[A-Za-z0-9_]+\\.mp4")))
        val count = args.getString("frameCount")!!.toInt()
        val edge = args.getString("maxLongEdge")?.toInt() ?: 1024
        val percentages = when (count) { 1 -> listOf(50); 3 -> listOf(15, 50, 85); 5 -> listOf(10, 30, 50, 70, 90); else -> error("Unsupported count") }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(android.content.Intent(context, VlmEngineeringActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val source = File(context.filesDir, "recordings/$name")
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val bytes = ByteArray(65536)
                while (true) { val read = input.read(bytes); if (read == -1) break; digest.update(bytes, 0, read) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val sourceHash = hash(source)
        val output = File(context.cacheDir, "checkpoint6-comparison").apply { mkdirs() }
        val stem = "${name.removeSuffix(".mp4")}-${count}frames-${edge}px"
        val report = JSONObject().put("sourceFilename", name).put("sourceSha256", sourceHash)
            .put("frameCount", count).put("maxLongEdge", edge).put("success", false)
            .put("foregroundEngineeringActivity", true).put("processCgroups", File("/proc/self/cgroup").readText())
        val reportFile = File(output, "$stem.json")
        val power = context.getSystemService(PowerManager::class.java)
        fun memory() = JSONObject().apply {
            File("/proc/self/status").readLines().filter { it.startsWith("VmRSS:") || it.startsWith("VmHWM:") }.forEach {
                put(it.substringBefore(':') + "KiB", it.substringAfter(':').trim().substringBefore(' ').toLong())
            }
            put("totalPssKiB", Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss)
            put("nativeAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        }
        var database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, "checkpoint6-engineering.db").build()
        val clipId = "engineering-$name"
        val engine = VlmEngineFactory.create(context, VlmRuntimeMode.QWEN_ENGINEERING,
            FrameSamplingConfig(percentages, edge), QwenGenerationConfig())
        val start = SystemClock.elapsedRealtime()
        try {
            database.clipDao().insert(ClipEntity(clipId, source.absolutePath, null, System.currentTimeMillis(), 0,
                0, 0, 0, "UNREVIEWED", "UNDECIDED", "checkpoint6", System.currentTimeMillis()))
            report.put("memoryBefore", memory()).put("thermalBefore", power.currentThermalStatus)
            report.put("stage", "loading"); reportFile.writeText(report.toString(2))
            val loadStart = SystemClock.elapsedRealtime()
            assertEquals(VlmLoadResult.Ready, engine.load())
            report.put("loadAndVerificationMs", SystemClock.elapsedRealtime() - loadStart).put("memoryLoaded", memory())
            report.put("stage", "generating"); reportFile.writeText(report.toString(2))
            val generated = engine.generate(VlmRequest(clipId, source))
            report.put("memoryAfterGeneration", memory()).put("thermalAfterGeneration", power.currentThermalStatus)
            assertTrue("Real generation failed: $generated", generated is VlmResult.Success)
            val result = generated as VlmResult.Success
            File(output, "$stem-output.txt").writeText(result.rawOutput, Charsets.UTF_8)
            assertEquals(result.rawOutput, result.description)
            assertEquals("ecological_scene_description_v2", result.promptVersion)
            val saved = VlmRunRepository(database).save(result)
            database.close()
            database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, "checkpoint6-engineering.db").build()
            val persisted = VlmRunRepository(database).history(clipId).first { it.vlmRunId == saved.vlmRunId }
            assertEquals(result.rawOutput, persisted.rawOutput)
            assertEquals(result.frameSamplingJson, persisted.frameSamplingJson)
            assertEquals(result.generationConfigJson, persisted.generationConfigJson)
            assertNull(persisted.firstPresentedAtEpochMs)
            report.put("rawOutput", persisted.rawOutput).put("modelId", persisted.modelId)
                .put("modelQuant", persisted.modelQuant).put("languageModelSha256", persisted.languageModelSha256)
                .put("mmprojSha256", persisted.mmprojSha256).put("runtimeCommit", persisted.runtimeCommit)
                .put("promptVersion", persisted.promptVersion).put("frameSampling", JSONObject(persisted.frameSamplingJson))
                .put("generationConfig", JSONObject(persisted.generationConfigJson)).put("inferenceDurationMs", persisted.inferenceDurationMs)
                .put("engineeringRunId", persisted.vlmRunId).put("persistedAndReopened", true).put("success", true)
        } finally {
            withContext(NonCancellable) {
                try { engine.unload() } finally {
                    database.close()
                    report.put("memoryAfterUnload", memory()).put("thermalAfterUnload", power.currentThermalStatus)
                        .put("totalWallMs", SystemClock.elapsedRealtime() - start).put("stage", "finished")
                    reportFile.writeText(report.toString(2))
                    instrumentation.runOnMainSync { activity.finish() }
                    assertEquals("Recording must remain unchanged", sourceHash, hash(source))
                }
            }
        }
    }
}
