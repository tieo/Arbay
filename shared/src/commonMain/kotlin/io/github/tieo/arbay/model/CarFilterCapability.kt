package io.github.tieo.arbay.model

/**
 * Which car-filter dimensions each marketplace applies at the source (a native URL parameter the
 * crawler sends), versus dimensions Arbay enforces locally by post-filtering the parsed
 * VehicleInfo. Keys match the facet/chip dimension keys used across the app.
 *
 * A dimension not listed for a platform is enforced by us (post-filter) when the listing carries a
 * verified value, and soft-passed (kept, badged unverified) when it does not — never silently
 * dropped. This table drives the user-facing coverage note ("filtered at source" vs "filtered by
 * us") so a platform is never hidden just because it lacks a native filter.
 */
object CarFilterCapability {

    /** Dimensions each platform filters natively (verified against each crawler's URL builder). */
    val native: Map<PlatformId, Set<String>> = mapOf(
        PlatformId.AUTOSCOUT24 to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.TRUCKSCOUT24 to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.OTOMOTO to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.SAUTO to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.DBA to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.BILBASEN to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.BYTBIL to setOf("year", "mileage", "price", "power", "transmission"),
        PlatformId.KLEINANZEIGEN to setOf("price", "fuel", "transmission"),
        PlatformId.EBAY_DE to setOf("price", "condition"),
        PlatformId.EBAY_COM to setOf("price", "condition"),
        // mobile.de + Marktplaats crawlers are query-only: everything is post-filtered.
        PlatformId.MOBILE_DE to emptySet(),
        PlatformId.MARKTPLAATS to emptySet(),
    )

    /** True when [platform] applies [dimension] at the source (native URL param). */
    fun isNative(platform: PlatformId, dimension: String): Boolean =
        native[platform]?.contains(dimension) == true
}
