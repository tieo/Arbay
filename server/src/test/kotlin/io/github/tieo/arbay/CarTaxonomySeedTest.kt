package io.github.tieo.arbay

import io.github.tieo.arbay.model.CarTaxonomySeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CarTaxonomySeedTest {

    private val taxonomy = CarTaxonomySeed.taxonomy

    @Test
    fun versionAndMakesPresent() {
        assertTrue(taxonomy.version.isNotBlank())
        assertTrue(taxonomy.makes.size >= 30, "seed should carry the full make list")
    }

    @Test
    fun makeIdsAreUnique() {
        val ids = taxonomy.makes.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate make id in seed")
    }

    @Test
    fun modelIdsUniqueWithinEachMake() {
        taxonomy.makes.forEach { make ->
            val ids = make.models.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "duplicate model id under ${make.id}")
        }
    }

    @Test
    fun coversTheCrafterTarget() {
        val vw = taxonomy.makes.firstOrNull { it.id == "volkswagen" }
        assertTrue(vw != null && vw.models.any { it.id == "crafter" }, "VW Crafter must be in the seed")
    }
}
