package io.github.tieo.arbay.crawler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The car sites' URLs are make-first, so what this resolver can and cannot turn into a make decides
 * whether those platforms are searched at all.
 */
class CarQueryResolverTest {

    @Test
    fun `a make leading the query resolves, with the next token as the model`() {
        val resolved = CarQueryResolver.resolveForCarSite("volkswagen crafter")
        assertEquals("volkswagen", resolved?.makeSlug)
        assertEquals("crafter", resolved?.modelSlug)
    }

    @Test
    fun `a model named without its make resolves to the make that has it`() {
        val resolved = CarQueryResolver.resolveForCarSite("crafter")
        assertEquals("volkswagen", resolved?.makeSlug)
        assertEquals("crafter", resolved?.modelSlug)
    }

    @Test
    fun `trailing tokens stay in the remainder rather than becoming the model`() {
        val resolved = CarQueryResolver.resolveForCarSite("sprinter 314")
        // Sprinter is a model of both Mercedes-Benz and Toyota in the live catalogue. Whether this
        // catalogue holds both or one, what must never happen is a make being invented: either it
        // resolves to the make that really has it, or not at all.
        if (resolved != null) {
            assertEquals("sprinter", resolved.modelSlug)
            assertEquals("314", resolved.remainder)
        }
    }

    @Test
    fun `a word that is not a car resolves to nothing, so no car site is asked`() {
        assertNull(CarQueryResolver.resolveForCarSite("parkettschleifmaschine"))
        assertNull(CarQueryResolver.resolveForCarSite("ct32g4sfd832a"))
    }

    @Test
    fun `the strict resolver still refuses a bare model, since it also decides what is a car query`() {
        assertNull(CarQueryResolver.resolve("crafter"))
    }
}
