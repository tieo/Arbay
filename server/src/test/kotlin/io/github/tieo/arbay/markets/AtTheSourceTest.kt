package io.github.tieo.arbay.markets

import io.github.tieo.arbay.neuland.NeulandOffers

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
import io.github.tieo.arbay.relevance.CarQueryResolver

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
        val mobile = MobileDeCrawler().vehicleQuery(crafter)
        assertEquals(2018, mobile.registeredFrom); assertEquals(150_000, mobile.mileageMaxKm); assertEquals(listOf("diesel"), mobile.fuels)
        assertEquals(null, mobile.powerMinKw); assertEquals(null, mobile.dealer); assertEquals(null, mobile.drivetrain)
        val as24 = AutoScout24Crawler().vehicleQuery(crafter)!!
        assertEquals(2018, as24.registeredFrom); assertEquals(150_000, as24.mileageMaxKm)
        assertEquals(listOf("diesel"), as24.fuels); assertEquals("automatic", as24.gearbox)
        assertEquals(null, as24.powerMinKw); assertEquals(null, as24.dealer)
    }

    @Test
    fun `mobile_de is asked for the wished gearbox while somebody waits, for none by the watch, whose cards state their own`() {
        val mobile = MobileDeCrawler()
        assertEquals("automatic", mobile.vehicleQuery(crafter).gearbox)
        assertEquals(null, mobile.vehicleQuery(crafter.copy(unattended = true)).gearbox)
    }

    @Test
    fun `TruckScout24 is asked for the price only, Kleinanzeigen never for the gearbox`() {
        val ts = TruckScout24Crawler().vehicleQuery(crafter)
        assertEquals(NeulandOffers.VehicleQuery(make = ts.make, model = ts.model), ts, "nothing but make and model beside the price")
        val strict = TruckScout24Crawler().vehicleQuery(crafter.copy(carFilters = crafter.carCriteria.copy(strictUnknown = true)))
        assertTrue(strict.gearbox != null && strict.fuels.isNotEmpty(), "$strict")
        val ka = KleinanzeigenCrawler(HttpClient(MockEngine { respond("") })).vehicleQuery(crafter, CarQueryResolver.resolve("vw crafter")!!)
        assertEquals(null, ka.gearbox, "$ka")
    }
}
