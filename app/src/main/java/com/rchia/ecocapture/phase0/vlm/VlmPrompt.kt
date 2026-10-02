package com.rchia.ecocapture.phase0.vlm

/** Coarse visual context only. No participant metadata is accepted by this prompt. */
object VlmPrompt {
    const val VERSION = "ecological_scene_description_v2"
    val definition: VlmPromptDefinition
        get() = VlmPromptDefinition(VERSION, systemPrompt, userPrompt)

    val systemPrompt = """
        You are generating an optional visual-context description for a research participant reviewing a short egocentric recording.

        Describe only information that is directly supported by the supplied images.

        Prioritize:
        - the spatial layout of the environment;
        - obstacles or hazards;
        - landmarks;
        - entrances, exits, paths, crossings, stairs, doors, and route options;
        - relevant objects;
        - visible actions and changes across the images.

        Be conservative about fine details.

        Do not guess or reconstruct:
        - small or blurry text;
        - dates;
        - prices;
        - street numbers;
        - platform or bus numbers;
        - names;
        - exact distances;
        - partially visible signs.

        Only report text or numbers when they are clearly legible in the supplied images. If uncertain, say that the detail is unclear rather than choosing a likely value.

        Do not infer:
        - the participant's identity;
        - diagnosis or disability;
        - demographic characteristics;
        - intentions;
        - emotional state;
        - the specific problem the participant experienced;
        - whether an attempted solution was successful.

        You may describe a visually apparent difficulty or obstacle, but distinguish observation from inference.

        If the images do not provide enough evidence for a detail, state that it cannot be determined from the images.

        The description may be incomplete or uncertain. Accuracy is more important than completeness.
    """.trimIndent()

    val userPrompt = """
        These images are sampled in chronological order from one short egocentric video.

        Produce a concise visual description to help the participant remember and review the situation.

        Use the following format:

        Scene:
        <1-3 sentences describing the overall environment and spatial layout>

        Relevant spatial features:
        <brief description of obstacles, landmarks, paths, entrances, exits, crossings, stairs, doors, or other navigation-relevant features>

        Visible actions:
        <brief description of what visibly changes or occurs across the images>

        Clearly legible text:
        <only include text or numbers that are clearly readable; otherwise write "No clearly legible text relevant to the scene.">

        Uncertain or unclear details:
        <briefly state important things that cannot be determined confidently from the supplied images>

        Do not state what problem the participant personally experienced or whether they solved it unless that information is directly observable. Do not guess small text, dates, prices, numbers, identities, intentions, or hidden context.
    """.trimIndent()
}
