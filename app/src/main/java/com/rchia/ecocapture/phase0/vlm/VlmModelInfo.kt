package com.rchia.ecocapture.phase0.vlm

data class VlmModelInfo(
    val modelId: String,
    val modelQuant: String,
    val languageModelSha256: String,
    val mmprojSha256: String,
    val runtimeName: String,
    val runtimeCommit: String,
)
