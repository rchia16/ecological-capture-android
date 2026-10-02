package com.rchia.ecocapture.phase0

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.Manifest.permission.POST_NOTIFICATIONS
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.rchia.ecocapture.phase0.ui.Phase0Screen
import com.rchia.ecocapture.phase0.ui.review.ReviewQueueScreen
import com.rchia.ecocapture.phase0.ui.review.ReviewQueueViewModel
import com.rchia.ecocapture.phase0.ui.review.ClipReviewScreen
import com.rchia.ecocapture.phase0.ui.review.ClipReviewViewModel
import com.rchia.ecocapture.phase0.ui.review.AiSettingsScreen
import com.rchia.ecocapture.phase0.vlm.background.AiPreparationNotifications
import com.rchia.ecocapture.phase0.domain.ClipRecord
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {

    companion object {
        // BLUETOOTH and INTERNET are normal permissions. Only CONNECT needs a
        // runtime prompt on the Android versions supported by this app.
        val REQUIRED_PERMISSIONS = arrayOf(BLUETOOTH_CONNECT)
    }

    private var startupRequested = false
    private var wearablesInitializationStarted = false

    private val viewModel: Phase0ViewModel by viewModels()
    private val reviewQueueViewModel: ReviewQueueViewModel by viewModels()
    private val clipReviewViewModel: ClipReviewViewModel by viewModels()
    private var showingReviewQueue by mutableStateOf(false)
    private var showingAiSettings by mutableStateOf(false)
    private var selectedClip by mutableStateOf<ClipRecord?>(null)
    private val notificationPermissionLauncher = registerForActivityResult(RequestPermission()) {
        clipReviewViewModel.setBackgroundPreparationEnabled(true)
    }

    private fun changeBackgroundPreparation(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        } else clipReviewViewModel.setBackgroundPreparationEnabled(enabled)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == AiPreparationNotifications.OPEN_REVIEW) {
            showingAiSettings = false
            selectedClip = null
            showingReviewQueue = true
        }
    }

    private val androidPermissionsLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { result ->
            val granted = result.entries.all { it.value }
            if (granted) {
                initializeWearables()
            } else {
                viewModel.onFatalError(
                    "Bluetooth permissions are required before the glasses can be used."
                )
            }
        }

    private val permissionMutex = Mutex()
    private var wearablesPermissionContinuation: CancellableContinuation<PermissionStatus>? = null

    private val wearablePermissionLauncher =
        registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
            val status = result.getOrDefault(PermissionStatus.Denied)
            wearablesPermissionContinuation?.resume(status)
            wearablesPermissionContinuation = null
        }

    private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
        permissionMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                wearablesPermissionContinuation = continuation
                continuation.invokeOnCancellation { wearablesPermissionContinuation = null }
                wearablePermissionLauncher.launch(permission)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == AiPreparationNotifications.OPEN_REVIEW) showingReviewQueue = true
        setContent {
            val queueState by reviewQueueViewModel.uiState.collectAsStateWithLifecycle()
            val automaticPreparationStatus by clipReviewViewModel.automaticQueueStatus.collectAsStateWithLifecycle()
            val captureBusy by remember(viewModel) {
                viewModel.uiState.map { it.isPreparingRecording || it.isRecordingRequested || it.isRecordingConfirmed || it.isFinalizingRecording }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = viewModel.uiState.value.let {
                it.isPreparingRecording || it.isRecordingRequested || it.isRecordingConfirmed || it.isFinalizingRecording
            })
            BackHandler(enabled = showingReviewQueue && selectedClip == null && !showingAiSettings) {
                showingReviewQueue = false
            }
            if (selectedClip != null) {
                ClipReviewScreen(
                    viewModel = clipReviewViewModel,
                    isCaptureBusy = captureBusy,
                    onBack = { selectedClip = null },
                    onDeferred = {
                        viewModel.onClipDeferred()
                        selectedClip = null
                    },
                    onApproved = {
                        viewModel.onClipApproved()
                        selectedClip = null
                    },
                    onDeleted = {
                        viewModel.onClipDeleted()
                        selectedClip = null
                    },
                    onError = viewModel::onReviewError,
                    onPlaybackStateChanged = viewModel::onPlaybackChanged,
                )
            } else if (showingAiSettings) {
                val automaticPreparation by clipReviewViewModel.automaticPreparationEnabled.collectAsStateWithLifecycle()
                val preferenceSaving by clipReviewViewModel.preferenceSaving.collectAsStateWithLifecycle()
                val preferenceError by clipReviewViewModel.preferenceError.collectAsStateWithLifecycle()
                val backgroundPreparation by clipReviewViewModel.backgroundPreparationEnabled.collectAsStateWithLifecycle()
                val chargingOnly by clipReviewViewModel.chargingOnly.collectAsStateWithLifecycle()
                AiSettingsScreen(
                    automaticPreparationStatus = automaticPreparationStatus,
                    automaticPreparationEnabled = automaticPreparation,
                    saving = preferenceSaving,
                    error = preferenceError,
                    onAutomaticPreparationChanged = clipReviewViewModel::setAutomaticPreparationEnabled,
                    backgroundPreparationEnabled = backgroundPreparation,
                    chargingOnly = chargingOnly,
                    onBackgroundPreparationChanged = ::changeBackgroundPreparation,
                    onChargingOnlyChanged = clipReviewViewModel::setChargingOnly,
                    onBack = { showingAiSettings = false },
                )
            } else if (showingReviewQueue) {
                ReviewQueueScreen(
                    automaticPreparationStatus = automaticPreparationStatus,
                    viewModel = reviewQueueViewModel,
                    onSettings = { showingAiSettings = true },
                    onBack = { showingReviewQueue = false },
                    onClipSelected = {
                        clipReviewViewModel.selectClip(it.clipId)
                        selectedClip = it
                    },
                )
            } else {
                Phase0Screen(
                    viewModel = viewModel,
                    onRegister = { viewModel.startRegistration(this) },
                    onStartCamera = { viewModel.startCamera(::requestWearablesPermission) },
                    onReviewRecordings = { showingReviewQueue = true },
                    pendingCount = queueState.clips.size,
                    reviewQueueError = queueState.error,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        clipReviewViewModel.setAppForeground(true)
        if (!startupRequested) {
            startupRequested = true
            androidPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onStop() {
        clipReviewViewModel.setAppForeground(false)
        super.onStop()
    }

    private fun initializeWearables() {
        if (wearablesInitializationStarted) return

        if (!hasWearablesConfiguration()) {
            viewModel.onFatalError(
                "Meta Wearables credentials are missing. Set mwdat_application_id and " +
                    "mwdat_client_token in local.properties, then rebuild the app."
            )
            return
        }

        wearablesInitializationStarted = true
        try {
            Wearables.initialize(this)
                .onSuccess { viewModel.onWearablesInitialized() }
                .onFailure { error, _ ->
                    viewModel.onFatalError("DAT initialization failed: ${error.description}")
                }
        } catch (error: Exception) {
            Log.e("EcologicalCapturePhase0", "DAT initialization threw", error)
            wearablesInitializationStarted = false
            viewModel.onFatalError(
                "DAT initialization failed. Check the Meta Wearables application ID " +
                    "and client token."
            )
        }
    }

    private fun hasWearablesConfiguration(): Boolean {
        val metadata = packageManager
            .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            .metaData
        val applicationId = metadata?.getString("com.meta.wearable.mwdat.APPLICATION_ID")
        val clientToken = metadata?.getString("com.meta.wearable.mwdat.CLIENT_TOKEN")

        return !applicationId.isNullOrBlank() &&
            applicationId != "0" &&
            !applicationId.contains("YOUR_", ignoreCase = true) &&
            !clientToken.isNullOrBlank() &&
            clientToken != "0" &&
            !clientToken.contains("YOUR_", ignoreCase = true)
    }
}
