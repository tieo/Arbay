package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class Transmission {
    AUTOMATIC, MANUAL;

    companion object {
        fun parse(raw: String?): Transmission? {
            val s = raw?.lowercase()?.trim() ?: return null
            return when {
                s.isBlank() -> null
                "automat" in s || "dsg" in s || "tiptronic" in s || "s tronic" in s ||
                    "s-tronic" in s || "pdk" in s || "automaat" in s -> AUTOMATIC
                "schalt" in s || "manuell" in s || "manual" in s || "handgeschakeld" in s ||
                    "manuál" in s || "manuel" in s -> MANUAL
                else -> null
            }
        }
    }
}

/** Structured car-search criteria, carried from the UI to the crawler search endpoint,
 *  which turns them into per-site URL filter parameters. Prices are in EUR. */
@Serializable
data class CarFilters(
    val firstRegFromYear: Int? = null,
    val firstRegToYear: Int? = null,
    val minMileageKm: Int? = null,
    val maxMileageKm: Int? = null,
    val minPriceEur: Int? = null,
    val maxPriceEur: Int? = null,
    val minPowerKw: Int? = null,
    val maxPowerKw: Int? = null,
    val transmission: Transmission? = null,
    // Multi-select: a listing matches if its value is in the set (empty = no constraint).
    val fuels: Set<Fuel> = emptySet(),
    val bodyTypes: Set<BodyType> = emptySet(),
    val conditions: Set<VehicleCondition> = emptySet(),
    val colors: Set<String> = emptySet(),
    val drivetrain: Drivetrain? = null,
    val minDoors: Int? = null,
    val minSeats: Int? = null,
    val minEmissionEuro: Int? = null,   // e.g. 6 = at least Euro 6
    val sellerType: SellerType? = null,
    // Panel-van sizes on the app's own scale (VanSize), multi-select: a listing matches if what
    // it verifiably states is in the set. The server reads each listing in its maker's terms, so a
    // size it cannot place for sure, like a Crafter's "L3H2", passes as unchecked.
    val vanLengths: Set<Int> = emptySet(),   // VanSize.SHORT..EXTRA_LONG
    val vanHeights: Set<Int> = emptySet(),   // VanSize.NORMAL_ROOF..SUPER_HIGH_ROOF
    // Wheelbase band in millimetres. No market filters on it and few state it, so this narrows
    // what states one and leaves the rest marked unchecked, like every other criterion here.
    val minWheelbaseMm: Int? = null,
    val maxWheelbaseMm: Int? = null,
    // Free text that must appear in the listing's title or description. A literal match on
    // text we already have, so it works on every platform and can safely exclude.
    val descriptionContains: String? = null,
    // Free-form description of the ideal car ("well kept, tow bar, no accidents, full service
    // history"). Not a hard filter — the server embeds it and ranks results by semantic similarity
    // to each listing's text, surfacing the best matches first. Local embeddings, no API cost.
    val idealDescription: String? = null,
    // Filter strictness (behaviour, not a constraint — excluded from isEmpty). Specs read from the
    // listing text always count (a stated "345.000 km" is honoured), so filtering works on sites with
    // no structured data. strictUnknown: exclude a listing whose filtered spec can't be determined at
    // all (precise but loses coverage); off = keep unknowns.
    val strictUnknown: Boolean = false,
) {
    /**
     * The criteria this listing was never actually checked against: active here, unknown there.
     *
     * A listing whose year, power and gearbox the market never published is kept, because losing
     * every listing that fails to state a spec loses most of the market. Kept is not the same as
     * matching, and only this says which is which.
     */
    fun uncheckedFor(v: VehicleInfo?): List<String> = buildList {
        if ((firstRegFromYear != null || firstRegToYear != null) && v?.firstRegYear == null) add("year")
        if ((minMileageKm != null || maxMileageKm != null) && v?.mileageKm == null) add("km")
        if ((minPowerKw != null || maxPowerKw != null) && v?.powerKw == null) add("power")
        if (transmission != null && v?.gearbox == null) add("gearbox")
        if (fuels.isNotEmpty() && v?.fuel == null) add("fuel")
        if (bodyTypes.isNotEmpty() && v?.bodyType == null) add("body")
        if (conditions.isNotEmpty() && v?.condition == null) add("condition")
        if (drivetrain != null && v?.drivetrain == null) add("drive")
        if (colors.isNotEmpty() && v?.color == null) add("colour")
        if (minDoors != null && v?.doors == null) add("doors")
        if (minSeats != null && v?.seats == null) add("seats")
        if (minEmissionEuro != null && v?.emissionClassEuro == null) add("emission")
        if (sellerType != null) { /* the seller is on the listing itself, not the vehicle */ }
        // A size read out of a word ("Lang") is a guess about one maker's naming, so a van
        // described that way was never actually checked against the size asked for.
        if (vanLengths.isNotEmpty() && v?.vanLength?.takeIf { v.isVerified(VehicleField.VAN_LENGTH) } == null)
            add("length")
        if (vanHeights.isNotEmpty() && v?.vanHeight?.takeIf { v.isVerified(VehicleField.VAN_HEIGHT) } == null)
            add("height")
        if ((minWheelbaseMm != null || maxWheelbaseMm != null) && v?.wheelbaseMm == null) add("wheelbase")
    }

    val isEmpty: Boolean get() =
        firstRegFromYear == null && firstRegToYear == null &&
            minMileageKm == null && maxMileageKm == null &&
            minPriceEur == null && maxPriceEur == null &&
            minPowerKw == null && maxPowerKw == null && transmission == null &&
            fuels.isEmpty() && bodyTypes.isEmpty() && conditions.isEmpty() && colors.isEmpty() &&
            drivetrain == null && minDoors == null && minSeats == null &&
            minEmissionEuro == null && sellerType == null &&
            vanLengths.isEmpty() && vanHeights.isEmpty() &&
            minWheelbaseMm == null && maxWheelbaseMm == null &&
            descriptionContains.isNullOrBlank() && idealDescription.isNullOrBlank()
}

