package io.github.tieo.arbay

import io.github.tieo.arbay.judging.KleinanzeigenCosts
import io.github.tieo.arbay.model.BuyerProtection
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.ShippingOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Kleinanzeigen's own shipping list and door prices as it stated them on 2026-10-08 (Neuland reads them). */
class KleinanzeigenCostsTest {
    private val options = listOf(
        KleinanzeigenCosts.Option("HERMES_001", "Päckchen", "HERMES", "Hermes", "SMALL", 99, fromPrice = true),
        KleinanzeigenCosts.Option("HERMES_002", "S-Paket", "HERMES", "Hermes", "SMALL", 199, fromPrice = true),
        KleinanzeigenCosts.Option("HERMES_003", "M-Paket", "HERMES", "Hermes", "MEDIUM", 299, fromPrice = true),
        KleinanzeigenCosts.Option("DHL_001", "Paket 2 kg", "DHL", "DHL", "SMALL", 619),
        KleinanzeigenCosts.Option("DHL_002", "Paket 5 kg", "DHL", "DHL", "MEDIUM", 769),
    )
    private val door = listOf(
        KleinanzeigenCosts.DoorPrice("Hermes", "Päckchen", 495),
        KleinanzeigenCosts.DoorPrice("Hermes", "S-Paket", 645),
    )

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
