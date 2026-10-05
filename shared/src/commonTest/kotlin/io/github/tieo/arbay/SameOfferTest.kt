package io.github.tieo.arbay

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SameOffer
import io.github.tieo.arbay.model.VehicleInfo
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SameOfferTest {
    private fun listing(
        id: String,
        platform: PlatformId,
        title: String,
        euros: Long,
        zip: String?,
        vehicle: VehicleInfo? = null,
        city: String? = null,
    ) = Listing(
        id = id,
        platformId = platform,
        externalId = id,
        url = "https://example.invalid/$id",
        title = title,
        price = Money(euros * 100, Currency.EUR),
        location = if (zip == null && city == null) null else Location(zip = zip, city = city),
        scrapedAt = Instant.fromEpochSeconds(0),
        vehicle = vehicle,
    )

    @Test
    fun `the same ad on two markets is one offer`() {
        // Found on both on the same search: a dealer's Grand California, same title, price, place.
        val autoscout = listing("a", PlatformId.AUTOSCOUT24, "Volkswagen Crafter Grand California 600 FWD *GARANTIE*",
            54990, "54340", VehicleInfo(firstRegYear = 2020, mileageKm = 28435))
        val kleinanzeigen = listing("k", PlatformId.KLEINANZEIGEN, "Volkswagen Crafter Grand California 600 FWD *GARANTIE*",
            54990, "54340")
        val other = listing("o", PlatformId.EBAY_DE, "Volkswagen Crafter Kasten L3H2", 30000, "10115")
        val offers = SameOffer.group(listOf(other, autoscout, kleinanzeigen))
        assertEquals(listOf(listOf("o"), listOf("a", "k")), offers.map { o -> o.listings.map { it.id } })
    }

    @Test
    fun `one dealer's different vans at one price stay apart`() {
        // A Mönchengladbach dealer had these at the same price on AutoScout24 and Kleinanzeigen.
        val a = listing("a", PlatformId.AUTOSCOUT24, "Volkswagen Crafter TDI *TOP ZUSTAND* BUS-PAKET+9-SITZE (3414)", 34498, "41066")
        val k = listing("k", PlatformId.KLEINANZEIGEN, "Volkswagen Crafter PLUS MIXTO *4MOTION* 5-SITZE+AHK 3,0t 62", 34498, "41066")
        assertEquals(2, SameOffer.group(listOf(a, k)).size)
    }

    @Test
    fun `a stated fact that disagrees keeps them apart`() {
        val a = listing("a", PlatformId.AUTOSCOUT24, "VW Crafter 35 TDI Kasten Hochdach", 20000, "79331",
            VehicleInfo(firstRegYear = 2018, mileageKm = 88700))
        val b = listing("b", PlatformId.KLEINANZEIGEN, "VW Crafter 35 TDI Kasten Hochdach", 20000, "79331",
            VehicleInfo(firstRegYear = 2019, mileageKm = 88700))
        assertEquals(2, SameOffer.group(listOf(a, b)).size)
    }

    @Test
    fun `different titles match on the same registration and mileage`() {
        val a = listing("a", PlatformId.MOBILE_DE, "Volkswagen Crafter 35 2.0 TDI DSG", 17600, "79331",
            VehicleInfo(firstRegYear = 2018, mileageKm = 88700, powerKw = 130))
        val b = listing("b", PlatformId.KLEINANZEIGEN, "VW Crafter Automatik 177 PS", 17600, "79331",
            VehicleInfo(firstRegYear = 2018, mileageKm = 88650))
        assertEquals(1, SameOffer.group(listOf(a, b)).size)
    }

    @Test
    fun `similar ads on one market are not folded`() {
        val a = listing("a", PlatformId.TRUCKSCOUT24, "Volkswagen Crafter 35 Kasten L3H3", 45000, "74564")
        val b = listing("b", PlatformId.TRUCKSCOUT24, "VW Crafter 35 Kasten L3H3 Klima", 45000, "74564")
        assertEquals(2, SameOffer.group(listOf(a, b)).size)
    }

    @Test
    fun `similar ads without a place are not folded`() {
        val a = listing("a", PlatformId.EBAY_DE, "Volkswagen Crafter 35 Kasten L3H3", 45000, null)
        val b = listing("b", PlatformId.KLEINANZEIGEN, "VW Crafter 35 Kasten L3H3 Klima", 45000, "74564")
        assertEquals(2, SameOffer.group(listOf(a, b)).size)
    }

    @Test
    fun `a raw place is read for its postcode`() {
        val a = listing("a", PlatformId.AUTOSCOUT24, "Volkswagen Crafter 35 Kasten L3H3", 45000, "74564")
        val b = listing("b", PlatformId.KLEINANZEIGEN, "VW Crafter 35 Kasten L3H3 Klima", 45000, null)
            .copy(location = Location(raw = "74564 Crailsheim"))
        assertEquals(1, SameOffer.group(listOf(a, b)).size)
    }

    @Test
    fun `one title on several eBay locales is one offer led by the cheapest`() {
        val de = listing("de", PlatformId.EBAY_DE, "Crucial 32GB DDR5 5600 Kit", 120, null)
        val it = listing("it", PlatformId.EBAY_IT, "Crucial 32GB DDR5 5600 Kit", 95, null)
        val offers = SameOffer.group(listOf(de, it))
        assertEquals(listOf(listOf("it", "de")), offers.map { o -> o.listings.map { l -> l.id } })
    }

    @Test
    fun `a short title is not enough`() {
        val a = listing("a", PlatformId.EBAY_DE, "VW Crafter", 120, null)
        val b = listing("b", PlatformId.EBAY_IT, "VW Crafter", 95, null)
        assertEquals(2, SameOffer.group(listOf(a, b)).size)
    }
}
