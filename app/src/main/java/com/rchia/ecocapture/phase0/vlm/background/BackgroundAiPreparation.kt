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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class BackgroundAiState(val phase: VlmReviewPhase, val automatic: Boolean, val error: String? = null)

/** Durable requests contain clip IDs and preferences, never participant text or profile information. */
class BackgroundAiPreparation private constructor(context: Context) {
    private val context = context.applicationContext
    private val work = WorkManager.getInstance(this.context)
    private val charging = MutableStateFlow(isCharging())
    private val executions = ConcurrentHashMap<String, Job>()
    private val automaticExecutions = ConcurrentHashMap.newKeySet<String>()
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
        combine(work.getWorkInfosForUniqueWorkFlow(name(clipId)), charging, VlmExecutionGate.captureState) { infos, plugged, recording ->
            val info = infos.maxByOrNull { item -> item.tags.firstOrNull { it.startsWith("requested:") }?.substringAfter(':')?.toLongOrNull() ?: 0 }
                ?: return@combine null
            val phase = when (info.state) {
                WorkInfo.State.SUCCEEDED -> VlmReviewPhase.SUCCESS
                WorkInfo.State.FAILED -> VlmReviewPhase.ERROR
                WorkInfo.State.CANCELLED -> VlmReviewPhase.CANCELLED
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    if (recording) VlmReviewPhase.WAITING_RECORDING
                    else if ("charging-only" in info.tags && !plugged) VlmReviewPhase.WAITING_CHARGE else VlmReviewPhase.QUEUED
                WorkInfo.State.RUNNING -> runCatching {
                    VlmReviewPhase.valueOf(info.progress.getString("phase") ?: "QUEUED")
                }.getOrDefault(VlmReviewPhase.QUEUED)
            }
            BackgroundAiState(phase, "automatic" in info.tags,
                if (phase == VlmReviewPhase.ERROR) info.outputData.getString("error") ?: "AI preparation stopped. You can try again or review without it." else null)
        }

    suspend fun enqueue(clipId: String, automatic: Boolean, chargingOnly: Boolean) {
        check(AiSuggestionPreferences(context).backgroundEnabled.value) { "Background preparation is off." }
        check(AiPreparationNotifications.allowed(context)) { "Allow AI preparation notifications before preparing in the background." }
        val request = OneTimeWorkRequestBuilder<AiPreparationWorker>()
            .setInputData(workDataOf("clipId" to clipId))
            .setConstraints(Constraints.Builder().setRequiresCharging(chargingOnly).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(TAG).addTag("requested:${System.currentTimeMillis()}")
            .apply { if (automatic) addTag("automatic"); if (chargingOnly) addTag("charging-only") }
            .build()
        withContext(Dispatchers.IO) { work.enqueueUniqueWork(name(clipId), ExistingWorkPolicy.KEEP, request).result.get() }
    }

    fun cancel(clipId: String) { work.cancelUniqueWork(name(clipId)); executions[clipId]?.cancel() }
    suspend fun cancelAndJoin(clipId: String) {
        withContext(Dispatchers.IO) { work.cancelUniqueWork(name(clipId)).result.get() }
        executions[clipId]?.cancelAndJoin()
    }
    suspend fun cancelAllAndJoin() {
        withContext(Dispatchers.IO) { work.cancelAllWorkByTag(TAG).result.get() }
        executions.values.toList().forEach { it.cancelAndJoin() }
    }

    suspend fun cancelAutomaticAndJoin() {
        withContext(Dispatchers.IO) { work.cancelAllWorkByTag("automatic").result.get() }
        automaticExecutions.toList().forEach { executions[it]?.cancelAndJoin() }
    }

    internal fun started(clipId: String, job: Job, automatic: Boolean) {
        if (automatic) automaticExecutions.add(clipId)
        executions[clipId] = job
    }
    internal fun finished(clipId: String, job: Job) {
        if (executions.remove(clipId, job)) automaticExecutions.remove(clipId)
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
