package io.github.tieo.arbay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.loadBannedIds
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.saveBannedIds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import io.github.tieo.arbay.model.SortMode
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
data class PlatformStatus(
    val platformId: String,
    val platformName: String,
    val status: PlatformSearchStatus,
    val resultCount: Int = 0,
    val rawCount: Int = 0,
    val error: String? = null,
    val errorType: String? = null,
    val captchaUrl: String? = null,
    val fetchStage: String? = null,
    // The translated term a cross-border market was searched with, when it differs from the query.
    val queryUsed: String? = null,
    // The market had further pages that this search did not fetch, so its answer is a sample.
    val hasMore: Boolean = false,
    // The answer came from a stored crawl rather than a fresh one.
    val fromCache: Boolean = false,
)

class ListingViewModel(
    private val client: ArbayClient = ArbayClient(),
    // Results and market answers handed in rather than crawled, so the views can be rendered with
    // no server to ask. Empty everywhere except the gallery renderer.
    sample: List<Listing> = emptyList(),
    sampleStatuses: List<PlatformStatus> = emptyList(),
    // A search that has not finished, so the view can be drawn while it waits.
    sampleLoading: Boolean = false,
    // Markets asked but not yet answered, for the same reason.
    sampleTotal: Int = 0,
    sampleCompleted: Int = 0,
    // Blocked words reach this through an effect, and an effect does not run in the
    // single frame a render draws, so a render of "the filters admit none" quietly
    // showed everything.
    sampleBlocked: List<String> = emptyList(),
) : ViewModel() {

    private val rendersASample = sample.isNotEmpty() || sampleStatuses.isNotEmpty() || sampleLoading

    // All results from the search (unfiltered by platform)
    private val _allListings = MutableStateFlow(sample)

    private val _loading = MutableStateFlow(sampleLoading)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Which markets the results are narrowed to, and which countries. Empty means every market
    // that answered. A country selects every market whose listings are in it, so the two grains of
    // the same question stay one filter rather than two that can contradict each other.
    private val _shownMarkets = MutableStateFlow<Set<PlatformId>>(emptySet())
    val shownMarkets: StateFlow<Set<PlatformId>> = _shownMarkets

    private val _shownCountries = MutableStateFlow<Set<String>>(emptySet())
    val shownCountries: StateFlow<Set<String>> = _shownCountries

    // What each market can do, so the app can say why a field is empty instead of leaving it blank.
    private val _capabilities = MutableStateFlow<Map<PlatformId, MarketCapability>>(emptyMap())
    val capabilities: StateFlow<Map<PlatformId, MarketCapability>> = _capabilities

    init {
        viewModelScope.launch {
            runCatching { client.getMarketCapabilities() }
                .onSuccess { list -> _capabilities.value = list.associateBy { it.platform } }
        }
    }

    private val _platformStatuses = MutableStateFlow(sampleStatuses)
    val platformStatuses: StateFlow<List<PlatformStatus>> = _platformStatuses

    private val _totalPlatforms = MutableStateFlow(sampleTotal)
    val totalPlatforms: StateFlow<Int> = _totalPlatforms

    private val _completedPlatforms = MutableStateFlow(sampleCompleted)
    val completedPlatforms: StateFlow<Int> = _completedPlatforms

    private val _bannedIds = MutableStateFlow<Set<String>>(loadBannedIds())
    val bannedIds: StateFlow<Set<String>> = _bannedIds

    private val _priceHistory = MutableStateFlow<List<Listing>>(emptyList())
    val priceHistory: StateFlow<List<Listing>> = _priceHistory

    private val _soldLoading = MutableStateFlow(false)
    val soldLoading: StateFlow<Boolean> = _soldLoading

    // Per active car filter, how many more results dropping it would add — summed across platforms.
    private val _facets = MutableStateFlow<Map<String, Int>>(emptyMap())
    val facets: StateFlow<Map<String, Int>> = _facets

    // Per-bookmark blocked keywords: a listing whose title or description contains any of these
    // terms is hidden. Filtered client-side (never sent to a crawler's own search).
    private val _blockedTerms = MutableStateFlow(sampleBlocked)
    val blockedTerms: StateFlow<List<String>> = _blockedTerms

    /** Text reduced to its words, lowercase and single-spaced, so a match does not depend on the
     *  punctuation a seller happened to type. */
    private fun wordsOnly(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // Derived: listings filtered by selected platform, not banned, not matching a blocked keyword.
    private val _marketFilter = combine(_shownMarkets, _shownCountries) { markets, countries ->
        markets to countries
    }

    /** Whether a listing survives everything except the market and country picks: not banned by
     *  hand, and carrying none of the blocked words. Punctuation is collapsed to single spaces on
     *  both sides, so a blocked word still matches "OVP!Lagerverkauf" and a blocked phrase still
     *  matches "NEU ! Lagerverkauf". */
    private fun kept(listing: Listing, banned: Set<String>, blocked: List<String>): Boolean {
        if (listing.id in banned) return false
        if (blocked.isEmpty()) return true
        val hay = wordsOnly("${listing.title} ${listing.description ?: ""}")
        return blocked.none { it.isNotBlank() && hay.contains(wordsOnly(it)) }
    }

    /**
     * Everything that survives every filter except the market and country picks.
     *
     * This is what the market picker lists. Building that list out of [listings] instead meant the
     * choices were whatever was already showing, so picking one market deleted every other market
     * from the list and a second one could never be picked.
     */
    val marketBasis: StateFlow<List<Listing>> =
        combine(_allListings, _bannedIds, _blockedTerms) { all, banned, blocked ->
            all.filter { kept(it, banned, blocked) }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            // The first value, before the flow above has run once. Handed a sample and words to
            // block, the blocking has to be in that first value too, or a single frame shows
            // everything and a render of "the filters admit none" is a picture of the opposite.
            sample.filter { kept(it, emptySet(), sampleBlocked) },
        )

    val listings: StateFlow<List<Listing>> = combine(marketBasis, _marketFilter) { kept, filter ->
        val (markets, countries) = filter
        // Markets and countries are one list of picks at two grains, so they add up rather than
        // narrow each other: picking Germany and ricardo.ch shows both, where requiring both at
        // once would show nothing. Nothing picked means everything.
        val narrowed = markets.isNotEmpty() || countries.isNotEmpty()
        val picked = kept.filter { listing ->
            !narrowed ||
                listing.platformId in markets ||
                MarketSets.countryOf(listing.platformId) in countries
        }
        collapseRepeats(picked)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        sample.filter { kept(it, emptySet(), sampleBlocked) },
    )

    /**
     * One row per thing offered, not one per market offering it.
     *
     * The same seller lists the same item on every eBay locale, so a search comes back with the
     * identical title three or six times over — in one measured search, 106 of 400 results were
     * repeats of 42 titles. Which of the copies to keep is not a matter of taste here: the point of
     * the app is the cheapest way to get the thing, so the cheapest copy stays and the rest go. The
     * comparison across borders is not lost, it is the thing being decided.
     */
    private fun collapseRepeats(listings: List<Listing>): List<Listing> {
        if (listings.size < 2) return listings
        val seen = HashMap<String, Listing>(listings.size)
        val order = ArrayList<String>(listings.size)
        for (l in listings) {
            val key = l.title.lowercase().filter { it.isLetterOrDigit() }
            if (key.length < 12) {
                // Too short to be sure two listings with it are the same thing.
                order.add(l.id)
                seen[l.id] = l
                continue
            }
            val existing = seen[key]
            if (existing == null) {
                order.add(key)
                seen[key] = l
            } else if (priceOf(l) < priceOf(existing)) {
                seen[key] = l
            }
        }
        return order.mapNotNull { seen[it] }
    }

    /** Everything the markets returned, before any filter of ours. What the empty results screen
     *  needs to tell "nobody had one" apart from "the filters hide all of them". */
    val fetched: StateFlow<List<Listing>> = _allListings

    /** Set the active blocked-keyword list (from the bookmark being viewed). */
    fun setBlockedTerms(terms: List<String>) { _blockedTerms.value = terms }

    /** Add a word/phrase to the block list (from a listing's Block button). Returns the new list. */
    fun blockTerm(term: String): List<String> {
        val t = term.trim()
        if (t.isNotEmpty() && _blockedTerms.value.none { it.equals(t, ignoreCase = true) }) {
            _blockedTerms.value = _blockedTerms.value + t
        }
        return _blockedTerms.value
    }

    /** Remove a blocked word/phrase (from its removable chip). Returns the new list. */
    fun unblockTerm(term: String): List<String> {
        _blockedTerms.value = _blockedTerms.value.filterNot { it.equals(term, ignoreCase = true) }
        return _blockedTerms.value
    }

    fun ban(listing: Listing) {
        val updated = _bannedIds.value + listing.id
        _bannedIds.value = updated
        saveBannedIds(updated)
    }

    // Why this search never reached the markets, when it did not: the server's own words.
    private var refusedBy: String? = null

    /**
     * Said once, at the top, when the search did not run at all: the server was busy, or could not
     * be reached, and what is on the screen is what it had stored from an earlier run. Every market
     * then reading "no answer" is seven copies of one fact, and none of them the fact itself.
     */
    private val _notSearched = MutableStateFlow<String?>(null)
    val notSearched: StateFlow<String?> = _notSearched

    private var searchJob: Job? = null
    private var carFilters: CarFilters? = null
    // What must not appear, and alternate phrasings that count as this same search — set once per
    // search() call, alongside carFilters, so a re-search (refresh, sold history) uses the same
    // identity as the search that first ran.
    private var excludeKeywords: List<String> = emptyList()
    private var aliases: List<String> = emptyList()

    /** Show only these markets; empty shows every market that answered. */
    fun showMarkets(markets: Set<PlatformId>) { _shownMarkets.value = markets }

    /** Show only markets whose listings are in these countries; empty shows every country. */
    fun showCountries(countries: Set<String>) { _shownCountries.value = countries }

    /**
     * Show listings the server already holds, without crawling for them: a saved search's own
     * backlog, served as the watch stored it. [refresh] still crawls from here, so this is a
     * starting point rather than a dead end.
     */
    fun showStored(query: String, stored: List<Listing>) {
        if (rendersASample) return
        searchJob?.cancel()
        _searchQuery.value = query
        _loading.value = false
        _error.value = null
        refusedBy = null
        _notSearched.value = null
        _platformStatuses.value = emptyList()
        _totalPlatforms.value = 0
        _completedPlatforms.value = 0
        _facets.value = emptyMap()
        _priceHistory.value = emptyList()
        _allListings.value = stored
    }

    fun refresh(platforms: List<PlatformId>? = null) {
        val query = _searchQuery.value
        if (query.isBlank() || _loading.value) return
        _allListings.value = emptyList()
        search(query, platforms, carFilters, excludeKeywords, aliases, force = true)
    }

    fun searchSold() {
        val query = _searchQuery.value
        if (query.isBlank() || _soldLoading.value) return
        viewModelScope.launch {
            _soldLoading.value = true
            try {
                val history = try { client.getPriceHistory(query) } catch (_: Exception) { emptyList() }
                // Sold data is an eBay capability (LH_Sold): query every eBay locale, not just DE/COM,
                // for more completed listings. The active car filters are applied server-side so the
                // sold history matches the same year/mileage/power constraints as the live results.
                val ebayPlatforms = listOf(
                    PlatformId.EBAY_DE, PlatformId.EBAY_COM,
                    PlatformId.EBAY_IT, PlatformId.EBAY_FR, PlatformId.EBAY_ES,
                )
                val filters = carFilters
                val freshSold = ebayPlatforms.flatMap { platform ->
                    try {
                        client.crawlerSearch(
                            query, platform, limit = 500, sold = true, carFilters = filters,
                            excludeKeywords = excludeKeywords, aliases = aliases,
                        )
                    } catch (_: Exception) { emptyList() }
                }
                val seen = mutableSetOf<String>()
                _priceHistory.value = (freshSold + history + _priceHistory.value)
                    .filter { it.sold && seen.add(it.id) }
                    .sortedByDescending { it.soldDate ?: it.scrapedAt }
            } catch (_: Exception) {
            } finally {
                _soldLoading.value = false
            }
        }
    }

    // Device position, sent so the server fills in each listing's distance; and whether to order the
    // results nearest-first.
    private var userLat: Double? = null
    private var userLon: Double? = null
    private val _sortMode = MutableStateFlow(SortMode.BEST_MATCH)
    val sortMode: StateFlow<SortMode> = _sortMode
    // Kept for the callers that only ask "are we nearest-first?".
    val sortByDistance: StateFlow<Boolean> = _sortMode
        .map { it == SortMode.NEAREST }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setLocation(lat: Double?, lon: Double?) {
        userLat = lat
        userLon = lon
        // Measure the results already in hand against the new position. No crawl: the server
        // resolved each listing's coordinates when it delivered them.
        _allListings.value = sortListings(_allListings.value)
    }

    /** Distance from the device to each listing, computed locally from the coordinates the server
     *  resolved. A listing whose location could not be geocoded keeps a null distance. */
    private fun withDistances(list: List<Listing>): List<Listing> {
        val lat = userLat ?: return list
        val lon = userLon ?: return list
        return list.map { l -> GeoDistance.to(l, lat, lon)?.let { l.copy(distanceKm = it) } ?: l }
    }

    fun setSortByDistance(on: Boolean) {
        setSortMode(if (on) SortMode.NEAREST else SortMode.BEST_MATCH)
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _allListings.value = sortListings(_allListings.value)
    }

    private fun priceOf(listing: Listing): Long =
        DisplayCurrency.convert(listing.comparablePrice.amount, listing.comparablePrice.currency.name)

    /** Order results by the mode the user picked from the sort menu, after measuring each one
     *  against the device position. */
    /**
     * Listings that do not contain the searched word, when only one word was searched.
     *
     * A market's own search matches more loosely than the word given to it — eBay answered "grigri"
     * with "gris perle" and belt buckles, all of them a euro, all of them ahead of the actual belay
     * devices once the list is ordered by price. They are not removed, because a single word is
     * often a category a listing need not repeat: a ThinkPad X1 is a laptop without saying so. They
     * go last instead, so cheapest-first cannot put what the market guessed above what was asked
     * for.
     */
    private fun offQuery(listing: Listing): Boolean {
        val token = _searchQuery.value.trim().lowercase()
            .takeIf { it.isNotEmpty() && !it.contains(' ') }
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.length >= 3 } ?: return false
        return !"${listing.title} ${listing.description ?: ""}".lowercase()
            .filter { it.isLetterOrDigit() }
            .contains(token)
    }

    private fun sortListings(raw: List<Listing>): List<Listing> = withDistances(raw).let { list ->
        // Whatever the order asked for, what the market only guessed at comes after what was
        // actually asked for. Otherwise cheapest-first is an order over the guesses.
        val asked = compareBy<Listing> { offQuery(it) }
        when (_sortMode.value) {
        SortMode.NEAREST -> list.sortedWith(
            asked.thenBy { it.distanceKm ?: Double.MAX_VALUE }.thenBy { priceOf(it) })
        SortMode.PRICE_ASC -> list.sortedWith(asked.thenBy { priceOf(it) })
        SortMode.PRICE_DESC -> list.sortedWith(asked.thenByDescending { priceOf(it) })
        // Newest means when the ad was posted, not when this app happened to fetch it — which is
        // roughly now for everything and made this sort do nothing. A market that publishes no
        // date cannot be ordered, so those keep their order and go last.
        SortMode.NEWEST -> list.sortedWith(
            asked.thenByDescending<Listing> { it.listingDate ?: it.soldDate }
                .thenByDescending { it.listingDate != null || it.soldDate != null })
        SortMode.BEST_MATCH -> list.sortedWith(
            asked.thenByDescending<Listing> { it.matchScore ?: Double.NEGATIVE_INFINITY }
                .thenBy { priceOf(it) })
        }
    }

    /**
     * Give every market that produced listings a status, when the stream did not.
     *
     * The stream reports per-market progress, but the two fallback paths and a cached answer hand
     * back listings with no events at all. The screen then counted the markets it had heard from,
     * which was none, and said "0 answered" above a list of offers those very markets had sent.
     * A market whose listings are on the screen has answered, whatever the transport was.
     */
    /** Update this market's status, adding it if the stream never announced it starting. */
    private fun upsertStatus(
        platform: String,
        name: String,
        change: (PlatformStatus) -> PlatformStatus,
    ) {
        val existing = _platformStatuses.value.firstOrNull { it.platformId == platform }
        _platformStatuses.value =
            if (existing != null) _platformStatuses.value.map { if (it.platformId == platform) change(it) else it }
            else _platformStatuses.value + change(
                PlatformStatus(platformId = platform, platformName = name, status = PlatformSearchStatus.PENDING),
            )
    }

    private fun accountForListingsWithoutAStatus(asked: List<PlatformId>?) {
        val results = _allListings.value
        if (results.isEmpty()) return
        val known = _platformStatuses.value.map { it.platformId }.toSet()
        val missing = results.groupBy { it.platformId }.filterKeys { it.name !in known }
        if (missing.isNotEmpty()) {
            _platformStatuses.value = _platformStatuses.value + missing.map { (platform, items) ->
                PlatformStatus(
                    platformId = platform.name,
                    platformName = platform.displayName,
                    status = PlatformSearchStatus.DONE,
                    resultCount = items.size,
                    rawCount = items.size,
                    fromCache = true,
                )
            }
        }
        // A market the run never mentioned still belongs on the record: it was asked and said
        // nothing at all, which is a different thing from having nothing to give.
        val heardFrom = _platformStatuses.value.map { it.platformId }.toSet()
        val silent = asked.orEmpty().filter { it.name !in heardFrom }
        if (silent.isNotEmpty()) {
            val why = "not searched"
            _platformStatuses.value = _platformStatuses.value + silent.map { platform ->
                PlatformStatus(
                    platformId = platform.name,
                    platformName = platform.displayName,
                    status = PlatformSearchStatus.ERROR,
                    error = why,
                )
            }
        }

        // The denominator is what was asked, and a run that never announced itself still asked
        // whatever this search covers.
        val counted = _platformStatuses.value.size
        if (_totalPlatforms.value < counted) _totalPlatforms.value = maxOf(asked?.size ?: 0, counted)
        _completedPlatforms.value = _platformStatuses.value.count {
            it.status != PlatformSearchStatus.SEARCHING
        }
    }

    fun search(
        query: String,
        platforms: List<PlatformId>? = null,
        filters: CarFilters? = null,
        excludeKeywords: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
        force: Boolean = false,
    ) {
        if (query.isBlank() || rendersASample) return
        if (!force && query == _searchQuery.value && filters == carFilters &&
            excludeKeywords == this.excludeKeywords && aliases == this.aliases &&
            (_allListings.value.isNotEmpty() || _loading.value)
        ) return
        _searchQuery.value = query
        carFilters = filters
        this.excludeKeywords = excludeKeywords
        this.aliases = aliases
        _priceHistory.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            refusedBy = null
            _notSearched.value = null
            _allListings.value = emptyList()
            _platformStatuses.value = emptyList()
            _completedPlatforms.value = 0
            _totalPlatforms.value = 0
            _facets.value = emptyMap()

            try {
                withTimeoutOrNull(360_000L) {
                client.crawlerSearchStream(
                    query, platforms = platforms, filters = filters,
                    excludeKeywords = excludeKeywords, aliases = aliases,
                    lat = userLat, lon = userLon,
                ).collect { event ->
                    when (event.type) {
                        CrawlerEventType.SEARCH_STARTED -> {
                            _totalPlatforms.value = event.totalPlatforms
                        }

                        CrawlerEventType.PLATFORM_STARTED -> {
                            _platformStatuses.value = _platformStatuses.value + PlatformStatus(
                                platformId = event.platform,
                                platformName = event.platformName,
                                status = PlatformSearchStatus.SEARCHING,
                                queryUsed = event.queryUsed,
                            )
                        }

                        CrawlerEventType.PLATFORM_DONE -> {
                            _completedPlatforms.value = event.completedPlatforms
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(
                                    status = PlatformSearchStatus.DONE,
                                    resultCount = event.resultCount,
                                    rawCount = event.rawCount,
                                    hasMore = event.hasMore,
                                    fromCache = event.fromCache,
                                    captchaUrl = null, // solved (or never needed) — drop the link
                                ) else it
                            }
                            // Reconcile: replace this platform's streamed preview listings with its
                            // authoritative detail-enriched set, so any preview item the final filter
                            // dropped disappears and enriched specs replace the card-only ones.
                            _allListings.value = sortListings(
                                (_allListings.value.filterNot { it.platformId.name == event.platform } + event.listings)
                                    .distinctBy { it.id })
                            if (event.facets.isNotEmpty()) {
                                _facets.value = (_facets.value.keys + event.facets.keys).associateWith { k ->
                                    (_facets.value[k] ?: 0) + (event.facets[k] ?: 0)
                                }
                            }
                        }

                        CrawlerEventType.PLATFORM_ERROR -> {
                            _completedPlatforms.value = event.completedPlatforms
                            val errorStatus = when (event.errorType) {
                                "CAPTCHA" -> PlatformSearchStatus.CAPTCHA
                                "TIMEOUT" -> PlatformSearchStatus.TIMEOUT
                                "BLOCKED_403", "AUTH_REQUIRED_401" -> PlatformSearchStatus.IP_BLOCKED
                                else -> PlatformSearchStatus.ERROR
                            }
                            upsertStatus(event.platform, event.platformName) {
                                it.copy(
                                    status = errorStatus,
                                    error = event.error,
                                    errorType = event.errorType,
                                    captchaUrl = event.captchaUrl,
                                )
                            }
                        }

                        CrawlerEventType.PLATFORM_PROGRESS -> {
                            // A progress event either carries a fetch-stage label or a just-parsed
                            // page of listings (pipelined). Append the page live so results stream in
                            // rather than landing all at once when the platform finishes.
                            if (event.listings.isNotEmpty()) {
                                _allListings.value = sortListings(
                                    (_allListings.value + event.listings).distinctBy { it.id })
                            }
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(fetchStage = event.fetchStage ?: it.fetchStage)
                                else it
                            }
                        }

                        CrawlerEventType.CAPTCHA_INTERACTIVE -> {
                            // The crawl exposed its live browser for a human to solve a captcha in
                            // place. Attach the (base-qualified) solve link to the platform, which is
                            // still running — it resumes once solved.
                            val full = event.captchaUrl?.let {
                                if (it.startsWith("http")) it else client.baseUrl + it
                            }
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(captchaUrl = full) else it
                            }
                        }

                        CrawlerEventType.SEARCH_COMPLETE -> {}
                    }
                }
                } // withTimeoutOrNull
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Closing the results, or starting another search, cancels this one. Nothing
                // failed: saying the server could not be reached would be untrue, and the two
                // fallback fetches below would be work done for a screen already gone.
                throw e
            } catch (e: Exception) {
                if (e.message?.contains("Cancel", ignoreCase = true) == true) return@launch
                // The server refuses a search it has no capacity for. Falling back to what is
                // stored is right, but silently is not: without this the markets look like they
                // were asked and said nothing, when in truth none of them was asked at all.
                refusedBy = e.message?.takeIf { it.isNotBlank() }
                // Only a run that never reached a single market did not run. One that answered and
                // then broke its connection has results to show and no business claiming otherwise.
                _notSearched.value = if (_platformStatuses.value.isNotEmpty()) null else when {
                    refusedBy?.contains("busy", ignoreCase = true) == true ->
                        "The server was busy, so this search did not run."
                    refusedBy?.contains("Rate limit", ignoreCase = true) == true ||
                        refusedBy?.contains("Slow down", ignoreCase = true) == true ->
                        "Too many searches in a row, so this one did not run."
                    else -> "The server could not be reached, so this search did not run."
                }
                if (_allListings.value.isEmpty()) {
                    try {
                        val results = client.crawlerSearch(query, null)
                        _allListings.value = results
                        refusedBy = null
                        _notSearched.value = null
                    } catch (e2: Exception) {
                        try {
                            _allListings.value = client.searchListings(query)
                        } catch (e3: Exception) {
                            _error.value = e3.message
                        }
                    }
                }
            } finally {
                _loading.value = false
                // Mark any still-searching platforms as timed out
                _platformStatuses.value = _platformStatuses.value.map {
                    if (it.status == PlatformSearchStatus.SEARCHING)
                        it.copy(status = PlatformSearchStatus.TIMEOUT, error = null)
                    else it
                }
                accountForListingsWithoutAStatus(platforms)
            }
        }
    }
}