/** Car filters carried inside a saved search, so opening a bookmark reruns it with the
 *  same constraints instead of a bare text search. Null when the query has no car filter.
 *
 *  The price bound is not among the stored filters: every search has one, car or not, so it lives
 *  on the query's own minPrice/maxPrice and is overlaid here. Read through this and the filter view
 *  is complete; write through [withCarFilters] and there is still only one copy to disagree with. */
fun SearchQuery.toCarFilters(): CarFilters? {
    val complete = (carFilters ?: CarFilters()).copy(
        minPriceEur = minPrice?.let { (it.amount / 100).toInt() },
        maxPriceEur = maxPrice?.let { (it.amount / 100).toInt() },
    )
    return if (complete.isEmpty) null else complete
}

/** The vehicle criteria of a search, empty when it carries none. Every crawler reads its native
 *  filter parameters from here, so a criterion cannot reach one site and be silently dropped on
 *  another for want of a field on the query. The price band is not among them: it is not
 *  car-specific and lives on minPrice/maxPrice, where a non-car crawler can reach it too. */
val SearchQuery.carCriteria: CarFilters get() = toCarFilters() ?: CarFilters()

/** Store a filter set on the query, lifting its price bound out to minPrice/maxPrice — the one
 *  place a price is kept, whether or not the search is for a car. */
fun SearchQuery.withCarFilters(filters: CarFilters?): SearchQuery = copy(
    carFilters = filters?.copy(minPriceEur = null, maxPriceEur = null)?.takeUnless { it.isEmpty },
    minPrice = filters?.minPriceEur?.let { Money(it * 100L, Currency.EUR) },
    maxPrice = filters?.maxPriceEur?.let { Money(it * 100L, Currency.EUR) },
)

