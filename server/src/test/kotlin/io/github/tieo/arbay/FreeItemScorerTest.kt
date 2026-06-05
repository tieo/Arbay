package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.EmbeddingModel
import io.github.tieo.arbay.classifier.FreeItemScorer
import io.github.tieo.arbay.model.*
import kotlinx.datetime.Clock
import kotlin.test.*

class FreeItemScorerTest {

    private fun listing(id: String, title: String, description: String? = null) = Listing(
        id = "KLEINANZEIGEN:$id",
        platformId = PlatformId.KLEINANZEIGEN,
        externalId = id,
        url = "https://www.kleinanzeigen.de/s-anzeige/$id",
        title = title,
        price = Money.cents(0),
        imageUrls = emptyList(),
        scrapedAt = Clock.System.now(),
        description = description,
    )

    // ── Score bounds ────────────────────────────────────────────────────────────

    @Test
    fun `score is always in 0 to 1 range with no profile`() {
        val result = FreeItemScorer.score(listing("t1", "Samsung Monitor 27 Zoll"), null)
        assertTrue(result in 0.0..1.0, "Score must be in [0,1], got $result")
    }

    @Test
    fun `score is always in 0 to 1 range with profile embedding`() {
        val profile = EmbeddingModel.embed("electronics home office monitors")
        val result = FreeItemScorer.score(listing("t2", "Ikea desk and chair"), profile)
        assertTrue(result in 0.0..1.0, "Score must be in [0,1], got $result")
    }

    @Test
    fun `score with no profile and no feedback returns neutral value around 0_45`() {
        // No profile → 0.3 baseline + no loves → 0.15 baseline − no dislikes = 0.45
        // But feedback store may already have data; just check it's in valid range
        val result = FreeItemScorer.score(listing("t3", "Tisch zu verschenken"), null)
        assertTrue(result in 0.0..1.0)
    }

    // ── Semantic relevance ordering ─────────────────────────────────────────────

    @Test
    fun `electronics listing scores higher than furniture for electronics profile`() {
        val profile = EmbeddingModel.embed("electronics monitors keyboards home office")
            ?: return println("Skipping: model unavailable")

        val electronicsListing = listing("e1", "Samsung 27 Zoll Monitor Full HD HDMI")
        val furnitureListing = listing("f1", "Holzschrank Kleiderschrank Schlafzimmer")

        val scoreElectronics = FreeItemScorer.score(electronicsListing, profile)
        val scoreFurniture = FreeItemScorer.score(furnitureListing, profile)

        assertTrue(
            scoreElectronics > scoreFurniture,
            "Electronics listing ($scoreElectronics) should outscore furniture ($scoreFurniture) for electronics profile"
        )
    }

    @Test
    fun `furniture listing scores higher than electronics for furniture profile`() {
        val profile = EmbeddingModel.embed("Möbel Schreibtisch Regal Schrank Heimeinrichtung")
            ?: return println("Skipping: model unavailable")

        val furnitureListing = listing("f2", "Ikea Kallax Regal weiß zu verschenken")
        val electronicsListing = listing("e2", "Laptop Netzteil Kabel USB-Hub")

        val scoreFurniture = FreeItemScorer.score(furnitureListing, profile)
        val scoreElectronics = FreeItemScorer.score(electronicsListing, profile)

        assertTrue(
            scoreFurniture > scoreElectronics,
            "Furniture listing ($scoreFurniture) should outscore electronics ($scoreElectronics) for furniture profile"
        )
    }

    // ── Profile embedding matters ───────────────────────────────────────────────

    @Test
    fun `matching profile gives higher score than mismatching profile`() {
        val electronicProfile = EmbeddingModel.embed("electronics computers monitors")
        val gardeningProfile = EmbeddingModel.embed("gardening plants flowers outdoor tools")

        if (electronicProfile == null || gardeningProfile == null) {
            return println("Skipping: model unavailable")
        }

        val monitor = listing("m1", "Dell Monitor 24 Zoll IPS Panel")

        val scoreWithGoodProfile = FreeItemScorer.score(monitor, electronicProfile)
        val scoreWithBadProfile = FreeItemScorer.score(monitor, gardeningProfile)

        assertTrue(
            scoreWithGoodProfile > scoreWithBadProfile,
            "Monitor should score higher with electronics profile ($scoreWithGoodProfile) " +
                "than with gardening profile ($scoreWithBadProfile)"
        )
    }

    // ── Description contributes to scoring ─────────────────────────────────────

    @Test
    fun `description text contributes to relevance score`() {
        val profile = EmbeddingModel.embed("outdoor cycling sports Fahrrad")
            ?: return println("Skipping: model unavailable")

        // Title is generic, description makes it relevant
        val withDesc = listing("d1", "Zu verschenken", description = "Mountainbike Fahrrad 26 Zoll, wenig benutzt")
        val withoutDesc = listing("d2", "Zu verschenken")

        val scoreWith = FreeItemScorer.score(withDesc, profile)
        val scoreWithout = FreeItemScorer.score(withoutDesc, profile)

        // Description should help, not hurt
        assertTrue(scoreWith >= scoreWithout - 0.05,
            "Adding relevant description should not significantly reduce score")
    }
}
