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

    @Test
    fun `a new car with a registration date is not new`() {
        // willhaben: "VW ID.4 GTX 4MOTION", condition new, registered 09/2026, 560 km.
        assertEquals(VehicleCondition.PRE_REGISTRATION, NeulandOffers.drivenOrNew(560, registered = true))
        assertEquals(VehicleCondition.DEMO, NeulandOffers.drivenOrNew(8_000, registered = true))
        assertEquals(VehicleCondition.USED, NeulandOffers.drivenOrNew(30_000, registered = true))
        assertEquals(VehicleCondition.NEW, NeulandOffers.drivenOrNew(10, registered = false))
    }

    @Test
    fun `the title's kW wins over a power field holding the battery`() {
        val typed = io.github.tieo.arbay.model.VehicleInfo(powerKw = 77, verified = setOf(io.github.tieo.arbay.model.VehicleField.POWER))
        assertEquals(250, NeulandOffers.withTitlePower("VW ID.4 GTX 4MOTION 250 kW Business -77 kWh", typed)?.powerKw)
        assertEquals(77, NeulandOffers.withTitlePower("VW Polo 77 kW", typed)?.powerKw)
        // The battery written as kW, the power field right.
        val right = io.github.tieo.arbay.model.VehicleInfo(powerKw = 210, verified = setOf(io.github.tieo.arbay.model.VehicleField.POWER))
        assertEquals(210, NeulandOffers.withTitlePower("Volkswagen ID.4 Pro ID.4 77KW Wärmepumpe, ab Lager", right)?.powerKw)
        // The field holds the battery and the title names no power: unknown.
        assertEquals(null, NeulandOffers.withTitlePower("VW ID.4 Pro 77 kWh", typed)?.powerKw)
    }
}
