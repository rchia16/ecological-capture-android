package com.rchia.ecocapture.phase0.vlm

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VlmModelManagerTest {
    private val directory = Files.createTempDirectory("vlm-model-policy-").toFile()
    private val language = byteArrayOf(1, 2, 3, 4)
    private val projector = byteArrayOf(5, 6, 7)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val specs = listOf(VlmModelFileSpec("language.gguf", language.size.toLong(), hash(language)),
        VlmModelFileSpec("projector.gguf", projector.size.toLong(), hash(projector)))
    private fun manager() = VlmModelManager(directory, specs)
    private fun provision() { File(directory, specs[0].filename).writeBytes(language); File(directory, specs[1].filename).writeBytes(projector) }
    @After fun cleanup() { directory.listFiles()?.forEach { it.delete() }; directory.delete() }

    @Test fun missingBundleIsNotInstalled() = runBlocking {
        assertEquals(VlmModelStatus.NOT_INSTALLED, manager().inspect().status)
    }
    @Test fun missingAndZeroByteProjectorsAreIncomplete() = runBlocking {
        File(directory, specs[0].filename).writeBytes(language)
        assertEquals(VlmModelStatus.INCOMPLETE, manager().inspect().status)
        File(directory, specs[1].filename).writeBytes(byteArrayOf())
        assertEquals(VlmModelStatus.INCOMPLETE, manager().inspect().status)
    }
    @Test fun truncatedFileIsDetectedBeforeLoad() = runBlocking {
        provision(); File(directory, specs[0].filename).writeBytes(byteArrayOf(1))
        assertEquals(VlmModelStatus.INCOMPLETE, manager().inspect().status)
    }
    @Test fun sameSizeCorruptionIsHashMismatch() = runBlocking {
        provision(); File(directory, specs[1].filename).writeBytes(byteArrayOf(7, 6, 5))
        val result = manager().inspect()
        assertEquals(VlmModelStatus.HASH_MISMATCH, result.status)
        assertEquals(VlmFailure.HashMismatch, result.failure)
        assertNull(result.bundle)
    }
    @Test fun verifiedFilesAreReadyAndUnchanged() = runBlocking {
        provision()
        val manager = manager()
        assertEquals(VlmModelStatus.NOT_INSTALLED, manager.status.value)
        val result = manager.inspect()
        assertEquals(VlmModelStatus.READY, manager.status.value)
        val bundle = requireNotNull(result.bundle)
        assertEquals(specs[0].sha256, bundle.language.sha256)
        assertTrue(bundle.language.metadataUnchanged())
        assertArrayEquals(language, bundle.language.file.readBytes())
        bundle.language.file.writeBytes(byteArrayOf(1))
        assertFalse(bundle.language.metadataUnchanged())
    }
    @Test fun wrongFilenamesDoNotSubstituteAnotherBundle() = runBlocking {
        File(directory, "alternative.gguf").writeBytes(language)
        assertEquals(VlmModelStatus.NOT_INSTALLED, manager().inspect().status)
    }
    @Test fun invalidDirectoryIsTypedError() = runBlocking {
        val path = File(directory, "not-a-directory").apply { writeText("fixture") }
        val result = VlmModelManager(path, specs).inspect()
        assertEquals(VlmModelStatus.ERROR, result.status)
        assertEquals(VlmFailure.ModelVerificationFailed, result.failure)
    }
    @Test fun officialBundleContractCannotDrift() {
        assertEquals(545593888L, VlmModelManager.OFFICIAL_FILES.sumOf { it.bytes })
        assertEquals("SmolVLM2-500M-Video-Instruct-Q8_0.gguf", VlmModelManager.OFFICIAL_FILES[0].filename)
        assertEquals("6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc", VlmModelManager.OFFICIAL_FILES[0].sha256)
        assertEquals("921dc7e259f308e5b027111fa185efcbf33db13f6e35749ddf7f5cdb60ef520b", VlmModelManager.OFFICIAL_FILES[1].sha256)
    }
}
