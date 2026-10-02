package com.rchia.ecocapture.phase0.vlm.background

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration

/** Only critical pressure stops work; UI_HIDDEN must not stop optional background preparation. */
class VlmMemoryPressure(private val context: Context, private val onCritical: () -> Unit) : ComponentCallbacks2, AutoCloseable {
    init { context.registerComponentCallbacks(this) }
    override fun onConfigurationChanged(configuration: Configuration) = Unit
    override fun onLowMemory() = onCritical()
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) onCritical()
    }
    override fun close() { context.unregisterComponentCallbacks(this) }

    companion object {
        fun available(context: Context): Boolean {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            return !info.lowMemory
        }
    }
}
