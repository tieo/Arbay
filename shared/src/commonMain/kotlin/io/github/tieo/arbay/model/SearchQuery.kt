package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class Transmission { AUTOMATIC, MANUAL }

/** Structured car-search criteria, carried from the UI to the crawler search endpoint,
 *  which turns them into per-site URL filter parameters. Prices are in EUR. */
@Serializable
data class CarFilters(
    val firstRegFromYear: Int? = null,
    val firstRegToYear: Int? = null,
    val maxMileageKm: Int? = null,
    val maxPriceEur: Int? = null,
    val minPowerKw: Int? = null,
    val transmission: Transmission? = null,
) {
    val isEmpty: Boolean get() =
        firstRegFromYear == null && firstRegToYear == null && maxMileageKm == null &&
            maxPriceEur == null && minPowerKw == null && transmission == null
}

@Serializable
data class SearchQuery(
    val text: String,
    val platforms: List<PlatformId> = PlatformId.entries,
    val minPrice: Money? = null,
    val maxPrice: Money? = null,
    val condition: List<Condition>? = null,
    val soldOnly: Boolean = false,
    val freeOnly: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
    val location: String? = null,   // city name / zip for location-based search
    val radiusKm: Int = 30,         // search radius in km
    val maxPages: Int? = null,      // override crawler's default page limit (null = use CrawlerConfig)
    val startPage: Int = 1,         // start from this page (for paginated batches)
    // Vehicle filters — applied at the source by crawlers that support them (e.g. AutoScout24).
    val firstRegFromYear: Int? = null,  // earliest first-registration year
    val firstRegToYear: Int? = null,    // latest first-registration year
    val maxMileageKm: Int? = null,      // mileage ceiling
    val minPowerKw: Int? = null,        // minimum engine power in kW
    val transmission: Transmission? = null,
) {
    /** Search text with negative keywords and OR logic resolved — for platforms that don't support exclusion/OR syntax.
     *  For OR queries, picks the group with the most tokens (most specific variant). */
    val positiveText: String
        get() {
            val withoutNegatives = text.split(" ")
                .filter { it.isNotBlank() && !it.startsWith("-") }
                .joinToString(" ")
            return if (withoutNegatives.contains(" OR ", ignoreCase = true)) {
                // Split on OR and pick the group with the most tokens (most descriptive)
                withoutNegatives.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE))
                    .maxByOrNull { it.trim().split("\\s+".toRegex()).size }
                    ?.trim() ?: withoutNegatives
            } else {
                withoutNegatives
            }
        }
}
