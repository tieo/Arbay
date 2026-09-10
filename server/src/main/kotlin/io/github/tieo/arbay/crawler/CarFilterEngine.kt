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
    // Every market that sells anything, as against the vehicle sites where each result is a
    // vehicle by construction. A car search reaches these too, and what they send back for one is
    // mostly parts: the shops among them (Amazon, Geizhals, Idealo) sell nothing else.
    private val GENERAL_PLATFORMS = setOf(
        PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE, PlatformId.MARKTPLAATS, PlatformId.WILLHABEN,
        PlatformId.RICARDO, PlatformId.SUBITO, PlatformId.TWEEDEHANDS,
        PlatformId.AMAZON_DE, PlatformId.GEIZHALS, PlatformId.IDEALO, PlatformId.EBAY_COM,
        PlatformId.EBAY_IT, PlatformId.EBAY_FR, PlatformId.EBAY_ES, PlatformId.VINTED_DE,
        PlatformId.REBUY, PlatformId.REFURBED, PlatformId.BACKMARKET_DE,
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
            """\bzierleiste\b|\bschriftzug\b|\bersatzteile?\b|steuerger(ä|ae)t|""" +
            // Bodies of parts an eBay vehicle search floats up: brake components, glass, a parcel
            // shelf, a radio, a catalytic converter, filters — none the subject of a car-for-sale ad.
            """\bhutablage\b|\bautoradio\b|\b(euro)?kat(alysator)?\b|\bbremssattel\b|\bbremskl(ö|oe)tze\b|""" +
            // Parts a whole-vehicle title never carries at all, wherever they appear in it.
            """\beinstiegblech\b|\bk(ü|ue)hlergrill\b|\bt(ü|ue)rgriff\b|\bquerlenker\b|""" +
            """\bspurstange\b|\bturbolader\b|\banlasser\b|\blichtmaschine\b|""" +
            """\bausr(ü|ue)cklager\b|\b(massen)?schwungrad\b|\babgasrohr\b|\bsto(ß|ss)f(ä|ae)nger\b|""" +
            """\b(ö|oe)lwanne\b|\bzylinderkopf\b|\beinspritzd(ü|ue)se\b|\bhochdruckpumpe\b|""" +
            """\bladeluftk(ü|ue)hler\b|\bkupplungssatz\b|\bt(ü|ue)rrahmen\b|""" +
            """\bbeifahrert(ü|ue)r\b|\bfahrert(ü|ue)r\b|\bpumpen?\b|\bfensterheber\b|""" +
            // Paperwork sold for a model, never the car: an owner's manual, a service book.
            """\b(bedienungsanleitung|betriebsanleitung|serviceplan|handbuch|reparaturhandbuch)\b|""" +
            // Rims/alloys on their own are a wheel ad, not a car; unlike tyres (which a car brags
            // about, "mit neuen Reifen"), a whole car is never titled after its Felgen.
            """\b(alu|stahl)?felgen?\b|\bkomplettr(ä|ae)der\b|\brims?\b|""" +
            // Never a whole vehicle: a repair kit, a bulb, a brochure, a lift kit, a keyring.
            """\breparatursatz\b|\bkeilrippenriemen\b|\bkennzeichenleuchte\b|\bpositionsleuchte\b|""" +
            """\bschl(ü|ue)sselanh(ä|ae)nger\b|\bprospekt\b|\bpreisliste\b|\bh(ö|oe)herlegungs?\s?kit\b|""" +
            """\b(bracket|floor ?mat|seat ?cover|headlight|tail ?light|fender|mudflap|wheel ?trim|badge)\b|""" +
            // Dutch (Marktplaats): unambiguous car-part nouns, never a whole-vehicle listing.
            """\b(koplamp|achterlicht|spatbord|onderdeel|onderdelen|dakdrager|portier|motorkap|spiegelkap|stoelhoezen?)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Bare tyres are softer than rims: a real car brags "mit neuen Reifen", so a tyre word marks an
    // ad only when it also carries a size token (195/65 R15, 17 Zoll) or leads the title — a bragging
    // car does neither. The no-spec gate still exempts any car the site gave specs for.
    private val tyres = Regex("""\b(sommer|winter|ganzjahres)?reifen\b|\btyres?\b""", RegexOption.IGNORE_CASE)
    private val tyreSize = Regex("""\b\d{3}/\d{2}\s?(r|zr)?\s?\d{2}\b|\b1\d\s?(zoll|inch|")\b""", RegexOption.IGNORE_CASE)
    private val leadingTyre = Regex("""^\s*\d*\s*x?\s*(sommer|winter|ganzjahres)?reifen\b""", RegexOption.IGNORE_CASE)
    private fun isTyreAd(title: String) =
        tyres.containsMatchIn(title) && (tyreSize.containsMatchIn(title) || leadingTyre.containsMatchIn(title))

    // Dutch salvage ads name the component and the donor vehicle: "Expansievat van een Volkswagen
    // Crafter", "Spiegel Schakelaar van een ...". A whole vehicle is never described as coming from
    // another vehicle, and a real listing carries verified specs which exempt it from this guard.
    private val partFromDonorVehicle = Regex(
        """\b(van|voor)\s+een\s+\w""",
        RegexOption.IGNORE_CASE,
    )

    // Body panels a van legitimately lists as equipment ("Crafter 35 mit Trennwand"), so they only
    // mark a part when the title leads with them, which is how a parts ad is written.
    // A word like "Zahnriemen" or "Bremsbeläge" is a part when the ad is about it and a selling
    // point when a car mentions what was replaced ("VW Crafter 2.0 TDI Zahnriemen neu"). Position
    // is what tells them apart: the part leads its own ad, and never leads a car's.
    /** A part named near the front of a title, wherever a make's own name got there first: "2X
     *  Sachs Gasdruck Stoßdämpfer hinten", "Kit Wartung Filter Und Öl Mercedes Sprinter 314". Read
     *  as a part only alongside [fitsAVehicle], so a car mentioning what was replaced ("VW Crafter
     *  2.0 TDI Zahnriemen neu") is not one. */
    private val partNamedEarly = Regex(
        """^(\S+\s+){0,3}(bremsbel(ä|ae)ge?|bremsscheiben?|sto(ß|ss)d(ä|ae)mpfer|dichtung(en)?|""" +
            """radlager|z(ü|ue)ndkerzen?|(luft|(ö|oe)l|innenraum)filter|wasserpumpe|auspuff|""" +
            """zahnriemen|keilrippenriemen|wischerbl(ä|ae)tter|kit|set|satz|kette|r(ü|ue)ckfahrkamera|""" +
            """k(ü|ue)hlergrillrahmen|rozrz(ą|a)d)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** What a part says about the vehicles it belongs on. A whole vehicle is not sold "für" or
     *  "passend für" another one. */
    private val fitsAVehicle = Regex(
        """\b(f(ü|ue)r|passend\s+f(ü|ue)r|kompatibel|for|per|pour|para)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val partAccessoryLead = Regex(
        """^\s*(schiebet(ü|ue)r|trennwand|seitenwand|heckt(ü|ue)r|stossstange|sto(ß|ss)stange|""" +
            """bremsbel(ä|ae)ge?|bremsscheiben?|sto(ß|ss)d(ä|ae)mpfer|dichtung(en)?|radlager|""" +
            """z(ü|ue)ndkerzen?|(luft|(ö|oe)l|innenraum)filter|wasserpumpe|auspuff|zahnriemen|""" +
            """keilrippenriemen|wischerbl(ä|ae)tter|ersatzrad|servicepaket|inspektionskit)\b""",
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
            """st(ü|ue)tzen?|griffe?|deckel|schl(ö|oe)sser|schloss|d(ü|ue)sen?|bleche?|halter|""" +
            """scharnier|verst(ä|ae)rker|scheiben?|gitter(sets?|n)?|pumpen?|kn(auf|opf|äufe)|kedern?|filter)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A car-query result that is not a car for sale: a wanted ad, a rental, or a part/accessory.
     *  All matched on the title only (safe, explicit). The part guard is needed because eBay's
     *  price-ascending sort floats up cheap seller-miscategorised parts that sit inside the vehicle
     *  category (a "Drehkonsole" listed under Fahrzeuge); the source category alone does not stop
     *  them. Any listing the site gave structured vehicle specs for (verified mileage / first-reg /
     *  power) is exempt, so a real car is never dropped. No price threshold: a cheap or broken car
     *  is still a car. */
    /**
     * Whether the title names a vehicle part rather than a vehicle.
     *
     * The same vocabulary the vehicle post-filter uses, reachable from the general relevance
     * filter: a search for "sprinter 314" is not recognised as a car search at all — a model
     * without its make is not enough to send a search to the car sites — and came back led by
     * trim strips, sill plates and wheel bolts at 13 to 25 euro.
     */
    fun namesAVehiclePart(listing: Listing): Boolean {
        // A listing the market publishes mileage, a year or power for is a vehicle for sale, even
        // when its title mentions the clutch that was just replaced.
        val hasVehicleSpec = listing.vehicle?.let { v ->
            v.isVerified(VehicleField.MILEAGE) || v.isVerified(VehicleField.FIRST_REG_YEAR) ||
                v.isVerified(VehicleField.POWER)
        } ?: false
        if (hasVehicleSpec) return false
        val title = listing.title
        return partAccessory.containsMatchIn(title) || partAccessoryLead.containsMatchIn(title) ||
            partFromDonorVehicle.containsMatchIn(title) || partSuffix.containsMatchIn(title) ||
            partNumber.containsMatchIn(title) || isTyreAd(title) ||
            (partNamedEarly.containsMatchIn(title) && fitsAVehicle.containsMatchIn(title))
    }

    /** An OEM part number, which is what a parts seller titles a part with and what a whole
     *  vehicle is never titled with: "A9018900065", "A0031532728", "804528". Ten digits of it
     *  cannot be a year, a mileage or a price. */
    private val partNumber = Regex("""\b[A-Z]?\d{6,11}\b(?!\s*(km|tkm|kw|ps|eur|€))""", RegexOption.IGNORE_CASE)

    private fun isLikelyNonVehicle(listing: Listing): Boolean {
        if (listing.platformId !in GENERAL_PLATFORMS) return false
        if (wantedAd.containsMatchIn(listing.title)) return true
        if (rentalAd.containsMatchIn(listing.title)) return true
        // One reading of what a part looks like, shared with the general search filter, so a rule
        // added for one of them cannot quietly leave the other behind.
        return namesAVehiclePart(listing)
    }
}
