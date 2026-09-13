package io.github.tieo.arbay.crawler

import java.net.URLEncoder

/**
 * Builds Kleinanzeigen search URLs using their path-segment filter format.
 *
 * Kleinanzeigen encodes filters as URL path segments (not query params):
 * - Price: `preis:{min}:{max}` or `preis::0` (free only)
 * - Page: `seite:{n}`
 * - City: `/s-{city-slug}/`
 * - Location+radius: `k0l{locationId}r{radiusKm}`
 * - Poster type: `anbieter:privat` or `anbieter:gewerblich`
 * - Category slug: `zu-verschenken` (after all filters, before `k0...`)
 * - Sort: `?sortingField=SORTING_DATE` (query parameter)
 */
object KleinanzeigenUrlBuilder {

    private const val BASE = "https://www.kleinanzeigen.de"

    /**
     * Build a URL for the "zu verschenken" (free items) category.
     *
     * Uses `preis::0` to ensure only genuinely free items appear on all pages
     * (without this, pages 2+ get backfilled with paid ads).
     */
    fun freeItems(
        locationId: String?,
        citySlug: String?,
        radiusKm: Int?,
        page: Int,
        privateOnly: Boolean = false,
        sortByDate: Boolean = false,
    ): String {
        val segments = buildList {
            // City slug (if location-based)
            if (citySlug != null) add(citySlug)
            // Price filter: free only
            add("preis::0")
            // Poster type
            if (privateOnly) add("anbieter:privat")
            // Pagination
            if (page > 1) add("seite:$page")
            // Category slug
            add("zu-verschenken")
        }

        val locationSuffix = if (locationId != null && radiusKm != null) {
            "k0l${locationId}r${radiusKm}"
        } else {
            "k0"
        }

        val path = "/s-${segments.joinToString("/")}/$locationSuffix"
        val queryString = if (sortByDate) "?sortingField=SORTING_DATE" else ""
        return "$BASE$path$queryString"
    }

    /**
     * Build a URL for a regular (non-free) keyword search.
     */
    fun regularSearch(
        query: String,
        page: Int,
        locationId: String? = null,
        radiusKm: Int? = null,
        minPriceCents: Long? = null,
        maxPriceCents: Long? = null,
    ): String {
        val segments = buildList {
            // Price filter
            if (minPriceCents != null || maxPriceCents != null) {
                val min = minPriceCents?.let { it / 100 } ?: ""
                val max = maxPriceCents?.let { it / 100 } ?: ""
                add("preis:$min:$max")
            }
            // Pagination
            if (page > 1) add("seite:$page")
            // Search query
            add(query.encodeUrl())
        }

        val locationSuffix = if (locationId != null && radiusKm != null) {
            "k0l${locationId}r${radiusKm}"
        } else {
            "k0"
        }

        return "$BASE/s-${segments.joinToString("/")}/$locationSuffix"
    }

    /**
     * Build a URL for a keyword search restricted to the Autos category (c216).
     *
     * The query becomes a path slug: lowercase with spaces replaced by hyphens,
     * so "Volkswagen Crafter" yields `/s-autos/volkswagen-crafter/k0c216`.
     */
    fun carSearch(
        query: String,
        page: Int,
        locationId: String? = null,
        radiusKm: Int? = null,
        minPriceCents: Long? = null,
        maxPriceCents: Long? = null,
        attrFilters: List<String> = emptyList(),
    ): String {
        val segments = buildList {
            // Price filter
            if (minPriceCents != null || maxPriceCents != null) {
                val min = minPriceCents?.let { it / 100 } ?: ""
                val max = maxPriceCents?.let { it / 100 } ?: ""
                add("preis:$min:$max")
            }
            // Pagination
            if (page > 1) add("seite:$page")
            // Search query as a slug
            add(query.lowercase().replace(" ", "-"))
        }

        // Kleinanzeigen car attribute filters (fuel/gearbox) attach to the category code with
        // '+', e.g. k0c216+autos.fuel_s:diesel — verified live to actually filter at the source.
        val attrSuffix = if (attrFilters.isEmpty()) "" else "+" + attrFilters.joinToString("+")
        val categorySuffix = if (locationId != null && radiusKm != null) {
            "k0c216l${locationId}r${radiusKm}$attrSuffix"
        } else {
            "k0c216$attrSuffix"
        }

        return "$BASE/s-autos/${segments.joinToString("/")}/$categorySuffix"
    }

    /**
     * Kleinanzeigen's own car attribute filters, derived from the search's criteria.
     *
     * The site filters on all of these itself, which decides what the crawl is even made of:
     * asked for "Volkswagen Crafter" alone it answers with 74 vans of every year, size and engine,
     * of which one survived a real set of criteria. Asked with the criteria in the URL it answers
     * with vans that already match — measured live: 582 results from 2018 on, 471 under 150.000 km,
     * 389 from 150 PS up, and ten matching vans on the first page instead of one in three pages.
     *
     * Ranges are written `name:min,max` with either end allowed to be empty. Power is in PS here,
     * as the site states it, so a filter held in kW is converted.
     */
    fun carAttrFilters(
        fuel: String? = null,      // "benzin" | "diesel" | "elektro" | "hybrid" | "lpg" | "cng"
        gearbox: String? = null,   // "automatik" | "manuell" | "halbautomatik"
        minYear: Int? = null,
        maxYear: Int? = null,
        minMileageKm: Int? = null,
        maxMileageKm: Int? = null,
        minPowerKw: Int? = null,
        maxPowerKw: Int? = null,
        strictUnknown: Boolean = false,
    ): List<String> = buildList {
        fuel?.let { add("autos.fuel_s:$it") }
        gearbox?.let { add("autos.shift_s:$it") }
        range("autos.ez_i", minYear, maxYear)?.let { add(it) }
        range("autos.km_i", minMileageKm, maxMileageKm)?.let { add(it) }
        // The power is stated by 1364 of 1503 car ads here; this site's filter drops the other 139
        // rather than leaving them unjudged, so it is asked for only when the search excludes
        // unstated specs anyway. Year, mileage and gearbox are on ~all of them and always asked.
        // Rounded outwards, so the conversion itself never excludes a van at the boundary.
        if (strictUnknown) range(
            "autos.power_i",
            minPowerKw?.let { kotlin.math.floor(it * 1.35962).toInt() },
            maxPowerKw?.let { kotlin.math.ceil(it * 1.35962).toInt() },
        )?.let { add(it) }
    }

    private fun range(name: String, min: Int?, max: Int?): String? =
        if (min == null && max == null) null else "$name:${min ?: ""},${max ?: ""}"

    /**
     * Encode a city name as a URL-safe slug for Kleinanzeigen path segments.
     * "Frankfurt (Oder)" → "frankfurt-%28oder%29"
     * "Bad Saulgau" → "bad-saulgau"
     */
    fun citySlug(cityName: String): String {
        return cityName
            .lowercase()
            .replace(" ", "-")
            .let { URLEncoder.encode(it, "UTF-8") }
            .replace("+", "-") // URLEncoder uses + for space, but we already replaced spaces
            .replace("%2F", "/")
    }
}
