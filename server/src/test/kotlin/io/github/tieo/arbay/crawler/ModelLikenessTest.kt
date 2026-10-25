package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ModelWish
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.ProductWishes
import io.github.tieo.arbay.model.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class ModelLikenessTest {
    private val sanders = ProductWishes(
        models = listOf(
            ModelWish("Lägler HUMMEL", listOf("hummel"), 1.0),
            ModelWish("Frank Cobra C", listOf("cobra"), 0.9),
            ModelWish("Lägler FLIP", listOf("flip"), 0.0),
        ),
    )
    private val query = SearchQuery(text = "lägler hummel", category = io.github.tieo.arbay.model.MarketGroup.GENERAL, aliases = listOf("frank cobra parkett", "parkettschleifmaschine"))
    private val kind = ModelLikeness.kindWords(query, sanders)

    private fun ad(title: String, text: String? = null) = Listing(
        id = "KLEINANZEIGEN:${title.hashCode()}", platformId = PlatformId.KLEINANZEIGEN, externalId = title.hashCode().toString(),
        url = "https://www.kleinanzeigen.de/s-anzeige/x/1", title = title, description = text,
        price = Money(50000, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `the kind of thing is told by the search's words that name no model`() {
        assertEquals(setOf("lagler", "frank", "parkett", "parkettschleifmaschine"), kind)
    }

    @Test
    fun `an offer of the search naming one model in its title is a reference for it`() {
        assertEquals("Lägler HUMMEL", ModelLikeness.namedIn(ad("Lägler Hummel Parkettschleifmaschine"), sanders, kind)?.name)
        assertEquals("Frank Cobra C", ModelLikeness.namedIn(ad("Cobra 230V", "Parkett Schleifer gut erhalten"), sanders, kind)?.name)
    }

    @Test
    fun `a namesake of another kind, or a title naming two models, is none`() {
        assertNull(ModelLikeness.namedIn(ad("Cobra King Driver Golfschläger"), sanders, kind))
        assertNull(ModelLikeness.namedIn(ad("Hummel Figur", "aus Frankfurt"), sanders, kind))
        assertNull(ModelLikeness.namedIn(ad("Lägler Hummel und Flip Parkett"), sanders, kind))
    }

    @Test
    fun `a model is told from photos only from the lead where it was right often enough`() {
        val hummel = sanders.models[0]; val cobra = sanders.models[1]
        fun told(truth: ModelWish, as_: ModelWish, lead: Double) = truth to ModelLikeness.Told(as_, lead)
        // HUMMEL: right five of five from a lead of 0.03, wrong below it. Cobra: never right.
        val record = List(5) { told(hummel, hummel, 0.04) } + List(3) { told(cobra, hummel, 0.025) } +
            List(6) { told(hummel, cobra, 0.06) }
        val gates = ModelLikeness.gates(record)
        assertEquals(0.03, gates.getValue("Lägler HUMMEL").first)
        assertEquals(1.0, gates.getValue("Lägler HUMMEL").second)
        assertNull(gates["Frank Cobra C"])
    }
}
