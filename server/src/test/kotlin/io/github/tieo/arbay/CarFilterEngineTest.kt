package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarFilterEngine
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleInfo
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarFilterEngineTest {

    private fun listing(
        id: String,
        platform: PlatformId,
        priceCents: Long,
        vehicle: VehicleInfo? = null,
    ) = Listing(
        id = "$platform:$id", platformId = platform, externalId = id,
        url = "https://x/$id", title = "VW Crafter", price = Money(priceCents, Currency.EUR),
        scrapedAt = Clock.System.now(), vehicle = vehicle,
    )

    @Test
    fun dropsPartsListingWithNoVehicleSignalOnGeneralPlatform() {
        val parts = listing("p1", PlatformId.KLEINANZEIGEN, 100, vehicle = null) // €1, no signal
        val kept = CarFilterEngine.apply(listOf(parts), CarFilters())
        assertTrue(kept.isEmpty())
    }

    @Test
    fun keepsRealCarWithSignalOnGeneralPlatform() {
        val car = listing("c1", PlatformId.KLEINANZEIGEN, 1_800_000, vehicle = VehicleInfo(firstRegYear = 2022, mileageKm = 90_000))
        val kept = CarFilterEngine.apply(listOf(car), CarFilters())
        assertEquals(1, kept.size)
    }

    @Test
    fun neverDropsCarOnlyPlatformForMissingSignal() {
        // AutoScout24 result with no parsed signal must survive (every result there is a car).
        val car = listing("a1", PlatformId.AUTOSCOUT24, 100, vehicle = null)
        assertEquals(1, CarFilterEngine.apply(listOf(car), CarFilters()).size)
    }

    @Test
    fun enforcesMileageWhenDataPresent() {
        val filters = CarFilters(maxMileageKm = 200_000)
        val over = listing("o", PlatformId.KLEINANZEIGEN, 1_500_000, VehicleInfo(firstRegYear = 2018, mileageKm = 260_000))
        val under = listing("u", PlatformId.KLEINANZEIGEN, 1_500_000, VehicleInfo(firstRegYear = 2018, mileageKm = 150_000))
        val kept = CarFilterEngine.apply(listOf(over, under), filters)
        assertEquals(listOf("KLEINANZEIGEN:u"), kept.map { it.id })
    }

    @Test
    fun enforcesPowerAndYearAndGearbox() {
        val filters = CarFilters(firstRegFromYear = 2021, firstRegToYear = 2023, minPowerKw = 110, transmission = Transmission.AUTOMATIC)
        val good = listing("g", PlatformId.MOBILE_DE, 1_800_000, VehicleInfo(firstRegYear = 2022, powerKw = 130, gearbox = Transmission.AUTOMATIC))
        val tooOld = listing("old", PlatformId.MOBILE_DE, 1_800_000, VehicleInfo(firstRegYear = 2019, powerKw = 130, gearbox = Transmission.AUTOMATIC))
        val tooWeak = listing("weak", PlatformId.MOBILE_DE, 1_800_000, VehicleInfo(firstRegYear = 2022, powerKw = 90, gearbox = Transmission.AUTOMATIC))
        val manual = listing("man", PlatformId.MOBILE_DE, 1_800_000, VehicleInfo(firstRegYear = 2022, powerKw = 130, gearbox = Transmission.MANUAL))
        val kept = CarFilterEngine.apply(listOf(good, tooOld, tooWeak, manual), filters)
        assertEquals(listOf("MOBILE_DE:g"), kept.map { it.id })
    }

    @Test
    fun softPassesWhenValueUnknown() {
        // Mileage filter set, but this listing has no mileage parsed — keep it (unknown != excluded).
        val filters = CarFilters(maxMileageKm = 200_000)
        val car = listing("s", PlatformId.MOBILE_DE, 1_800_000, VehicleInfo(firstRegYear = 2022, powerKw = 130))
        assertEquals(1, CarFilterEngine.apply(listOf(car), filters).size)
    }
}
