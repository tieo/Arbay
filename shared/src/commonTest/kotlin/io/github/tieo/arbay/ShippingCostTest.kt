package io.github.tieo.arbay

import io.github.tieo.arbay.model.BuyerProtection
import io.github.tieo.arbay.model.ChatCosts
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.doorDeliveryUsed
import io.github.tieo.arbay.model.offerFor
import io.github.tieo.arbay.model.shippingCostEur
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The rule only; the prices come from the market and are made up here. */
class ShippingCostTest {
    private val ka = PlatformId.KLEINANZEIGEN
    private val parcelShop = Shipping(cost = Money.cents(99), doorCost = Money.cents(495), doorBy = "Hermes Päckchen")
    private val fee = ChatCosts(protection = BuyerProtection(fixedEur = 0.5, share = 0.045))

    @Test
    fun doorDeliveryReplacesTheParcelShopPrice() {
        assertEquals(4.95, shippingCostEur(parcelShop, toDoor = true))
        assertEquals(0.99, shippingCostEur(parcelShop, toDoor = false))
        assertEquals("Hermes Päckchen to the door, 4,95 € instead of 0,99 € to a parcel shop", doorDeliveryUsed(parcelShop, toDoor = true))
    }

    @Test
    fun aPriceWithoutDoorDeliveryStands() {
        assertEquals(8.5, shippingCostEur(Shipping(cost = Money.cents(850)), toDoor = true))
        assertNull(doorDeliveryUsed(Shipping(cost = Money.cents(850)), toDoor = true))
    }

    @Test
    fun pickupAndFreeCostNothingAndUnknownStaysUnknown() {
        assertEquals(0.0, shippingCostEur(Shipping(pickup = true, available = false), toDoor = true))
        assertEquals(0.0, shippingCostEur(Shipping(free = true), toDoor = true))
        assertNull(shippingCostEur(Shipping(), toDoor = true))
        assertEquals(0.0, shippingCostEur(null, toDoor = true))
    }

    @Test
    fun offerStaysWithinTheLimitAndNeverAboveTheAskingPrice() {
        val offer = offerFor(ka, 600, parcelShop, 450.0, toDoor = true, fee)
        // 450 - 4.95 - 0.50 = 444.55, / 1.045 = 425.4
        assertEquals(425, offer.price)
        assertEquals(449, offer.direct)
        assertEquals(400, offerFor(ka, 400, parcelShop, 450.0, toDoor = true, fee).price)
    }

    @Test
    fun directTotalStaysWithinTheAskPlusShipping() {
        // Asks 350, ships for 6.19: the direct total would be 372, but the phone itself may not go above 350.
        val parcel = Shipping(cost = Money.cents(619))
        val offer = offerFor(ka, 350, parcel, 450.0, toDoor = false, fee)
        assertEquals(350, offer.price)
        assertEquals(356, offer.direct)
    }

    @Test
    fun noPriceWithoutTheFee() {
        val offer = offerFor(ka, 600, parcelShop, 450.0, toDoor = true, ChatCosts())
        assertNull(offer.price)
        assertEquals("the Sicher bezahlen fee could not be read from Kleinanzeigen", offer.note)
    }

    @Test
    fun pickupIsPaidInPersonWithoutFee() {
        val offer = offerFor(ka, 600, Shipping(pickup = true, available = false), 450.0, toDoor = true, ChatCosts())
        assertEquals(450, offer.price)
        assertEquals("pickup, paid in person", offer.note)
    }
}
