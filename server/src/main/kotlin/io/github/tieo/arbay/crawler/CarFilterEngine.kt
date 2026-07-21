package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.VehicleInfo

/**
 * Enforces car filters against the parsed VehicleInfo, so a constraint a marketplace can't
 * apply server-side (mileage on Kleinanzeigen, anything on the query-only crawlers) is still
 * honored from our own data. A listing is dropped only when it has the relevant value and it
 * fails; a missing value is a soft pass (unknown != excluded), matching the "note it, don't
 * silently hide" rule; the coverage note for unverified filters is layered on separately.
 *
 * Also drops obvious non-vehicles (parts/accessories) that leak from general classifieds on a
 * car query: no year, mileage, power or displacement and a price too low to be a running car.
 */
object CarFilterEngine {

    /** Sites that carry non-car inventory, where a car query can return parts/accessories. */
    private val GENERAL_PLATFORMS = setOf(
        PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE, PlatformId.MARKTPLAATS, PlatformId.WILLHABEN,
        PlatformId.RICARDO, PlatformId.SUBITO, PlatformId.TWEEDEHANDS,
    )

    /** @param keepNonVehicles when true, the parts/accessories guard is skipped, for a query that
     *  is itself asking for a part ("Crafter Drehkonsole") rather than a whole vehicle. */
    fun apply(listings: List<Listing>, filters: CarFilters, keepNonVehicles: Boolean = false): List<Listing> =
        listings.map { annotateVanDims(it) }.filter { keep(it, filters, keepNonVehicles) }

