package com.rchia.ecocapture.phase0.transfer

enum class TransferState {
    PREPARING, SIMULATING, SIMULATED_COMPLETE, FAILED_RETRYABLE, CANCELLED;

    fun message(progress: Int): String = when (this) {
        PREPARING -> "Preparing a local snapshot"
        SIMULATING -> "Simulating send: $progress percent. Nothing leaves this phone."
        SIMULATED_COMPLETE -> "Simulation complete. Nothing was sent or archived."
        FAILED_RETRYABLE -> "Simulation could not complete. Recording is still on this phone."
        CANCELLED -> "Simulation cancelled. Recording remains on this phone."
    }
}
