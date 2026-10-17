package io.github.tieo.arbay

import io.github.tieo.arbay.chat.planSend
import io.github.tieo.arbay.model.AdNow
import io.github.tieo.arbay.model.AdState
import io.github.tieo.arbay.model.BuyerProtection
import io.github.tieo.arbay.model.ChatCosts
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Shipping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SendPlanTest {
    private val drive = Listing(
        id = "KLEINANZEIGEN:1", platformId = PlatformId.KLEINANZEIGEN, externalId = "1",
        url = "https://www.kleinanzeigen.de/s-anzeige/x/1", title = "Fanxiang 2TB",
        price = Money(14_500, Currency.EUR), shipping = Shipping(cost = Money(99, Currency.EUR), available = true),
        scrapedAt = Instant.fromEpochSeconds(0),
    )

    @Test
    fun `an ask lowered since the search caps the offer and never the one the search saw`() {
        val line = planSend(listOf(drive), allInEur = 999.0, template = "{preis}", edits = emptyMap(),
            costs = ChatCosts(BuyerProtection(fixedEur = 0.49, share = 0.06)),
            adsNow = mapOf(drive.id to AdNow(AdState.LIVE, Money(13_500, Currency.EUR), Shipping(cost = Money(99, Currency.EUR), available = true)))).single()
        val price = assertNotNull(line.price)
        assertTrue(price <= 135, "offered $price on a 135 euro ask")
    }

    @Test
    fun `a paused ad is not written to`() {
        val line = planSend(listOf(drive), allInEur = 100.0, template = "{preis}", edits = emptyMap(),
            adsNow = mapOf(drive.id to AdNow(state = AdState.PAUSED))).single()
        assertEquals("The ad is paused", line.unreachable)
        assertNull(line.price)
    }
}
