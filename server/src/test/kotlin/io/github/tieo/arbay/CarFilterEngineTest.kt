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
    fun dropsPartsListingByTitleWithNoVehicleSignal() {
        // eBay "GEBRAUCHTER MOTOR ENGINE ... CRAFTER" — the title names a part, no car signal.
        val engine = carListing("p1", PlatformId.EBAY_DE, "GEBRAUCHTER MOTOR ENGINE VW Crafter 2.5 TDI", vehicle = VehicleInfo())
        assertTrue(CarFilterEngine.apply(listOf(engine), CarFilters()).isEmpty())
    }

    @Test
    fun dropsPartWithPartTitleAndBareYear() {
        // A year in the title is not a car signal; the part word still drops it.
        val bumper = carListing("b", PlatformId.EBAY_DE, "Frontstoßstange Stoßstange MAN TGE 2023",
            priceCents = 27_400, vehicle = VehicleInfo(firstRegYear = 2023))
        assertTrue(CarFilterEngine.apply(listOf(bumper), CarFilters()).isEmpty())
    }

    @Test
    fun keepsCheapCarWithNoSignalAndNoPartWord() {
        // No price floor: a cheap/broken car with no parsed specs and no part word survives.
        val cheap = carListing("cheap", PlatformId.KLEINANZEIGEN, "VW Crafter Bastlerfahrzeug",
            priceCents = 50_000, vehicle = VehicleInfo())
        assertEquals(1, CarFilterEngine.apply(listOf(cheap), CarFilters()).size)
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

    private fun carListing(
        id: String, platform: PlatformId, title: String, description: String? = null,
        priceCents: Long = 1_800_000, vehicle: VehicleInfo? = null,
    ) = Listing(
        id = "$platform:$id", platformId = platform, externalId = id, url = "u",
        title = title, price = Money(priceCents, Currency.EUR), description = description,
        scrapedAt = Clock.System.now(), vehicle = vehicle ?: verified(mileageKm = 100_000),
    )

    @Test
    fun dropsRentalAdOnGeneralPlatform() {
        // A van offered to rent carries real specs but is not a car for sale.
        val rental = carListing("r", PlatformId.KLEINANZEIGEN, "Transporter mieten VW Crafter Langzeitmiete")
        assertTrue(CarFilterEngine.apply(listOf(rental), CarFilters()).isEmpty())
    }

    @Test
    fun keepsRentalWordOnCarOnlyPlatform() {
        // Car-only platforms are sale-only; don't apply the general-platform guards there.
        val car = carListing("c", PlatformId.MOBILE_DE, "VW Crafter — auch zur Miete gedacht gewesen")
        assertEquals(1, CarFilterEngine.apply(listOf(car), CarFilters()).size)
    }

    @Test
    fun vanCodeExcludesOnExplicitMismatch() {
        val filters = CarFilters(vanLengths = setOf(3), vanHeights = setOf(2))
        val match = carListing("m", PlatformId.KLEINANZEIGEN, "VW Crafter L3H2 Kastenwagen")
        val wrong = carListing("w", PlatformId.KLEINANZEIGEN, "VW Crafter L1H1 kurz")
        val kept = CarFilterEngine.apply(listOf(match, wrong), filters)
        assertEquals(listOf("KLEINANZEIGEN:m"), kept.map { it.id })
    }

    @Test
    fun vanCodeSoftPassesWhenNoCodeStated() {
        // A van that fits but never states its size must not be dropped.
        val filters = CarFilters(vanHeights = setOf(2))
        val noCode = carListing("n", PlatformId.KLEINANZEIGEN, "VW Crafter Kastenwagen 2.0 TDI")
        assertEquals(1, CarFilterEngine.apply(listOf(noCode), filters).size)
    }

    @Test
    fun vanRoofWordNeverExcludes() {
        // "Hochdach" is model-specific — it may fill the display but must never drop a listing.
        val filters = CarFilters(vanHeights = setOf(1))
        val hochdach = carListing("h", PlatformId.KLEINANZEIGEN, "VW Crafter Hochdach langer Radstand")
        assertEquals(1, CarFilterEngine.apply(listOf(hochdach), filters).size)
    }

    private fun verified(
        firstRegYear: Int? = null, mileageKm: Int? = null,
        powerKw: Int? = null, gearbox: Transmission? = null,
    ) = io.github.tieo.arbay.crawler.VehicleTextParser.verifiedByPresence(
        VehicleInfo(firstRegYear = firstRegYear, mileageKm = mileageKm, powerKw = powerKw, gearbox = gearbox),
    )
}
