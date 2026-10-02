package com.rchia.ecocapture.phase0.vlm

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.vlm.background.BackgroundAiPreparation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in multi-process protocol. Host orchestrates kill/force-stop/relaunch between invocations. */
@RunWith(AndroidJUnit4::class)
class Checkpoint8SystemRecoveryTest {
    @Test fun stage(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("checkpoint8Action") != null)
        val action = requireNotNull(args.getString("checkpoint8Action"))
        val token = UUID.fromString(requireNotNull(args.getString("testToken"))).toString()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val clipId = "checkpoint8-$token"
        val databaseName = "checkpoint8-system-$token.db"
        val database = Room.databaseBuilder(context, EcologicalCaptureDatabase::class.java, databaseName).build()
        val video = File(context.cacheDir, "checkpoint8-$token.mp4")
        val work = WorkManager.getInstance(context)
        val requestFile = File(context.filesDir, "checkpoint8-$token.request")
        try {
            when (action) {
                "seed" -> {
                    val activity = instrumentation.startActivitySync(android.content.Intent(context, VlmEngineeringActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    video.writeBytes(byteArrayOf(1, 2, 3)) // Explicitly invalid frame fixture; Fake engine does not decode.
                    database.clipDao().insert(ClipEntity(clipId, video.path, null, 1000, 2000, 640, 480, 14,
                        "DEFERRED", "UNDECIDED", "checkpoint8-system-test", 1000))
                    val request = OneTimeWorkRequestBuilder<Checkpoint8RecoveryWorker>()
                        .setInputData(workDataOf("clipId" to clipId, "testToken" to token,
                            "scenario" to (args.getString("scenario") ?: "success"),
                            "delayMs" to (args.getString("delayMs")?.toLong() ?: 1000)))
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS)
                        .addTag("checkpoint8-system-test").build()
                    requestFile.writeText(request.id.toString())
                    withContext(Dispatchers.IO) { work.enqueueUniqueWork("ai-preparation-$clipId", ExistingWorkPolicy.KEEP, request).result.get() }
                    if (args.getString("waitRunning") == "true") {
                        withTimeout(30000) { while (!File(context.filesDir, "checkpoint8-$token.running").exists()) delay(100) }
                        if (args.getString("holdSeed") == "true") {
                            // Instrumentation completion kills its process. Keep it alive while the host
                            // backgrounds or interrupts the worker so the requested event is genuine.
                            val cancelMarker = File(context.filesDir, "checkpoint8-$token.request-cancel")
                            var cancelSent = false
                            withTimeout(180000) {
                                while (work.getWorkInfoById(request.id).get()?.state?.isFinished != true) {
                                    if (!cancelSent && cancelMarker.exists()) {
                                        work.createCancelPendingIntent(request.id).send()
                                        cancelSent = true
                                    }
                                    delay(100)
                                }
                            }
                            if (cancelSent) BackgroundAiPreparation.get(context).cancelAndJoin(clipId)
                        }
                    } else {
                        withTimeout(30000) { while (work.getWorkInfoById(request.id).get()?.state?.isFinished != true) delay(100) }
                    }
                    // Keep the service's independent execution alive; host may background or kill the process next.
                    instrumentation.runOnMainSync { activity.finish() }
                }
                "verify" -> {
                    val info = requireNotNull(withContext(Dispatchers.IO) { work.getWorkInfoById(UUID.fromString(requestFile.readText())).get() })
                    val successful = args.getString("expectSuccess") == "true"
                    val runs = database.vlmRunDao().history(clipId)
                    if (successful) {
                        assertEquals(WorkInfo.State.SUCCEEDED, info.state)
                        assertEquals(1, runs.size)
                        assertEquals(FakeVlmEngine.DEFAULT_OUTPUT, runs.single().rawOutput)
                        assertNull(runs.single().firstPresentedAtEpochMs)
                    } else {
                        assertTrue(info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED)
                        assertTrue(runs.isEmpty())
                    }
                    assertTrue(database.annotationDao().history(clipId).isEmpty())
                    assertEquals("DEFERRED", database.clipDao().getClip(clipId)?.reviewState)
                    assertEquals("UNDECIDED", database.clipDao().getClip(clipId)?.approvalState)
                    assertArrayEquals(byteArrayOf(1, 2, 3), video.readBytes())
                }
                "cancel" -> BackgroundAiPreparation.get(context).cancelAndJoin(clipId)
                "cancel_notification" -> {
                    val id = UUID.fromString(requestFile.readText())
                    work.createCancelPendingIntent(id).send()
                    withTimeout(10000) { while (work.getWorkInfoById(id).get()?.state != WorkInfo.State.CANCELLED) delay(100) }
                    BackgroundAiPreparation.get(context).cancelAndJoin(clipId)
                }
                "cleanup" -> {
                    BackgroundAiPreparation.get(context).cancelAndJoin(clipId)
                    database.close(); context.deleteDatabase(databaseName)
                    video.delete()
                    context.filesDir.listFiles()?.filter { it.name.startsWith("checkpoint8-$token.") }?.forEach { it.delete() }
                    val models = File(context.cacheDir, "checkpoint8-model-$token")
                    models.listFiles()?.forEach { it.delete() }; models.delete()
                }
                else -> error("Unknown recovery stage")
            }
        } finally { database.close() }
    }
}