    /** A car query that is really after a part or accessory, so the non-vehicle guard must not fire.
     *  Recognised from the same part nouns the guard drops, plus the wheels/tyres wording those
     *  omit because a car-for-sale never leads with them. */
    fun isPartQuery(text: String): Boolean =
        partAccessory.containsMatchIn(text) || partAccessoryLead.containsMatchIn(text) ||
            partFromDonorVehicle.containsMatchIn(text) || partSuffix.containsMatchIn(text) ||
            Regex("""\b(felge|felgen|reifen|winterreifen|sommerreifen|kompletträder|alufelgen|tyres?|wheels?)\b""",
                RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** For each active filter dimension, how many more of [candidates] would pass if that one
     *  filter were dropped (all others kept): the count a chip is hiding. Computed locally over
     *  the fetched candidate set; only card/text-derived specs are known, so it is an estimate
     *  for detail-only fields (fuel/gearbox), exact for price/year/mileage/power/van size. */
    fun facetCounts(candidates: List<Listing>, filters: CarFilters): Map<String, Int> {
        if (filters.isEmpty) return emptyMap()
        val base = apply(candidates, filters).size
        val out = mutableMapOf<String, Int>()
        fun probe(key: String, relaxed: CarFilters) {
            if (relaxed != filters) out[key] = apply(candidates, relaxed).size - base
        }
        probe("price", filters.copy(minPriceEur = null, maxPriceEur = null))
        probe("year", filters.copy(firstRegFromYear = null, firstRegToYear = null))
        probe("mileage", filters.copy(minMileageKm = null, maxMileageKm = null))
        probe("power", filters.copy(minPowerKw = null, maxPowerKw = null))
        probe("transmission", filters.copy(transmission = null))
        probe("fuel", filters.copy(fuels = emptySet()))
        probe("bodyType", filters.copy(bodyTypes = emptySet()))
        probe("condition", filters.copy(conditions = emptySet()))
        probe("color", filters.copy(colors = emptySet()))
        probe("drivetrain", filters.copy(drivetrain = null))
        probe("doors", filters.copy(minDoors = null))
        probe("seats", filters.copy(minSeats = null))
        probe("emission", filters.copy(minEmissionEuro = null))
        probe("seller", filters.copy(sellerType = null))
        probe("vanLength", filters.copy(vanLengths = emptySet()))
        probe("vanHeight", filters.copy(vanHeights = emptySet()))
        probe("description", filters.copy(descriptionContains = null))
        return out
    }

    /** Fill the van size classes from the listing text. A size the listing states, either an
     *  explicit code ("L3H2") or a wheelbase/roof word ("Maxi", "lang", "Hochdach"), is a known
     *  value that the filter may exclude on: filtering L3 must drop a van that says "Maxi" (L4).
     *  Only a van that states nothing is soft-passed. */
    private fun annotateVanDims(listing: Listing): Listing {
        val text = "${listing.title} ${listing.description ?: ""}"
        val inferred = VanDimensions.inferred(text)
        if (inferred.length == null && inferred.height == null) return listing
        val v = listing.vehicle ?: VehicleInfo()
        val verified = v.verified.toMutableSet()
        if (inferred.length != null) verified += VehicleField.VAN_LENGTH
        if (inferred.height != null) verified += VehicleField.VAN_HEIGHT
        return listing.copy(
            vehicle = v.copy(vanLength = inferred.length, vanHeight = inferred.height, verified = verified),
        )
    }

    private fun keep(listing: Listing, filters: CarFilters, keepNonVehicles: Boolean = false): Boolean {
        val v = listing.vehicle

        if (!keepNonVehicles && isLikelyNonVehicle(listing)) return false

        // Find-in-description: every whitespace-separated term must appear in the title or
        // description. This is a literal match on text we hold, so excluding is safe.
        filters.descriptionContains?.takeIf { it.isNotBlank() }?.let { needle ->
            val haystack = "${listing.title} ${listing.description ?: ""}".lowercase()
            if (!needle.lowercase().split(Regex("\\s+")).all { it.isBlank() || haystack.contains(it) })
                return false
        }

        // Price is always known (it's on the listing), so it can always exclude.
        val priceEur = priceEurCents(listing) / 100
        filters.minPriceEur?.let { if (priceEur < it) return false }
        filters.maxPriceEur?.let { if (priceEur > it) return false }

        // Seller type comes from the listing, not VehicleInfo.
        filters.sellerType?.let { want -> listing.seller?.type?.let { if (it != want) return false } }

        // Per-spec strictness. A spec is "known" when its value is present — whether from the site's
        // structured data or read from the listing text (a stated "345.000 km" counts). Known +
        // out-of-range drops. Unknown drops only in strict mode; otherwise soft-pass (keep, don't
        // lose a listing that simply doesn't state that spec).
        val strict = filters.strictUnknown
        fun drop(active: Boolean, present: Boolean, matches: () -> Boolean): Boolean {
            if (!active) return false
            return if (present) !matches() else strict
        }

        if (drop(filters.firstRegFromYear != null || filters.firstRegToYear != null, v?.firstRegYear != null) {
                val y = v!!.firstRegYear!!
                (filters.firstRegFromYear?.let { y >= it } ?: true) && (filters.firstRegToYear?.let { y <= it } ?: true)
            }) return false
        if (drop(filters.minMileageKm != null || filters.maxMileageKm != null, v?.mileageKm != null) {
                val km = v!!.mileageKm!!
                (filters.minMileageKm?.let { km >= it } ?: true) && (filters.maxMileageKm?.let { km <= it } ?: true)
            }) return false
        if (drop(filters.minPowerKw != null || filters.maxPowerKw != null, v?.powerKw != null) {
                val kw = v!!.powerKw!!
                (filters.minPowerKw?.let { kw >= it } ?: true) && (filters.maxPowerKw?.let { kw <= it } ?: true)
            }) return false
        if (drop(filters.transmission != null, v?.gearbox != null) { v!!.gearbox == filters.transmission }) return false
        if (drop(filters.fuels.isNotEmpty(), v?.fuel != null) { v!!.fuel in filters.fuels }) return false
        if (drop(filters.bodyTypes.isNotEmpty(), v?.bodyType != null) { v!!.bodyType in filters.bodyTypes }) return false
        if (drop(filters.conditions.isNotEmpty(), v?.condition != null) { v!!.condition in filters.conditions }) return false
        if (drop(filters.drivetrain != null, v?.drivetrain != null) { v!!.drivetrain == filters.drivetrain }) return false
        if (drop(filters.minDoors != null, v?.doors != null) { v!!.doors!! >= filters.minDoors!! }) return false
        if (drop(filters.minSeats != null, v?.seats != null) { v!!.seats!! >= filters.minSeats!! }) return false
        if (drop(filters.minEmissionEuro != null, v?.emissionClassEuro != null) { v!!.emissionClassEuro!! >= filters.minEmissionEuro!! }) return false
        if (drop(filters.colors.isNotEmpty(), v?.color != null) { val c = v!!.color!!; filters.colors.any { c.contains(it, ignoreCase = true) } }) return false
        if (drop(filters.vanLengths.isNotEmpty(), v?.vanLength != null) { v!!.vanLength in filters.vanLengths }) return false
        if (drop(filters.vanHeights.isNotEmpty(), v?.vanHeight != null) { v!!.vanHeight in filters.vanHeights }) return false
        return true
    }

    private fun priceEurCents(listing: Listing): Long =
        if (listing.price.currency == Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")

    // Wanted-ad openers on the German classifieds: someone looking to BUY, not a car for sale.
    private val wantedAd = Regex("""^\s*(suche|suchen|gesucht|kaufe|ankauf|ankaufe|biete geld)\b""", RegexOption.IGNORE_CASE)

    // Rental/hire ads: a van offered to rent, not to buy. These carry real specs (mileage,
    // power), so they must be caught before the spec-signal exemption below.
    private val rentalAd = Regex("""\b(mieten|zu mieten|vermiet\w+|mietwagen|leihwagen|autovermietung|langzeitmiete|tagesmiete)\b""", RegexOption.IGNORE_CASE)

    // A part or accessory, never a whole vehicle. Only nouns that a car-for-sale title would not
    // lead with; deliberately not the "recently replaced" parts a seller brags about (Zahnriemen,
    // Kupplung, Bremsscheibe, Turbolader), which appear in genuine car titles and would false-drop.
    private val partAccessory = Regex(
        """\b(dreh|sitz|mittel)?konsole\b|\bhalterung\b|\bsitzbez(ug|üge|uege)\b|\bfu(ß|ss)matten\b|""" +
            """\bgummimatten\b|\bkofferraumwanne\b|\bdach(gepäck)?träger\b|\bradkappen?\b|\babdeckplane\b|""" +
            """\bwindschott\b|\bspiegelglas\b|\bscheinwerfer\b|\brück(leuchte|licht)\b|\bkotflügel\b|""" +
            """\bzierleiste\b|\bschriftzug\b|\bersatzteile?\b|\bsteuergerät\b|""" +
            // Never a whole vehicle: a repair kit, a bulb, a brochure, a lift kit, a keyring.
            """\breparatursatz\b|\bkeilrippenriemen\b|\bkennzeichenleuchte\b|\bpositionsleuchte\b|""" +
            """\bschl(ü|ue)sselanh(ä|ae)nger\b|\bprospekt\b|\bpreisliste\b|\bh(ö|oe)herlegungs?\s?kit\b|""" +
            """\b(bracket|floor ?mat|seat ?cover|headlight|tail ?light|fender|mudflap|wheel ?trim|badge)\b|""" +
            // Dutch (Marktplaats): unambiguous car-part nouns, never a whole-vehicle listing.
            """\b(koplamp|achterlicht|spatbord|onderdeel|onderdelen|dakdrager|portier|motorkap|spiegelkap|stoelhoezen?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Dutch salvage ads name the component and the donor vehicle: "Expansievat van een Volkswagen
    // Crafter", "Spiegel Schakelaar van een ...". A whole vehicle is never described as coming from
    // another vehicle, and a real listing carries verified specs which exempt it from this guard.
    private val partFromDonorVehicle = Regex(
        """\b(van|voor)\s+een\s+\w""",
        RegexOption.IGNORE_CASE,
    )

    // Body panels a van legitimately lists as equipment ("Crafter 35 mit Trennwand"), so they only
    // mark a part when the title leads with them, which is how a parts ad is written.
    private val partAccessoryLead = Regex(
        """^\s*(schiebet(ü|ue)r|trennwand|seitenwand|heckt(ü|ue)r|stossstange|sto(ß|ss)stange)\b""",
        RegexOption.IGNORE_CASE,
    )

    // German car parts are compounds whose head noun is a small set of morphemes a whole-vehicle
    // title never leads with: a Golf is sold as "VW Golf 1.4 TSI", never as an "-schalter" or
    // "-leuchte". Catching the suffix generalises past a fixed noun list (Radzierblende,
    // Fensterheberschalter, Einstiegsleuchte, Schachtleiste, Kopfstütze all fall out of one rule).
    // Gated on the no-spec branch, so a real car the site gave specs for is never touched.
    // (?U) makes \w match umlauts, so a compound like "Türverkleidung" keeps its head noun intact
    // (without it, ü splits the word and the two-letter stem before the suffix is lost).
    private val partSuffix = Regex(
        """(?U)\b\w{2,}(schalter|leuchten?|leisten?|blenden?|verkleidung(en)?|abdeckung(en)?|""" +
            """st(ü|ue)tzen?|griffe?|deckel|schl(ö|oe)sser|schloss|d(ü|ue)sen?|bleche?|halter)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A car-query result that is not a car for sale: a wanted ad, a rental, or a part/accessory.
     *  All matched on the title only (safe, explicit). The part guard is needed because eBay's
     *  price-ascending sort floats up cheap seller-miscategorised parts that sit inside the vehicle
     *  category (a "Drehkonsole" listed under Fahrzeuge); the source category alone does not stop
     *  them. Any listing the site gave structured vehicle specs for (verified mileage / first-reg /
     *  power) is exempt, so a real car is never dropped. No price threshold: a cheap or broken car
     *  is still a car. */
    private fun isLikelyNonVehicle(listing: Listing): Boolean {
        if (listing.platformId !in GENERAL_PLATFORMS) return false
        if (wantedAd.containsMatchIn(listing.title)) return true
        if (rentalAd.containsMatchIn(listing.title)) return true
        val hasVehicleSpec = listing.vehicle?.let { v ->
            v.isVerified(VehicleField.MILEAGE) || v.isVerified(VehicleField.FIRST_REG_YEAR) ||
                v.isVerified(VehicleField.POWER)
        } ?: false
        if (!hasVehicleSpec && partAccessory.containsMatchIn(listing.title)) return true
        if (!hasVehicleSpec && partAccessoryLead.containsMatchIn(listing.title)) return true
        if (!hasVehicleSpec && partFromDonorVehicle.containsMatchIn(listing.title)) return true
        if (!hasVehicleSpec && partSuffix.containsMatchIn(listing.title)) return true
        return false
    }
}
