package com.rchia.ecocapture.phase0.vlm

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class VlmModelStatus { NOT_INSTALLED, VERIFYING, READY, HASH_MISMATCH, INCOMPLETE, ERROR }
data class VlmModelFileSpec(val filename: String, val bytes: Long, val sha256: String)
data class VerifiedModelFile(val file: File, val bytes: Long, val modifiedAt: Long, val sha256: String) {
    fun metadataUnchanged() = file.isFile && file.canRead() && file.length() == bytes && file.lastModified() == modifiedAt
}
data class VerifiedModelBundle(val language: VerifiedModelFile, val projector: VerifiedModelFile)
data class VlmModelInspection(val status: VlmModelStatus, val bundle: VerifiedModelBundle? = null,
    val failure: VlmFailure? = null)

/** Explicit inspection only. No startup hashing, downloads, model loads or participant inputs. */
class VlmModelManager(
    val directory: File,
    private val specs: List<VlmModelFileSpec> = OFFICIAL_FILES,
) {
    constructor(context: Context) : this(File(context.filesDir, "models/$MODEL_DIRECTORY"))
    init {
        require(specs.size == 2 && specs.map { it.filename }.distinct().size == 2)
        require(specs.all { it.filename == File(it.filename).name && it.bytes > 0 &&
            it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }
    private val inspectionLock = Mutex()
    private val mutableStatus = MutableStateFlow(VlmModelStatus.NOT_INSTALLED)
    val status = mutableStatus.asStateFlow()

    suspend fun inspect(): VlmModelInspection = inspectionLock.withLock {
        mutableStatus.value = VlmModelStatus.VERIFYING
        try {
            val result = withContext(Dispatchers.IO) { verify() }
            mutableStatus.value = result.status
            result
        } catch (cancelled: CancellationException) {
            mutableStatus.value = VlmModelStatus.ERROR
            throw cancelled
        } catch (_: Exception) {
            mutableStatus.value = VlmModelStatus.ERROR
            VlmModelInspection(VlmModelStatus.ERROR, failure = VlmFailure.ModelVerificationFailed)
        }
    }

    private suspend fun verify(): VlmModelInspection {
        val root = directory.canonicalFile
        if (root.exists() && !root.isDirectory) return VlmModelInspection(VlmModelStatus.ERROR, failure = VlmFailure.ModelVerificationFailed)
        val files = specs.map { File(root, it.filename) }
        if (files.none { it.exists() }) return VlmModelInspection(VlmModelStatus.NOT_INSTALLED, failure = VlmFailure.ModelMissing)
        if (files.any { !it.isFile || it.length() == 0L }) return VlmModelInspection(VlmModelStatus.INCOMPLETE, failure = VlmFailure.ModelMissing)
        if (files.any { !it.canRead() || it.canonicalFile.parentFile != root }) {
            return VlmModelInspection(VlmModelStatus.ERROR, failure = VlmFailure.ModelVerificationFailed)
        }
        val verified = mutableListOf<VerifiedModelFile>()
        for ((spec, file) in specs.zip(files)) {
            currentCoroutineContext().ensureActive()
            if (file.length() != spec.bytes) return VlmModelInspection(VlmModelStatus.INCOMPLETE, failure = VlmFailure.ModelMissing)
            val modified = file.lastModified()
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
            if (file.length() != spec.bytes || file.lastModified() != modified) {
                return VlmModelInspection(VlmModelStatus.ERROR, failure = VlmFailure.ModelVerificationFailed)
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (hash != spec.sha256) return VlmModelInspection(VlmModelStatus.HASH_MISMATCH, failure = VlmFailure.HashMismatch)
            verified += VerifiedModelFile(file, spec.bytes, modified, hash)
        }
        return VlmModelInspection(VlmModelStatus.READY, VerifiedModelBundle(verified[0], verified[1]))
    }

    companion object {
        const val MODEL_DIRECTORY = "smolvlm2"
        const val MODEL_ID = "HuggingFaceTB/SmolVLM2-500M-Video-Instruct"
        const val GGUF_REPOSITORY = "ggml-org/SmolVLM2-500M-Video-Instruct-GGUF"
        const val MODEL_REVISION = "ccd7aae53bcb1997355c2f094959e72b3642ce17"
        const val QUANTIZATION = "Q8_0"
        val OFFICIAL_FILES = listOf(
            VlmModelFileSpec("SmolVLM2-500M-Video-Instruct-Q8_0.gguf", 436808704L,
                "6f67b8036b2469fcd71728702720c6b51aebd759b78137a8120733b4d66438bc"),
            VlmModelFileSpec("mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf", 108785184L,
                "921dc7e259f308e5b027111fa185efcbf33db13f6e35749ddf7f5cdb60ef520b"),
        )
    }
}
