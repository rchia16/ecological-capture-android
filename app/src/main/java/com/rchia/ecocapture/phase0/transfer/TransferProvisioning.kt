package com.rchia.ecocapture.phase0.transfer

import android.content.Context
import com.rchia.ecocapture.phase0.BuildConfig
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface TransferEligibility {
    fun permitsSimulation(clipId: String): Boolean
}

/** Debug-only disposable-clip allowlist. This is NOT consent or a production gateway credential. */
class TransferProvisioning(context: Context) : TransferEligibility {
    private val preferences = context.getSharedPreferences("transfer_simulation", Context.MODE_PRIVATE)
    private val mutableAllowed = MutableStateFlow(readAllowed())
    val allowed: StateFlow<Set<String>> = mutableAllowed
    private fun readAllowed() = if (BuildConfig.DEBUG && preferences.getString("policy", null) == "disposable-simulation-v1" &&
        preferences.getString("installation_id", null)?.let { runCatching { UUID.fromString(it) }.isSuccess } == true)
        preferences.getStringSet("allowed_clips", emptySet())!!.toSet() else emptySet()

    override fun permitsSimulation(clipId: String) = clipId in readAllowed()
    fun allowDisposableClip(clipId: String) {
        check(BuildConfig.DEBUG)
        require(UUID.fromString(clipId).toString() == clipId)
        val allowed = readAllowed() + clipId
        check(preferences.edit().putString("installation_id", preferences.getString("installation_id", null) ?: UUID.randomUUID().toString())
            .putString("policy", "disposable-simulation-v1").putStringSet("allowed_clips", allowed).commit())
        mutableAllowed.value = allowed
    }
    fun consumeFailure(): Boolean {
        val fail = BuildConfig.DEBUG && preferences.getBoolean("fail_next", false)
        if (fail) check(preferences.edit().putBoolean("fail_next", false).commit())
        return fail
    }
    fun failNextSimulation() { check(BuildConfig.DEBUG); check(preferences.edit().putBoolean("fail_next", true).commit()) }
}
