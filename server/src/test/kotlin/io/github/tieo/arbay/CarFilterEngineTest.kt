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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
        // A real car whose specs are only in the description (none parsed) must not be dropped
        // just because the title names a feature: the part guard matches only part nouns, never
        // feature words like "Standheizung" or "Klima".
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
        // "Zahnriemen neu" is a selling point on a real car, not a part listing; must survive.
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
    fun textSpecsExcludeByDefault() {
        // A stated-but-unverified (text-read) value that fails the filter DOES drop the listing:
        // a ≤200k km filter must not show a stated 345.000 km van.
        val over = listing("o", PlatformId.EBAY_DE, 1_000_000,
            VehicleInfo(mileageKm = 345_000)) // present, not verified (text-read)
        assertEquals(0, CarFilterEngine.apply(listOf(over), CarFilters(maxMileageKm = 200_000)).size)
    }

    @Test
    fun strictUnknownDropsMissingSpec() {
        // Strict mode: a listing whose filtered spec is unknown is excluded.
        val noPower = listing("n", PlatformId.EBAY_DE, 1_000_000, VehicleInfo(mileageKm = 100_000))
        assertEquals(0, CarFilterEngine.apply(listOf(noPower), CarFilters(minPowerKw = 110, strictUnknown = true)).size)
        // Default (lenient): the same unknown-spec listing is kept.
        assertEquals(1, CarFilterEngine.apply(listOf(noPower), CarFilters(minPowerKw = 110)).size)
    }

    @Test
    fun textSpecInRangeIsKept() {
        val ok = listing("k", PlatformId.EBAY_DE, 1_000_000, VehicleInfo(mileageKm = 150_000))
        assertEquals(1, CarFilterEngine.apply(listOf(ok), CarFilters(maxMileageKm = 200_000)).size)
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
        val car = carListing("c", PlatformId.MOBILE_DE, "VW Crafter, auch zur Miete gedacht gewesen")
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
        // Filtering L3 must drop a van that says "Maxi" (L4) and keep one that says "lang" (L3).
        val filters = CarFilters(vanLengths = setOf(3))
        val maxi = carListing("x", PlatformId.KLEINANZEIGEN, "VW Crafter Maxi 7 Meter")       // L4, drop
        val lang = carListing("l", PlatformId.KLEINANZEIGEN, "VW Crafter lang Hochdach")       // L3, keep
        val kurz = carListing("k", PlatformId.KLEINANZEIGEN, "VW Crafter kompakt kurz")        // L1, drop
        val kept = CarFilterEngine.apply(listOf(maxi, lang, kurz), filters).map { it.id }
        assertEquals(listOf("KLEINANZEIGEN:l"), kept)
    }

    @Test
    fun vanHeightWordExcludes() {
        // Filtering H1 (flat roof) drops a "Hochdach" (H2) van; a stated roof is a known size.
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

    @Test
    fun verifiedSpecExcludes() {
        // A verified (structured) out-of-range value drops the listing.
        val over = listing("v", PlatformId.MOBILE_DE, 1_500_000, verified(mileageKm = 300_000))
        assertEquals(0, CarFilterEngine.apply(listOf(over), CarFilters(maxMileageKm = 200_000)).size)
    }

    @Test
    fun statedPassingSpecKeptUnderStrict() {
        // A text-read spec counts as known, so strict mode keeps a listing whose stated value passes.
        val stated = listing("s2", PlatformId.EBAY_DE, 1_000_000, VehicleInfo(mileageKm = 150_000))
        val filters = CarFilters(maxMileageKm = 200_000, strictUnknown = true)
        assertEquals(1, CarFilterEngine.apply(listOf(stated), filters).size)
    }

    @Test
    fun strictUnknownKeepsListingWithKnownPassingSpec() {
        // Strict mode only punishes unknown specs; a known in-range value still passes.
        val ok = listing("k2", PlatformId.MOBILE_DE, 1_500_000, verified(powerKw = 130))
        assertEquals(1, CarFilterEngine.apply(listOf(ok), CarFilters(minPowerKw = 110, strictUnknown = true)).size)
    }

    @Test
    fun strictUnknownDropsListingWithNoVehicleRecord() {
        // A null vehicle record means every spec is unknown; strict mode drops the listing.
        val bare = listing("b", PlatformId.AUTOSCOUT24, 1_500_000, vehicle = null)
        assertEquals(0, CarFilterEngine.apply(listOf(bare), CarFilters(minPowerKw = 110, strictUnknown = true)).size)
    }

    @Test
    fun dropsWantedAdOnGeneralPlatform() {
        // A wanted ad is a buyer, not a car for sale; it drops even with verified specs attached.
        val wanted = carListing("wa", PlatformId.KLEINANZEIGEN, "Suche VW Crafter bis 10000 Euro")
        assertTrue(CarFilterEngine.apply(listOf(wanted), CarFilters()).isEmpty())
    }

    @Test
    fun dropsDutchPartOnMarktplaats() {
        val part = carListing("nl", PlatformId.MARKTPLAATS, "Koplamp VW Crafter links origineel",
            priceCents = 8_000, vehicle = VehicleInfo())
        assertEquals(0, CarFilterEngine.apply(listOf(part), CarFilters()).size)
    }

    @Test
    fun keepsPartWordOnCarOnlyPlatform() {
        // The non-vehicle guards apply only to general classifieds; a car-only platform listing
        // survives a part word in its title even without any parsed specs.
        val car = carListing("co", PlatformId.MOBILE_DE, "VW Crafter neue Scheinwerfer", vehicle = VehicleInfo())
        assertEquals(1, CarFilterEngine.apply(listOf(car), CarFilters()).size)
    }

    @Test
    fun vanDimsExcludeWrongSize() {
        // A stated van size (L1) excludes when the filter wants a different one (L3).
        val filters = CarFilters(vanLengths = setOf(3))
        val wrong = carListing("w2", PlatformId.KLEINANZEIGEN, "VW Crafter L1H1 kurz")
        assertEquals(0, CarFilterEngine.apply(listOf(wrong), filters).size)
    }

    private fun verified(
        firstRegYear: Int? = null, mileageKm: Int? = null,
        powerKw: Int? = null, gearbox: Transmission? = null,
    ) = io.github.tieo.arbay.crawler.VehicleTextParser.verifiedByPresence(
        VehicleInfo(firstRegYear = firstRegYear, mileageKm = mileageKm, powerKw = powerKw, gearbox = gearbox),
    )

    @Test
    fun `dutch salvage part naming its donor vehicle is dropped`() {
        // "Expansievat van een Volkswagen Crafter" is a component taken from a van, not a van.
        val part = carListing("Expansievat van een Volkswagen Crafter")
        val van = carListing("Volkswagen Crafter 2.0 TDI L3H2")
        val kept = CarFilterEngine.apply(listOf(part, van), CarFilters())
        assertEquals(listOf("Volkswagen Crafter 2.0 TDI L3H2"), kept.map { it.title })
    }

    private fun carListing(title: String) = Listing(
        id = "MARKTPLAATS:$title",
        platformId = PlatformId.MARKTPLAATS,
        externalId = title,
        url = "https://www.2dehands.be/v/1",
        title = title,
        price = Money(900_000, Currency.EUR),
        scrapedAt = kotlinx.datetime.Clock.System.now(),
    )

    @Test
    fun `a part query keeps parts that the guard would otherwise drop`() {
        val part = partListing("Drehkonsole VW Crafter Mercedes Sprinter")
        // Without parts intent the guard drops it; with it, the part is kept.
        assertTrue(CarFilterEngine.apply(listOf(part), CarFilters()).isEmpty())
        assertEquals(1, CarFilterEngine.apply(listOf(part), CarFilters(), keepNonVehicles = true).size)
    }

    @Test
    fun `isPartQuery recognises part and wheel searches, not a plain model`() {
        assertTrue(CarFilterEngine.isPartQuery("Crafter Drehkonsole"))
        assertTrue(CarFilterEngine.isPartQuery("Golf Winterreifen Alufelgen"))
        assertTrue(CarFilterEngine.isPartQuery("Scheinwerfer VW Crafter"))
        assertFalse(CarFilterEngine.isPartQuery("Volkswagen Crafter"))
    }

    @Test
    fun `part-suffix guard drops compound part titles a fixed noun list misses`() {
        // Real eBay "vw golf" hits: parts whose head noun ends in a part morpheme, no parsed specs.
        val leaks = listOf(
            "4 Radzierblenden VW Golf 6",
            "Blinkschalter Abblendschalter VW Golf V, Tiguan 5N",
            "Fensterheber Schalter Master Switch VW Golf 4 Passat B5",
            "VW Golf Kopfstütze",
            "VW Golf 1 Schachtleiste 4 türig",
            "ORIG. VW Golf 7 Einstiegsleuchte Außenspiegel",
            "Türverkleidung Türpappe VW Golf 4",
            "Tankdeckel Tankklappe VW Golf 5",
        ).map { carListing(it, PlatformId.EBAY_DE, it, vehicle = VehicleInfo()) }
        val survivors = CarFilterEngine.apply(leaks, CarFilters()).map { it.title }
        assertTrue(survivors.isEmpty(), "not dropped: $survivors")
        // A whole car with the same suffix-bearing word in its title is exempt once it has specs.
        val realCar = carListing("real", PlatformId.EBAY_DE, "VW Golf 4 Kombi 1.9 TDI Klimaanlage",
            vehicle = verified(firstRegYear = 2003, mileageKm = 180_000))
        assertEquals(1, CarFilterEngine.apply(listOf(realCar), CarFilters()).size)
    }

    @Test
    fun `part-suffix guard does not drop genuine car titles`() {
        // Model, trim, engine, and equipment words a real car leads with — none is a part morpheme.
        val cars = listOf(
            "VW Golf 4 Kombi 1.4 75 PS für Export",
            "Volkswagen Golf VII GTI 2.0 TSI DSG",
            "VW Golf Bluemotion Comfortline Highline Trendline",
            "Golf 6 Cabrio 1.6 TDI Bastlerfahrzeug",
        ).map { carListing(it, PlatformId.EBAY_DE, it, vehicle = VehicleInfo()) }
        assertEquals(cars.size, CarFilterEngine.apply(cars, CarFilters()).size)
    }

    private fun partListing(title: String) = io.github.tieo.arbay.model.Listing(
        id = "KLEINANZEIGEN:$title",
        platformId = io.github.tieo.arbay.model.PlatformId.KLEINANZEIGEN,
        externalId = title,
        url = "https://x/1",
        title = title,
        price = io.github.tieo.arbay.model.Money(5000, io.github.tieo.arbay.model.Currency.EUR),
        scrapedAt = kotlinx.datetime.Clock.System.now(),
    )
}
