package io.github.tieo.arbay

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Nearest-first has to hold for every listing on the screen, including the ones that arrive after
 * the order was worked out.
 *
 * Off a real phone: a saved search sorted nearest-first showed 33 km, 44 km, 489 km, 82 km, 159 km.
 * The order was applied where listings were assigned, and three of those places — a saved search
 * opening on its stored listings and two fallbacks — assigned without it, so whatever landed there
 * kept the position it happened to arrive in.
 */
class ResultOrderTest {

    private fun van(id: String, km: Double, priceEur: Long, lat: Double, lon: Double) = Listing(
        id = id, platformId = PlatformId.KLEINANZEIGEN, externalId = id,
        url = "https://example.invalid/$id", title = "Volkswagen Crafter $id",
        price = Money(priceEur * 100, Currency.EUR),
        location = Location(city = id, latitude = lat, longitude = lon),
        distanceKm = km,
        scrapedAt = Clock.System.now(),
    )

    @Test
    fun `nearest first orders every listing however it arrived`() = runTest {
        // The view model's own scope runs on the main dispatcher, which a test has to provide.
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        // Ulm, and vans at increasing distances from it, handed over in the wrong order.
        val near = van("near", 33.0, 28_500, 48.40, 10.00)
        val mid = van("mid", 82.0, 28_750, 48.90, 10.10)
        val far = van("far", 489.0, 18_900, 52.62, 10.08)
        val viewModel = ListingViewModel(sample = listOf(near, far, mid))
        viewModel.setSortMode(SortMode.NEAREST)
        assertEquals(
            listOf("near", "mid", "far"),
            viewModel.marketBasis.first().map { it.id },
            "nearest-first must hold over the listings a search opens with",
        )
    }
}
