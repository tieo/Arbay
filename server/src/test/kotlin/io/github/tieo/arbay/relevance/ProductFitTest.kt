package io.github.tieo.arbay.relevance

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.FitPart
import io.github.tieo.arbay.model.FitWish
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.SearchQuery
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
import io.github.tieo.arbay.judging.FitScore

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
    fun `a search for a thing without wishes is weighed by none, a vehicle search by its criteria`() {
        val drive = ad("Mushkin Vortex 2TB NVMe SSD", 45)
        val ssd = SearchQuery(text = "2tb nvme", category = MarketGroup.GENERAL, maxPrice = Money(160_00, Currency.EUR))
        assertNull(FitScore.forSearch(drive, ssd, 4.0))
        assertNotNull(FitScore.forSearch(ad("Lägler Hummel", 900), ssd.copy(productWishes = sanders), 4.0))
        val crafter = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES, maxPrice = Money(30_000_00, Currency.EUR))
        assertNotNull(FitScore.forSearch(ad("VW Crafter", 25_000), crafter, 4.0))
    }

    @Test
    fun `an offer its text says is sold broken has no fit, one taken from a broken machine has`() {
        assertNull(fit(ad("Lägler Hummel", 300, "Läuft nicht mehr an, daher ausdrücklich als defekt an Bastler abzugeben.")))
        assertNotNull(fit(ad("Lägler Hummel", 900, "Walze aus einer defekten Maschine ausgebaut und eingesetzt, läuft einwandfrei.")))
    }

    @Test
    fun `a model only the text names counts a little less, and no model is not stated`() {
        val inText = assertNotNull(fit(ad("Parkettschleifmaschine", 700, "Lägler Profit, guter Zustand")))
        assertEquals(0.8 * 0.8, inText.parts.single { it.wish == FitWish.MODEL }.let { it.points / it.of }, 1e-9)
        val none = assertNotNull(fit(ad("Parkettschleifmaschine", 700)))
        assertTrue(!none.parts.single { it.wish == FitWish.MODEL }.known)
    }

    @Test
    fun `a model named only in the search words closing the ad is not stated`() {
        // KA 3561535204: a Zentra, its text closing with the words it wants to be found by.
        val zentra = assertNotNull(fit(ad("Zentra Parkettschleifmaschine", 450,
            "Gebrauchte Zentra Parkettschleifmaschine.\nMotor läuft top.\nStandort ist 53426\n\nParkett schleifen\nDielen schleifen\nSchleifmaschine\nWalzenschleifer\nLägler\nHummel")))
        assertTrue(!zentra.parts.single { it.wish == FitWish.MODEL }.known, "${zentra.parts}")
        // A text made only of short lines says what the machine is.
        val short = assertNotNull(fit(ad("Parkettschleifmaschine", 700, "Lägler Profit\nguter Zustand\nmit Walze")))
        assertEquals("Lägler PROFIT (in the text)", short.parts.single { it.wish == FitWish.MODEL }.note)
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

    @Test
    fun `a model only mentioned in an ad's text counts as not stated`() {
        val handle = ad("Griff für Schleifmaschinen", 300, text = "Passend für Lägler Hummel und andere")
        val named = assertNotNull(fit(handle)).parts.single { it.wish == FitWish.MODEL }
        val read = assertNotNull(fit(handle, ModelGuess(ModelGuess.NONE_OF_THEM, ModelGuess.ONLY_MENTIONED, 0.0, sure = false))).parts.single { it.wish == FitWish.MODEL }
        assertTrue(named.note!!.endsWith("(in the text)"))
        assertEquals(FitPart.NOT_STATED, read.note)
        assertTrue(read.points < named.points)
    }

    @Test
    fun `a title naming a model is not undone by a reading of its text`() {
        val hummel = ad("Lägler Hummel Parkettschleifmaschine", 600)
        val part = assertNotNull(fit(hummel, ModelGuess(ModelGuess.NONE_OF_THEM, ModelGuess.ONLY_MENTIONED, 0.0, sure = false))).parts.single { it.wish == FitWish.MODEL }
        assertEquals("Lägler HUMMEL", part.note)
    }

    @Test
    fun `what the judge is shown of a mention says where the text names each model`() {
        val wishes = ProductWishes(models = listOf(ModelWish("Creality Raptor", listOf("creality raptor"), 0.9), ModelWish("Creality Otter", listOf("creality otter"), 1.0)))
        val plate = ad("Kalibrierplatte für Scanner", 99, text = "Nur passend für Creality Raptor und Creality Otter, nicht für andere.")
        val mention = assertNotNull(io.github.tieo.arbay.judging.ModelMentions.of(plate, plate.description!!, wishes))
        assertEquals("Creality Otter", mention.model.name)
        assertEquals(2, mention.where.size)
        val line = kotlinx.serialization.json.Json.parseToJsonElement(io.github.tieo.arbay.judging.ModelMentions.line(4, mention)).toString()
        assertTrue("\"i\":4" in line && "\"title\":\"Kalibrierplatte" in line && "99.0 EUR" in line)
        assertNull(io.github.tieo.arbay.judging.ModelMentions.of(plate, "Sonst nichts dazu.", wishes))
    }

    @Test
    fun `a reviewer's verdict outranks the ad's words, and none of them rules it out`() {
        val workshop = ad("Schleifmaschine Walzenschleifmaschine Bandschleifer Bauknecht", 300)
        assertNull(fit(workshop, ModelGuess(ModelGuess.NONE_OF_THEM, ModelGuess.CHECKED, 1.0, sure = true)))
        val figurine = ad("Hummel Parkettschleifmaschine", 600)
        assertNull(fit(figurine, ModelGuess(ModelGuess.NONE_OF_THEM, ModelGuess.CHECKED, 1.0, sure = true)))
        val unnamed = ad("Parkettschleifmaschine", 600)
        val checked = assertNotNull(fit(unnamed, ModelGuess("Lägler PROFIT", ModelGuess.CHECKED, 1.0, sure = true)))
        assertEquals("Lägler PROFIT (checked)", checked.parts.first { it.wish == FitWish.MODEL }.note)
    }

    private val scanners = ProductWishes(
        models = listOf(
            ModelWish("Otter", listOf("otter"), 1.0, idealPriceEur = 300, maxPriceEur = 450),
            ModelWish("Otter Lite", listOf("otter lite"), 0.8, idealPriceEur = 250, maxPriceEur = 400),
            ModelWish("Raptor", listOf("raptor"), 0.9, maxPriceEur = 650),
        ),
    )

    @Test
    fun `the longest model's words win, and a model named beside it still counts`() {
        val folded = { t: String -> io.github.tieo.arbay.judging.FitScore.folded(t) }
        val models = scanners.models
        assertEquals(listOf("Otter Lite"), io.github.tieo.arbay.judging.FitScore.modelsNamed(folded("CR-Scan Otter Lite 3D Scanner"), models).map { it.name })
        val asSaved = listOf(ModelWish("Otter", listOf("cr-scan otter"), 1.0), ModelWish("Lite", listOf("otter lite"), 0.85))
        assertEquals(listOf("Lite"), io.github.tieo.arbay.judging.FitScore.modelsNamed(folded("Creality CR-Scan Otter Lite"), asSaved).map { it.name })
        assertEquals(listOf("Otter"), io.github.tieo.arbay.judging.FitScore.modelsNamed(folded("Creality CR-Scan Otter 3D"), asSaved).map { it.name })
        assertEquals(setOf("Otter", "Otter Lite"), io.github.tieo.arbay.judging.FitScore.modelsNamed(folded("Otter und Otter Lite"), models).map { it.name }.toSet())
    }

    private fun scanner(l: Listing, price: Double = FitScore.landedEur(l)) = FitScore.score(l, scanners, price, 4.0) { null }

    @Test
    fun `each model is priced against its own limit`() {
        val otterAt420 = assertNotNull(scanner(ad("CR-Scan Otter 3D Scanner", 420)))
        val liteAt420 = assertNotNull(scanner(ad("CR-Scan Otter Lite 3D Scanner", 420)))
        val raptorAt420 = assertNotNull(scanner(ad("Creality Raptor 3D Scanner", 420)))
        fun price(f: FitScore.Fit) = f.parts.single { it.wish == FitWish.PRICE }.points
        assertTrue(price(liteAt420) < price(otterAt420), "420 is past the Lite's 400")
        assertTrue(price(otterAt420) < price(raptorAt420), "420 is near the Otter's limit, far under the Raptor's")
    }

    @Test
    fun `a cheaper offer of a model fits better even under its ideal price`() {
        val cheap = assertNotNull(scanner(ad("CR-Scan Otter 3D Scanner", 216)))
        val dearer = assertNotNull(scanner(ad("CR-Scan Otter 3D Scanner B", 299)))
        assertTrue(cheap.score > dearer.score, "${cheap.score} vs ${dearer.score}")
    }

    @Test
    fun `a shop's list of variants and a rental are not weighed`() {
        assertNull(scanner(ad("Scanner Otter lite/Otter/Raptor Pro/Raptor", 299)))
        assertNull(scanner(ad("Raptor 3D Scanner mieten", 45).copy(saleType = io.github.tieo.arbay.model.SaleType.RENT)))
    }

    @Test
    fun `an offer is pushed once it is over the floor and not told of, new or not`() {
        val scored = listOf(ad("a", 1) to fitOf(0.83), ad("b", 1) to fitOf(0.78), ad("c", 1) to fitOf(0.77))
        fun crossed(told: Set<String>) = io.github.tieo.arbay.monitoring.SavedSearchMonitor.crossedTheFloor(scored, 0.78, told) { it }.map { it.first.title }
        assertEquals(listOf("a", "b"), crossed(emptySet()))
        assertEquals(listOf("b"), crossed(setOf(scored[0].first.id)))
    }

    private fun fitOf(score: Double) = FitScore.Fit(score, null, null)
}
