package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.*
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelevanceFilterTest {

    private fun listing(title: String, price: Long = 10000) = Listing(
        id = "test:${title.hashCode()}",
        platformId = PlatformId.EBAY_DE,
        externalId = title.hashCode().toString(),
        url = "https://example.com",
        title = title,
        price = Money(price, Currency.EUR),
        scrapedAt = Clock.System.now(),
    )

    private fun search(query: String, listings: List<Listing>): List<Listing> =
        RelevanceFilter.filter(listings, SearchQuery(text = query))

    // === Placeholder title filtering ===

    @Test
    fun `filters out placeholder titles`() {
        val results = search("WH-1000XM5", listOf(
            listing("Neues Angebot"),
            listing("Sony WH-1000XM5 Bluetooth Kopfhörer"),
            listing("x"),
        ))
        assertEquals(1, results.size)
        assertTrue(results[0].title.contains("Bluetooth"))
    }

    // === Numeric model token must match as whole word ===

    @Test
    fun `Galaxy Z Fold 6 filters older Fold generations`() {
        val results = search("Samsung Galaxy Z Fold 6", listOf(
            listing("Samsung Galaxy Z Fold 6 SM-F956B 256GB Phantom Black"),
            listing("Samsung Galaxy Z Fold 6 512GB Shadow Cyan"),
            listing("Samsung Galaxy Z Fold3 5G SM-F926B 256GB Schwarz"),
            listing("Samsung Galaxy Z Fold5 SM-F946B 512GB Phantom Black"),
        ))
        assertTrue(results.any { it.title.contains("Fold 6 SM-F956B") }, "Fold 6 should be kept")
        assertTrue(results.any { it.title.contains("Fold 6 512GB") }, "Fold 6 should be kept")
        assertTrue(results.none { it.title.contains("Fold3") }, "Fold3 should be filtered")
        assertTrue(results.none { it.title.contains("Fold5") }, "Fold5 should be filtered")
    }

    // === Negative keywords ===

    @Test
    fun `negative keywords exclude matching listings`() {
        val results = search("MFC-L2750DW -toner -drum", listOf(
            listing("Brother MFC-L2750DW Multifunktionsdrucker"),
            listing("Toner kompatibel für Brother MFC-L2750DW"),
            listing("DR2400 Trommel Drum für MFC-L2750DW"),
            listing("Brother MFC-L2750DW 4-in-1 Laserdrucker"),
        ))
        assertEquals(2, results.size)
        assertTrue(results.all { !it.title.lowercase().contains("toner") && !it.title.lowercase().contains("drum") })
    }

    // === Token matching ===

    @Test
    fun `hyphenated model numbers match compact and split forms`() {
        val results = search("WH-1000XM5", listOf(
            listing("Sony WH-1000XM5 Bluetooth Kopfhörer"),
            listing("Sony WH1000XM5 Noise Cancelling"),
            listing("Sony WH 1000 XM5 Headphones"),
        ))
        assertEquals(3, results.size, "All forms should match")
    }

    // === Model qualifier bigram ===

    @Test
    fun `model qualifier must be adjacent`() {
        val results = search("Samsung Galaxy S25 Ultra", listOf(
            listing("Samsung Galaxy S25 Ultra 256GB Titanium Black"),
            listing("Samsung Galaxy S25 FE ultra sauber wie neu"),
        ))
        assertTrue(results.any { it.title.contains("Ultra 256GB") }, "Actual Ultra should be kept")
        assertTrue(results.none { it.title.contains("ultra sauber") }, "German 'ultra clean' should be filtered")
    }

    // === Score threshold ===

    @Test
    fun `low match ratio listings are filtered`() {
        val results = search("Canon EOS R5 Mark II", listOf(
            listing("Canon EOS R5 Mark II Mirrorless Camera Body"),
            listing("Canon EOS 5D Mark IV DSLR Camera"),
        ))
        assertTrue(results.any { it.title.contains("R5 Mark II") })
        assertTrue(results.none { it.title.contains("5D Mark IV") }, "Different model should be filtered by score threshold")
    }

    // === OR queries ===

    @Test
    fun `OR query matches either group`() {
        val results = search("Sony WH-1000XM5 OR Sony WH-1000XM4", listOf(
            listing("Sony WH-1000XM5 Noise Cancelling Headphones"),
            listing("Sony WH-1000XM4 Wireless Headphones"),
            listing("Sony WF-1000XM5 Earbuds"),
        ))
        assertTrue(results.any { it.title.contains("XM5 Noise") })
        assertTrue(results.any { it.title.contains("XM4 Wireless") })
    }

    // === All results pass through when they match ===

    @Test
    fun `matching listings are not filtered by removed kill lists`() {
        val results = search("MFC-L2750DW", listOf(
            listing("Brother MFC-L2750DW Multifunktionsdrucker"),
            listing("Toner kompatibel für Brother MFC-L2750DW"),
            listing("Schutzhülle Case für MFC-L2750DW"),
            listing("Brother MFC-L2750DW Mainboard Formatter"),
        ))
        // No hardcoded kill lists: the printer itself and a spare part named without an
        // "accessory for" phrasing both pass. What does not pass is an accessory whose title
        // says it is made FOR the searched product — a searcher after the printer does not
        // want its toner or a case (see the accessory-for tests below).
        assertTrue(results.any { it.title.contains("Multifunktionsdrucker") })
        assertTrue(results.any { it.title.contains("Mainboard") })
        assertTrue(results.none { it.title.contains("Toner") }, "toner is an accessory for the printer")
        assertTrue(results.none { it.title.contains("Schutzhülle") }, "case is an accessory for the printer")
    }

    // === Irrelevance report (platform ignored the query) ===

    private val garbageListings = listOf(
        listing("BMW 320d Touring Sportpaket"),
        listing("Audi A4 Avant 2.0 TDI"),
        listing("Opel Corsa 1.2 Edition"),
        listing("Ford Focus Turnier Titanium"),
        listing("Renault Clio TCe 90"),
        listing("Skoda Octavia Combi Style"),
        listing("Toyota Yaris Hybrid Comfort"),
        listing("Fiat 500 Lounge Cabrio"),
        listing("Peugeot 208 Allure Pack"),
        listing("Hyundai i30 Kombi Trend"),
    )

    @Test
    fun `irrelevanceReport flags result set without any query matches`() {
        val report = RelevanceFilter.irrelevanceReport(garbageListings, SearchQuery(text = "Volkswagen Crafter"))
        assertNotNull(report, "10 listings with zero query matches should be flagged")
    }

    @Test
    fun `irrelevanceReport passes genuine result set`() {
        val genuine = listOf(
            listing("Volkswagen Crafter 35 Kasten Hochdach"),
            listing("VW Crafter 2.0 TDI L3H3"),
            listing("Volkswagen Crafter Pritsche Doka"),
            listing("Volkswagen Crafter Kombi 9-Sitzer"),
            listing("VW Crafter Grand California 600"),
        )
        val report = RelevanceFilter.irrelevanceReport(genuine, SearchQuery(text = "Volkswagen Crafter"))
        assertNull(report, "Matching results should not be flagged")
    }

    @Test
    fun `irrelevanceReport exempts single-token queries`() {
        val report = RelevanceFilter.irrelevanceReport(garbageListings, SearchQuery(text = "Laptop"))
        assertNull(report, "Single-token queries may match beyond the title and are exempt")
    }

    @Test
    fun `irrelevanceReport skips small result sets`() {
        val report = RelevanceFilter.irrelevanceReport(garbageListings.take(4), SearchQuery(text = "Volkswagen Crafter"))
        assertNull(report, "Fewer than 5 results is too small a sample to flag")
    }

    @Test
    fun `rental offers are dropped, unless the query asks to rent`() {
        val listings = listOf(
            listing("Parkettschleifmaschine Mieten", price = 100),
            listing("Parkettschleifmaschine Lägler Hummel", price = 90000),
            listing("Parkettschleifmaschine zu vermieten", price = 500),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }
        assertEquals(listOf("Parkettschleifmaschine Lägler Hummel"), kept)
        // Asking to rent keeps them: that is exactly what was searched for.
        val rentKept = search("parkettschleifmaschine mieten", listings).map { it.title }
        assertTrue(rentKept.any { it.contains("Mieten") }, "rental query must keep rental offers")
    }



    @Test
    fun `a long compound single-token query drops results that only share its tail`() {
        val listings = listOf(
            listing("Die Zeitmaschine", price = 389),
            listing("Leo und die Abenteuermaschine", price = 289),
            listing("Parkettschleifmaschine Lägler Hummel", price = 250000),
            listing("Parkett Schleifmaschine Bandschleifer", price = 90000),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }.toSet()
        assertEquals(
            setOf("Parkettschleifmaschine Lägler Hummel", "Parkett Schleifmaschine Bandschleifer"),
            kept,
        )
    }

    @Test
    fun `a short generic single-token query still trusts the platform search`() {
        val listings = listOf(
            listing("Lenovo ThinkPad X1 Carbon", price = 50000),
            listing("Dell XPS 13", price = 60000),
        )
        assertEquals(2, search("laptop", listings).size, "generic category words must not stem-match")
    }

    @Test
    fun `consumables and spares for the machine are dropped, unless the query asks for them`() {
        val listings = listOf(
            listing("Schleifpapier / Schleifband Parkettschleifmaschine (Boels)", price = 600),
            listing("TM Rent 5 Beutel Staubfangsack Parkettschleifmaschine", price = 994),
            listing("Schleifscheiben für Parkettschleifmaschine 150mm", price = 1200),
            listing("Parkettschleifmaschine Laegler Hummel", price = 250000),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }
        assertEquals(listOf("Parkettschleifmaschine Laegler Hummel"), kept)
        // A search for the consumable keeps it.
        assertEquals(1, search("schleifpapier parkett", listOf(listings[0])).size)
    }
}
