package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.QueryVariants
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Clock

class QueryVariantsTest {

    private fun listings(vararg titles: String) = titles.mapIndexed { i, t -> listing(i, t) }

    private fun listing(i: Int, title: String) = Listing(
        id = "KLEINANZEIGEN:$i",
        platformId = PlatformId.KLEINANZEIGEN,
        externalId = i.toString(),
        url = "https://example.invalid/$i",
        title = title,
        price = Money(50_000, Currency.EUR),
        scrapedAt = Clock.System.now(),
    )

    /** Nothing crawled yet: every word looks distinctive. */
    private val noBackground = QueryVariants.TermBackground { 0.0 }

    /** The words a marketplace corpus is full of. */
    private val commonWords = QueryVariants.TermBackground { term ->
        when (term) {
            "gebraucht", "abholung", "versand", "neuwertig" -> 0.4
            else -> 0.0
        }
    }

    /** The related searches Kleinanzeigen actually prints for "parkettschleifmaschine". */
    private val realSuggestions = listOf(
        "lägler", "parkettschleifmaschine mieten", "lägler hummel", "parkett schleifen",
        "parkettschleifer", "bodenschleifmaschine", "parkettschleifmaschine lägler",
        "einscheibenmaschine", "parkett", "schleifmaschine", "pallmann", "randschleifer",
    )

    /** What that search actually returned, in the proportions measured live: the make in a third of
     *  the titles, each other name for the tool in exactly one, the adjacent tool in a few. */
    private val realResults = listings(
        "Lägler Hummel Parkettschleifmaschine",
        "lägler Parkettschleifmaschine künzle und Tasin pegasus",
        "Lägler Elan Parkettschleifmaschine",
        "Lagler Flip Parkettschleifmaschine Wie neu",
        "Frank Cobra Parkettschleifmaschine Parkettschleifer FBS",
        "Profi Bodenschleifmaschine Parkettschleifmaschine Künzle",
        "Randschleifer Parkettschleifmaschine im Systainer",
        "Künzle & Tasin Parkettschleifmaschine Randschleifer",
        "Künzle & Tasin Scorpion Parkettschleifmaschine",
    )

    @Test
    fun `takes the market's own words over anything inferred`() {
        val ranked = QueryVariants.rank(realSuggestions, "Parkettschleifmaschine", realResults)
        assertTrue(ranked.size <= 2, "got $ranked")
        // "lägler" is in half the results already, so searching it would only fetch them again.
        assertFalse(ranked.contains("lägler"), "got $ranked")
        assertContains(ranked, "bodenschleifmaschine")
    }

    @Test
    fun `skips suggestions that only re-run the same search`() {
        val ranked = QueryVariants.rank(realSuggestions, "Parkettschleifmaschine", realResults)
        // These merely narrow the query, so they find nothing it did not already.
        assertFalse(ranked.contains("parkettschleifmaschine mieten"))
        assertFalse(ranked.contains("parkettschleifmaschine lägler"))
        assertFalse(ranked.contains("parkett"))
    }

    @Test
    fun `picks up the market's other word for the same thing`() {
        val results = listings(
            "Künzle Tasin Scorpion Parkettschleifer Parkettschleifmaschine",
            "Frank Cobra Parkettschleifmaschine Parkettschleifer FBS",
            "Lägler Hummel Parkettschleifmaschine",
        )
        assertContains(QueryVariants.candidatesFrom(results, "Parkettschleifmaschine", noBackground), "parkettschleifer")
    }

    @Test
    fun `a word common across the whole corpus cannot win`() {
        val results = listings(
            "Parkettschleifmaschine gebraucht abholung",
            "Parkettschleifmaschine gebraucht abholung",
            "Parkettschleifmaschine gebraucht abholung",
        )
        assertEquals(emptyList(), QueryVariants.candidatesFrom(results, "Parkettschleifmaschine", commonWords))
    }

    @Test
    fun `one seller's word is not the market's`() {
        val results = listings(
            "Künzle Tasin Scorpion Parkettschleifer Parkettschleifmaschine",
            "Lägler Hummel Parkettschleifmaschine",
        )
        assertEquals(emptyList(), QueryVariants.candidatesFrom(results, "Parkettschleifmaschine", noBackground))
    }

    @Test
    fun `ignores the query's own plural and stem`() {
        val results = listings(
            "Parkett Parkettschleifmaschinen Parkettschleifmaschine",
            "Parkett Parkettschleifmaschinen Parkettschleifmaschine gebraucht",
        )
        assertEquals(emptyList(), QueryVariants.candidatesFrom(results, "Parkettschleifmaschine", noBackground))
    }

    @Test
    fun `leaves short and multi-word queries alone`() {
        val results = listings("Laptop Lenovo ThinkPad Business", "Laptop Lenovo ThinkPad Business")
        assertEquals(emptyList(), QueryVariants.candidatesFrom(results, "laptop", noBackground))
        assertEquals(emptyList(), QueryVariants.candidatesFrom(results, "volkswagen crafter", noBackground))
    }

    @Test
    fun `caps the fan-out at two follow-up searches`() {
        val results = listings(
            "Parkettschleifer Bodenschleifer Walzenschleifer Parkettschleifmaschine",
            "Parkettschleifer Bodenschleifer Walzenschleifer Parkettschleifmaschine",
        )
        assertTrue(QueryVariants.candidatesFrom(results, "Parkettschleifmaschine", noBackground).size <= 2)
    }

    @Test
    fun `a service or rental phrase is not another name for the thing`() {
        val ranked = QueryVariants.rank(realSuggestions, "Parkettschleifmaschine", realResults)
        assertFalse(ranked.contains("parkett schleifen"), "got $ranked")
    }

    @Test
    fun `a word no result uses names something else`() {
        val ranked = QueryVariants.rank(realSuggestions, "Parkettschleifmaschine", realResults)
        // Neither appears in a single result title, so neither is this market's word for the tool.
        assertFalse(ranked.contains("pallmann"), "got $ranked")
    }
}
