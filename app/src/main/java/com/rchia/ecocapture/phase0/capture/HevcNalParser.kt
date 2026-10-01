package com.rchia.ecocapture.phase0.capture

internal object HevcNalParser {
    private const val VPS = 32
    private const val SPS = 33
    private const val PPS = 34
    private const val IRAP_FIRST = 16
    private const val IRAP_LAST = 21

    fun isKeyFrame(data: ByteArray): Boolean {
        var found = false
        forEachNal(data) { _, type, _ ->
            if (type in IRAP_FIRST..IRAP_LAST) found = true
        }
        return found
    }

    fun extractParameterSets(data: ByteArray): Map<Int, ByteArray> {
        val result = linkedMapOf<Int, ByteArray>()
        forEachNal(data) { start, type, end ->
            if (type == VPS || type == SPS || type == PPS) {
                result[type] = data.copyOfRange(start, end)
            }
        }
        return result
    }

    fun concatenateCompleteParameterSets(sets: Map<Int, ByteArray>): ByteArray? {
        val vps = sets[VPS] ?: return null
        val sps = sets[SPS] ?: return null
        val pps = sets[PPS] ?: return null
        return vps + sps + pps
    }

    private inline fun forEachNal(
        data: ByteArray,
        action: (startOffset: Int, nalType: Int, endOffset: Int) -> Unit,
    ) {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                val startCodeLength = when {
                    data[i + 2] == 1.toByte() -> 3
                    i + 3 < data.size &&
                        data[i + 2] == 0.toByte() &&
                        data[i + 3] == 1.toByte() -> 4
                    else -> {
                        i++
                        continue
                    }
                }

                val headerOffset = i + startCodeLength
                if (headerOffset < data.size && !startsWithStartCode(data, headerOffset)) {
                    val nalType = (data[headerOffset].toInt() and 0x7E) shr 1
                    starts += i to nalType
                }
                i = headerOffset
            } else {
                i++
            }
        }

        starts.forEachIndexed { index, (start, type) ->
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            action(start, type, end)
        }
    }

    private fun startsWithStartCode(data: ByteArray, offset: Int): Boolean {
        if (offset + 2 >= data.size) return false
        if (data[offset] != 0.toByte() || data[offset + 1] != 0.toByte()) return false
        if (data[offset + 2] == 1.toByte()) return true
        return offset + 3 < data.size &&
            data[offset + 2] == 0.toByte() &&
            data[offset + 3] == 1.toByte()
    }
}

internal class HevcParameterSetCollector {
    private val lock = Any()
    private val sets = linkedMapOf<Int, ByteArray>()

    fun offer(data: ByteArray) {
        synchronized(lock) {
            HevcNalParser.extractParameterSets(data).forEach { (type, bytes) ->
                sets[type] = bytes
            }
        }
    }

    fun complete(): ByteArray? = synchronized(lock) {
        HevcNalParser.concatenateCompleteParameterSets(sets)
    }

    fun clear() = synchronized(lock) { sets.clear() }
}
