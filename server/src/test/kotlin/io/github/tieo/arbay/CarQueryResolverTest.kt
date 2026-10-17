package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarQueryResolver
import kotlin.test.*

class CarQueryResolverTest {

    @Test
    fun `resolves make and model`() {
        val result = CarQueryResolver.resolve("Volkswagen Crafter")
        assertNotNull(result)
        assertEquals("volkswagen", result.makeSlug)
        assertEquals("crafter", result.modelSlug)
        assertEquals("", result.remainder)
    }

    @Test
    fun `resolves make alias and keeps remainder`() {
        val result = CarQueryResolver.resolve("vw crafter 2020")
        assertNotNull(result)
        assertEquals("volkswagen", result.makeSlug)
        assertEquals("crafter", result.modelSlug)
        assertEquals("2020", result.remainder)
    }

    @Test
    fun `resolves multi word make`() {
        val result = CarQueryResolver.resolve("mercedes benz sprinter")
        assertNotNull(result)
        assertEquals("mercedes-benz", result.makeSlug)
        assertEquals("sprinter", result.modelSlug)
        assertEquals("", result.remainder)
    }

    @Test
    fun `returns null for non-car query`() {
        assertNull(CarQueryResolver.resolve("Sony WH-1000XM5"))
    }

    @Test
    fun `a make followed by a part is not a car search`() {
        assertNull(CarQueryResolver.resolve("Ford Alufelgen 17 Zoll Sommerreifen 5x108"))
        assertNull(CarQueryResolver.resolve("BMW Felgen 16 Zoll"))
    }

    @Test
    fun `a model with its engine letter or a year still is`() {
        assertEquals("320d", CarQueryResolver.resolve("BMW 320d Touring")?.modelSlug)
        assertEquals("2019", CarQueryResolver.resolve("Ford 2019")?.modelSlug)
        assertNotNull(CarQueryResolver.resolve("Ford"))
    }
}
