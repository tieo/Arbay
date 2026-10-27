package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * What a listing from another country actually costs to have here.
 *
 * A price on a market outside the EU is quoted without the import VAT that is charged on the way
 * in — eBay adds it at checkout — so a US listing at $150 and a German one at €150 look like a
 * €21 saving when the American one really lands at €153.69. An app whose whole point is comparing
 * across borders cannot compare the two numbers that are not the same kind of number.
 *
 * Customs duty is deliberately not modelled: below €150 of goods there is none, and above it the
 * rate depends on the commodity code, which a listing does not carry.
 */
@Serializable
data class ImportSettings(
    /** Where the buyer is, ISO 3166-1 alpha-2. Decides what counts as an import. */
    val homeCountry: String = "DE",
    /** The buyer's own import VAT rate, applied to goods arriving from outside their VAT area. */
    val importVatPercent: Int = 19,
    /** Off leaves every price exactly as the market quoted it. */
    val enabled: Boolean = true,
)

/** The EU VAT area, as far as a purchase from outside it is concerned. */
val EU_VAT_AREA: Set<String> = setOf(
    "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
    "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE",
)

/** Standard VAT by ISO 3166-1 alpha-2 country, as a fraction: what a business there charges a
 *  private buyer on top of a price it calls net. */
val STANDARD_VAT: Map<String, Double> = mapOf(
    "DE" to 0.19, "AT" to 0.20, "CH" to 0.081, "FR" to 0.20, "IT" to 0.22, "ES" to 0.21, "NL" to 0.21,
    "BE" to 0.21, "PL" to 0.23, "CZ" to 0.21, "DK" to 0.25, "SE" to 0.25, "GB" to 0.20, "LU" to 0.17,
    "RO" to 0.21, "SK" to 0.23, "SI" to 0.22, "HU" to 0.27, "PT" to 0.23, "FI" to 0.255, "NO" to 0.25,
)

/** Where this listing is sold from: what the listing itself says, else the market's own country,
 *  else the buyer's own country (a home market carries no origin). */
fun Listing.originCountry(homeCountry: String): String =
    location?.country?.uppercase()?.takeIf { it.length == 2 }
        ?: platformId.country
        ?: homeCountry

/** Whether buying this listing crosses the buyer's VAT border. */
fun ImportSettings.importVatDueOn(listing: Listing): Boolean {
    if (!enabled || importVatPercent <= 0) return false
    val home = homeCountry.uppercase()
    val origin = listing.originCountry(home).uppercase()
    if (origin == home) return false
    // Only the EU case is modelled, because it is the one this app buys in. A buyer outside the EU
    // gets no assumed charge rather than a guessed one.
    if (home !in EU_VAT_AREA) return false
    return origin !in EU_VAT_AREA
}

/**
 * The price to compare with: what the market quotes, plus shipping, plus the import VAT that will
 * be charged if this crosses a VAT border. Same currency as the listing — converting to the display
 * currency stays a separate step.
 */
fun Listing.landedPrice(settings: ImportSettings): Money {
    val base = effectivePrice
    if (!settings.importVatDueOn(this)) return base
    return Money(base.amount + base.amount * settings.importVatPercent / 100, base.currency)
}

/** The import VAT alone, or null when none is due — for showing what was added. */
fun Listing.importVat(settings: ImportSettings): Money? {
    if (!settings.importVatDueOn(this)) return null
    val base = effectivePrice
    return Money(base.amount * settings.importVatPercent / 100, base.currency)
}
