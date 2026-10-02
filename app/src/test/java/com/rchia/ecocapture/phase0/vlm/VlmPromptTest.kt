package com.rchia.ecocapture.phase0.vlm

import org.junit.Assert.*
import org.junit.Test

/** Tests the prompt contract, not a claim that a probabilistic model always complies. */
class VlmPromptTest {
    @Test fun unclearSmallTextHasAnExplicitAlternativeToTranscription() {
        assertTrue(VlmPrompt.systemPrompt.contains("Do not guess or reconstruct:"))
        assertTrue(VlmPrompt.systemPrompt.contains("small or blurry text"))
        assertTrue(VlmPrompt.systemPrompt.contains("say that the detail is unclear rather than choosing a likely value"))
        assertTrue(VlmPrompt.userPrompt.contains("No clearly legible text relevant to the scene."))
    }

    @Test fun ExactDatesPricesAndNumbersRequireLegibility() {
        for (detail in listOf("dates", "prices", "street numbers", "platform or bus numbers", "exact distances", "partially visible signs")) {
            assertTrue("Missing conservative rule for $detail", VlmPrompt.systemPrompt.contains("- $detail"))
        }
        assertTrue(VlmPrompt.systemPrompt.contains("Only report text or numbers when they are clearly legible"))
        assertTrue(VlmPrompt.userPrompt.contains("Do not guess small text, dates, prices, numbers"))
    }

    @Test fun participantProblemAndSolutionAreNotInferredAsFacts() {
        for (detail in listOf("the participant's identity", "diagnosis or disability", "demographic characteristics", "intentions", "emotional state", "the specific problem the participant experienced", "whether an attempted solution was successful")) {
            assertTrue(VlmPrompt.systemPrompt.contains("- $detail"))
        }
        assertTrue(VlmPrompt.systemPrompt.contains("distinguish observation from inference"))
        assertTrue(VlmPrompt.userPrompt.contains("Do not state what problem the participant personally experienced"))
    }

    @Test fun coarseSpatialDescriptionAndChronologicalActionsRemainRequired() {
        for (feature in listOf("spatial layout", "obstacles or hazards", "landmarks", "entrances", "exits", "route options", "relevant objects", "visible actions and changes")) {
            assertTrue(VlmPrompt.systemPrompt.contains(feature))
        }
        val headings = listOf("Scene:", "Relevant spatial features:", "Visible actions:", "Clearly legible text:", "Uncertain or unclear details:")
        val positions = headings.map { VlmPrompt.userPrompt.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        assertTrue(VlmPrompt.userPrompt.contains("chronological order"))
    }

    @Test fun versionAndUncertaintyContractAreExplicit() {
        assertEquals("ecological_scene_description_v2", VlmPrompt.VERSION)
        assertTrue(VlmPrompt.systemPrompt.contains("optional visual-context description"))
        assertTrue(VlmPrompt.systemPrompt.contains("cannot be determined from the images"))
        assertTrue(VlmPrompt.systemPrompt.contains("Accuracy is more important than completeness."))
        assertTrue(VlmPrompt.userPrompt.contains("cannot be determined confidently"))
    }
}
