package io.github.tieo.arbay

import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.directPrice
import io.github.tieo.arbay.model.doorDeliveryAssumed
import io.github.tieo.arbay.model.offerWithin
import io.github.tieo.arbay.model.shippingCostEur
import io.github.tieo.arbay.model.buyerProtection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShippingCostTest {
    private val ka = PlatformId.KLEINANZEIGEN
    private val hermesFrom = Shipping(cost = Money.cents(99))

    @Test
    fun parcelShopPriceCountsAsDoorDeliveryOnKleinanzeigen() {
        assertEquals(6.19, shippingCostEur(ka, hermesFrom, toDoor = true))
        assertEquals(0.99, shippingCostEur(ka, hermesFrom, toDoor = false))
    }

    @Test
    fun dearerStatedShippingStands() {
        assertEquals(8.5, shippingCostEur(ka, Shipping(cost = Money.cents(850)), toDoor = true))
    }

    @Test
    fun unstatedShippingIsPricedToTheDoor() {
        assertEquals(6.19, shippingCostEur(ka, null, toDoor = true))
        assertTrue(doorDeliveryAssumed(ka, null, toDoor = true))
        assertFalse(doorDeliveryAssumed(ka, hermesFrom, toDoor = true))
    }

    @Test
    fun pickupAndFreeCostNothing() {
        assertEquals(0.0, shippingCostEur(ka, Shipping(pickup = true, available = false), toDoor = true))
        assertEquals(0.0, shippingCostEur(ka, Shipping(free = true), toDoor = true))
    }

    @Test
    fun otherMarketsKeepWhatTheAdStates() {
        assertNull(shippingCostEur(PlatformId.EBAY_DE, Shipping(), toDoor = true))
        assertEquals(0.0, shippingCostEur(PlatformId.EBAY_DE, null, toDoor = true))
    }

    @Test
    fun fourHundredFiftyAllInToTheDoor() {
        val shipping = shippingCostEur(ka, hermesFrom, toDoor = true)!!
        val offer = offerWithin(450.0, shipping, ka.buyerProtection)
        assertEquals(424, offer)
        assertEquals(449, directPrice(offer, shipping, ka.buyerProtection))
    }
}
