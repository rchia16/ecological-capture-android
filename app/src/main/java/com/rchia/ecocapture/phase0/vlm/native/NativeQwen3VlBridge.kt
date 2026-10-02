package com.rchia.ecocapture.phase0.vlm.native

import com.rchia.ecocapture.phase0.vlm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NativeRuntimeInfo(val name: String, val commit: String, val abi: String,
    val backend: String, val contextSize: Int, val threads: Int)

/** Lazy JNI entry points; no native pointers leave this component. */
internal object NativeQwenBindings {
    init { System.loadLibrary("ecocapture_qwen3vl") }
    external fun nativeLoadModel(languagePath: String, projectorPath: String): Int
    external fun nativeUnload(): Int
    external fun nativeCancel()
    external fun nativeGetRuntimeInfo(): String
    external fun nativeBeginGeneration(): Int
    external fun nativeAddFrame(bitmap: android.graphics.Bitmap): Int
    external fun nativeGenerate(system: ByteArray, user: ByteArray, maxTokens: Int): Array<ByteArray>
    external fun nativeEndGeneration()
}

class NativeQwen3VlBridge(private val manager: VlmModelManager) : QwenRuntime {
    override suspend fun load(): VlmLoadResult = lifecycle.withLock {
        if (owner != null) return@withLock VlmLoadResult.Failure(VlmFailure.NativeFailure(7))
        val inspection = manager.inspect()
        val bundle = inspection.bundle ?: return@withLock VlmLoadResult.Failure(
            inspection.failure ?: VlmFailure.ModelVerificationFailed)
        if (manager.directory.name != "qwen3vl" || bundle.language.sha256 != VlmModelManager.OFFICIAL_FILES[0].sha256 ||
            bundle.projector.sha256 != VlmModelManager.OFFICIAL_FILES[1].sha256) {
            return@withLock VlmLoadResult.Failure(VlmFailure.ModelVerificationFailed)
        }
        var nativeStarted = false
        try {
            if (runtimeInfo() != EXPECTED_RUNTIME) return@withLock VlmLoadResult.Failure(VlmFailure.RuntimeMismatch)
            if (!bundle.language.metadataUnchanged() || !bundle.projector.metadataUnchanged()) {
                return@withLock VlmLoadResult.Failure(VlmFailure.ModelVerificationFailed)
            }
            nativeStarted = true
            loadingOwner = this
            val code = withContext(Dispatchers.IO) {
                NativeQwenBindings.nativeLoadModel(bundle.language.file.absolutePath, bundle.projector.file.absolutePath)
            }
            if (code == 0) { owner = this; VlmLoadResult.Ready }
            else VlmLoadResult.Failure(failureFor(code))
        } catch (cancelled: CancellationException) {
            if (nativeStarted) withContext(NonCancellable + Dispatchers.IO) { NativeQwenBindings.nativeUnload() }
            throw cancelled
        } catch (_: OutOfMemoryError) {
            if (nativeStarted) withContext(NonCancellable + Dispatchers.IO) { NativeQwenBindings.nativeUnload() }
            VlmLoadResult.Failure(VlmFailure.OutOfMemory)
        } catch (_: LinkageError) {
            VlmLoadResult.Failure(VlmFailure.NativeLibraryUnavailable)
        } catch (_: Exception) {
            if (nativeStarted) withContext(NonCancellable + Dispatchers.IO) { NativeQwenBindings.nativeUnload() }
            VlmLoadResult.Failure(VlmFailure.NativeFailure(6))
        } finally {
            if (loadingOwner === this) loadingOwner = null
        }
    }

