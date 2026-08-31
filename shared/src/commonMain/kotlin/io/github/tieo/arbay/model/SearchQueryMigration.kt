package io.github.tieo.arbay.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Backfills [SearchQuery.category] on JSON persisted before the field existed (server's saved
 * searches, a device's search history) — required fields don't tolerate a missing key, so old
 * data needs an actual migration, not a silent default that would have hidden the very bug this
 * replaced. The category comes from whatever legacy signal is still in the raw JSON: the old
 * isVehicleSearch flag, or carFilters being set at all.
 *
 * In the same pass, narrows a platforms list that strayed outside the computed category (the
 * isVehicleSearch-fallback bug this replaced saved literally every platform, car and real-estate
 * sites included, for an ordinary product search) down to the platforms that category actually
 * reaches — but only the platforms that don't belong, so a deliberately narrower selection the
 * platforms already had is left alone. Idempotent: an object that already has "category" is
 * returned unchanged.
 */
object SearchQueryMigration {
    fun migrateList(raw: JsonArray): JsonArray = JsonArray(raw.map(::migrateEntry))

    private fun migrateEntry(entry: JsonElement): JsonElement {
        val obj = entry as? JsonObject ?: return entry
        val sq = obj["searchQuery"] as? JsonObject ?: return entry
        val migrated = migrateSearchQuery(sq)
        if (migrated === sq) return entry
        return JsonObject(obj + ("searchQuery" to migrated))
    }

    private fun migrateSearchQuery(sq: JsonObject): JsonObject {
        if ("category" in sq) return sq

        val hasCarFilters = sq["carFilters"]?.let { it !is JsonNull } == true
        val wasVehicle = (sq["isVehicleSearch"] as? JsonPrimitive)?.booleanOrNull == true
        val category = if (wasVehicle || hasCarFilters) MarketGroup.VEHICLES else MarketGroup.GENERAL

        val currentPlatforms = (sq["platforms"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val allowedNames = MarketSets.platformsFor(category).map { it.name }.toSet()
        val narrowedPlatforms = currentPlatforms?.filter { it in allowedNames }

        val patch = buildMap<String, JsonElement> {
            put("category", JsonPrimitive(category.name))
            if (narrowedPlatforms != null && narrowedPlatforms.isNotEmpty() && narrowedPlatforms.size < currentPlatforms.size) {
                put("platforms", JsonArray(narrowedPlatforms.map { JsonPrimitive(it) }))
            }
        }
        return JsonObject(sq + patch)
    }
}
