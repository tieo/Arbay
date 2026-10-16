package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.AdState
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.TopOfferState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class TopOffersTest {
    private fun van(id: String) = Listing(
        id = "MOBILE_DE:$id", platformId = PlatformId.MOBILE_DE, externalId = id, url = "https://example.invalid/$id",
        title = "Volkswagen Crafter $id", price = Money(23_950_00, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `a best offer the search stops returning is reported until it comes back, unless its ad is gone`() = runBlocking {
        val search = "top-offers-test-${System.nanoTime()}"
        val oldenburg = van("oldenburg"); val heide = van("heide"); val other = van("other")
        val pages = mapOf("MOBILE_DE:oldenburg" to AdState.DELETED, "MOBILE_DE:heide" to AdState.LIVE)
        val read: suspend (io.github.tieo.arbay.model.TopOffer) -> AdState? = { pages[it.listingId] }

        assertEquals(emptyList(), TopOffers.update(search, listOf(oldenburg to 0.81, heide to 0.75), setOf(oldenburg.id, heide.id), emptyMap(), read))
        // Neither comes back: the one whose page says it is gone is gone, the other is lost.
        val lost = TopOffers.update(search, listOf(other to 0.7), setOf(other.id), emptyMap(), read)
        assertEquals(listOf("MOBILE_DE:heide"), lost.map { it.listingId })
        val states = TopOffers.of(search).associate { it.listingId to it.state }
        assertEquals(TopOfferState.GONE, states["MOBILE_DE:oldenburg"]); assertEquals(TopOfferState.LOST, states["MOBILE_DE:heide"])
        // Still missing next run: reported again; found again: no longer lost.
        assertEquals(listOf("MOBILE_DE:heide"), TopOffers.update(search, emptyList(), setOf(other.id), emptyMap(), read).map { it.listingId })
        assertEquals(emptyList(), TopOffers.update(search, emptyList(), setOf(other.id, heide.id), emptyMap(), read))
        assertEquals(TopOfferState.FOUND, TopOffers.of(search).first { it.listingId == "MOBILE_DE:heide" }.state)
        assertEquals(0.81, TopOffers.of(search).first().bestScore)
    }

    @Test
    fun `a best offer a rule of the search removes is reported once, with the rule, and never read as lost`() = runBlocking {
        val search = "top-offers-test-${System.nanoTime()}"
        val pasing = van("pasing"); val other = van("other")
        val read: suspend (io.github.tieo.arbay.model.TopOffer) -> AdState? = { AdState.LIVE }
        TopOffers.update(search, listOf(pasing to 0.75), setOf(pasing.id), emptyMap(), read)
        val rule = mapOf(pasing.id to "vehicle criteria: mileage")
        val first = TopOffers.update(search, listOf(other to 0.7), setOf(other.id), rule, read).single()
        assertEquals(TopOfferState.RULED_OUT, first.state); assertEquals("vehicle criteria: mileage", first.ruledOutBy)
        assertEquals(listOf(pasing.id), TopOffers.missing(search).map { it.listingId })
        // The same rule on the next run: nothing new to report.
        assertEquals(emptyList(), TopOffers.update(search, listOf(other to 0.7), setOf(other.id), rule, read))
        assertEquals(emptyList(), TopOffers.missing(search))
    }
}
