package com.rchia.ecocapture.phase0.transfer

import kotlinx.coroutines.delay

/** Has no networking dependency and cannot produce a remote-receipt state. */
class FakeTransferTransport(private val delayMs: Long = 600, private val fail: () -> Boolean = { false }) {
    suspend fun simulate(progress: suspend (Int) -> Unit) {
        val shouldFail = fail()
        for (percent in listOf(0, 25, 50, 75, 100)) {
            delay(delayMs)
            if (shouldFail && percent == 50) error("SIMULATION_FAILED")
            progress(percent)
        }
    }
}
