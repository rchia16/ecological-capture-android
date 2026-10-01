package com.rchia.ecocapture.phase0

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.rchia.ecocapture.phase0.capture.FrameWriteResult
import com.rchia.ecocapture.phase0.capture.HevcMp4Recorder
import com.rchia.ecocapture.phase0.capture.RecordingResult
import com.rchia.ecocapture.phase0.data.ClipRepository
import com.rchia.ecocapture.phase0.data.addCompletedRecording
import com.rchia.ecocapture.phase0.data.LegacyClipReconciler
import com.rchia.ecocapture.phase0.data.RoomClipRepository
import com.rchia.ecocapture.phase0.data.local.EcologicalCaptureDatabase
import com.rchia.ecocapture.phase0.feedback.FeedbackController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "EcologicalCapturePhase0"
private const val TARGET_FPS = 7
private const val MAX_RECORD_SECONDS = 60L


data class Phase0UiState(
    val sdkInitialized: Boolean = false,
    val registrationState: RegistrationState = RegistrationState.UNAVAILABLE,
    val hasActiveDevice: Boolean = false,
    val sessionState: DeviceSessionState? = null,
    val streamState: StreamState? = null,
    val hasReceivedFirstFrame: Boolean = false,
    val streamFps: Float = 0f,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val isRecordingRequested: Boolean = false,
    val isRecordingConfirmed: Boolean = false,
    val isFinalizingRecording: Boolean = false,
    val recordingElapsedSeconds: Long = 0L,
    val status: String = "Starting Phase 0 app...",
    val error: String? = null,
    val lastSavedFile: String? = null,
    val lastSavedBytes: Long = 0L,
) {
    val isRegistered: Boolean
        get() = registrationState == RegistrationState.REGISTERED

    val isCameraReady: Boolean
        get() = streamState == StreamState.STREAMING && hasReceivedFirstFrame

    val canRecord: Boolean
        get() = isCameraReady && !isRecordingRequested && !isFinalizingRecording
}

