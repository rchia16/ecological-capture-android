package com.rchia.ecocapture.phase0.vlm.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.work.*
import com.rchia.ecocapture.phase0.data.AiSuggestionPreferences
import com.rchia.ecocapture.phase0.ui.review.VlmReviewPhase
import com.rchia.ecocapture.phase0.vlm.VlmExecutionGate
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BackgroundAiState(val phase: VlmReviewPhase, val automatic: Boolean, val error: String? = null)
internal class AiPreparationPausedException : CancellationException("Waiting for the app to reopen")

/** Durable requests contain clip IDs and preferences, never participant text or profile information. */
class BackgroundAiPreparation private constructor(context: Context) {
    private val context = context.applicationContext
    private val work = WorkManager.getInstance(this.context)
    private val charging = MutableStateFlow(isCharging())
    private val executions = ConcurrentHashMap<String, Job>()
    private val automaticExecutions = ConcurrentHashMap.newKeySet<String>()
    private val submissions = Mutex()
    private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val executionVersion = MutableStateFlow(0L)
    private val mutableAppForeground = MutableStateFlow(false)
    val appForeground = mutableAppForeground.asStateFlow()

    fun setAppForeground(visible: Boolean) {
        mutableAppForeground.value = visible
        pauseAutomaticIfNeeded()
    }

    fun pauseAutomaticIfNeeded() {
        if (!appForeground.value && !AiSuggestionPreferences(context).backgroundEnabled.value)
            automaticExecutions.toList().forEach { executions[it]?.cancel(AiPreparationPausedException()) }
    }

