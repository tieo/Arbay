package io.github.tieo.arbay

import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchQueryMigration
import io.github.tieo.arbay.model.TrackedProduct
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchQueryMigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // The exact shape a pre-migration "parkettschleifmaschine" bookmark had: isVehicleSearch
    // false, but every platform baked in (the isVehicleSearch-fallback-to-PlatformId.entries bug).
    private val legacyBloatedGeneral = """
        [{
            "id": "bybk6r2gxakz",
            "name": "parkettschleifmaschine",
            "searchQuery": {
                "text": "parkettschleifmaschine",
                "platforms": ["EBAY_DE","KLEINANZEIGEN","MOBILE_DE","AUTOSCOUT24","IMMOSCOUT24","AMAZON_DE"],
                "isVehicleSearch": false
            },
            "identifiers": {"gtins": [], "mpn": null},
            "createdAt": "2026-07-22T15:29:46.727933Z"
        }]
    """.trimIndent()

    // A real vehicle search saved through the car form: isVehicleSearch true, carFilters set.
    private val legacyVehicle = """
        [{
            "id": "9t3tnpxbigit",
            "name": "Volkswagen Crafter",
            "searchQuery": {
                "text": "Volkswagen Crafter",
                "platforms": ["MOBILE_DE","AUTOSCOUT24"],
                "isVehicleSearch": true,
                "carFilters": {"firstRegFromYear": 2019}
            },
            "identifiers": {"gtins": [], "mpn": null},
            "createdAt": "2026-07-22T15:29:46.727933Z"
        }]
    """.trimIndent()

    // Already-migrated data must round-trip untouched.
    private val alreadyMigrated = """
        [{
            "id": "x",
            "name": "bosch",
            "searchQuery": {"text": "bosch", "platforms": ["EBAY_DE"], "category": "GENERAL"},
            "identifiers": {"gtins": [], "mpn": null},
            "createdAt": "2026-07-22T15:29:46.727933Z"
        }]
    """.trimIndent()

    private fun migrate(raw: String): List<TrackedProduct> {
        val parsed = json.parseToJsonElement(raw) as JsonArray
        val migrated = SearchQueryMigration.migrateList(parsed)
        return json.decodeFromJsonElement(migrated)
    }

    @Test
    fun `backfills category as GENERAL and strips car-only platforms for a non-vehicle search`() {
        val products = migrate(legacyBloatedGeneral)
        val sq = products.single().searchQuery
        assertEquals(MarketGroup.GENERAL, sq.category)
        assertTrue(PlatformId.MOBILE_DE !in sq.platforms, "car-only platform must be dropped")
        assertTrue(PlatformId.AUTOSCOUT24 !in sq.platforms, "car-only platform must be dropped")
        assertTrue(PlatformId.IMMOSCOUT24 !in sq.platforms, "real-estate platform must be dropped")
        assertTrue(PlatformId.EBAY_DE in sq.platforms, "general platform must survive")
        assertTrue(PlatformId.KLEINANZEIGEN in sq.platforms, "general platform must survive")
        assertTrue(PlatformId.AMAZON_DE in sq.platforms, "general platform must survive")
    }

    @Test
    fun `backfills category as VEHICLES and keeps car platforms for a vehicle search`() {
        val products = migrate(legacyVehicle)
        val sq = products.single().searchQuery
        assertEquals(MarketGroup.VEHICLES, sq.category)
        assertEquals(setOf(PlatformId.MOBILE_DE, PlatformId.AUTOSCOUT24), sq.platforms.toSet())
    }

    @Test
    fun `leaves already-migrated data untouched`() {
        val products = migrate(alreadyMigrated)
        val sq = products.single().searchQuery
        assertEquals(MarketGroup.GENERAL, sq.category)
        assertEquals(listOf(PlatformId.EBAY_DE), sq.platforms)
    }

    @Test
    fun `does not narrow a deliberately smaller platform selection within its own category`() {
        // "Volkswagen Crafter" on the real phone had 11/24 vehicle platforms — a genuine manual
        // narrowing, not the bug. None of them are outside MarketSets.vehicles, so nothing should
        // be removed.
        val eleven = MarketSets.vehicles.take(11)
        val sq = SearchQuery(text = "x", platforms = eleven, category = MarketGroup.VEHICLES)
        assertEquals(eleven, sq.platforms)
    }
}
