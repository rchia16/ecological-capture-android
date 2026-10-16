package com.rchia.ecocapture.phase0.ui.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rchia.ecocapture.phase0.data.local.*
import com.rchia.ecocapture.phase0.data.local.toDomain
import com.rchia.ecocapture.phase0.domain.ClipRecord
import com.rchia.ecocapture.phase0.export.EventSnapshotSource
import com.rchia.ecocapture.phase0.export.ExportSnapshotRepository
import com.rchia.ecocapture.phase0.transfer.TransferRuntime
import kotlinx.coroutines.flow.*

data class ApprovedTransferRow(val clip: ClipRecord, val job: TransferJobEntity?, val changed: Boolean,
    val enabled: Boolean, val available: Boolean)
data class TransferUiState(val rows: List<ApprovedTransferRow> = emptyList(), val loading: Boolean = true,
    val error: String? = null)

class TransferViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = TransferRuntime.get(application)
    private val errors = MutableStateFlow<String?>(null)
    val uiState = combine(runtime.database.clipDao().observeApprovedSources(),
        runtime.database.transferJobDao().observeAll(), runtime.provisioning.allowed, errors) { sources, jobs, allowed, error ->
        TransferUiState(sources.map { source ->
            val latest = jobs.lastOrNull { it.clipId == source.clip.clipId }
            val version = runCatching { ExportSnapshotRepository.version(EventSnapshotSource(source.clip, source.annotations, source.vlmRuns)) }.getOrNull()
            ApprovedTransferRow(source.clip.toDomain(), latest,
                latest != null && version != latest.sourceVersionSha256,
                source.clip.clipId in allowed && version != null,
                java.io.File(source.clip.videoPath).isFile)
        }, false, error)
    }.catch { emit(TransferUiState(loading = false, error = "Approved recordings could not be loaded. Restart the app to retry.")) }
        .flowOn(kotlinx.coroutines.Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransferUiState())

    fun send(id: String) { errors.value = null; runtime.send(id) { errors.value = it } }
    fun retry(id: String) { errors.value = null; runtime.retry(id) { errors.value = it } }
    fun cancel(id: String) { errors.value = null; runtime.cancel(id) { errors.value = it } }
}
