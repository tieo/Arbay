package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.KleinanzeigenCosts
import io.github.tieo.arbay.model.BuyerProtection
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.ShippingOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Kleinanzeigen's own shipping list and help articles, as saved on 2026-10-08. */
class KleinanzeigenCostsTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private val options = KleinanzeigenCosts.parseOptions(fixture("kleinanzeigen_shipping_options.json"))
    private val door = KleinanzeigenCosts.parseDoorPrices(fixture("kleinanzeigen_help_delivery.json"))

    @Test
    fun readsTheShippingList() {
        val paeckchen = options.first { it.id == "HERMES_001" }
        assertEquals(99, paeckchen.priceInEuroCent)
        assertEquals(true, paeckchen.fromPrice)
        assertEquals(false, options.first { it.id == "DHL_001" }.fromPrice)
    }

    @Test
    fun readsHermesDoorPricesUnderTheirHeader() {
        // Päckchen: 3,70 to a parcel shop; to the door 4,95 (label in a shop) or 4,89 (online).
        assertEquals(KleinanzeigenCosts.DoorPrice("Hermes", "Päckchen", 495), door.first { it.product == "Päckchen" })
        assertEquals(645, door.first { it.product == "S-Paket" }.cents)
    }

    @Test
    fun aParcelShopFromPriceGetsItsPackagesDoorPrice() {
        assertEquals("Hermes Päckchen" to 495L, KleinanzeigenCosts.doorDelivery(Money.cents(99), options, door))
        assertEquals("Hermes S-Paket" to 645L, KleinanzeigenCosts.doorDelivery(Money.cents(199), options, door))
    }

    @Test
    fun withoutTheArticleTheSameSizesFixedPriceCounts() {
        assertEquals("DHL Paket 2 kg" to 619L, KleinanzeigenCosts.doorDelivery(Money.cents(99), options, emptyList()))
    }

    @Test
    fun aSellersOwnPriceIsNoParcelShopPrice() {
        assertNull(KleinanzeigenCosts.doorDelivery(Money.cents(500), options, door))
        assertNull(KleinanzeigenCosts.doorDelivery(Money.cents(619), options, door))
    }

    @Test
    fun readsTheSicherBezahlenFee() {
        assertEquals(BuyerProtection(fixedEur = 0.5, share = 0.045), KleinanzeigenCosts.parseProtection(fixture("kleinanzeigen_help_sicher_bezahlen.json")))
    }

    @Test
    fun theAdsOwnListAtPaymentSetsTheDoorPrice() {
        // As the ad's list answered on 2026-10-11: every way goes to the door, the Päckchen at 5,19.
        val live = listOf(ShippingOption("Hermes Päckchen", 5.19), ShippingOption("Hermes S-Paket", 5.79), ShippingOption("DHL Paket 2 kg", 6.19))
        val shipping = kotlinx.coroutines.runBlocking {
            KleinanzeigenCosts.withDoorDelivery(Shipping(cost = Money.cents(99), available = true)) { live }
        }
        assertEquals(Money.cents(519), shipping?.doorCost)
        assertEquals("Hermes Päckchen", shipping?.doorBy)
    }
}
