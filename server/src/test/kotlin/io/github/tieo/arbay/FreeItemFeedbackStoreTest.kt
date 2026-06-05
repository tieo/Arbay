package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.FeedbackAction
import io.github.tieo.arbay.classifier.FreeItemFeedbackStore
import kotlin.test.*

/**
 * Tests for FreeItemFeedbackStore.
 * NOTE: FreeItemFeedbackStore is a singleton that writes to ~/.arbay/free_item_feedback.json.
 * Tests that add feedback will persist to disk. Tests verify behavior, not isolation.
 */
class FreeItemFeedbackStoreTest {

    // ── love / dislike embeddings ───────────────────────────────────────────────

    @Test
    fun `love feedback adds to lovedEmbeddings`() {
        val before = FreeItemFeedbackStore.lovedEmbeddings().size
        FreeItemFeedbackStore.add("love-test-${System.currentTimeMillis()}", "Samsung Galaxy S25 Ultra", FeedbackAction.LOVE)
        val after = FreeItemFeedbackStore.lovedEmbeddings().size
        assertEquals(before + 1, after, "lovedEmbeddings should grow by 1 after adding LOVE feedback")
    }

    @Test
    fun `dislike feedback adds to dislikedEmbeddings`() {
        val before = FreeItemFeedbackStore.dislikedEmbeddings().size
        FreeItemFeedbackStore.add("dislike-test-${System.currentTimeMillis()}", "Kinderspielzeug Lego Duplo Set", FeedbackAction.DISLIKE)
        val after = FreeItemFeedbackStore.dislikedEmbeddings().size
        assertEquals(before + 1, after, "dislikedEmbeddings should grow by 1 after adding DISLIKE feedback")
    }

    @Test
    fun `embedding vectors are 384-dimensional`() {
        FreeItemFeedbackStore.add("dim-test-${System.currentTimeMillis()}", "Test item for dimension check", FeedbackAction.LOVE)
        val embeddings = FreeItemFeedbackStore.lovedEmbeddings()
        assertTrue(embeddings.isNotEmpty())
        assertEquals(384, embeddings.last().size, "Stored embeddings should be 384-dimensional")
    }

    @Test
    fun `duplicate listing ID replaces existing entry`() {
        val id = "dedup-test-${System.currentTimeMillis()}"
        // Use a title with words that won't collide with scorer test titles (avoid "monitor", "samsung", etc.)
        FreeItemFeedbackStore.add(id, "Gartengerät Schaukel Holz", FeedbackAction.LOVE)
        val lovedBefore = FreeItemFeedbackStore.lovedEmbeddings().size
        val dislikedBefore = FreeItemFeedbackStore.dislikedEmbeddings().size

        // Re-add same ID as dislike — should replace, not duplicate
        FreeItemFeedbackStore.add(id, "Gartengerät Schaukel Holz", FeedbackAction.DISLIKE)
        val lovedAfter = FreeItemFeedbackStore.lovedEmbeddings().size
        val dislikedAfter = FreeItemFeedbackStore.dislikedEmbeddings().size

        assertEquals(lovedBefore - 1, lovedAfter, "Old love entry should be removed on re-add")
        assertEquals(dislikedBefore + 1, dislikedAfter, "New dislike entry should be added")
    }
}