class Phase0ViewModel(application: Application) : AndroidViewModel(application) {
    private val feedback = FeedbackController(application)
    private val recorder = HevcMp4Recorder(application, frameRate = TARGET_FPS)
    private val clipRepository: ClipRepository = RoomClipRepository(
        EcologicalCaptureDatabase.getInstance(application).clipDao(),
        BuildConfig.VERSION_NAME,
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            LegacyClipReconciler.runSafely(
                LegacyClipReconciler(
                    repository = clipRepository,
                    recordingsDirectory = File(application.filesDir, "recordings"),
                )
            )
        }
    }

    private val _uiState = MutableStateFlow(Phase0UiState())
    val uiState: StateFlow<Phase0UiState> = _uiState.asStateFlow()

    private val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }
    private var monitoringStarted = false

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var stream: Stream? = null
    private var cameraAttachInProgress = false

    private var registrationJob: Job? = null
    private var activeDeviceJob: Job? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var streamErrorJob: Job? = null
    private var frameJob: Job? = null
    private var recordingTimerJob: Job? = null

    private val frameDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var fpsWindowStartMs = 0L
    private var fpsFrames = 0

    fun onWearablesInitialized() {
        if (monitoringStarted) return
        monitoringStarted = true
        _uiState.update {
            it.copy(
                sdkInitialized = true,
                status = "Meta Wearables DAT initialized.",
                error = null,
            )
        }

        registrationJob = viewModelScope.launch {
            Wearables.registrationState.collect { state ->
                _uiState.update {
                    it.copy(
                        registrationState = state,
                        status = when (state) {
                            RegistrationState.REGISTERED -> "Registered with Meta Wearables."
                            RegistrationState.REGISTERING -> "Registering with Meta Wearables..."
                            RegistrationState.UNREGISTERING -> "Unregistering..."
                            RegistrationState.AVAILABLE -> "Ready to register with Meta Wearables."
                            RegistrationState.UNAVAILABLE -> "Meta Wearables registration unavailable."
                        },
                    )
                }
            }
        }

        activeDeviceJob = viewModelScope.launch {
            deviceSelector.activeDeviceFlow().collect { device ->
                _uiState.update {
                    it.copy(
                        hasActiveDevice = device != null,
                        status = when {
                            device != null -> "Glasses detected. Start the glasses camera."
                            it.isRegistered -> "Registered. Waiting for supported glasses..."
                            else -> it.status
                        },
                    )
                }
            }
        }
    }

    fun onFatalError(message: String) {
        Log.e(TAG, message)
        _uiState.update { it.copy(error = message, status = "Needs attention") }
        feedback.attention(message)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onClipDeferred() = feedback.clipDeferred()
    fun onClipApproved() = feedback.clipApproved()
    fun onClipDeleted() = feedback.clipDeleted()
    fun onPlaybackChanged(playing: Boolean) = feedback.playbackChanged(playing)

    fun onReviewError(message: String) = feedback.attention(message)

    fun startRegistration(activity: Activity) {
        if (!_uiState.value.sdkInitialized) {
            onFatalError("Meta Wearables DAT is not initialized yet.")
            return
        }
        Wearables.startRegistration(activity)
    }

    fun startCamera(requestPermission: suspend (Permission) -> PermissionStatus) {
        if (!_uiState.value.sdkInitialized) {
            onFatalError("Meta Wearables DAT is not initialized yet.")
            return
        }
        if (!_uiState.value.isRegistered) {
            onFatalError("Register the app with Meta Wearables first.")
            return
        }
        if (!_uiState.value.hasActiveDevice) {
            onFatalError("No active glasses are available.")
            return
        }
        if (stream != null) return

        _uiState.update { it.copy(status = "Checking glasses camera permission...", error = null) }
        viewModelScope.launch {
            Wearables.checkPermissionStatus(Permission.CAMERA)
                .onSuccess { status ->
                    val finalStatus = if (status == PermissionStatus.Granted) {
                        status
                    } else {
                        requestPermission(Permission.CAMERA)
                    }
                    if (finalStatus == PermissionStatus.Granted) {
                        ensureSession()
                    } else {
                        onFatalError("Glasses camera permission was denied.")
                    }
                }
                .onFailure { error, _ ->
                    onFatalError("Could not check glasses camera permission: ${error.description}")
                }
        }
    }

    private fun ensureSession() {
        val existing = session
        if (existing != null) {
            if (_uiState.value.sessionState == DeviceSessionState.STARTED) {
                attachCamera()
            }
            return
        }

        _uiState.update { it.copy(status = "Connecting to glasses session...") }
        Wearables.createSession(deviceSelector)
            .onSuccess { created ->
                session = created
                observeSession(created)
                _uiState.update { it.copy(sessionState = DeviceSessionState.STARTING) }
                created.start()
            }
            .onFailure { error, _ ->
                onFatalError("Could not create glasses session: ${error.description}")
            }
    }

    private fun observeSession(created: DeviceSession) {
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()

        sessionStateJob = viewModelScope.launch {
            created.state.collect { state ->
                _uiState.update { it.copy(sessionState = state) }
                when (state) {
                    DeviceSessionState.STARTED -> {
                        _uiState.update { it.copy(status = "Glasses session connected.") }
                        attachCamera()
                    }
                    DeviceSessionState.STOPPED -> {
                        if (_uiState.value.isRecordingRequested) finishRecording("Session stopped")
                        cleanupCameraReferences()
                        session = null
                        _uiState.update {
                            it.copy(
                                status = "Glasses session stopped.",
                                hasReceivedFirstFrame = false,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        sessionErrorJob = viewModelScope.launch {
            created.errors.collect { error ->
                onFatalError("Glasses session error: ${error.description}")
            }
        }
    }

    private fun attachCamera() {
        val currentSession = session ?: return
        if (camera != null || stream != null || cameraAttachInProgress) return
        cameraAttachInProgress = true
        recorder.resetStreamCodecState()
        _uiState.update {
            it.copy(
                status = "Starting glasses camera stream...",
                streamState = StreamState.STARTING,
                hasReceivedFirstFrame = false,
                streamFps = 0f,
            )
        }

        currentSession
            .addCamera(
                StreamConfiguration(
                    videoQuality = VideoQuality.HIGH,
                    frameRate = TARGET_FPS,
                    compressVideo = true,
                )
            )
            .onSuccess { addedCamera ->
                cameraAttachInProgress = false
                camera = addedCamera
                val addedStream = addedCamera.stream
                stream = addedStream
                observeStream(addedStream)
                addedStream.start()
                    .onFailure { error, _ ->
                        onFatalError("Could not start glasses stream: ${error.description}")
                        stopCamera()
                    }
            }
            .onFailure { error, _ ->
                cameraAttachInProgress = false
                onFatalError("Could not attach glasses camera: ${error.description}")
            }
    }

    private fun observeStream(activeStream: Stream) {
        streamStateJob?.cancel()
        streamErrorJob?.cancel()
        frameJob?.cancel()

        streamStateJob = viewModelScope.launch {
            // DAT replays the stream's current STOPPED state as soon as we subscribe. Do not
            // interpret that initial replay as a real termination; only converge teardown after
            // the stream has first entered an active/transitional state.
            var hasBeenActive = false
            activeStream.state.collect { state ->
                _uiState.update { it.copy(streamState = state) }
                val terminal = state == StreamState.STOPPED || state == StreamState.CLOSED
                if (!terminal) {
                    hasBeenActive = true
                } else if (hasBeenActive) {
                    hasBeenActive = false
                    if (_uiState.value.isRecordingRequested) {
                        finishRecording("Camera stream stopped")
                    }
                    cleanupCameraReferences()
                }
            }
        }

        streamErrorJob = viewModelScope.launch {
            activeStream.errorStream.collect { error ->
                onFatalError("Glasses camera stream error: ${error.description}")
            }
        }

        frameJob = viewModelScope.launch(frameDispatcher) {
            activeStream.videoStream.collect { frame ->
                if (!frame.isCompressed) {
                    onFatalError(
                        "DAT returned an uncompressed frame. Phase 0 requires compressVideo=true."
                    )
                    return@collect
                }

                val buffer = frame.buffer
                val bytes = ByteArray(buffer.remaining())
                val originalPosition = buffer.position()
                buffer.get(bytes)
                buffer.position(originalPosition)

                recorder.observeStreamFrame(bytes)
                updateFrameStats(frame.width, frame.height)

                if (!_uiState.value.hasReceivedFirstFrame && !frame.isCodecConfig) {
                    _uiState.update {
                        it.copy(
                            hasReceivedFirstFrame = true,
                            status = "Glasses camera ready. You can record a Phase 0 clip.",
                        )
                    }
                    feedback.cameraReady()
                }

                if (_uiState.value.isRecordingRequested) {
                    when (
                        val result = recorder.writeCompressedFrame(
                            data = bytes,
                            presentationTimeUs = frame.presentationTimeUs,
                            frameWidth = frame.width,
                            frameHeight = frame.height,
                        )
                    ) {
                        FrameWriteResult.RecordingBegan -> onRecordingConfirmed()
                        is FrameWriteResult.Failed -> {
                            onFatalError("Recording failed: ${result.message}")
                            finishRecording("Recording write failed")
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun updateFrameStats(width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        fpsFrames += 1
        val elapsed = now - fpsWindowStartMs
        if (elapsed >= 1000L) {
            val fps = fpsFrames * 1000f / elapsed
            fpsFrames = 0
            fpsWindowStartMs = now
            _uiState.update {
                it.copy(
                    streamFps = fps,
                    frameWidth = width,
                    frameHeight = height,
                )
            }
        } else if (_uiState.value.frameWidth == 0) {
            _uiState.update { it.copy(frameWidth = width, frameHeight = height) }
        }
    }

    fun startRecording() {
        if (!_uiState.value.canRecord) return
        recorder.arm()
            .onSuccess {
                _uiState.update {
                    it.copy(
                        isRecordingRequested = true,
                        isRecordingConfirmed = false,
                        isFinalizingRecording = false,
                        recordingElapsedSeconds = 0,
                        status = "Preparing recording. Waiting for a decodable video frame...",
                        error = null,
                    )
                }
            }
            .onFailure { error ->
                onFatalError("Could not prepare local MP4: ${error.message}")
            }
    }

    private fun onRecordingConfirmed() {
        if (_uiState.value.isRecordingConfirmed) return
        _uiState.update {
            it.copy(
                isRecordingConfirmed = true,
                status = "Recording in progress.",
                recordingElapsedSeconds = 0,
            )
        }
        feedback.recordingStarted()
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (isActive && _uiState.value.isRecordingConfirmed) {
                delay(1000L)
                val next = _uiState.value.recordingElapsedSeconds + 1
                _uiState.update { it.copy(recordingElapsedSeconds = next) }
                if (next >= MAX_RECORD_SECONDS) {
                    finishRecording("Maximum Phase 0 clip duration reached")
                    break
                }
            }
        }
    }

    fun stopRecording() {
        if (!_uiState.value.isRecordingRequested) return
        finishRecording("Participant stopped recording")
    }

    private fun finishRecording(reason: String) {
        val current = _uiState.value
        if (!current.isRecordingRequested || current.isFinalizingRecording) return

        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.update {
            it.copy(
                isRecordingRequested = false,
                isRecordingConfirmed = false,
                isFinalizingRecording = true,
                status = "Finalizing recording...",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = recorder.stop()) {
                is RecordingResult.Completed -> onRecordingSaved(result, reason)
                RecordingResult.NoVideo -> {
                    val message = "Recording stopped before a usable video frame was written."
                    _uiState.update {
                        it.copy(
                            status = "No clip saved.",
                            error = message,
                            isFinalizingRecording = false,
                            recordingElapsedSeconds = 0,
                        )
                    }
                    feedback.attention(message)
                }
                is RecordingResult.Failed -> {
                    val message = "Recording could not be finalized: ${result.message}"
                    _uiState.update {
                        it.copy(
                            status = "Recording save failed.",
                            error = message,
                            isFinalizingRecording = false,
                        )
                    }
                    feedback.attention(message)
                }
            }
        }
    }

    private suspend fun onRecordingSaved(result: RecordingResult.Completed, reason: String) {
        val file: File = result.videoFile
        Log.i(
            TAG,
            "Saved ${file.absolutePath}, ${file.length()} bytes, " +
                "${result.width}x${result.height}, ${result.samples} samples; $reason",
        )
        _uiState.update {
            it.copy(
                status = "Recording saved locally.",
                isFinalizingRecording = false,
                recordingElapsedSeconds = 0,
                lastSavedFile = file.absolutePath,
                lastSavedBytes = file.length(),
                error = null,
            )
        }
        runCatching { clipRepository.addCompletedRecording(result) }
            .onFailure {
                Log.e(TAG, "Room insert failed for ${result.clipId}; preserving MP4 for reconciliation", it)
                _uiState.update { state ->
                    state.copy(error = "Recording saved on this phone, but could not be added to the review queue. Restart the app to recover it.")
                }
            }
        feedback.recordingStopped()
    }

    fun stopCamera() {
        if (_uiState.value.isRecordingRequested) {
            finishRecording("Camera stopped")
        }
        runCatching { camera?.close() }
        runCatching { session?.stop() }
        cleanupCameraReferences()
        _uiState.update {
            it.copy(
                streamState = StreamState.STOPPED,
                sessionState = DeviceSessionState.STOPPED,
                hasReceivedFirstFrame = false,
                status = "Glasses camera stopped.",
            )
        }
    }

    private fun cleanupCameraReferences() {
        frameJob?.cancel()
        frameJob = null
        streamStateJob?.cancel()
        streamStateJob = null
        streamErrorJob?.cancel()
        streamErrorJob = null
        runCatching { camera?.close() }
        camera = null
        stream = null
        cameraAttachInProgress = false
        fpsWindowStartMs = 0L
        fpsFrames = 0
        recorder.resetStreamCodecState()
    }

    override fun onCleared() {
        recordingTimerJob?.cancel()
        if (_uiState.value.isRecordingRequested) {
            runCatching { recorder.stop() }
        } else {
            recorder.abort()
        }
        cleanupCameraReferences()
        runCatching { session?.stop() }
        session = null
        registrationJob?.cancel()
        activeDeviceJob?.cancel()
        sessionStateJob?.cancel()
        sessionErrorJob?.cancel()
        feedback.close()
        super.onCleared()
    }
}
