package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Dealer
import io.github.tieo.arbay.model.DealerHours
import io.github.tieo.arbay.model.DealerMoney
import io.github.tieo.arbay.model.DealerRating
import io.github.tieo.arbay.model.DealerStock
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.stockLine
import io.github.tieo.arbay.model.summary
import io.github.tieo.arbay.model.text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.serialization.json.Json

class DealerTest {
    private val page = "https://www.autoscout24.de/haendler/autozentrum-tuebingen"

    private fun van(seller: Seller) = Listing(
        id = "AUTOSCOUT24:1", platformId = PlatformId.AUTOSCOUT24, externalId = "1", url = "https://example.test/1",
        title = "VW Crafter", price = Money(2_000_000, Currency.EUR), seller = seller, scrapedAt = Clock.System.now(),
    )

    private val dealer = Dealer(market = "autoscout24.de", ratings = listOf(DealerRating("google.com", 2.0, 50), DealerRating("autoscout24.de", 4.36, 13, 84)))

    @Test
    fun `a dealer's stars on the offer's own market count where the card has none`() {
        val v = van(Seller(type = SellerType.BUSINESS, url = page))
        assertEquals(4.36 to 13, FitScore.marketStars(v) { if (it == page) dealer else null })
        assertNull(FitScore.marketStars(van(Seller(type = SellerType.BUSINESS, url = page))) { null })
        assertNull(FitScore.marketStars(van(Seller(type = SellerType.PRIVATE, url = page))) { if (it == page) dealer else null })
        assertEquals(3.0 to 7, FitScore.marketStars(van(Seller(type = SellerType.BUSINESS, url = page, rating = 3.0, reviewCount = 7))) { dealer })
    }

    @Test
    fun `Neuland's answer reads as written and says itself in a few words`() {
        val read = Json { ignoreUnknownKeys = true }.decodeFromString(
            Dealer.serializer(),
            """{"market":"autoscout24.de","name":"Autozentrum Tübingen","name_add_on":null,"ratings":[{"source":"autoscout24.de","stars":4.36,"count":13,"recommend_percent":84,"reviews":[]}],"stock":{"count":21,"shown":20,"cheapest":{"cents":399000,"currency":"EUR"},"dearest":{"cents":2999000,"currency":"EUR"}},"extra":1}""",
        )
        assertEquals("4.4★ from 13 reviews, 84 % recommend", read.ratings.single().summary())
        assertEquals("21 offers on AutoScout24, 3.990 € to 29.990 € among the 20 shown", read.stockLine())
        assertEquals("no reviews", DealerRating("mobile.de", null, 0).summary())
        val hours = DealerHours(days = List(5) { listOf("08:30-18:00") } + listOf(listOf("09:00-16:00"), emptyList()))
        assertEquals("Mon to Fri 08:30-18:00, Sat 09:00-16:00", hours.text())
        assertEquals("1 offer on AutoScout24", Dealer("autoscout24.de", stock = DealerStock(count = 1)).stockLine())
        assertEquals("2 offers on mobile.de, 5 € to 7 €", Dealer("mobile.de", stock = DealerStock(2, 2, DealerMoney(500, "EUR"), DealerMoney(700, "EUR"))).stockLine())
    }
}
