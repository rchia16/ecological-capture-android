package com.rchia.ecocapture.phase0.vlm

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Serializes complete participant engine cycles and releases native memory before recording. */
object VlmExecutionGate {
    private val mutex = Mutex()
    private val mutableCaptureBusy = MutableStateFlow(false)
    val captureState = mutableCaptureBusy.asStateFlow()
    val captureBusy get() = mutableCaptureBusy.value
    @Volatile private var active: Job? = null

    suspend fun <T> execute(wait: Boolean = true, block: suspend () -> T): T? {
        if (wait) mutex.lock() else if (!mutex.tryLock()) return null
        try {
            if (captureBusy) return null
            active = currentCoroutineContext()[Job]
            if (captureBusy) return null
            return block()
        } finally { active = null; mutex.unlock() }
    }

    suspend fun reserveForCapture() {
        mutableCaptureBusy.value = true
        active?.cancel()
        // The holder unloads in NonCancellable before releasing this mutex.
        mutex.lock()
        mutex.unlock()
    }

    fun releaseCapture() { mutableCaptureBusy.value = false }
}
