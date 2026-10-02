package com.rchia.ecocapture.phase0.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local user preference, not participant-profile data. Attempt IDs never enter a VLM request. */
class AiSuggestionPreferences(context: Context, storeName: String = "ai_suggestion_preferences") {
    private val preferences = context.applicationContext.getSharedPreferences(storeName, Context.MODE_PRIVATE)
    private val writes = Mutex()
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("automatic_preparation", false))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableBackground = MutableStateFlow(preferences.getBoolean("background_preparation", false))
    val backgroundEnabled = mutableBackground.asStateFlow()
    private val mutableChargingOnly = MutableStateFlow(preferences.getBoolean("charging_only", true))
    val chargingOnly = mutableChargingOnly.asStateFlow()

    suspend fun setBackgroundEnabled(value: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("background_preparation", value).commit())
        }
        mutableBackground.value = value
    }

    suspend fun setChargingOnly(value: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("charging_only", value).commit())
        }
        mutableChargingOnly.value = value
    }

    suspend fun setEnabled(value: Boolean) = writes.withLock {
        withContext(Dispatchers.IO) {
            check(preferences.edit().putBoolean("automatic_preparation", value).commit()) { "Preference could not be saved" }
        }
        mutableEnabled.value = value
    }

    /** Persist before starting, so failures, cancellation and reopen do not trigger silent retries. */
    suspend fun claimFirstPreparation(clipId: String): Boolean = writes.withLock {
        if (!enabled.value) return@withLock false
        withContext(Dispatchers.IO) {
            val attempted = preferences.getStringSet("attempted_clips", emptySet()).orEmpty().toMutableSet()
            if (!attempted.add(clipId)) return@withContext false
            check(preferences.edit().putStringSet("attempted_clips", attempted).commit()) { "Preparation attempt could not be saved" }
            true
        }
    }
}
