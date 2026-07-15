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
    fun dropsPartWithOnlyBareYearSignal() {
        // eBay "Frontstoßstange ... MAN TGE 2023" — a year parsed from the title is not a car signal.
        val bumper = listing("b", PlatformId.EBAY_DE, 27_400, VehicleInfo(firstRegYear = 2023))
        assertTrue(CarFilterEngine.apply(listOf(bumper), CarFilters()).isEmpty())
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
    fun enforcesMileageWhenVerified() {
        val filters = CarFilters(maxMileageKm = 200_000)
        val over = listing("o", PlatformId.KLEINANZEIGEN, 1_500_000, verified(firstRegYear = 2018, mileageKm = 260_000))
        val under = listing("u", PlatformId.KLEINANZEIGEN, 1_500_000, verified(firstRegYear = 2018, mileageKm = 150_000))
        val kept = CarFilterEngine.apply(listOf(over, under), filters)
        assertEquals(listOf("KLEINANZEIGEN:u"), kept.map { it.id })
    }

    @Test
    fun enforcesPowerAndYearAndGearboxWhenVerified() {
        val filters = CarFilters(firstRegFromYear = 2021, firstRegToYear = 2023, minPowerKw = 110, transmission = Transmission.AUTOMATIC)
        val good = listing("g", PlatformId.MOBILE_DE, 1_800_000, verified(firstRegYear = 2022, powerKw = 130, gearbox = Transmission.AUTOMATIC))
        val tooOld = listing("old", PlatformId.MOBILE_DE, 1_800_000, verified(firstRegYear = 2019, powerKw = 130, gearbox = Transmission.AUTOMATIC))
        val tooWeak = listing("weak", PlatformId.MOBILE_DE, 1_800_000, verified(firstRegYear = 2022, powerKw = 90, gearbox = Transmission.AUTOMATIC))
        val manual = listing("man", PlatformId.MOBILE_DE, 1_800_000, verified(firstRegYear = 2022, powerKw = 130, gearbox = Transmission.MANUAL))
        val kept = CarFilterEngine.apply(listOf(good, tooOld, tooWeak, manual), filters)
        assertEquals(listOf("MOBILE_DE:g"), kept.map { it.id })
    }

    @Test
    fun neverExcludesOnInferredValue() {
        // A text-inferred (unverified) power that fails the filter must NOT drop the listing —
        // a misread number would silently discard a car that actually fits.
        val filters = CarFilters(minPowerKw = 110)
        val inferred = listing("i", PlatformId.KLEINANZEIGEN, 1_800_000,
            VehicleInfo(mileageKm = 100_000, powerKw = 90)) // powerKw present but NOT verified
        assertEquals(1, CarFilterEngine.apply(listOf(inferred), filters).size)
    }

    @Test
    fun softPassesWhenValueUnknown() {
        val filters = CarFilters(maxMileageKm = 200_000)
        val car = listing("s", PlatformId.MOBILE_DE, 1_800_000, verified(firstRegYear = 2022, powerKw = 130))
        assertEquals(1, CarFilterEngine.apply(listOf(car), filters).size)
    }

    @Test
    fun findInDescriptionExcludesLiterally() {
        val filters = CarFilters(descriptionContains = "camper")
        val camper = Listing(id = "K:c", platformId = PlatformId.KLEINANZEIGEN, externalId = "c",
            url = "u", title = "VW Crafter", price = Money(1_800_000, Currency.EUR),
            description = "liebevoll zum Camper ausgebaut", scrapedAt = Clock.System.now(),
            vehicle = verified(mileageKm = 100_000))
        val plain = Listing(id = "K:p", platformId = PlatformId.KLEINANZEIGEN, externalId = "p",
            url = "u", title = "VW Crafter Kasten", price = Money(1_800_000, Currency.EUR),
            description = "Handwerkerfahrzeug", scrapedAt = Clock.System.now(),
            vehicle = verified(mileageKm = 100_000))
        assertEquals(listOf("K:c"), CarFilterEngine.apply(listOf(camper, plain), filters).map { it.id })
    }

    @Test
    fun facetCountsMarginalRemovalPerFilter() {
        // Filter: ≥110 kW. Three verified cars: 130 (passes), 90 and 75 (fail power).
        val filters = CarFilters(minPowerKw = 110)
        val ls = listOf(
            listing("a", PlatformId.MOBILE_DE, 1_800_000, verified(mileageKm = 100_000, powerKw = 130)),
            listing("b", PlatformId.MOBILE_DE, 1_800_000, verified(mileageKm = 100_000, powerKw = 90)),
            listing("c", PlatformId.MOBILE_DE, 1_800_000, verified(mileageKm = 100_000, powerKw = 75)),
        )
        assertEquals(mapOf("power" to 2), CarFilterEngine.facetRemoved(ls, filters))
    }

    @Test
    fun facetIgnoresInferredValues() {
        // Inferred power below the floor must not count — it can't exclude, so it can't "remove".
        val filters = CarFilters(minPowerKw = 110)
        val inferred = listing("i", PlatformId.KLEINANZEIGEN, 1_800_000, VehicleInfo(mileageKm = 100_000, powerKw = 80))
        assertTrue(CarFilterEngine.facetRemoved(listOf(inferred), filters).isEmpty())
    }

    private fun verified(
        firstRegYear: Int? = null, mileageKm: Int? = null,
        powerKw: Int? = null, gearbox: Transmission? = null,
    ) = io.github.tieo.arbay.crawler.VehicleTextParser.verifiedByPresence(
        VehicleInfo(firstRegYear = firstRegYear, mileageKm = mileageKm, powerKw = powerKw, gearbox = gearbox),
    )
}
