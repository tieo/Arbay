package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.Rentals
import io.github.tieo.arbay.crawler.markRented
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.RentPeriod
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class RentalsTest {
    private fun ad(title: String, description: String? = null, url: String = "https://www.kleinanzeigen.de/s-anzeige/x/1") = Listing(
        id = "KLEINANZEIGEN:${title.hashCode()}", platformId = PlatformId.KLEINANZEIGEN, externalId = title.hashCode().toString(),
        url = url, title = title, description = description, price = Money(4500, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    private val parquet = SearchQuery(text = "parkettschleifmaschine mieten", category = MarketGroup.GENERAL)

    @Test
    fun `a Kleinanzeigen ad hiring the machine out is a rental`() {
        // Live titles from a "parkettschleifmaschine mieten" search, each listed at a fixed price.
        val marked = listOf(
            ad("Parkettschleifmaschine - ZU VERMIETEN"),
            ad("Parkettschleifer mieten / leihen", "45 € pro Tag, Wochenende 80 €"),
            ad("Vermiete Bodenschleifmaschine Lägler Hummel"),
        ).markRented(parquet)
        assertTrue(marked.all { it.saleType == SaleType.RENT }, marked.map { it.title }.toString())
        assertEquals(RentPeriod.DAY, marked[1].rentPer)
        assertNull(marked[0].rentPer, "no period stated, none guessed")
    }

    @Test
    fun `a machine sold out of a rental fleet is sold`() {
        val sold = listOf(
            ad("Lägler Hummel aus der Vermietung"),
            ad("Parkettschleifmaschine Mietrückläufer"),
            ad("Parkettschleifmaschine Hummel gebraucht, früher zum Verleih"),
            ad("Parkettschleifmaschine Lägler Hummel"),
        ).markRented(parquet)
        assertTrue(sold.none { it.saleType == SaleType.RENT }, sold.map { it.title to it.saleType }.toString())
    }

    @Test
    fun `a flat to let is left to a real-estate search`() {
        val flat = listOf(ad("2-Zimmer-Wohnung zu vermieten")).markRented(SearchQuery(text = "wohnung", category = MarketGroup.REAL_ESTATE))
        assertFalse(flat.single().saleType == SaleType.RENT)
    }

    @Test
    fun `the period is the first one the text names`() {
        assertEquals(RentPeriod.DAY, Rentals.periodStated("Tagesmiete 45 €, Woche 180 €"))
        assertEquals(RentPeriod.WEEKEND, Rentals.periodStated("Wochenende 80 €, pro Tag 45 €"))
        assertEquals(RentPeriod.DAY, Rentals.periodStated("45€/Tag"))
        assertNull(Rentals.periodStated("Parkettschleifmaschine zu vermieten"))
        assertTrue(Rentals.rentalSite("miet24"))
        assertFalse(Rentals.rentalSite("verleihnix"))
    }
}
