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

        val categorySuffix = if (locationId != null && radiusKm != null) {
            "k0c216l${locationId}r${radiusKm}"
        } else {
            "k0c216"
        }

        return "$BASE/s-autos/${segments.joinToString("/")}/$categorySuffix"
    }

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
