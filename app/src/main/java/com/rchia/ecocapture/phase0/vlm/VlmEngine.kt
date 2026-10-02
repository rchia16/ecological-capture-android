package com.rchia.ecocapture.phase0.vlm

/** Engines receive saved media only. Callers unload in finally; cancellation propagates. */
interface VlmEngine {
    suspend fun load(): VlmLoadResult
    suspend fun generate(request: VlmRequest): VlmResult
    suspend fun unload()
    fun modelInfo(): VlmModelInfo
}

sealed interface VlmLoadResult {
    data object Ready : VlmLoadResult
    data class Failure(val reason: VlmFailure) : VlmLoadResult
}

sealed interface VlmFailure {
    data object ModelNotLoaded : VlmFailure
    data object ModelMissing : VlmFailure
    data object HashMismatch : VlmFailure
    data object ModelLoadFailed : VlmFailure
    data object MmprojLoadFailed : VlmFailure
    data object ContextLoadFailed : VlmFailure
    data object ModelVerificationFailed : VlmFailure
    data object NativeLibraryUnavailable : VlmFailure
    data object RuntimeMismatch : VlmFailure
    data object Cancelled : VlmFailure
    data object FrameExtractionFailed : VlmFailure
    data object InvalidPrompt : VlmFailure
    data object EmptyOutput : VlmFailure
    data object OutOfMemory : VlmFailure
    data class NativeFailure(val code: Int) : VlmFailure
}