/** Narrow the search to a price band, in whole euro. Used by the results slider, which edits the
 *  same bound the search form sets. */
fun SearchQuery.withPriceRangeEur(minEur: Int?, maxEur: Int?): SearchQuery = copy(
    minPrice = minEur?.let { Money(it * 100L, Currency.EUR) },
    maxPrice = maxEur?.let { Money(it * 100L, Currency.EUR) },
)

/**
 * The two ways a search is allowed to ask for something other than the words typed into it, each
 * off until it is switched on, and each showing what it will send before it sends it.
 *
 * Both exist because a marketplace matches a title as literal text: the same machine is titled
 * "Parkettschleifer" on one listing and "levigatrice per parquet" on an Italian one, and a search
 * for neither of those finds neither of them. Both used to happen on their own — one behind a
 * server environment variable, one behind a translation call — so the term a market was asked
 * was something the app had decided and never said.
 */
@Serializable
data class SearchReach(
    /** Also search the market's own other names for the thing, taken from the related searches it
     *  prints for this query. The extra terms are reported back per market. */
    val otherWords: Boolean = false,
    /** Also ask a market in its own language, using [termByLanguage] — and only that: a language
     *  with no term here is asked in the words that were typed. */
    val otherLanguages: Boolean = false,
    /** The exact term to send a market that searches in this language, keyed by ISO-639-1 code.
     *  Filled by accepting a suggested translation, and editable, so nothing is ever sent that has
     *  not been seen first. */
    val termByLanguage: Map<String, String> = emptyMap(),
    /** Words picked by hand off the market's own list of other names for the thing. Searched
     *  alongside the typed words whatever [otherWords] says: a word someone chose is not a guess
     *  the app is making. */
    val extraTerms: List<String> = emptyList(),
) {
    val isDefault: Boolean get() = !otherWords && !otherLanguages && extraTerms.isEmpty()
}

/** One of the other words a market printed under a search, with what the app makes of it.
 *
 *  There is no probability here on purpose. Five ways to score how likely a word is to mean the
 *  same thing were measured against hand-labelled pairs — embedding distance, result overlap,
 *  price ratio, reciprocity, learned heads — and none of them separated a synonym from an
 *  accessory. What survives is a set of rules about how the word is built, which is right about
 *  93% of the words it accepts and reaches fewer than a third of the real ones. A number would be
 *  invented; the reason is real. */
@Serializable
data class SuggestedTerm(
    val term: String,
    /** Whether the app would spend a search on this word on its own. */
    val worthTrying: Boolean,
    /** Why, in the words of what the rule looked at. */
    val why: String,
    /** Whether this run actually searched it. */
    val searched: Boolean = false,
    /** How many listings searching it added that the typed words had not already found. Known only
     *  once it has been searched, which costs a crawl — so it is what a word turned out to be
     *  worth, never a promise made before the request. */
    val added: Int? = null,
)

/** Terms offered for a search, one per language, for someone to look at before any of them is
 *  sent anywhere. [unavailable] names the languages nothing could be suggested for, which is a
 *  different thing from a translation that happens to equal the original. */
@Serializable
data class TermSuggestions(
    val text: String,
    val suggestions: Map<String, String> = emptyMap(),
    val unavailable: List<String> = emptyList(),
)

