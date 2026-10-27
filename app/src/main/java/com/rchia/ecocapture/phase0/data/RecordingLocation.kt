package com.rchia.ecocapture.phase0.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object RecordingLocation {
    private const val PREFS = "recording_context"
    private const val ENABLED = "save_approximate_suburb"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
    }

    /** One snapshot at capture time. Coordinates are never returned or stored. */
    suspend fun suburbAtRecording(context: Context): String? {
        if (!enabled(context) || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val location = withTimeoutOrNull(10_000) {
            if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return@withTimeoutOrNull null
            suspendCancellableCoroutine<Location?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, signal,
                        context.mainExecutor) { fix -> if (continuation.isActive) continuation.resume(fix) }
                } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
            }
        } ?: return null
        if (location.time <= 0 || kotlin.math.abs(System.currentTimeMillis() - location.time) > 120_000) return null
        if (!Geocoder.isPresent()) return null
        return withTimeoutOrNull(5_000) {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                if (continuation.isActive) continuation.resume(addresses)
                            }
                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(emptyList())
                            }
                        })
                }
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1).orEmpty()
            }
            addresses.firstOrNull()?.let { it.subLocality ?: it.locality ?: it.subAdminArea }
                ?.trim()?.take(100)?.takeIf { it.isNotBlank() }
        }
    }
}
