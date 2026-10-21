package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Drivetrain
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.carCriteria
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the markets are asked to filter themselves: the search's limits on fields every ad states,
 * never a wish the fit score weighs, and never a field some ads leave empty.
 */
class AtTheSourceTest {
    private val wishes = CarFilters(
        firstRegFromYear = 2018, maxMileageKm = 150_000, minPowerKw = 110, transmission = Transmission.AUTOMATIC,
        fuels = setOf(Fuel.DIESEL), drivetrain = Drivetrain.FWD, sellerType = SellerType.BUSINESS, maxWheelbaseMm = 4500,
    )
    private val crafter = SearchQuery(
        text = "Volkswagen Crafter", category = MarketGroup.VEHICLES,
        minPrice = Money(10_000_00, Currency.EUR), maxPrice = Money(30_990_00, Currency.EUR), carFilters = wishes,
    )
    private val client = HttpClient(MockEngine { respond("") })

    @Test
    fun `the wishes the fit score weighs never reach a market, a strict search's do`() {
        val sent = wishes.atTheSource()
        assertEquals(null, sent.transmission); assertEquals(null, sent.minPowerKw); assertEquals(null, sent.drivetrain)
        assertEquals(null, sent.sellerType); assertEquals(null, sent.maxWheelbaseMm)
        assertEquals(2018, sent.firstRegFromYear); assertEquals(150_000, sent.maxMileageKm); assertEquals(setOf(Fuel.DIESEL), sent.fuels)
        val strict = wishes.copy(strictUnknown = true)
        assertEquals(strict, strict.atTheSource())
    }

    @Test
    fun `mobile_de and AutoScout24 are asked for the limits only, AutoScout24 for the gearbox too`() {
        val mobile = MobileDeCrawler(client).filterParams(crafter)
        listOf("&fr=2018:", "&ml=:150000", "&p=10000:30990", "&ft=DIESEL").forEach { assertTrue(it in mobile, "$it in $mobile") }
        listOf("&tr=", "&pw=", "&st=", "&dt=").forEach { assertFalse(it in mobile, "$it in $mobile") }
        val as24 = AutoScout24Crawler(client).filterParams(crafter)
        listOf("&fregfrom=2018", "&kmto=150000", "&fuel=", "&gear=A").forEach { assertTrue(it in as24, "$it in $as24") }
        listOf("&powerfrom=", "&custtype=").forEach { assertFalse(it in as24, "$it in $as24") }
    }

    @Test
    fun `mobile_de is asked for the wished gearbox while somebody waits, for every gearbox in turn for the watch`() {
        val mobile = MobileDeCrawler(client)
        assertEquals(listOf("AUTOMATIC_GEAR"), mobile.gearboxPasses(crafter))
        assertEquals(listOf("AUTOMATIC_GEAR", "SEMIAUTOMATIC_GEAR", "MANUAL_GEAR"), mobile.gearboxPasses(crafter.copy(unattended = true)))
        assertEquals(listOf(null), mobile.gearboxPasses(crafter.copy(carFilters = wishes.copy(transmission = null), unattended = true)))
        assertTrue("&tr=MANUAL_GEAR" in mobile.filterParams(crafter, "MANUAL_GEAR"))
        val card = Listing(id = "MOBILE_DE:1", platformId = PlatformId.MOBILE_DE, externalId = "1", url = "https://suchen.mobile.de/1", title = "VW Crafter", price = Money(20_000_00, Currency.EUR), scrapedAt = kotlin.time.Instant.fromEpochSeconds(0))
        val manual = mobile.filedUnder(card, "MANUAL_GEAR").vehicle!!
        assertEquals(Transmission.MANUAL, manual.gearbox); assertTrue(manual.isVerified(VehicleField.GEARBOX))
        assertEquals(Transmission.AUTOMATIC, mobile.filedUnder(card, "SEMIAUTOMATIC_GEAR").vehicle?.gearbox)
        assertEquals(card, mobile.filedUnder(card, null))
    }

    @Test
    fun `TruckScout24 is asked for the price only, Kleinanzeigen never for the gearbox`() {
        val ts = TruckScout24Crawler(client).filterParams(crafter)
        assertTrue("&price-to=30990" in ts, ts)
        listOf("mileage", "power", "fuel+type", "gearing").forEach { assertFalse(it in ts, "$it in $ts") }
        val c = crafter.carCriteria.atTheSource()
        val ka = KleinanzeigenUrlBuilder.carAttrFilters(gearbox = c.transmission?.name, minYear = c.firstRegFromYear, maxMileageKm = c.maxMileageKm)
        assertFalse(ka.any { it.startsWith("autos.shift_s") }, "$ka")
    }

    @Test
    fun `Kleinanzeigen's commercial vehicles are searched by the model's word and the price`() {
        assertEquals(
            "https://www.kleinanzeigen.de/s-nutzfahrzeuge-anhaenger/preis:10000:30990/seite:2/sortierung:preis/crafter/k0c276",
            KleinanzeigenUrlBuilder.vanSearch("crafter", 2, minPriceCents = 10_000_00, maxPriceCents = 30_990_00),
        )
    }
}
