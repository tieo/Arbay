package io.github.tieo.arbay

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.nextRepost
import io.github.tieo.arbay.selling.Selling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun io.github.tieo.arbay.model.AdDraft.nextRepostOrNull() = nextRepost()

class SellingBlankTest {
    @Test
    fun `a blank ad needs a title and a category number`() {
        assertFailsWith<Selling.SellingFailure> { Selling.blank(" ", "173") }
        assertFailsWith<Selling.SellingFailure> { Selling.blank("Garn", "") }
        assertFailsWith<Selling.SellingFailure> { Selling.blank("Garn", "Handy") }
        val draft = Selling.blank(" Garn ", "173")
        assertEquals("Garn", draft.title)
        assertEquals(null, draft.fromListingId)
        Selling.delete(draft.id)
    }

    @Test
    fun `a photo left out of a draft loses its file, one just written stays`() {
        val draft = Selling.blank("Garn", "173")
        val folder = DataDir.file("selling/${draft.id}").apply { mkdirs() }
        val old = java.io.File(folder, "p1.jpg").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000) }
        val fresh = java.io.File(folder, "p2.jpg").apply { writeText("x") }
        Selling.change(draft)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        Selling.delete(draft.id)
    }

    @Test
    fun `an ad taken down is never due to go up again`() {
        val now = kotlin.time.Clock.System.now()
        val posted = io.github.tieo.arbay.model.AdDraft(id = "x", categoryId = "173", title = "t", description = "d", postedAdId = "1", postedAt = now, repostEveryDays = 7)
        assertTrue(posted.nextRepostOrNull() != null)
        assertEquals(null, posted.copy(takenDownAt = now).nextRepostOrNull())
    }
}
