package com.rchia.ecocapture.phase0.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcNalParserTest {
    private fun nal(type: Int, payload: ByteArray = byteArrayOf(0x01)): ByteArray {
        val header = ((type shl 1) and 0x7E).toByte()
        return byteArrayOf(0, 0, 0, 1, header, 0x01) + payload
    }

    @Test
    fun detectsIrapKeyframe() {
        assertTrue(HevcNalParser.isKeyFrame(nal(19)))
        assertTrue(HevcNalParser.isKeyFrame(nal(21)))
        assertFalse(HevcNalParser.isKeyFrame(nal(1)))
    }

    @Test
    fun collectsCompleteParameterSets() {
        val data = nal(32) + nal(33) + nal(34) + nal(19)
        val sets = HevcNalParser.extractParameterSets(data)
        assertNotNull(HevcNalParser.concatenateCompleteParameterSets(sets))
    }
}