@Serializable
data class SearchQuery(
    val text: String,
    // What kind of thing this search is for — set once, when the search is created, from wherever
    // it was actually started (the vehicle form, a catalogue category, or a plain typed search),
    // and never re-derived from the query text afterward. No default: every call site names its
    // group explicitly rather than silently landing on one. Two things this replaced both failed
    // the same way — carFilters alone can't answer "is this a car search" (a vehicle search with
    // no criteria chosen yet has empty carFilters, indistinguishable from a plain search that
    // never was one), and guessing from words ("does a car make's name appear in this text")
    // misfires on any make that is also an ordinary word — RAM is a real vehicle brand and also
    // what a "32GB SODIMM RAM" listing calls itself. Declared before [platforms] so that field's
    // own default can read it: a platforms list omitted at construction defaults to whatever this
    // category actually reaches, never "every platform including car-only and real-estate sites"
    // — the default this replaced, which silently matched the exact bug it was hiding.
    val category: MarketGroup,
    val platforms: List<PlatformId> = MarketSets.platformsFor(category),
    val minPrice: Money? = null,
    val maxPrice: Money? = null,
    // Which conditions are being looked at. Null or empty means every one of them. Any set is
    // allowed, because any set is a real question: new and used but not for-parts, refurbished
    // only, for-parts only. It was a three-way switch (new / used / any) where "used" meant
    // "anything that is not new", so a listing sold for parts could not be told from a working
    // one, and asking for two of the eight was not expressible at all.
    val condition: List<Condition>? = null,
    /** Whether listings whose market never said what condition they are in are shown. */
    val conditionUnstated: Boolean = true,
    // How the thing is sold. An auction's price is the bid so far and rises until it ends, so a
    // search for what something costs is a different search from one that includes auctions.
    // Null or empty means both.
    val saleTypes: List<SaleType>? = null,
    /** Whether listings whose market never said how they are sold are shown. */
    val saleTypeUnstated: Boolean = true,
    // How the results are ordered. Part of the search rather than of the screen showing it, so
    // reopening a saved search restores the order it was left in.
    val sort: SortMode? = null,
    // Which of the fetched markets the results are narrowed to, and which countries. Empty means
    // every market that answered. These filter what was already fetched; they do not change which
    // markets are asked, which is `platforms`.
    val showOnlyMarkets: Set<PlatformId> = emptySet(),
    val showOnlyCountries: Set<String> = emptySet(),
    val soldOnly: Boolean = false,
    val freeOnly: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
    // Where the search is centred and how far it reaches. Both are set by hand or not at all: a
    // search with no place and no radius covers everywhere, so nothing is ever removed for being
    // far away unless someone asked for a place. A default radius here quietly fenced in every
    // search that predates the field, since a stored search that never wrote it decodes to it.
    val location: String? = null,   // city name / zip for location-based search
    val radiusKm: Int = 0,          // search radius in km; 0 = everywhere
    // The searcher's position (from the device GPS). When set, the server geocodes each listing and
    // fills in distanceKm, so results can be shown and sorted nearest-first.
    val userLat: Double? = null,
    val userLon: Double? = null,
    val maxPages: Int? = null,      // override crawler's default page limit (null = use CrawlerConfig)
    val startPage: Int = 1,         // start from this page (for paginated batches)
    // Vehicle criteria, read through carCriteria: crawlers turn what their site supports into
    // native URL parameters, and post-filtering enforces the rest.
    val carFilters: CarFilters? = null,
    // Alternate phrasings that count as this same search — a listing matching any one of these as
    // well as [text] is a match (e.g. text="Fujifilm X-T5", aliases=["XT5"]). Structured data, not
    // embedded "OR" syntax: the field the user searches stays exactly what they typed or what a
    // catalog entry names as its one canonical phrase; an alternate spelling is its own thing.
    val aliases: List<String> = emptyList(),
    // How far the search may travel from the words that were typed. Off on both counts unless
    // someone turns it on: a search that quietly asks a market something other than what was typed
    // makes a wrong answer unexplainable, since the two sides can no longer be compared.
    val reach: SearchReach = SearchReach(),
) {
    /** The one phrase to hand a platform search box that only takes a single term — [text] itself,
     *  or the most descriptive of [aliases] when [text] is less specific than one of them. */
    val positiveText: String
        get() = (listOf(text) + aliases).maxByOrNull { it.trim().split("\\s+".toRegex()).size } ?: text
}
