package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.FitWish
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ModelWish
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.ProductWishes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class ProductFitTest {
    private val sanders = ProductWishes(
        models = listOf(
            ModelWish("Lägler HUMMEL", listOf("hummel"), 1.0),
            ModelWish("Lägler PROFIT", listOf("lägler profit", "laegler profit"), 0.8),
            ModelWish("Lägler ELF", listOf("lägler elf"), 0.6),
            ModelWish("Frank FSR 20", listOf("fsr 20", "fsr20"), 0.25),
            ModelWish("Lägler FLIP", listOf("flip", "randschleifer"), 0.0),
        ),
        idealPriceEur = 450,
        maxPriceEur = 1500,
        ruledOutWords = listOf("400 V", "Drehstrom", "superhummel"),
        plusWords = listOf("Schleifbänder", "neue Walze"),
    )

    private fun ad(title: String, euros: Long, text: String? = null, km: Double? = 60.0) = Listing(
        id = "KLEINANZEIGEN:${title.hashCode()}", platformId = PlatformId.KLEINANZEIGEN, externalId = title.hashCode().toString(),
        url = "https://www.kleinanzeigen.de/s-anzeige/x/1", title = title, description = text,
        price = Money(euros * 100, Currency.EUR), scrapedAt = Clock.System.now(), distanceKm = km,
    )

    private fun fit(l: Listing, guess: ModelGuess? = null) = FitScore.score(l, sanders, FitScore.priceEur(l), 4.0) { guess }

    @Test
    fun `the model wanted most at the price wanted fits best`() {
        val cheapProfit = assertNotNull(fit(ad("Fußbodenschleifmaschine LÄGLER Profit", 450, "mit Schleifbänder")))
        val dearHummel = assertNotNull(fit(ad("Lägler Hummel Parkettschleifmaschine", 2400)))
        val fsr = assertNotNull(fit(ad("Frank FSR 20 Parkettschleifmaschine", 400)))
        assertTrue(cheapProfit.score > dearHummel.score, "${cheapProfit.score} vs ${dearHummel.score}")
        assertTrue(cheapProfit.score > fsr.score, "${cheapProfit.score} vs ${fsr.score}")
        assertTrue(cheapProfit.score >= 0.8, "${cheapProfit.parts}")
        assertEquals("Lägler PROFIT", cheapProfit.parts.single { it.wish == FitWish.MODEL }.note)
    }

    @Test
    fun `a word the search rules out rules the offer out, unless the ad says it is not so`() {
        assertNull(fit(ad("Lägler Superhummel", 900)))
        assertNull(fit(ad("Lägler Hummel", 900, "Anschluss 400 V")))
        assertNotNull(fit(ad("Lägler Hummel", 900, "230 Volt, kein Drehstrom nötig")))
        assertNull(fit(ad("Lägler Hummel", 900).copy(condition = Condition.PARTS_ONLY)))
    }

    @Test
    fun `a model only the text names counts a little less, and no model is not stated`() {
        val inText = assertNotNull(fit(ad("Parkettschleifmaschine", 700, "Lägler Profit, guter Zustand")))
        assertEquals(0.8 * 0.8, inText.parts.single { it.wish == FitWish.MODEL }.let { it.points / it.of }, 1e-9)
        val none = assertNotNull(fit(ad("Parkettschleifmaschine", 700)))
        assertTrue(!none.parts.single { it.wish == FitWish.MODEL }.known)
    }

    @Test
    fun `a model that would not do rules out an ad naming only it, not one that brings it along`() {
        assertNull(fit(ad("Randschleifmaschine Parkettschleifmaschine Lägler Flip", 615)))
        val withEdge = assertNotNull(fit(ad("Hummel Parkettschleifmaschine + ELAN Randschleifer + Zubehör", 2500)))
        assertEquals("Lägler HUMMEL", withEdge.parts.single { it.wish == FitWish.MODEL }.note)
    }

    @Test
    fun `a model told from the photos counts, and a likeness alone rules nothing out`() {
        val unnamed = ad("Parkettschleifmaschine", 450)
        val printed = assertNotNull(fit(unnamed, ModelGuess("Lägler PROFIT", "printed on the machine", 0.9, sure = true)))
        assertEquals(0.8 * 0.9, printed.parts.single { it.wish == FitWish.MODEL }.let { it.points / it.of }, 1e-9)
        assertNull(fit(unnamed, ModelGuess("Lägler FLIP", "printed on the machine", 0.9, sure = true)))
        assertNotNull(fit(unnamed, ModelGuess("Lägler FLIP", "looks like the Lägler FLIP ads", 0.75, sure = false)))
    }
}
