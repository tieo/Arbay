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
        assertEquals(
            listOf("bandschleifer"),
            terms(listOf("bandschleifer", "bandschleifern"), "bandschleifmaschine"),
        )
    }

    @Test
    fun `the query's own word spelled differently is the same search`() {
        // Umlauts folded, so a transliterated query does not chase its own spelling, and a
        // truncated form is recognised as part of the query rather than a word of its own.
        assertEquals(emptyList(), terms(listOf("tischkreissäge"), "tischkreissaege"))
        assertEquals(emptyList(), terms(listOf("oberfräs"), "oberfraese"))
    }

    @Test
    fun `drops what the machine stands on or a different machine of its family`() {
        assertFalse(terms(listOf("magnetbohrständer"), "magnetbohrmaschine").contains("magnetbohrständer"))
        assertFalse(terms(listOf("siebdrucktisch"), "siebdruckmaschine").contains("siebdrucktisch"))
        assertFalse(terms(listOf("espressomühle"), "espressomaschine").contains("espressomühle"))
        // …unless the query asks for that kind itself.
        assertTrue(terms(listOf("espressomühle"), "kaffeemuehle").contains("espressomühle"))
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

    @Test
    fun `drops a make, which names who built it rather than what it is`() {
        // Kleinanzeigen's real suggestions: the makes beside the machines.
        val kept = terms(listOf("lescha", "atika", "zementmischer", "zwangsmischer"), "betonmischmaschine")
        assertFalse(kept.contains("lescha"), "got $kept")
        assertFalse(kept.contains("atika"), "got $kept")
        assertTrue(kept.any { it == "zementmischer" || it == "zwangsmischer" }, "got $kept")
    }

    @Test
    fun `keeps another name for the machine that shares no spelling`() {
        // These are the ones the job test cannot see; they ride on the market naming the query
        // back, so they must at least survive as candidates.
        assertTrue(terms(listOf("motorsäge"), "kettensaege").contains("motorsäge"))
        assertTrue(terms(listOf("freischneider"), "motorsense").contains("freischneider"))
        assertTrue(terms(listOf("microbagger"), "minibagger").contains("microbagger"))
    }

    // ── Trusting a word that is built differently ─────────────────────────────

    @Test
    fun `a word naming the same job is trusted on that alone`() {
        // Both reduce to the job "parkettschleif" once the word for machine comes off.
        assertTrue(QueryVariants.candidates(listOf("parkettschleifer"), "Parkettschleifmaschine").single().sharesStem)
        assertTrue(QueryVariants.candidates(listOf("kernbohrgerät"), "kernbohrmaschine").single().sharesStem)
        assertTrue(QueryVariants.candidates(listOf("drechselmaschine"), "drechselbank").single().sharesStem)
    }

    @Test
    fun `sharing only the thing worked on is not the same machine`() {
        // A Kartoffellegemaschine plants, a Kartoffelroder lifts: the jobs "kartoffellege" and
        // "kartoffelrod" share only the crop.
        assertFalse(QueryVariants.candidates(listOf("kartoffellegemaschine"), "kartoffelroder").single().sharesStem)
    }

    @Test
    fun `naming a different tool is not the same machine`() {
        // A Furnierpresse presses veneer, a Furniersäge cuts it.
        assertFalse(QueryVariants.candidates(listOf("furniersäge"), "furnierpresse").single().sharesStem)
    }

    @Test
    fun `a word built differently is decided by the market, not by spelling`() {
        val candidate = QueryVariants.candidates(listOf("motorsäge"), "kettensaege").single()
        assertFalse(candidate.sharesStem, "shares no stem with the query")

        // Kleinanzeigen's real lists. Asked about the synonym it names the query back and the two
        // lists are asked about in the same company; the adjacent tool does neither.
        val queryList = listOf("motorsäge", "stihl", "stihl kettensäge", "husqvarna", "akku kettensäge")
        assertTrue(QueryVariants.marketConfirms("kettensaege", queryList,
            listOf("motorsäge", "stihl motorsäge", "kettensäge", "stihl", "husqvarna")))
        assertFalse(QueryVariants.marketConfirms("kettensaege", queryList,
            listOf("heckenschere", "stihl hochentaster", "hochentaster akku", "astsäge")))
    }

    @Test
    fun `naming the query back is not enough on its own`() {
        // A chisel names its machine back too, so the lists must also look alike.
        assertFalse(QueryVariants.marketConfirms("drechselbank",
            listOf("drechselmaschine", "holzdrehbank", "drehbank", "killinger"),
            listOf("drechselbank", "drechselwerkzeug", "drechselmesser", "crown", "drechselfutter")))
    }
}
