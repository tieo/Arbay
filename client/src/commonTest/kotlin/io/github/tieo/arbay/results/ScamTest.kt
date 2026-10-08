package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ScamTest {
    private val now = Instant.parse("2026-10-08T12:00:00Z")

    private fun offer(id: String, euros: Int, daysOnline: Int) = Listing(
        id = "KLEINANZEIGEN:$id", platformId = PlatformId.KLEINANZEIGEN, externalId = id, url = "https://example.invalid/$id",
        title = "Google Pixel 9 Pro XL 256GB", price = Money(euros * 100L, Currency.EUR),
        listingDate = now - daysOnline.days, scrapedAt = now,
    )

    /** Measured on a Pixel 9 Pro XL 256 GB search, 2026-10-08. */
    private val search = listOf(
        offer("obsidian", 400, 59) to 2053,
        offer("hazel-old", 500, 59) to 375,
        offer("rose", 600, 43) to 249,
        offer("salzuflen", 450, 30) to 229,
        offer("schorndorf", 465, 26) to 409,
        offer("limburg", 450, 22) to 228,
        offer("ottweiler", 400, 16) to 367,
        offer("neu-anspach", 458, 14) to 395,
        offer("mosbach", 440, 8) to 141,
        offer("top-angebot", 549, 3) to 410,
    )

    @Test
    fun `only the cheap old ad seen five times as often is a likely scam`() {
        val scams = likelyScams(search.map { it.first }, search.associate { it.first.id to it.second }, now)
        assertEquals(setOf("KLEINANZEIGEN:obsidian"), scams.keys)
    }

    @Test
    fun `without view counts nothing is called a scam`() {
        assertEquals(emptyMap(), likelyScams(search.map { it.first }, emptyMap(), now))
    }
}
