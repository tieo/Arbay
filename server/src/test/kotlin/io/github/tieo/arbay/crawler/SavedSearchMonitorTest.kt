package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.NotificationSubfilter
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whether a fresh listing is worth a push notification under a search's own subfilter, not just
 *  a count on the bookmark. Every case here is a subfilter someone could plausibly set. */
class SavedSearchMonitorTest {

    private fun listing(
        title: String,
        priceEur: Int,
        condition: Condition? = null,
    ) = Listing(
        id = title.hashCode().toString(),
        platformId = io.github.tieo.arbay.model.PlatformId.EBAY_DE,
        externalId = title.hashCode().toString(),
        url = "https://example.com/$title",
        title = title,
        price = Money(priceEur * 100L, Currency.EUR),
        condition = condition,
        scrapedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun sf(
        minPriceEur: Int? = null,
        maxPriceEur: Int? = null,
        condition: String? = null,
        mustContainAnyOf: List<String> = emptyList(),
        excludeKeywords: List<String> = emptyList(),
    ) = NotificationSubfilter(
        id = "sf1", name = "test",
        minPriceEur = minPriceEur, maxPriceEur = maxPriceEur, condition = condition,
        mustContainAnyOf = mustContainAnyOf, excludeKeywords = excludeKeywords,
    )

    @Test
    fun `a bare subfilter matches anything the search already found`() {
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 8000), sf()))
    }

    @Test
    fun `max price excludes anything over it, keeps anything at or under`() {
        val filter = sf(maxPriceEur = 8000)
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 8000), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 7999), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 8001), filter))
    }

    @Test
    fun `min price excludes anything under it`() {
        val filter = sf(minPriceEur = 5000)
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 4999), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 5000), filter))
    }

    @Test
    fun `a price band combines both bounds`() {
        val filter = sf(minPriceEur = 5000, maxPriceEur = 8000)
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 4000), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 6000), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 9000), filter))
    }

    @Test
    fun `condition NEW keeps only NEW, USED keeps everything else, null is Any`() {
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.NEW), sf(condition = "NEW")))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.USED), sf(condition = "NEW")))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("x", 100, null), sf(condition = "NEW")))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.USED), sf(condition = "USED")))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.NEW), sf(condition = "USED")))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.NEW), sf(condition = null)))
    }

    @Test
    fun `mustContainAnyOf requires at least one of the terms in the title, case-insensitively`() {
        val filter = sf(mustContainAnyOf = listOf("Automatik", "automatic"))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 AUTOMATIK", 100), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 automatic", 100), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Schaltgetriebe", 100), filter))
    }

    @Test
    fun `excludeKeywords rejects a title containing any of them, case-insensitively`() {
        val filter = sf(excludeKeywords = listOf("Unfall", "defekt"))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 UNFALLWAGEN", 100), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Motor defekt", 100), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 top Zustand", 100), filter))
    }

    @Test
    fun `every criterion must hold at once`() {
        val filter = sf(maxPriceEur = 9000, condition = "USED", mustContainAnyOf = listOf("automatik"))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Automatik", 8000, Condition.USED), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Automatik", 9500, Condition.USED), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Automatik", 8000, Condition.NEW), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Schaltung", 8000, Condition.USED), filter))
    }
}
