package com.rchia.ecocapture.phase0.vlm

/** Explicit, immutable policy. Changing this config must also change persisted sampling provenance. */
class FrameSamplingConfig(
    percentages: List<Int> = listOf(10, 30, 50, 70, 90),
    val maxLongEdge: Int = 1024,
) {
    val percentages: List<Int> = java.util.Collections.unmodifiableList(percentages.toList())
    init {
        require(this.percentages.isNotEmpty() && this.percentages.size <= 16)
        require(this.percentages.all { it in 1..99 })
        require(this.percentages.zipWithNext().all { (a, b) -> a < b })
        require(maxLongEdge in 1..2048)
    }

    fun timestampsMs(durationMs: Long): List<Long> {
        require(durationMs > 0 && durationMs <= Long.MAX_VALUE / 1000)
        // Avoid overflow and do not round a request past the playable endpoint.
        return percentages.map { percent -> durationMs / 100 * percent + durationMs % 100 * percent / 100 }
    }
}
