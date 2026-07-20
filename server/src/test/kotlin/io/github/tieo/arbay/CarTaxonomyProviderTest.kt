package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarTaxonomyProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CarTaxonomyProviderTest {

    private fun fixture(): String =
        javaClass.getResource("/fixtures/autoscout24_path.html")!!.readText()

    @Test
    fun `parses the full make catalog from AutoScout24 NEXT_DATA`() {
        val makes = CarTaxonomyProvider.parseAutoScout24Makes(fixture())
        // The search page embeds ~290 makes in taxonomy.makesSorted.
        assertTrue(makes.size >= 100, "expected the full make list, got ${makes.size}")

        val names = makes.map { it.first }
        assertTrue("Volkswagen" in names, "Volkswagen missing")
        assertTrue("BMW" in names, "BMW missing")
        assertTrue("Audi" in names, "Audi missing")

        // Each entry carries the site's numeric make id (the per-platform filter token).
        val vw = makes.first { it.first == "Volkswagen" }
        assertTrue(vw.second.toIntOrNull() != null, "make id should be numeric, was ${vw.second}")
    }

    @Test
    fun `returns empty on html without NEXT_DATA`() {
        assertTrue(CarTaxonomyProvider.parseAutoScout24Makes("<html><body>no data</body></html>").isEmpty())
    }

    @Test
    fun `parses a make's model catalog keyed by numeric make id`() {
        val html = """<html><body><script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"pageProps":{"taxonomy":{"models":{"74":[""" +
            """{"value":2084,"label":"Golf"},{"value":18781,"label":"Crafter"},{"value":2090,"label":"Polo"}""" +
            """]}}}}}</script></body></html>"""
        val models = CarTaxonomyProvider.parseAutoScout24Models(html, "74")
        assertEquals(3, models.size)
        val crafter = models.first { it.name == "Crafter" }
        assertEquals("crafter", crafter.id)
        assertEquals("18781", crafter.platformSlugs["AUTOSCOUT24"])
        // Sorted by name, so Crafter precedes Golf precedes Polo.
        assertEquals(listOf("Crafter", "Golf", "Polo"), models.map { it.name })
    }

    @Test
    fun `model parse falls back to the sole make when no id is given`() {
        val html = """<html><body><script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"pageProps":{"taxonomy":{"models":{"9":[{"value":10,"label":"X5"}]}}}}}</script></body></html>"""
        assertEquals(listOf("X5"), CarTaxonomyProvider.parseAutoScout24Models(html, null).map { it.name })
    }
}
