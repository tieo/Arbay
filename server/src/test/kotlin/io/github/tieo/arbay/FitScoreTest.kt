package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.FitScore
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Drivetrain
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VanSize
import io.github.tieo.arbay.model.VehicleInfo
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitScoreTest {

    // The wishes of the real search these weights were set on: a Crafter, mittellang Hochdach,
    // automatic, front-wheel drive, diesel, from 2018, up to 150,000 km, at least 110 kW.
    private val wishes = CarFilters(
        firstRegFromYear = 2018, maxMileageKm = 150_000, minPowerKw = 110,
        minPriceEur = 10_000, maxPriceEur = 30_990, transmission = Transmission.AUTOMATIC,
        drivetrain = Drivetrain.FWD, fuels = setOf(Fuel.DIESEL),
        vanLengths = setOf(VanSize.MEDIUM), vanHeights = setOf(VanSize.HIGH_ROOF),
    )

    private fun van(
        title: String = "Volkswagen Crafter Kasten 35 mittellang Hochdach FWD",
        description: String = "Klimaanlage",
        euros: Long = 23_950,
        vehicle: VehicleInfo = VehicleInfo(
            firstRegYear = 2022, mileageKm = 126_000, powerKw = 130, fuel = Fuel.DIESEL,
            gearbox = Transmission.AUTOMATIC, drivetrain = Drivetrain.FWD,
            vanLength = VanSize.MEDIUM, vanLengthMax = VanSize.MEDIUM,
            vanHeight = VanSize.HIGH_ROOF, vanHeightMax = VanSize.HIGH_ROOF,
        ),
        seller: Seller? = Seller(name = "Autohaus", type = SellerType.BUSINESS, rating = 4.9, reviewCount = 198),
        distanceKm: Double? = 300.0,
    ) = Listing(
        id = "MOBILE_DE:$title$euros", platformId = PlatformId.MOBILE_DE, externalId = "1", url = "https://x",
        title = title, description = description, price = Money(euros * 100, Currency.EUR),
        scrapedAt = Clock.System.now(), vehicle = vehicle, seller = seller, distanceKm = distanceKm,
    )

    private fun fit(l: Listing) = FitScore.score(l, wishes, FitScore.priceEur(l), softDriveHours = 4.0)

    @Test
    fun `a van that meets every wish misses nothing`() {
        val f = assertNotNull(fit(van()))
        assertEquals(emptyList(), f.misses)
        assertEquals(true, f.airConditioning)
    }

    @Test
    fun `a dealer under four stars rules the offer out`() {
        assertNull(fit(van(seller = Seller(name = "X", type = SellerType.BUSINESS, rating = 3.4, reviewCount = 26))))
    }

    @Test
    fun `a conversion or another fuel is not the van wanted`() {
        assertNull(fit(van(title = "Volkswagen Crafter California Camper")))
        assertNull(fit(van(vehicle = van().vehicle!!.copy(fuel = Fuel.ELECTRIC))))
    }

    @Test
    fun `every miss costs something and is named`() {
        val perfect = fit(van())!!.score
        val noAc = fit(van(description = "Keine Klimaanlage oder -automatik"))!!
        val awd = fit(van(vehicle = van().vehicle!!.copy(drivetrain = Drivetrain.AWD)))!!
        assertTrue(noAc.score < perfect && "no air conditioning" in noAc.misses)
        assertTrue(awd.score < perfect && "AWD" in awd.misses)
    }

    @Test
    fun `cheaper is better all the way down, and distance counts little`() {
        assertTrue(fit(van(euros = 18_950))!!.score > fit(van(euros = 24_999))!!.score)
        val near = fit(van(distanceKm = 50.0))!!.score
        val far = fit(van(distanceKm = 550.0))!!.score
        assertTrue(near > far && near - far < 0.05, "near $near far $far")
    }

    @Test
    fun `a drive is estimated from the straight line`() {
        // Stuttgart to Hamburg: 534 km apart, under seven hours on the road.
        assertEquals(6.7, FitScore.driveHours(534.0)!!, 0.1)
    }
}
