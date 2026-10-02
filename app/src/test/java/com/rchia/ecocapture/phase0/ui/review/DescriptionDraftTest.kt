package com.rchia.ecocapture.phase0.ui.review

import org.junit.Assert.*
import org.junit.Test

class DescriptionDraftTest {
    @Test fun emptyAndWhitespaceOnlyDraftsCannotBeSaved() {
        listOf("", " ", "\n\t ").forEach { assertFalse(DescriptionDraft(it, null).canSave) }
    }
    @Test fun descriptionIsRetainedWithoutTrimmingOrRewriting() {
        val text = "  The sign was unclear.\nI could not tell which path to use.  "
        val draft = DescriptionDraft(text, null)
        assertTrue(draft.canSave)
        assertEquals(text, draft.text)
    }
    @Test fun editingRetainsOriginalRevisionReference() {
        val draft = DescriptionDraft("Original", "revision-id").copy(text = "Edited")
        assertEquals("revision-id", draft.expectedCurrentId)
        assertEquals("Edited", draft.text)
    }
}
