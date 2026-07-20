package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Offline guard for crawler wiring. The live fleet scan (crawler-scan.yml) proves each site still
 * returns data; this proves, without network, that every platform the app offers is actually
 * registered and wired to itself. It catches the class of bug where a PlatformId is added to a
 * market list but never registered, or a parametrised crawler (per-country AutoScout24, the OLX
 * and Marktplaats siblings) is handed the wrong platformId.
 */
class FleetWiringTest {

    /** Every platform a car or product search can target. Kept in sync with CAR_PLATFORMS /
     *  GENERAL_PLATFORMS and CAR_MARKETS; a new market must be added here and registered. */
    private val fleet = listOf(
        // Cars
        PlatformId.AUTOSCOUT24, PlatformId.MOBILE_DE, PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE,
        PlatformId.TRUCKSCOUT24, PlatformId.OTOMOTO, PlatformId.DBA, PlatformId.BILBASEN,
        PlatformId.BYTBIL, PlatformId.SAUTO, PlatformId.MARKTPLAATS, PlatformId.WILLHABEN,
        PlatformId.AUTOSCOUT24_IT, PlatformId.AUTOSCOUT24_FR, PlatformId.AUTOSCOUT24_ES,
        PlatformId.AUTOSCOUT24_BE, PlatformId.AUTOVIT, PlatformId.RICARDO, PlatformId.SUBITO,
        PlatformId.TWEEDEHANDS, PlatformId.AUTOPLIUS, PlatformId.NETTIAUTO, PlatformId.FINN,
        PlatformId.OLX_PT, PlatformId.KUPUJEM,
        // Products
        PlatformId.EBAY_COM, PlatformId.EBAY_IT, PlatformId.EBAY_FR, PlatformId.EBAY_ES,
        PlatformId.AMAZON_DE, PlatformId.IDEALO, PlatformId.GEIZHALS, PlatformId.BACKMARKET_DE,
        PlatformId.REBUY, PlatformId.REFURBED, PlatformId.VINTED_DE,
    )

    @Test
    fun `every fleet platform has a registered crawler`() {
        val missing = fleet.filter { CrawlerRegistry.crawlerFor(it) == null }
        assertEquals(emptyList(), missing, "platforms offered but not registered: $missing")
    }

    @Test
    fun `each registered crawler reports the platform id it is keyed under`() {
        // A parametrised crawler (AutoScout24 per country, OLX/Marktplaats siblings) that forgets
        // to override platformId would surface listings tagged as the base platform.
        CrawlerRegistry.supportedPlatforms().forEach { id ->
            val crawler = CrawlerRegistry.crawlerFor(id)
            assertNotNull(crawler, "crawlerFor($id) returned null despite being registered")
            assertEquals(id, crawler.platformId, "crawler registered under $id reports ${crawler.platformId}")
        }
    }

    @Test
    fun `per-country AutoScout24 markets are distinct registrations`() {
        // The split from one EU chip into per-country markets is easy to half-wire.
        listOf(PlatformId.AUTOSCOUT24_IT, PlatformId.AUTOSCOUT24_FR, PlatformId.AUTOSCOUT24_ES, PlatformId.AUTOSCOUT24_BE)
            .forEach { assertEquals(it, CrawlerRegistry.crawlerFor(it)?.platformId, "$it not wired to itself") }
    }
}
