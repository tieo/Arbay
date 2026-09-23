package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.VintedDeCrawler
import io.github.tieo.arbay.testing.offlineClient
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jsoup.Jsoup

/**
 * What a Vinted card's title attribute actually holds.
 *
 * It is the whole card read out for a screen reader — the name, then the brand, the condition, the
 * size and both prices — and the cut that was meant to keep only the name looked for a lowercase
 * ", marke:" that the attribute never contains. So a drive was called "Disque dur externe 2TB SSD,
 * Marke: M2, Zustand: Sehr gut, 50.00 €, 53.20 €", price and all.
 */
class VintedTitleTest {

    private val crawler = VintedDeCrawler(offlineClient())

    private fun titleOf(attr: String): String {
        val html = """<div><a data-testid="x--overlay-link" title="$attr"></a></div>"""
        val item = Jsoup.parseBodyFragment(html).body().child(0)
        return crawler.cardTitle(item).orEmpty()
    }

    @Test
    fun `the name stops where the labelled fields start`() {
        assertEquals(
            "Disque dur externe 2TB SSD",
            titleOf("Disque dur externe 2TB SSD, Marke: M2, Zustand: Sehr gut, 50.00 €, 53.20 €"),
        )
    }

    @Test
    fun `the same in the languages Vinted runs in`() {
        assertEquals("Lexar NM790 2TB", titleOf("Lexar NM790 2TB, Brand: Lexar, Condition: New"))
        assertEquals("Kingston NV3 2TB", titleOf("Kingston NV3 2TB, Marca: Kingston, Condizione: Nuovo"))
        assertEquals("Crucial P3 2TB", titleOf("Crucial P3 2TB, Marque: Crucial, État: Neuf"))
        assertEquals("Samsung 990 Pro", titleOf("Samsung 990 Pro, Größe: 2TB"))
    }

    @Test
    fun `a card with nothing but a name keeps it whole`() {
        assertEquals(
            "Western Digital WD Red SA500 2TB M.2 2280 SATA SSD",
            titleOf("Western Digital WD Red SA500 2TB M.2 2280 SATA SSD"),
        )
    }
}
