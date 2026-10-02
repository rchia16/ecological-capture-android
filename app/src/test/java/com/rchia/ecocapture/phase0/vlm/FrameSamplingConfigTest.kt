package com.rchia.ecocapture.phase0.vlm

import org.junit.Assert.*
import org.junit.Test

class FrameSamplingConfigTest {
    @Test fun defaultsCoverThirtyAndSixtySecondTimelines() {
        val config = FrameSamplingConfig()
        assertEquals(1024, config.maxLongEdge)
        assertEquals(listOf(3000L, 9000L, 15000L, 21000L, 27000L), config.timestampsMs(30000))
        assertEquals(listOf(6000L, 18000L, 30000L, 42000L, 54000L), config.timestampsMs(60000))
    }
    @Test fun shortClipHasFiveDeterministicNondecreasingRequests() {
        val config = FrameSamplingConfig()
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), config.timestampsMs(1))
        val times = config.timestampsMs(73)
        assertEquals(times, config.timestampsMs(73))
        assertTrue(times.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(times.all { it in 0 until 73 })
    }
    @Test fun customPolicyIsExplicitAndInputListIsCopied() {
        val source = mutableListOf(15, 50, 85)
        val config = FrameSamplingConfig(source, 768)
        source[0] = 20
        assertEquals(listOf(1500L, 5000L, 8500L), config.timestampsMs(10000))
        assertEquals(768, config.maxLongEdge)
    }
    @Test fun rejectsInvalidPoliciesAndDurations() {
        listOf(emptyList(), listOf(0), listOf(100), listOf(50, 10), listOf(10, 10)).forEach {
            assertThrows(IllegalArgumentException::class.java) { FrameSamplingConfig(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { FrameSamplingConfig(maxLongEdge = 0) }
        assertThrows(IllegalArgumentException::class.java) { FrameSamplingConfig().timestampsMs(0) }
        assertThrows(IllegalArgumentException::class.java) { FrameSamplingConfig().timestampsMs(-1) }
        assertThrows(IllegalArgumentException::class.java) { FrameSamplingConfig().timestampsMs(Long.MAX_VALUE) }
    }
}
