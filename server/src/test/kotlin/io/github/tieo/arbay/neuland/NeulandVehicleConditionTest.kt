package io.github.tieo.arbay.neuland

import io.github.tieo.arbay.model.VehicleCondition
import kotlin.test.Test
import kotlin.test.assertEquals

class NeulandVehicleConditionTest {
    @Test
    fun `a van the site calls new is used once its mileage says it was driven`() {
        // Marktplaats, Dutchvans.com: "Volkswagen Crafter 2.0TDi Bestelbus 2023 L3 H3", condition "new", 132,263 km.
        assertEquals(VehicleCondition.USED, NeulandOffers.drivenOrNew(132_263))
        assertEquals(VehicleCondition.NEW, NeulandOffers.drivenOrNew(15))
        assertEquals(VehicleCondition.NEW, NeulandOffers.drivenOrNew(null))
        // A demonstrator's mileage: new or demo is not known.
        assertEquals(null, NeulandOffers.drivenOrNew(8_000))
    }
}
