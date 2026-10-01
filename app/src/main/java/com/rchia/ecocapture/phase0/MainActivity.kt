package com.rchia.ecocapture.phase0

import android.Manifest.permission.BLUETOOTH_CONNECT
import android.os.Bundle
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.rchia.ecocapture.phase0.ui.Phase0Screen
import com.rchia.ecocapture.phase0.ui.review.ReviewQueueScreen
import com.rchia.ecocapture.phase0.ui.review.ReviewQueueViewModel
import com.rchia.ecocapture.phase0.ui.review.ClipReviewScreen
import com.rchia.ecocapture.phase0.ui.review.ClipReviewViewModel
import com.rchia.ecocapture.phase0.domain.ClipRecord
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private var selectedClip by mutableStateOf<ClipRecord?>(null)

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
        setContent {
            val queueState by reviewQueueViewModel.uiState.collectAsStateWithLifecycle()
            BackHandler(enabled = showingReviewQueue && selectedClip == null) {
                showingReviewQueue = false
            }
            if (selectedClip != null) {
                ClipReviewScreen(
                    viewModel = clipReviewViewModel,
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
            } else if (showingReviewQueue) {
                ReviewQueueScreen(
                    viewModel = reviewQueueViewModel,
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
        if (!startupRequested) {
            startupRequested = true
            androidPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }
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