    override suspend fun unload(): VlmLoadResult = withContext(NonCancellable) { lifecycle.withLock {
        if (owner !== this@NativeQwen3VlBridge) return@withLock VlmLoadResult.Ready
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val code = NativeQwenBindings.nativeUnload()
                if (code == 0) { owner = null; VlmLoadResult.Ready }
                else VlmLoadResult.Failure(failureFor(code))
            } catch (_: LinkageError) { VlmLoadResult.Failure(VlmFailure.NativeLibraryUnavailable) }
        }
    } }

    override suspend fun generate(samples: SampledFrames, prompt: VlmPromptDefinition,
        config: QwenGenerationConfig): NativeGenerationResult = lifecycle.withLock {
        if (owner !== this) return@withLock NativeGenerationResult.Failure(VlmFailure.ModelNotLoaded)
        try {
            withContext(Dispatchers.IO) {
                val code = NativeQwenBindings.nativeBeginGeneration()
                if (code != 0) return@withContext NativeGenerationResult.Failure(failureFor(code))
                val caller = currentCoroutineContext()[Job]!!
                // Independent watcher observes cancellation while JNI blocks; always joined in finally.
                val watcher = CoroutineScope(Dispatchers.Default).launch {
                    while (isActive) {
                        if (!caller.isActive) { requestCancel(); break }
                        delay(25)
                    }
                }
                try {
                    for (frame in samples.frames) {
                        currentCoroutineContext().ensureActive()
                        val added = NativeQwenBindings.nativeAddFrame(frame.bitmap)
                        frame.close() // Native owns an RGB copy; free Java pixels immediately.
                        if (added != 0) return@withContext NativeGenerationResult.Failure(failureFor(added))
                    }
                    currentCoroutineContext().ensureActive()
                    val response = NativeQwenBindings.nativeGenerate(prompt.systemPrompt.toByteArray(Charsets.UTF_8),
                        prompt.userPrompt.toByteArray(Charsets.UTF_8), config.maxOutputTokens)
                    currentCoroutineContext().ensureActive()
                    require(response.size == 4)
                    val status = response[0].toString(Charsets.UTF_8).toInt()
                    if (status != 0) NativeGenerationResult.Failure(failureFor(status))
                    else NativeGenerationResult.Success(response[1].toString(Charsets.UTF_8),
                        response[2].toString(Charsets.UTF_8), response[3])
                } finally {
                    withContext(NonCancellable) { watcher.cancelAndJoin(); NativeQwenBindings.nativeEndGeneration() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { NativeGenerationResult.Failure(VlmFailure.OutOfMemory) }
        catch (_: LinkageError) { NativeGenerationResult.Failure(VlmFailure.NativeLibraryUnavailable) }
        catch (_: Exception) { NativeGenerationResult.Failure(VlmFailure.NativeFailure(6)) }
    }

    fun requestCancel() {
        // Native cancellation is lock-free so it can interrupt a load holding lifecycle.
        if (owner !== this && loadingOwner !== this) return
        try { NativeQwenBindings.nativeCancel() } catch (_: LinkageError) { }
    }

    fun runtimeInfo(): NativeRuntimeInfo {
        val parts = NativeQwenBindings.nativeGetRuntimeInfo().split('|')
        require(parts.size == 6)
        return NativeRuntimeInfo(parts[0], parts[1], parts[2], parts[3], parts[4].toInt(), parts[5].toInt())
    }

    companion object {
        const val RUNTIME_COMMIT = "0c1e57098bba43ac29e6e3b677cdceebdd22334f"
        val EXPECTED_RUNTIME = NativeRuntimeInfo("llama.cpp/libmtmd", RUNTIME_COMMIT, "arm64-v8a", "CPU", 8192, 4)
        private val lifecycle = Mutex()
        @Volatile private var owner: NativeQwen3VlBridge? = null
        @Volatile private var loadingOwner: NativeQwen3VlBridge? = null
        internal fun failureFor(code: Int): VlmFailure = when (code) {
            1 -> VlmFailure.ModelLoadFailed
            2 -> VlmFailure.MmprojLoadFailed
            3 -> VlmFailure.ContextLoadFailed
            4 -> VlmFailure.OutOfMemory
            5 -> VlmFailure.Cancelled
            else -> VlmFailure.NativeFailure(code)
        }
    }
}
