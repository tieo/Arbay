package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.BlockedDealer
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A dealer the user blocked is dropped from every search under a reason of its own, matched by the
 *  market's account number, and on another market only where an entry says so. */
class BlockedDealerDropTest {

    private val query = SearchQuery(text = "volkswagen crafter", category = MarketGroup.VEHICLES)
    private val at = Clock.System.now()

    private fun listing(id: String, seller: Seller?, platform: PlatformId = PlatformId.AUTOSCOUT24) = Listing(
        id = "${platform.name}:$id", platformId = platform, externalId = id,
        url = "https://example.invalid/$id", title = "Volkswagen Crafter Kasten 35 mittellang",
        price = Money(21_900_00, Currency.EUR), scrapedAt = at, seller = seller,
    )

    private val dealer = Seller(name = "Beispiel Nutzfahrzeuge GmbH", id = "900001", type = SellerType.BUSINESS)

    @Test
    fun `a blocked dealer's listing is dropped under its own reason, naming the dealer`() {
        val blocked = listOf(BlockedDealer(PlatformId.AUTOSCOUT24, "900001", "Beispiel Nutzfahrzeuge GmbH", at))
        val listings = listOf(listing("a", dealer), listing("b", Seller(name = "Other GmbH", id = "900002")), listing("c", null))
        val partitioned = RelevanceFilter.partition(listings, query, blocked)
        assertEquals(listOf("AUTOSCOUT24:b", "AUTOSCOUT24:c"), partitioned.kept.map { it.id }.sorted())
        val drop = partitioned.dropped.single()
        assertEquals(DropReason.BLOCKED_DEALER, drop.reason)
        assertEquals("Beispiel Nutzfahrzeuge GmbH", drop.detail)
    }

    @Test
    fun `the account number decides, not the name the dealer wrote on this ad`() {
        val blocked = listOf(BlockedDealer(PlatformId.AUTOSCOUT24, "900001", "Beispiel Nutzfahrzeuge GmbH", at))
        val renamed = listing("a", dealer.copy(name = "BEISPIEL NFZ"))
        val namesake = listing("b", dealer.copy(id = "900003"))
        val partitioned = RelevanceFilter.partition(listOf(renamed, namesake), query, blocked)
        assertEquals(listOf("AUTOSCOUT24:b"), partitioned.kept.map { it.id })
    }

    @Test
    fun `the same dealer on another market is left alone without an entry for that market`() {
        val blocked = listOf(BlockedDealer(PlatformId.AUTOSCOUT24, "900001", "Beispiel Nutzfahrzeuge GmbH", at))
        val elsewhere = listing("m", dealer.copy(id = "700001"), PlatformId.MOBILE_DE)
        assertTrue(RelevanceFilter.partition(listOf(elsewhere), query, blocked).dropped.isEmpty())
    }

    @Test
    fun `an entry by name matches that name on its own market only`() {
        val blocked = listOf(BlockedDealer(PlatformId.MOBILE_DE, null, "Beispiel Nutzfahrzeuge GmbH", at))
        val there = listing("m", Seller(name = "beispiel nutzfahrzeuge gmbh", id = "700001"), PlatformId.MOBILE_DE)
        val here = listing("a", dealer)
        val partitioned = RelevanceFilter.partition(listOf(there, here), query, blocked)
        assertEquals(listOf("AUTOSCOUT24:a"), partitioned.kept.map { it.id })
        assertEquals(DropReason.BLOCKED_DEALER, partitioned.dropped.single().reason)
    }

    @Test
    fun `an entry is made from a listing by the most stable thing its market gives`() {
        assertEquals("900001", BlockedDealer.of(listing("a", dealer), at)?.sellerId)
        val byName = BlockedDealer.of(listing("a", Seller(name = "Beispiel Nutzfahrzeuge GmbH")), at)!!
        assertNull(byName.sellerId)
        assertEquals("Beispiel Nutzfahrzeuge GmbH", byName.name)
        assertNull(BlockedDealer.of(listing("a", null), at))
    }
}
