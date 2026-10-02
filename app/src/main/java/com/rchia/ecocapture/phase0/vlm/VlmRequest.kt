package com.rchia.ecocapture.phase0.vlm

import java.io.File

data class VlmPromptDefinition(val version: String, val systemPrompt: String, val userPrompt: String)

/** No clinical/profile fields or live DAT frames are part of the engine input. */
data class VlmRequest(
    val clipId: String,
    val videoFile: File,
    val prompt: VlmPromptDefinition = VlmPrompt.definition,
)
