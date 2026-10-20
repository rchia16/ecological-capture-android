package com.rchia.ecocapture.phase0.vlm

import org.junit.Assert.*
import org.junit.Test

class VlmGenerationConfigTest {
    @Test fun defaultAllowsFullEcologicalSections() { assertEquals(384, VlmGenerationConfig().maxOutputTokens) }
    @Test fun rejectsUnboundedOrEmptyGeneration() {
        assertThrows(IllegalArgumentException::class.java) { VlmGenerationConfig(0) }
        assertThrows(IllegalArgumentException::class.java) { VlmGenerationConfig(1025) }
    }
}
