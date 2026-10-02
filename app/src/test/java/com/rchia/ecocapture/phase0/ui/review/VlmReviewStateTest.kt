package com.rchia.ecocapture.phase0.ui.review

import org.junit.Assert.*
import org.junit.Test

class VlmReviewStateTest {
    @Test fun queuedWaitingPreparingAndRunningAreGenerationStates() {
        VlmReviewPhase.entries.forEach { phase ->
            assertEquals(phase in setOf(VlmReviewPhase.QUEUED, VlmReviewPhase.WAITING_CHARGE,
                VlmReviewPhase.WAITING_RECORDING, VlmReviewPhase.WAITING_MEMORY, VlmReviewPhase.WAITING_APP,
                VlmReviewPhase.PREPARING, VlmReviewPhase.RUNNING, VlmReviewPhase.CANCELLING),
                VlmReviewState(phase = phase).isGenerating)
        }
    }
    @Test fun editingAmendmentKeepsOriginalRunAndRevisionReferences() {
        val edited = DescriptionDraft("Unclear signage", "previous", "original-run").copy(text = "My account")
        assertEquals("original-run", edited.parentVlmRunId)
        assertEquals("previous", edited.expectedCurrentId)
        assertTrue(edited.canSave)
    }
}
