package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.QueryVariants
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryVariantsTest {

    @Test
    fun `swaps the head noun of a compound for its agent form`() {
        val variants = QueryVariants.of("Parkettschleifmaschine")
        assertContains(variants, "parkettschleifer")
        assertTrue(variants.none { it == "parkettschleifmaschine" }, "must not repeat the query")
    }

    @Test
    fun `leaves a plain -er noun alone`() {
        assertEquals(emptyList(), QueryVariants.of("Bodenschleifer"))
    }

    @Test
    fun `keeps one spelling of gerät`() {
        val variants = QueryVariants.of("Parkettschleifmaschine")
        assertEquals(1, variants.count { it.startsWith("parkettschleifger") })
    }

    @Test
    fun `caps the fan-out at two variants`() {
        assertTrue(QueryVariants.of("Parkettschleifmaschine").size <= 2)
    }

    @Test
    fun `leaves multi-word and short queries alone`() {
        assertEquals(emptyList(), QueryVariants.of("volkswagen crafter"))
        assertEquals(emptyList(), QueryVariants.of("bohrer"))
        assertEquals(emptyList(), QueryVariants.of("iphone 15"))
    }

    @Test
    fun `leaves a compound with no swappable head alone`() {
        assertEquals(emptyList(), QueryVariants.of("waschtrockner"))
    }
}
