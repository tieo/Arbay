package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.*
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
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
            listing("Ohrpolster für Sony WH-1000XM5"),
            listing("Schutzhülle Case für MFC-L2750DW"),
            listing("Brother MFC-L2750DW Mainboard Formatter"),
        ))
        // All listings containing the model should pass (no hardcoded kills)
        assertTrue(results.any { it.title.contains("Multifunktionsdrucker") })
        assertTrue(results.any { it.title.contains("Toner") })
        assertTrue(results.any { it.title.contains("Schutzhülle") })
        assertTrue(results.any { it.title.contains("Mainboard") })
        // Ohrpolster listing doesn't contain query tokens → filtered by score
        assertTrue(results.none { it.title.contains("Ohrpolster") })
    }
}
