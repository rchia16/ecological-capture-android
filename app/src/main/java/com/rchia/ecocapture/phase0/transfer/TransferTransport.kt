package com.rchia.ecocapture.phase0.transfer

import com.rchia.ecocapture.phase0.data.local.TransferJobEntity
import java.io.File

interface TransferTransport {
    val mode: String
    val activeState: String
    suspend fun deliver(job: TransferJobEntity, bundle: File, progress: suspend (Int) -> Unit): String
}

/** Optional source-set implementation; ordinary builds have no Firebase dependency. */
interface LiveTransferBackend : TransferEligibility {
    val transport: TransferTransport
    val enabled: kotlinx.coroutines.flow.StateFlow<Boolean>
    fun openEnrolment(context: android.content.Context)
    fun cancel(jobId: String)
    suspend fun recover()
}
