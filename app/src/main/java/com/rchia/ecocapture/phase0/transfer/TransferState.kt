package com.rchia.ecocapture.phase0.transfer

enum class TransferState {
    PREPARING, SIMULATING, SIMULATED_COMPLETE, FAILED_RETRYABLE, CANCELLED,
    QUEUED, ENCRYPTING, UPLOADING, AWAITING_GB10, VERIFIED_GB10, NEEDS_ATTENTION;

    fun message(progress: Int): String = when (this) {
        PREPARING -> "Preparing a local snapshot"
        SIMULATING -> "Simulating send: $progress percent. Nothing leaves this phone."
        SIMULATED_COMPLETE -> "Simulation complete. Nothing was sent or archived."
        FAILED_RETRYABLE -> "Sending could not complete. Recording is still on this phone."
        CANCELLED -> "Sending cancelled. Recording remains on this phone. Copies already uploaded may remain."
        QUEUED -> "Waiting for Wi-Fi to send your approved recording."
        ENCRYPTING -> "Encrypting your approved recording."
        UPLOADING -> "Uploading encrypted recording: $progress percent."
        AWAITING_GB10 -> "Uploaded securely. Waiting for verification on GB10."
        VERIFIED_GB10 -> "Verified on GB10. Archival is a separate step."
        NEEDS_ATTENTION -> "Uploaded recording needs researcher attention. Your recording remains on this phone."
    }
}