    fun automaticQueueStatus(): Flow<String?> = work.getWorkInfosByTagFlow("automatic").map { infos ->
        val pending = infos.count { !it.state.isFinished }
        if (pending == 0) null else "AI preparation: $pending recording${if (pending == 1) "" else "s"} queued or preparing. One recording is prepared at a time."
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { charging.value = isCharging() }
    }
    init {
        this.context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun isCharging(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val state = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return state == BatteryManager.BATTERY_STATUS_CHARGING || state == BatteryManager.BATTERY_STATUS_FULL
    }

    fun observe(clipId: String): Flow<BackgroundAiState?> =
        combine(work.getWorkInfosForUniqueWorkFlow(name(clipId)), charging, VlmExecutionGate.captureState, executionVersion) { infos, plugged, recording, _ ->
            val info = infos.maxByOrNull { item -> item.tags.firstOrNull { it.startsWith("requested:") }?.substringAfter(':')?.toLongOrNull() ?: 0 }
                ?: return@combine null
            val phase = when (info.state) {
                WorkInfo.State.SUCCEEDED -> if (info.outputData.getBoolean("skipped", false)) VlmReviewPhase.IDLE
                    else if (EcologicalCaptureDatabase.getInstance(context).vlmRunDao().getRun(info.id.toString()) != null)
                    VlmReviewPhase.SUCCESS else VlmReviewPhase.ERROR
                WorkInfo.State.FAILED -> VlmReviewPhase.ERROR
                WorkInfo.State.CANCELLED -> if (executions.containsKey(clipId)) VlmReviewPhase.CANCELLING else VlmReviewPhase.CANCELLED
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    if ("automatic" in info.tags && !AiSuggestionPreferences(context).backgroundEnabled.value && !appForeground.value) VlmReviewPhase.WAITING_APP
                    else if (recording) VlmReviewPhase.WAITING_RECORDING
                    else if ("charging-only" in info.tags && !plugged) VlmReviewPhase.WAITING_CHARGE
                    else if (info.progress.getString("phase") == "WAITING_MEMORY") VlmReviewPhase.WAITING_MEMORY else VlmReviewPhase.QUEUED
                WorkInfo.State.RUNNING -> runCatching {
                    VlmReviewPhase.valueOf(info.progress.getString("phase") ?: "QUEUED")
                }.getOrDefault(VlmReviewPhase.QUEUED)
            }
            BackgroundAiState(phase, "automatic" in info.tags,
                if (phase == VlmReviewPhase.ERROR) info.outputData.getString("error") ?: "AI preparation stopped. You can try again or review without it." else null)
        }

    suspend fun enqueue(clipId: String, automatic: Boolean, chargingOnly: Boolean, onlyIfUnscheduled: Boolean = false,
        retryFinished: Boolean = false): Unit = submissions.withLock {
        val preferences = AiSuggestionPreferences(context)
        check(automatic || preferences.backgroundEnabled.value) { "Background preparation is off." }
        if (automatic) check(preferences.enabled.value) { "Automatic preparation is off." }
        if (preferences.backgroundEnabled.value)
            check(AiPreparationNotifications.allowed(context)) { "Allow AI preparation notifications before preparing in the background." }
        if (onlyIfUnscheduled) {
            val existing = withContext(Dispatchers.IO) { work.getWorkInfosForUniqueWork(name(clipId)).get() }
            if (existing.isNotEmpty() && (!retryFinished || existing.any { !it.state.isFinished })) return@withLock
        }
        val requiresCharge = chargingOnly && preferences.backgroundEnabled.value
        val request = OneTimeWorkRequestBuilder<AiPreparationWorker>()
            .setInputData(workDataOf("clipId" to clipId))
            .setConstraints(Constraints.Builder().setRequiresCharging(requiresCharge).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag("requested:${System.currentTimeMillis()}")
            .apply { if (automatic) addTag("automatic"); if (requiresCharge) addTag("charging-only") }
            .build()
        withContext(NonCancellable + Dispatchers.IO) { work.enqueueUniqueWork(name(clipId), ExistingWorkPolicy.KEEP, request).result.get() }
        Unit
    }

    fun cancel(clipId: String) {
        executions[clipId]?.cancel()
        cancellationScope.launch { cancelAndJoin(clipId) }
    }
    suspend fun cancelAndJoin(clipId: String) {
        submissions.withLock { withContext(NonCancellable + Dispatchers.IO) { work.cancelUniqueWork(name(clipId)).result.get() } }
        executions[clipId]?.cancelAndJoin()
    }
    suspend fun cancelAllAndJoin() {
        submissions.withLock { withContext(NonCancellable + Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() } }
        executions.values.toList().forEach { it.cancelAndJoin() }
    }

    suspend fun cancelManualAndJoin() {
        submissions.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                work.getWorkInfosByTag(TAG).get().filter { "automatic" !in it.tags && !it.state.isFinished }
                    .forEach { work.cancelWorkById(it.id).result.get() }
            }
        }
        executions.keys.toList().filter { it !in automaticExecutions }.forEach { executions[it]?.cancelAndJoin() }
        pauseAutomaticIfNeeded()
    }

    suspend fun cancelAutomaticAndJoin() {
        submissions.withLock { withContext(NonCancellable + Dispatchers.IO) { work.cancelAllWorkByTag("automatic").result.get() } }
        automaticExecutions.toList().forEach { executions[it]?.cancelAndJoin() }
    }

    internal fun started(clipId: String, job: Job, automatic: Boolean) {
        if (automatic) automaticExecutions.add(clipId)
        executions[clipId] = job
        executionVersion.update { it + 1 }
    }
    internal fun finished(clipId: String, job: Job) {
        if (executions.remove(clipId, job)) automaticExecutions.remove(clipId)
        executionVersion.update { it + 1 }
    }

    companion object {
        const val TAG = "background-ai-preparation"
        private fun name(clipId: String) = "ai-preparation-$clipId"
        @Volatile private var instance: BackgroundAiPreparation? = null
        fun get(context: Context): BackgroundAiPreparation = instance ?: synchronized(this) {
            instance ?: BackgroundAiPreparation(context).also { instance = it }
        }
    }
}
