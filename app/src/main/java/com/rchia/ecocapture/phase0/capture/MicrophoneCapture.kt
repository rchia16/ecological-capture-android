package com.rchia.ecocapture.phase0.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal enum class MicrophoneSource { GLASSES, PHONE }

internal data class RecordedAudio(
    val file: File,
    val startElapsedRealtimeUs: Long,
    val source: MicrophoneSource,
)

/** Records AAC to a private temporary file, verifying the actual input device. */
internal class MicrophoneCapture(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var recorder: MediaRecorder? = null
    private var tempFile: File? = null
    private var source: MicrophoneSource? = null
    private var startedAtUs = 0L
    private var ownsGlassesRoute = false
    private var previousMode = AudioManager.MODE_NORMAL
    @Volatile private var routeLost = false
    private var routingListener: AudioRouting.OnRoutingChangedListener? = null

    /** The Bluetooth headset route must settle before a glasses camera stream begins. */
    suspend fun prepareGlassesRoute(): Boolean {
        if (ownsGlassesRoute) return true
        // Without a device identity from DAT, a second headset makes the source ambiguous.
        val devices = runCatching { audioManager.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        } }.getOrNull() ?: return false
        val device = devices.singleOrNull() ?: return false
        previousMode = audioManager.mode
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            ownsGlassesRoute = true
            if (!audioManager.setCommunicationDevice(device)) {
                releaseGlassesRoute()
                false
            } else {
                val routed = withTimeoutOrNull(3_000) {
                    while (audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                        delay(50)
                    true
                } == true
                if (!routed) releaseGlassesRoute()
                routed
            }
        } catch (_: Exception) {
            releaseGlassesRoute()
            false
        }
    }

    /** Returns false rather than silently choosing a different microphone. Call on an IO thread. */
    @Synchronized fun start(requested: MicrophoneSource): Boolean {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) return false
        if (recorder != null) return false
        val wantedType = if (requested == MicrophoneSource.GLASSES)
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BUILTIN_MIC
        if (requested == MicrophoneSource.GLASSES &&
            (!ownsGlassesRoute || audioManager.communicationDevice?.type != wantedType)) return false
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == wantedType }
        val input = inputs.singleOrNull() ?: return false
        val directory = File(appContext.cacheDir, "recording_audio").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.m4a")
        var created: MediaRecorder? = null
        routeLost = false
        return try {
            created = MediaRecorder(appContext).apply {
                setAudioSource(if (requested == MicrophoneSource.GLASSES)
                    MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                check(setPreferredDevice(input)) { "Requested microphone was not accepted" }
                prepare()
                start()
            }
            val recordStartUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            var route = created.routedDevice?.type
            var attempts = 0
            while (route == null && attempts++ < 20) {
                Thread.sleep(25)
                route = created.routedDevice?.type
            }
            if (route != wantedType) {
                runCatching { created.stop() }
                created.release()
                file.delete()
                false
            } else {
                val listener = AudioRouting.OnRoutingChangedListener { routing ->
                    val actual = routing.routedDevice?.type
                    if (actual != null && actual != wantedType) routeLost = true
                }
                created.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
                routingListener = listener
                recorder = created
                tempFile = file
                source = requested
                startedAtUs = recordStartUs
                true
            }
        } catch (_: Exception) {
            runCatching { created?.stop() }
            runCatching { created?.release() }
            file.delete()
            false
        }
    }

    @Synchronized fun stop(): RecordedAudio? {
        val active = recorder ?: return null
        val file = tempFile
        val selected = source
        val started = startedAtUs
        recorder = null
        tempFile = null
        source = null
        startedAtUs = 0
        routingListener?.let { runCatching { active.removeOnRoutingChangedListener(it) } }
        routingListener = null
        val validRoute = !routeLost && runCatching { active.routedDevice?.type ==
            (if (selected == MicrophoneSource.GLASSES) AudioDeviceInfo.TYPE_BLUETOOTH_SCO
             else AudioDeviceInfo.TYPE_BUILTIN_MIC) }.getOrDefault(false)
        val stopped = runCatching { active.stop(); true }.getOrDefault(false)
        runCatching { active.release() }
        if (!stopped || !validRoute || file == null || selected == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            return null
        }
        return RecordedAudio(file, started, selected)
    }

    @Synchronized fun cleanup() {
        val active = recorder
        recorder = null
        routingListener?.let { listener -> runCatching { active?.removeOnRoutingChangedListener(listener) } }
        routingListener = null
        runCatching { active?.stop() }
        runCatching { active?.release() }
        tempFile?.delete()
        tempFile = null
        source = null
        startedAtUs = 0
        releaseGlassesRoute()
    }

    @Synchronized fun releaseGlassesRoute() {
        if (!ownsGlassesRoute) return
        runCatching { audioManager.clearCommunicationDevice() }
        runCatching { audioManager.mode = previousMode }
        ownsGlassesRoute = false
    }
}
