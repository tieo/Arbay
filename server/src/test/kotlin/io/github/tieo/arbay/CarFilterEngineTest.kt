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
    fun keepsCarThatMentionsAFeatureWithNoParsedSpecs() {
        // A real car whose specs are only in the description (none parsed) must NOT be dropped
        // just because the title names a feature. Category-constrained car searches already
        // exclude actual parts at the source, so no keyword parts guard runs here.
        val withHeater = carListing("h", PlatformId.KLEINANZEIGEN, "VW Crafter mit Standheizung und Klima",
            priceCents = 900_000, vehicle = VehicleInfo())
        assertEquals(1, CarFilterEngine.apply(listOf(withHeater), CarFilters()).size)
    }

    @Test
    fun keepsCheapBrokenCarWithNoSignal() {
        // No price floor: a cheap/broken car with no parsed specs survives.
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
    fun dropsEbayPartMiscategorisedAsVehicle() {
        // eBay's price-ascending sort floats up a seller-miscategorised part; drop it structurally.
        val part = carListing("p", PlatformId.EBAY_DE,
            "Drehkonsole DC Sprinter 906 VW Crafter ab 06 bis 2016 Sitzkonsole Beifahrer",
            priceCents = 20_000, vehicle = VehicleInfo())
        assertEquals(0, CarFilterEngine.apply(listOf(part), CarFilters()).size)
    }

    @Test
    fun dropsKleinanzeigenAccessory() {
        val cover = carListing("sb", PlatformId.KLEINANZEIGEN, "VW Crafter Sitzbezug Schonbezug",
            priceCents = 4_000, vehicle = VehicleInfo())
        assertEquals(0, CarFilterEngine.apply(listOf(cover), CarFilters()).size)
    }

    @Test
    fun keepsCarNamingAReplacedPart() {
        // "Zahnriemen neu" is a selling point on a real car, not a part listing — must survive.
        val car = carListing("z", PlatformId.EBAY_DE, "VW Crafter 2.0 TDI Zahnriemen neu Bremsen neu",
            priceCents = 850_000, vehicle = VehicleInfo())
        assertEquals(1, CarFilterEngine.apply(listOf(car), CarFilters()).size)
    }

    @Test
    fun partGuardExemptsListingWithVerifiedSpecs() {
        // A structured vehicle record is a real car even if its title contains a part word.
        val car = carListing("ex", PlatformId.EBAY_DE, "VW Crafter mit Dachträger Hochdach",
            priceCents = 1_500_000, vehicle = verified(firstRegYear = 2020, mileageKm = 80_000))
        assertEquals(1, CarFilterEngine.apply(listOf(car), CarFilters()).size)
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
    fun vanSoftPassesWhenNoSizeStated() {
        // A van that states no size at all (no code, no word) must not be dropped.
        val filters = CarFilters(vanHeights = setOf(2))
        val noSize = carListing("n", PlatformId.KLEINANZEIGEN, "VW Crafter Kastenwagen 2.0 TDI")
        assertEquals(1, CarFilterEngine.apply(listOf(noSize), filters).size)
    }

    @Test
    fun vanWordExcludesOnMismatch() {
        // The reported bug: filtering L3 must drop a van that says "Maxi" (L4), and keep an L3 one.
        val filters = CarFilters(vanLengths = setOf(3))
        val maxi = carListing("x", PlatformId.KLEINANZEIGEN, "VW Crafter Maxi 7 Meter")       // L4 → drop
        val lang = carListing("l", PlatformId.KLEINANZEIGEN, "VW Crafter lang Hochdach")       // L3 → keep
        val kurz = carListing("k", PlatformId.KLEINANZEIGEN, "VW Crafter kompakt kurz")        // L1 → drop
        val kept = CarFilterEngine.apply(listOf(maxi, lang, kurz), filters).map { it.id }
        assertEquals(listOf("KLEINANZEIGEN:l"), kept)
    }

    @Test
    fun vanHeightWordExcludes() {
        // Filtering H1 (flat roof) drops a "Hochdach" (H2) van — a stated roof is a known size.
        val filters = CarFilters(vanHeights = setOf(1))
        val hochdach = carListing("h", PlatformId.KLEINANZEIGEN, "VW Crafter Hochdach lang")
        val flach = carListing("f", PlatformId.KLEINANZEIGEN, "VW Crafter Flachdach kurz")
        val kept = CarFilterEngine.apply(listOf(hochdach, flach), filters).map { it.id }
        assertEquals(listOf("KLEINANZEIGEN:f"), kept)
    }

    @Test
    fun facetCountsReportWhatEachFilterHides() {
        // 3 cars: one matches all, others fail one dimension each.
        val f = CarFilters(maxPriceEur = 20000, minPowerKw = 110)
        val match = listing("m", PlatformId.MOBILE_DE, 1_800_000, verified(mileageKm = 100_000, powerKw = 130))
        val tooDear = listing("d", PlatformId.MOBILE_DE, 2_500_000, verified(mileageKm = 100_000, powerKw = 130))
        val tooWeak = listing("w", PlatformId.MOBILE_DE, 1_800_000, verified(mileageKm = 100_000, powerKw = 90))
        val facets = CarFilterEngine.facetCounts(listOf(match, tooDear, tooWeak), f)
        assertEquals(1, facets["price"])   // dropping price adds the €25k car
        assertEquals(1, facets["power"])   // dropping power adds the 90 kW car
        assertEquals(null, facets["year"]) // year not an active filter
    }

    private fun verified(
        firstRegYear: Int? = null, mileageKm: Int? = null,
        powerKw: Int? = null, gearbox: Transmission? = null,
    ) = io.github.tieo.arbay.crawler.VehicleTextParser.verifiedByPresence(
        VehicleInfo(firstRegYear = firstRegYear, mileageKm = mileageKm, powerKw = powerKw, gearbox = gearbox),
    )
}
