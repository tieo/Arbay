package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarTaxonomyProvider
import kotlin.test.Test
import kotlin.test.assertTrue

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
}
