package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.QueryVariants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every fixture here is what Kleinanzeigen actually returned, measured over 32 niche products. */
class QueryVariantsTest {

    private fun terms(suggestions: List<String>, query: String, offeredUnder: (String) -> Int = { 0 }) =
        QueryVariants.candidates(suggestions, query, offeredUnder).map { it.term }

    // ── What the market prints ────────────────────────────────────────────────

    @Test
    fun `keeps the other name for the thing`() {
        val suggestions = listOf(
            "lägler", "parkettschleifmaschine mieten", "lägler hummel", "parkett schleifen",
            "parkettschleifer", "bodenschleifmaschine", "parkettschleifmaschine lägler",
            "einscheibenmaschine", "parkett", "schleifmaschine", "pallmann", "randschleifer",
        )
        val kept = terms(suggestions, "Parkettschleifmaschine")
        assertTrue(kept.contains("parkettschleifer"), "got $kept")
    }

    @Test
    fun `drops rentals and services, which are always phrases`() {
        val kept = terms(
            listOf("parkettschleifmaschine mieten", "parkett schleifen", "parkettschleifer"),
            "Parkettschleifmaschine",
        )
        assertEquals(listOf("parkettschleifer"), kept)
    }

    @Test
    fun `drops the parts and materials a machine works with`() {
        // Kleinanzeigen's real suggestions for "drechselbank".
        val kept = terms(
            listOf("drechselmaschine", "drechseleisen", "drechselholz", "drechselwerkzeug",
                "drechseln", "drechselfutter"),
            "drechselbank",
        )
        assertTrue(kept.contains("drechselmaschine"), "got $kept")
        listOf("drechseleisen", "drechselholz", "drechselwerkzeug", "drechselfutter").forEach {
            assertFalse(kept.contains(it), "$it is a part or a material; got $kept")
        }
    }

    @Test
    fun `drops the job as opposed to the machine that does it`() {
        val kept = terms(
            listOf("kernbohrgerät", "kernbohrer", "kernbohrung", "kernbohrkrone"),
            "kernbohrmaschine",
        )
        assertTrue(kept.contains("kernbohrgerät") || kept.contains("kernbohrer"), "got $kept")
        assertFalse(kept.contains("kernbohrung"), "a Kernbohrung is the hole; got $kept")
        assertFalse(kept.contains("kernbohrkrone"), "a Kernbohrkrone is the bit; got $kept")
    }

    @Test
    fun `drops an infinitive built on the same stem`() {
        assertFalse(terms(listOf("vertikutieren", "rasenlüfter"), "vertikutierer").contains("vertikutieren"))
        assertFalse(terms(listOf("drechseln", "drechselmaschine"), "drechselbank").contains("drechseln"))
    }

    @Test
    fun `a plural of a term already kept is the same search`() {
        assertEquals(listOf("tischkreissäge"), terms(listOf("tischkreissäge", "tischkreissägen"), "tischkreissaege"))
    }

    @Test
    fun `caps the fan-out at two follow-up searches`() {
        val kept = terms(
            listOf("betonmischer", "betonmaschine", "zementmischer", "zwangsmischer", "mischer"),
            "betonmischmaschine",
        )
        assertTrue(kept.size <= 2, "got $kept")
    }

    @Test
    fun `leaves a short category query alone`() {
        assertEquals(emptyList(), terms(listOf("thinkpad", "notebook"), "laptop"))
    }

    @Test
    fun `drops a make and a category the market offers beside everything`() {
        // Counts measured across 32 niche products: einhell under five searches, stihl three,
        // rasenmäher six, while the real synonym freischneider was seen under two.
        val offeredUnder = mapOf("einhell" to 5, "stihl" to 3, "rasenmäher" to 6, "freischneider" to 2)
        val kept = terms(
            listOf("rasenmäher", "einhell", "stihl", "freischneider"),
            "motorsense",
        ) { offeredUnder[it] ?: 0 }
        assertEquals(listOf("freischneider"), kept)
    }

    // ── Trusting a word that is built differently ─────────────────────────────

    @Test
    fun `a word built like the query is trusted on that alone`() {
        assertTrue(QueryVariants.candidates(listOf("parkettschleifer"), "Parkettschleifmaschine").single().sharesStem)
    }

    @Test
    fun `a word built differently needs the market to name the query back`() {
        val candidate = QueryVariants.candidates(listOf("motorsäge"), "kettensaege").single()
        assertFalse(candidate.sharesStem, "shares no stem with the query")
        // Kleinanzeigen's real suggestions for "motorsäge" name the query back; the adjacent
        // "hochentaster" only names other tools.
        assertTrue(QueryVariants.namesBack("kettensaege",
            listOf("motorsäge", "stihl motorsäge", "kettensäge", "stihl")))
        assertFalse(QueryVariants.namesBack("kettensaege",
            listOf("heckenschere", "stihl hochentaster", "hochentaster akku", "astsäge")))
    }
}
