package com.rchia.ecocapture.phase0.vlm

import org.junit.Assert.*
import org.junit.Test

class QwenGenerationConfigTest {
    @Test fun defaultAllowsFullEcologicalSections() { assertEquals(384, QwenGenerationConfig().maxOutputTokens) }
    @Test fun rejectsUnboundedOrEmptyGeneration() {
        assertThrows(IllegalArgumentException::class.java) { QwenGenerationConfig(0) }
        assertThrows(IllegalArgumentException::class.java) { QwenGenerationConfig(1025) }
    }
}
