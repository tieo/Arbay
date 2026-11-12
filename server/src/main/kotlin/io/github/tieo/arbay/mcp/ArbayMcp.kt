package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.model.lines

import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.kwToPs
import io.github.tieo.arbay.model.fact
import io.github.tieo.arbay.model.wholePoints
import io.github.tieo.arbay.model.BlockedDealer
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.AdPriceType
import io.github.tieo.arbay.model.NewAdDraft
import io.github.tieo.arbay.model.PostAdResult
import io.github.tieo.arbay.model.RepostEvery
import io.github.tieo.arbay.model.nextRepost
import io.github.tieo.arbay.model.postedUrl
import io.github.tieo.arbay.model.problem
import io.github.tieo.arbay.model.dealerNameKey
import io.github.tieo.arbay.model.CrawlerEventType
import io.github.tieo.arbay.model.CrawlerSearchEvent
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.DropCount
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.label
import io.github.tieo.arbay.model.summaryText
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.SearchReach
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.model.landedPrice
import io.github.tieo.arbay.model.importDuty
import io.github.tieo.arbay.model.importVat
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.withCarFilters
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.OfferNote
import io.github.tieo.arbay.model.Verdict
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.AutoFetchSettings
import io.github.tieo.arbay.model.WATCH_INTERVALS
import io.github.tieo.arbay.model.FIT_ALERT_PERCENT
import io.github.tieo.arbay.model.weighsAFit
import io.github.tieo.arbay.model.newRule
import io.github.tieo.arbay.model.canMessage
import io.github.tieo.arbay.model.composeBlocks
import io.github.tieo.arbay.model.fillIn
import io.github.tieo.arbay.model.offerFor
import io.github.tieo.arbay.model.PriceOffer
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.model.driveFront
import io.github.tieo.arbay.model.summary
import io.ktor.client.HttpClient
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Arbay as tools an LLM can call: everything the phone and the browser can do, through the same
 * API they use, so a change made by talking is the same change a tap makes and every device shows it.
 *
 * Each tool is a call to Arbay's own API at [base] through [http]. Nothing here sends a message on
 * its own: writing to sellers goes through the same outbox as the send window, at the same pace,
 * and a client that asks before running a tool marked destructive asks the user first.
 */
class ArbayMcp(private val http: HttpClient, private val base: String) {
    /** How long a search call waits for markets before answering with what has arrived: inside
     *  the tool call's own time limit, which a search on a busy server outlasted. */
    private val ANSWER_WITHIN = 40.seconds
    /** How long a search asked to be complete waits for its slowest market, the web's search
     *  engines and shop pages, before answering with what it has. The call sends nothing meanwhile:
     *  its client must allow it this long (Claude Code: the server's "timeout" in its MCP
     *  settings, 600000 for arbay, past its 300 s default). */
    private val COMPLETE_WITHIN = 8.minutes

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Listings returned by searches in this session, so a later tool can name them by id. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Listing>()

    fun server(): Server = Server(
        Implementation(name = "arbay", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = """
            Arbay searches second-hand markets (Kleinanzeigen, eBay, Vinted, willhaben and more) for the user,
            keeps saved searches, and talks to Kleinanzeigen sellers through the user's own account.
            Prices are landed prices in EUR. Messages to sellers start from the user's own texts (list_texts) with
            their fill-ins ({preis}, {titel}, {versand}) filled; where none fits, adapt one or write a new one in
            the same short, casual wording. Send only what the user approved or a standing mandate covers.
        """.trimIndent(),
    ).apply { registerTools() }

    /** Searches started through [search], kept while they run and a while after, by id. */
    private val runs = java.util.concurrent.ConcurrentHashMap<String, SearchRun>()
    private val searches = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One search through the markets, filled in as each market answers. */
    private inner class SearchRun(val id: String, val words: String, val saved: TrackedProduct?, val savedId: String?) {
        /** The vehicle criteria the markets were asked with, which each offer is said to meet or not to state. */
        var criteria: CarFilters? = null
        val listings: MutableList<Listing> = java.util.Collections.synchronizedList(mutableListOf())
        val problems: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val searching: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** Every market asked, and those that have answered or failed: the rest wait for a crawl slot. */
        val asked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val answered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        /** Per market: what it sent that the search removed, with the reason for each. */
        val dropped = java.util.concurrent.ConcurrentHashMap<String, List<DroppedListing>>()
        /** Per market: how many offers each reason removed, over all of them, where [dropped] holds some. */
        val dropCounts = java.util.concurrent.ConcurrentHashMap<String, List<DropCount>>()
        /** Per market: how many offers it sent and how many the search kept. */
        val counts = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()
        val startedAt = kotlin.time.Clock.System.now()
        /** Set while the server's crawl throttle has it waiting for a free slot. */
        @Volatile var queued: String? = null
        lateinit var job: Job

        suspend fun describe(args: JsonObject): String {
            val q = saved?.searchQuery
            val hidden = hiddenOffers()
            val max = args.num("max_price_eur") ?: q?.maxPrice?.amount?.div(100.0)
            val min = args.num("min_price_eur") ?: q?.minPrice?.amount?.div(100.0)
            // The saved market and country picks narrow what was fetched, adding up as in the app.
            val markets = q?.showOnlyMarkets.orEmpty()
            val countries = q?.showOnlyCountries.orEmpty()
            val all = synchronized(listings) { listings.toList() }.distinctBy { it.id }
            val shown = all
                .filter { it.id !in hidden }
                .filter { (markets.isEmpty() && countries.isEmpty()) || it.platformId in markets || MarketSets.countryOf(it.platformId) in countries }
                .filter { max == null || it.effectivePrice.amount / 100.0 <= max }
                .filter { min == null || it.effectivePrice.amount / 100.0 >= min }
                // Vehicles carry their fit to the search as a whole and come best fit first, as in
                // the app's "Best match"; everything else comes cheapest first.
                // What the market guessed rather than what was asked for comes last, as in the app.
                .sortedWith(
                    compareBy<Listing> { it.offQuery }.then(
                        when (args.str("sort")) {
                            "cents_per_gb" -> compareBy { it.drive?.centsPerGb ?: Double.MAX_VALUE }
                            "life" -> compareByDescending { it.drive?.lifeHours ?: -1L }
                            "total_cost" -> compareBy { it.ownCost?.totalEur ?: Int.MAX_VALUE }
                            else -> compareByDescending<Listing> { it.matchScore ?: -1.0 }.thenBy { it.effectivePrice.amount }
                        },
                    ),
                )
            val listed = shown.take(args.int("limit") ?: 40)
            // Every offer the search found can be asked about, not only the ones listed: a search
            // read deep lists forty of a thousand.
            all.forEach { seen[it.id] = it }
            val removed = dropped.toSortedMap()
            removed.values.flatten().forEach { seen[it.listing.id] = it.listing }
            val views = io.github.tieo.arbay.judging.ViewCounts.of(listed.map { it.id })
            return buildString {
                // A search no market answered is no search that found nothing, and one some markets
                // failed found nothing only where the others looked.
                val failed = synchronized(problems) { problems.size }
                when {
                    all.isEmpty() && failed > 0 && counts.isEmpty() ->
                        appendLine("FAILED: no market answered the search for \"$words\"; nothing is known about its offers.")
                    all.isEmpty() && failed > 0 ->
                        appendLine("No offers for \"$words\" where the markets answered, but $failed failed (below), so the answer is incomplete.")
                    else -> appendLine("${listed.size} of ${all.size} offers for \"$words\":")
                }
                listed.forEach { l ->
                    // A vehicle that does not state a criterion is kept, so the line says which it never met.
                    val unchecked = criteria?.uncheckedFor(l.vehicle).orEmpty()
                    appendLine(l.line() + (views[l.id]?.let { v -> " | seen $v times" } ?: "") +
                        (if (unchecked.isEmpty()) "" else " | unchecked: " + unchecked.joinToString(", ")))
                }
                // Over every offer shown, listed or not: the front is what is worth weighing.
                append(frontLines(shown.filter { it.saleType != io.github.tieo.arbay.model.SaleType.AUCTION }))
                // An offer put away on a device is left out of the list, so it is named here: else a
                // van the watch reported reads as one the search lost.
                val putAway = all.filter { it.id in hidden }
                if (putAway.isNotEmpty()) appendLine("Put away (hidden on every device, not listed): ${putAway.size}: " + putAway.joinToString(", ") { it.id })
                synchronized(problems) { if (problems.isNotEmpty()) appendLine("Markets that failed: " + problems.joinToString("; ")) }
                // What each market sent against what the search kept, with the reasons for the
                // rest: a filter that is wrong shows up as a market whose answer went missing.
                val sentAndKept = counts.toSortedMap().filterValues { (sent, kept) -> sent > kept }
                if (sentAndKept.isNotEmpty()) appendLine("Removed by the search: " + sentAndKept.entries.joinToString("; ") { (market, n) ->
                    "$market ${n.first - n.second} of ${n.first} (" +
                        dropCounts[market].orEmpty().joinToString(", ") { "${why(it.reason, it.detail)} ${it.count}" } + ")"
                })
                // A market that found nothing for the words is otherwise missing from every line.
                val empty = counts.toSortedMap().filterValues { (sent, _) -> sent == 0 }.keys
                if (empty.isNotEmpty()) appendLine("No offers for these words: " + empty.joinToString(", "))
                if (args.bool("dropped") == true) removed.forEach { (market, list) ->
                    val total = dropCounts[market].orEmpty().sumOf { it.count }
                    if (list.isNotEmpty()) appendLine("Removed from $market (${list.size}" + (if (total > list.size) " of $total, every reason among them" else "") + " listed, in the market's order):")
                    list.forEach { appendLine("  " + it.why() + " | " + it.listing.line()) }
                }
                if (job.isActive) appendLine(
                    (queued?.let { "Queued: $it. It starts when a running search finishes." }
                        ?: ("Still searching: ${searching.joinToString(", ").ifEmpty { "the markets have not answered yet" }}." +
                            (asked - searching - answered).takeIf { it.isNotEmpty() }?.let { " Waiting for a crawl slot: ${it.sorted().joinToString(", ")}." }.orEmpty())) +
                        " Call search with search_id=$id for the rest.",
                )
            }
        }
    }

    /** Where a radius the call names is measured from: the place it names, else the saved search's, else the user's home. */
    private suspend fun nearOf(args: JsonObject): String? = args.str("near")
        ?: args.str("saved_search_id")?.let { getJson<TrackedProduct>("/api/products/${it.encodeURLPathPart()}") }?.searchQuery?.location?.takeIf { it.isNotBlank() }
        ?: io.github.tieo.arbay.classifier.FreeItemProfileStore.get()?.location?.takeIf { it.isNotBlank() }

    /** Starts a search in the background and returns it, or null when there is nothing to search for. */
    private suspend fun startSearch(args: JsonObject, vehicle: CarFilters? = null): SearchRun? {
        val savedId = args.str("saved_search_id")
        val saved = savedId?.let { getJson<TrackedProduct>("/api/products/${it.encodeURLPathPart()}") }
        val words = args.str("words") ?: saved?.searchQuery?.text ?: return null
        val q = saved?.searchQuery
        val run = SearchRun(UUID.randomUUID().toString().take(8), words, saved, savedId)
        val cutoff = kotlin.time.Clock.System.now() - kotlin.time.Duration.parse("30m")
        runs.values.removeIf { !it.job.isActive && it.startedAt < cutoff }
        run.job = searches.launch {
            // Arbay's crawl throttle answers 429 with Retry-After when searches arrive together, as
            // they do when an LLM runs several tool calls at once; the search waits its turn instead
            // of reporting the throttle as every market failing.
            var throttled = true
            while (throttled) http.prepareGet("$base/api/crawler/search/stream") {
                parameter("q", words)
                q?.platforms?.takeIf { it.isNotEmpty() }?.let { parameter("platforms", it.joinToString(",") { p -> p.name }) }
                q?.excludeKeywords?.forEach { parameter("exclude", it) }
                q?.aliases?.forEach { parameter("alias", it) }
                q?.productWishes?.takeUnless { it.isEmpty }?.let { parameter("wishes", json.encodeToString(io.github.tieo.arbay.model.ProductWishes.serializer(), it)) }
                val radius = args.int("radius_km")?.takeIf { it > 0 } ?: q?.radiusKm?.takeIf { it > 0 }
                radius?.let { parameter("radiusKm", it) }
                (if (args.int("radius_km") != null) nearOf(args) else q?.location)?.let { parameter("near", it) }
                // The saved vehicle criteria (year, mileage, power, gearbox, drive, van size, price),
                // sent as the app sends them, so the markets are asked for the same vans the app shows.
                // A price band named for this search goes to the markets too, which filter on it
                // themselves: a search for the cheapest of a thing reads the offers inside the band
                // instead of every page of every price.
                (vehicle ?: q?.toCarFilters() ?: CarFilters()).let { f ->
                    run.criteria = f.takeUnless { it.isEmpty }
                    f.copy(
                        minPriceEur = args.num("min_price_eur")?.let { kotlin.math.floor(it).toInt() } ?: f.minPriceEur,
                        maxPriceEur = args.num("max_price_eur")?.let { kotlin.math.ceil(it).toInt() } ?: f.maxPriceEur,
                    )
                }.takeUnless { it.isEmpty }?.let { parameter("carFilters", json.encodeToString(CarFilters.serializer(), it)) }
                val reach = (q?.reach ?: SearchReach()).let { r -> if (args.bool("other_words") == true) r.copy(otherWords = true) else r }
                reach.takeUnless { it.isDefault }?.let { parameter("reach", json.encodeToString(SearchReach.serializer(), it)) }
                args.int("per_market")?.let { parameter("perMarket", it) }
            }.execute { response ->
                throttled = response.status == HttpStatusCode.TooManyRequests
                if (throttled) {
                    run.queued = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.content }
                        .getOrNull() ?: "the server is busy"
                    return@execute delay((response.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 1) * 1000)
                }
                run.queued = null
                if (!response.status.isSuccess()) return@execute run.problems.add(response.bodyAsText())
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    val event = runCatching { json.decodeFromString<CrawlerSearchEvent>(line) }.getOrNull() ?: continue
                    val market = event.platformName.ifEmpty { event.platform }
                    when (event.type) {
                        CrawlerEventType.SEARCH_STARTED -> run.asked += event.markets
                        CrawlerEventType.PLATFORM_STARTED -> run.searching += market
                        CrawlerEventType.PLATFORM_DONE -> { run.searching -= market; run.answered += market }
                        CrawlerEventType.PLATFORM_ERROR -> { run.searching -= market; run.answered += market; run.problems += "$market: ${event.error ?: event.errorType}" }
                        else -> {}
                    }
                    // A market's final answer replaces what it streamed while still paging.
                    if (event.type == CrawlerEventType.PLATFORM_DONE) {
                        run.listings.removeIf { it.platformId.name == event.platform }
                        run.dropped[market] = event.dropped
                        run.dropCounts[market] = event.droppedCounts
                        run.counts[market] = event.rawCount to event.resultCount
                    }
                    run.listings += event.listings
                    if (event.type == CrawlerEventType.SEARCH_COMPLETE) break
                }
            }
        }
        runs[run.id] = run
        // The user sees what is being talked about: the same search opens on their screens.
        Live.show(savedId?.let { "/saved/${it.encodeURLPathPart()}" } ?: "/search/${words.encodeURLPathPart()}")
        return run
    }

    private fun Server.registerTools() {
        tool("list_saved_searches", "Every saved search with its id, words, markets, its alert rules with the price each has learned, the ids of the new offers it found since last opened, and the offers it keeps finding that have come down in price since then.") {
            val products = getJson<List<TrackedProduct>>("/api/products")
            val status = getJsonElement("/api/products/status").let { it as? JsonArray }.orEmpty()
                .associateBy({ (it as JsonObject)["productId"]?.jsonPrimitive?.contentOrNull }, { it as JsonObject })
            products.joinToString("\n") { p ->
                val s = status[p.id]
                listOfNotNull(
                    "${p.id}: ${p.name}",
                    "words \"${p.searchQuery.text}\"".takeIf { p.searchQuery.text != p.name },
                    p.searchQuery.aliases.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "also ") { "\"$it\"" },
                    p.searchQuery.excludeKeywords.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "blocked ") { "\"$it\"" },
                    "${p.searchQuery.platforms.size} markets",
                    p.searchQuery.maxPrice?.let { "up to ${it.amount / 100} €" },
                    s?.get("newSinceOpened")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { "$it new" },
                ).joinToString(" · ") +
                    // A run no market answered finds nothing, which reads as a quiet day unless said.
                    ((s?.get("unansweredMarkets") as? JsonObject)?.takeIf { it.isNotEmpty() }?.let { failed ->
                        val ran = s?.get("lastRunAtMillis")?.jsonPrimitive?.longOrNull?.let { hoursAgo(it) } ?: "never"
                        failed.entries.joinToString(", ", prefix = "\n  LAST RUN ($ran) got no answer from ${failed.size} of ${p.searchQuery.platforms.size} markets: ") { (name, e) ->
                            (runCatching { io.github.tieo.arbay.model.PlatformId.valueOf(name).displayName }.getOrNull() ?: name) + " (" + e.jsonPrimitive.content.take(80) + ")"
                        }
                    } ?: "") +
                    (if (p.fitAlert.enabled) "\n  fit alert: each offer, once, that fits ${(p.fitAlert.minScore * 100).roundToInt()} % or more" else "") +
                    (p.searchQuery.productWishes?.let { "\n  " + wishesLine(it) } ?: "") +
                    (p.searchQuery.carFilters?.takeUnless { it.isEmpty }?.let { "\n  vehicle " + vehicleJson.encodeToString(CarFilters.serializer(), it) } ?: "") +
                    p.notificationSubfilters.joinToString("") { r ->
                    // The rule's criteria, and for one that learns the market, the lowest price it has
                    // seen so far: the bar a find has to beat once learning ends.
                    "\n  alert rule ${r.id}" + (if (r.name.isNotBlank()) " \"${r.name}\"" else "") + ": " + r.summaryText() +
                        (r.bestSeenListingId?.let { ", learned from $it (put it away with hide_offers if it is no real offer, and the rule learns again on the next run)" } ?: "") +
                        if (r.enabled) "" else " (off)"
                } + ((s?.get("newListingIds") as? JsonArray).orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }.takeIf { it.isNotEmpty() }
                    ?.let { ids -> ids.take(20).joinToString(", ", prefix = "\n  new: ") + if (ids.size > 20) ", and ${ids.size - 20} more (new_offers lists them all)" else "" } ?: "") + ((s?.get("missingTopOffers") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .joinToString("") { o ->
                        val why = if (o.str("state") == "RULED_OUT") "RULED OUT of its top ${io.github.tieo.arbay.judging.TopOffers.TOP} by the search's rule \"${o.str("ruledOutBy")}\" (check the ad really breaks it)"
                            else o.str("marketError")?.let { e ->
                                val market = runCatching { io.github.tieo.arbay.model.PlatformId.valueOf(o.str("listingId")!!.substringBefore(':')).displayName }.getOrNull() ?: "its market"
                                "LOST from its top ${io.github.tieo.arbay.judging.TopOffers.TOP} ($market gave the last run no answer, so it was never looked for: $e)"
                            } ?: ("LOST from its top ${io.github.tieo.arbay.judging.TopOffers.TOP} (the last run did not return it; ad " + (o.str("adState")?.lowercase() ?: "state unknown") + ")")
                        val looked = o.str("checkedAt")?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                            ?.let { " (looked for ${hoursAgo(it)})" }.orEmpty()
                        "\n  $why$looked: ${o.str("title")} · ${o.num("priceEur")?.toInt()} € · best fit " +
                            "${o.num("bestScore")?.let { Math.round(it * 100) }}% · ${o.str("listingId")} · ${o.str("url")}"
                    }) + priceDropLines(s)
            }.ifEmpty { "No saved searches." }
        }

        tool(
            "new_offers",
            "Every offer a saved search found since it was last opened, as the watch found it, with its price, link and when the watch found it, then every offer it already knew that has come down in price since. Opens nothing, so they stay new.",
            props = {
                string("saved_search_id", "The saved search", required = true)
                integer("within_hours", "Only the offers the watch found, and the price cuts it saw, in this many last hours")
            },
        ) { args ->
            val id = args.str("saved_search_id")!!
            val status = (getJsonElement("/api/products/status") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.firstOrNull { it.str("productId") == id }
            val foundAt = (status?.get("foundAtMillis") as? JsonObject).orEmpty()
                .mapNotNull { (k, v) -> v.jsonPrimitive.longOrNull?.let { k to Instant.fromEpochMilliseconds(it) } }.toMap()
            val all = getJson<List<Listing>>("/api/products/${id.encodeURLPathPart()}/new")
            all.forEach { seen[it.id] = it }
            val since = args.int("within_hours")?.let { Clock.System.now() - it.hours }
            // An offer found before its find time was kept has no time, and is kept out of a
            // window rather than passed off as inside it.
            val found = if (since == null) all else all.filter { l -> foundAt[l.id]?.let { it >= since } == true }
            val window = args.int("within_hours")?.let { " in the last $it h" } ?: ""
            (if (found.isEmpty()) "Nothing new in this saved search$window."
            else "${found.size} new$window, best fit first, then cheapest:\n" + found.sortedWith(compareByDescending<Listing> { it.matchScore ?: -1.0 }.thenBy { it.effectivePrice.amount })
                .joinToString("\n") { l -> l.line(foundAt[l.id]) } + "\n" + frontLines(found.filter { it.saleType != io.github.tieo.arbay.model.SaleType.AUCTION })) +
                priceDropLines(status, since)
        }

        tool(
            "search",
            "Search the markets now and list the offers, vehicles best fit first and everything else cheapest first, each " +
                "with an id for other tools, its price with shipping and when it arrives where the market states them. Answers " +
                "with what has arrived within about half a minute and names the markets still searching; call again with the " +
                "search_id it gives to get the rest, or ask complete to wait for every market.",
            props = {
                string("words", "What to search for, as the user would type it")
                string("saved_search_id", "Search with a saved search's words, markets and settings instead")
                string("search_id", "A search already running, to get what has arrived since")
                integer("limit", "How many offers to list (default 40)")
                string("sort", "For drives: cents_per_gb (cheapest per gigabyte first) or life (most expected hours left first); for a vehicle search with costKmPerYear: total_cost (cheapest to own first)")
                number("max_price_eur", "Leave out offers above this landed price. The markets are asked for nothing dearer, so a search for the cheapest reads far fewer pages")
                number("min_price_eur", "Leave out offers below this landed price, such as accessories for the thing searched")
                boolean("other_words", "Also search each market under the thing's other names: for a part number, the ways the listings carrying it describe the part")
                integer("per_market", "How many offers to read from each market (default 60, up to 1000): more finds the cheapest of a thing sold by the hundred, and takes longer")
                boolean("dropped", "Also list the offers the search removed, per market with the reason for each, to check a filter took out only what it should")
                boolean("complete", "Wait for every market, the web's shops included, before answering (a few minutes), so the answer is the whole market")
                boolean("ask_cooling_down", "Ask a market that is cooling down after a block now instead of waiting the cooldown out, as the app's \"Ask now\"; a market that blocks again starts a new cooldown")
                string("vehicle", "$VEHICLE_ARG. Replaces a saved search's own criteria for this search")
                integer("radius_km", "Only offers at most this far from near, in km (straight line). AutoScout24, mobile.de, Kleinanzeigen and eBay are asked for it; the rest are measured by where their offers say they are, and an offer that says nowhere is kept")
                string("near", "The place radius_km is measured from, a postcode or town (default: the user's home)")
            },
        ) { args ->
            if (args.bool("ask_cooling_down") == true && args.str("search_id") == null) http.delete("$base/api/crawler/cooldown")
            val vehicle = vehicleCriteria(args).getOrElse { return@tool "Not searched: ${it.message}" }
            // A radius from a place that cannot be found would search everywhere and say nothing.
            if (args.int("radius_km") != null && args.str("search_id") == null) {
                val near = nearOf(args) ?: return@tool "Not searched: radius_km needs near, and no home is set."
                if (io.github.tieo.arbay.judging.Geocoder.place(near) == null) return@tool "Not searched: the place \"$near\" is not known."
            }
            val run = args.str("search_id")?.let { runs[it] ?: return@tool "No such search running; search again." }
                ?: startSearch(args, vehicle) ?: return@tool "Name the words or a saved search."
            // A tool call has a time limit of its own, and a search on a busy server can take
            // minutes: waiting for every market lost the whole answer to that limit. What has
            // arrived goes back, the rest keeps coming in, and the next call picks it up.
            withTimeoutOrNull(if (args.bool("complete") == true) COMPLETE_WITHIN else ANSWER_WITHIN) { run.job.join() }
            run.describe(args)
        }

        tool(
            "offer_details", "The seller's full description and details of one offer from a search.",
            props = { string("listing_id", "An offer id from search", required = true) },
        ) { args ->
            val kept = args.str("listing_id")?.let { offer(it) } ?: return@tool "Unknown offer; search first."
            // Whether the ad is up and what it asks are read now, where the market says them: the
            // kept listing may be from a search days old, and a Kleinanzeigen drive cut from 175 €
            // to 155 € still read as 175 € here.
            val (state, now) = adNowSaid(kept)
            val l = now?.price?.takeIf { it.currency == kept.price.currency }?.let { kept.copy(price = it) } ?: kept
            val detail = http.get("$base/api/crawler/listing-detail") {
                parameter("url", l.url); parameter("platform", l.platformId.name); parameter("id", l.id)
            }
            val head = l.line() + l.fitLine() + l.ownCostLines() +
                (if (l.id in hiddenOffers()) "\nPut away: hidden on every device, so searches do not list it (hide_offers with bring_back shows it again)." else "")
            // A page Arbay could not fetch says nothing about the ad, so it is not reported as one
            // that has nothing more to say: with the server offline every ad read that way.
            if (!detail.status.isSuccess()) return@tool head + "\nArbay could not read the ad page: " + detail.bodyAsText().ifBlank { detail.status.toString() }
            // A page that gives nothing may be an ad taken down, so whether it is up is still read.
            if (detail.status.value == 204) return@tool head + "\n" + state + "The market gave no more than this."
            // A detail carries a vehicle block for every listing; for anything but a vehicle it is
            // all empty and only buries the description, so it is left out.
            val body = runCatching { json.parseToJsonElement(detail.bodyAsText()) as JsonObject }.getOrNull()
                ?: return@tool head + "\n" + detail.bodyAsText()
            // The page states the delivery charge a search card often leaves out, so the head line
            // gives the landed price from it.
            val pageShipping = (body["shipping"] as? JsonObject)?.let {
                runCatching { json.decodeFromJsonElement(io.github.tieo.arbay.model.Shipping.serializer(), it) }.getOrNull()
            }
            // The drive facts are the search's, made before the page was read; here it has been.
            val read = l.copy(drive = l.drive?.copy(pageRead = true))
            val pageHead = (if (pageShipping != null) read.copy(shipping = pageShipping) else read).line() + l.fitLine() + l.ownCostLines()
            val vehicle = body["vehicle"] as? JsonObject
            val emptyVehicle = vehicle != null && vehicle.values.all { it is JsonNull || (it is JsonArray && it.isEmpty()) }
            // The seller's account age and badges decide whether an offer can be trusted, so they
            // are said in words rather than left inside the page's details.
            val seller = (body["seller"] as? JsonObject)?.let {
                runCatching { json.decodeFromJsonElement(io.github.tieo.arbay.model.SellerProfile.serializer(), it) }.getOrNull()
            }?.let { "Seller: ${it.fact}.\n" }.orEmpty()
            val dealer = io.github.tieo.arbay.judging.Dealers.pageOf(l)?.let { url ->
                val r = http.get("$base/api/dealer") { parameter("url", url) }
                if (r.status == io.ktor.http.HttpStatusCode.OK) runCatching { json.decodeFromString(io.github.tieo.arbay.model.Dealer.serializer(), r.bodyAsText()) }.getOrNull()
                    ?.let { it.lines().joinToString("\n") + "\n" } ?: "The dealer's pages could not be read.\n"
                else "The dealer's pages could not be read.\n"
            }.orEmpty()
            val lead = state + seller
            pageHead + "\n" + lead + (if (dealer.isNotEmpty() && lead.isNotEmpty() && !lead.endsWith("\n")) "\n" else "") + dealer + driveHealthLine(l, body) + "The details below are the ad page as Arbay last read it.\n" +
                (if (emptyVehicle) JsonObject(body - "vehicle") else body).toString()
        }

        tool("whats_on_screen", "Which page of Arbay each of the user's devices shows right now, newest first: a search, an offer in it, a conversation.") {
            Live.onScreen().joinToString("\n") { "${it.device}: ${it.path} (since ${it.at})" }.ifEmpty { "No app is open." }
        }

        tool(
            "show_on_screen",
            "Open a place in Arbay on every screen the user has it open on, so they see what is being talked about.",
            props = {
                string("saved_search_id", "Open this saved search")
                string("words", "Open a search for these words")
                string("listing_id", "Open this offer, inside the search it came from (give saved_search_id or words too)")
                string("conversation_id", "Open this conversation")
                string("ad_draft_id", "Open this ad for sale")
                string("place", "Or one of: searches, messages, free_items, settings, vehicle_search")
            },
        ) { args ->
            val search = args.str("saved_search_id")?.let { "/saved/${it.encodeURLPathPart()}" }
                ?: args.str("words")?.let { "/search/${it.encodeURLPathPart()}" }
            val path = when {
                args.str("conversation_id") != null -> "/inbox/${args.str("conversation_id")!!.encodeURLPathPart()}"
                args.str("ad_draft_id") != null -> "/sell/${args.str("ad_draft_id")!!.encodeURLPathPart()}"
                search != null -> search + (args.str("listing_id")?.let { "/${it.encodeURLPathPart()}" } ?: "")
                else -> when (args.str("place")) {
                    "messages" -> "/inbox"; "free_items" -> "/free"; "settings" -> "/settings"; "vehicle_search" -> "/vehicle"
                    else -> "/"
                }
            }
            Live.show(path)
            "Showing $path."
        }

        tool(
            "save_search", "Keep a search so Arbay watches it.",
            props = {
                string("words", "What to search for", required = true); string("name", "What to call it (defaults to the words)")
                string("vehicle", VEHICLE_ARG)
            },
        ) { args ->
            val words = args.str("words")!!
            val vehicle = vehicleCriteria(args).getOrElse { return@tool "Not saved: ${it.message}" }
            // Words naming a car, or vehicle criteria, reach the vehicle sites, as the app's vehicle
            // search does; other words reach the general marketplaces, as its search box does.
            val group = if (vehicle != null || io.github.tieo.arbay.relevance.CarQueryResolver.resolve(words) != null) MarketGroup.VEHICLES else MarketGroup.GENERAL
            val query = SearchQuery(text = words, category = group).withCarFilters(vehicle)
            val body = buildJsonObject {
                put("id", ""); put("name", args.str("name") ?: words); put("createdAt", Clock.System.now().toString())
                put("searchQuery", json.encodeToJsonElement(SearchQuery.serializer(), query))
            }
            val r = http.post("$base/api/products") { contentType(ContentType.Application.Json); setBody(body.toString()) }
            if (r.status.isSuccess()) "Saved: " + json.decodeFromString<TrackedProduct>(r.bodyAsText()).let { "${it.id}: ${it.name}" } else "Not saved: ${r.bodyAsText()}"
        }

        tool(
            "change_saved_search",
            "Change a saved search: its name, its words, other words the same thing is sold under (asked of the markets too), the price band it shows, how often it looks again on its own, and its alert rules. Opens it on the user's screens.",
            props = {
                string("id", "Saved search id", required = true); string("name", "New name"); string("words", "New words")
                stringArray("other_words", "Other words for the same thing, each searched as well, e.g. \"256GB\" next to \"256 GB\"; replaces the list")
                stringArray("blocked_words", "Words or phrases an offer's title must not carry, e.g. \"extern\" or \"2,5 zoll\"; replaces the list, as the app's Filters do")
                number("min_price_eur", "Show nothing cheaper (0 clears)"); number("max_price_eur", "Show nothing dearer (0 clears)")
                number("look_every_minutes", "Look again on its own this often: ${WATCH_INTERVALS.joinToString { it.first.toString() }}; 0 stops looking")
                stringArray("remove_alert_rules", "Alert rule ids to remove, as list_saved_searches names them")
                number("alert_from_eur", "Add an alert rule: push for finds from this landed price")
                number("alert_up_to_eur", "Add an alert rule: push for finds up to this landed price")
                number("alert_learn_days", "Add an alert rule that first watches this many days, then pushes only finds below the best it saw")
                number("fit_alert_percent", "Push an offer, once, when it first fits the search's wishes (a vehicle search's criteria, or a thing's fit_wishes) at least this well, a whole percent from ${FIT_ALERT_PERCENT.first} to ${FIT_ALERT_PERCENT.last}; 0 stops it")
                boolean("alerts_to_phone", "Whether this search's alerts also push to the phone; false sends them to the alert feed alone")
                string("vehicle", "$VEHICLE_ARG; replaces the search's criteria, {} clears them")
                boolean("alert_on_front", "For a drive search: push a new offer that lands on the trade-off front of price per GB and expected life; false stops it. Its first run only notes what is there, so it pushes nothing")
                number("front_min_tb", "The front alert counts drives from this size"); number("front_max_tb", "The front alert counts drives up to this size")
                string("front_interface", "The front alert leaves out drives naming another interface: SATA, SAS or NVME"); string("front_recording", "The front alert leaves out drives naming other recording: CMR or SMR")
                boolean("front_bare_only", "The front alert leaves out drives in a USB case")
                string("fit_wishes", "A search for a thing (not a vehicle): what its offers are weighed by, as JSON {\"models\":[{\"name\":\"Lägler HUMMEL\",\"words\":[\"hummel\"],\"worth\":1.0,\"idealPriceEur\":800,\"maxPriceEur\":1200}],\"idealPriceEur\":450,\"maxPriceEur\":1500,\"ruledOutWords\":[\"400 V\"],\"plusWords\":[\"Schleifbänder\"]}; worth 0 to 1, a model's own idealPriceEur and maxPriceEur replace the search's for it, words matched whole and umlaut-blind with the longest model's words winning, a ruled-out word not right after kein/ohne; {} clears")
            },
        ) { args ->
            val id = args.str("id")!!
            val p = getJson<TrackedProduct>("/api/products/${id.encodeURLPathPart()}")
            val vehicle = vehicleCriteria(args).getOrElse { return@tool "Not changed: ${it.message}" }
            fun band(key: String, now: Money?) = args.num(key)?.let { if (it <= 0) null else Money((it * 100).toLong()) } ?: now
            val every = args.num("look_every_minutes")?.toInt()
            if (every != null && every != 0 && WATCH_INTERVALS.none { it.first == every })
                return@tool "Not changed: look_every_minutes is one of ${WATCH_INTERVALS.joinToString { it.first.toString() }}, or 0."
            val fitPercent = args.num("fit_alert_percent")
            if (fitPercent != null && fitPercent != 0.0 && (fitPercent % 1.0 != 0.0 || fitPercent.toInt() !in FIT_ALERT_PERCENT))
                return@tool "Not changed: fit_alert_percent is a whole percent from ${FIT_ALERT_PERCENT.first} to ${FIT_ALERT_PERCENT.last}, or 0."
            val fitLevel = fitPercent?.let { it / 100 }
            val wishes = if ("fit_wishes" !in args) p.searchQuery.productWishes else args.str("fit_wishes")?.let { raw ->
                runCatching { json.decodeFromString(io.github.tieo.arbay.model.ProductWishes.serializer(), raw) }.getOrElse {
                    return@tool "Not changed: fit_wishes is not readable: ${it.message?.take(200)}"
                }
            }?.takeUnless { it.isEmpty }
            if (wishes != null && wishes.models.any { it.worth !in 0.0..1.0 || it.words.isEmpty() })
                return@tool "Not changed: each model in fit_wishes needs words and a worth from 0 to 1."
            // The price band stays where min_price_eur and max_price_eur put it: criteria carry none.
            val criteria = vehicle?.copy(minPriceEur = null, maxPriceEur = null)?.takeUnless { it.isEmpty }
            val carFilters = if (vehicle == null) p.searchQuery.carFilters else criteria
            if (fitLevel != null && fitLevel != 0.0 && !p.searchQuery.copy(productWishes = wishes, carFilters = carFilters).weighsAFit)
                return@tool "Not changed: a fit is weighed from a search's wishes, a vehicle's criteria or fit_wishes, and this search has none."
            val gone = args.strs("remove_alert_rules").toSet()
            val unknown = gone - p.notificationSubfilters.map { it.id }.toSet()
            if (unknown.isNotEmpty()) return@tool "Not changed: no alert rule ${unknown.joinToString()} on this search."
            val from = args.num("alert_from_eur")?.toInt(); val upTo = args.num("alert_up_to_eur")?.toInt(); val learn = args.num("alert_learn_days")?.toInt()
            val added = if (from != null || upTo != null || learn != null) newRule(upTo, null, "", learn, from) else null
            val next = p.copy(
                autoFetch = when (every) { null -> p.autoFetch; 0 -> p.autoFetch.copy(enabled = false); else -> AutoFetchSettings(enabled = true, intervalMinutes = every) },
                notificationSubfilters = p.notificationSubfilters.filterNot { it.id in gone } + listOfNotNull(added),
                driveFront = p.driveFront.copy(
                    enabled = args.bool("alert_on_front") ?: p.driveFront.enabled,
                    minGb = args.num("front_min_tb")?.let { (it * 1000).toInt() } ?: p.driveFront.minGb,
                    maxGb = args.num("front_max_tb")?.let { (it * 1000).toInt() } ?: p.driveFront.maxGb,
                    driveInterface = args.str("front_interface")?.let { io.github.tieo.arbay.model.DriveInterface.valueOf(it.uppercase()) } ?: p.driveFront.driveInterface,
                    recording = args.str("front_recording")?.let { io.github.tieo.arbay.model.DriveRecording.valueOf(it.uppercase()) } ?: p.driveFront.recording,
                    bareOnly = args.bool("front_bare_only") ?: p.driveFront.bareOnly,
                ),
                fitAlert = when (fitLevel) { null -> p.fitAlert; 0.0 -> p.fitAlert.copy(enabled = false); else -> p.fitAlert.copy(enabled = true, minScore = fitLevel) },
                alertsToPhone = args.bool("alerts_to_phone") ?: p.alertsToPhone,
                name = args.str("name") ?: p.name,
                searchQuery = p.searchQuery.copy(
                    text = args.str("words") ?: p.searchQuery.text,
                    aliases = if ("other_words" in args) args.strs("other_words") else p.searchQuery.aliases,
                    excludeKeywords = if ("blocked_words" in args) args.strs("blocked_words") else p.searchQuery.excludeKeywords,
                    minPrice = band("min_price_eur", p.searchQuery.minPrice),
                    maxPrice = band("max_price_eur", p.searchQuery.maxPrice),
                    productWishes = wishes,
                    carFilters = carFilters,
                ).let { q ->
                    // Criteria make it a vehicle search, which the vehicle sites answer.
                    if (criteria == null || q.category == MarketGroup.VEHICLES) q
                    else q.copy(category = MarketGroup.VEHICLES, platforms = io.github.tieo.arbay.model.MarketSets.platformsFor(MarketGroup.VEHICLES))
                },
            )
            val r = http.put("$base/api/products/${id.encodeURLPathPart()}") { contentType(ContentType.Application.Json); setBody(json.encodeToString(TrackedProduct.serializer(), next)) }
            if (!r.status.isSuccess()) return@tool "Not changed: ${r.bodyAsText()}"
            Live.show("/saved/${id.encodeURLPathPart()}")
            "Changed: ${next.name} (\"${next.searchQuery.text}\")" +
                (if (next.autoFetch.enabled) ", looks every ${next.autoFetch.intervalMinutes} min" else ", does not look on its own") +
                (if (next.fitAlert.enabled) ", pushes a fit of ${(next.fitAlert.minScore * 100).roundToInt()} % or more" else "") +
                (next.searchQuery.productWishes?.let { "\n  " + wishesLine(it) } ?: "") +
                next.notificationSubfilters.joinToString("") { "\n  alert rule ${it.id}: ${it.summaryText()}" }
        }

        tool(
            "delete_saved_search", "Stop keeping a saved search.", destructive = true,
            props = { string("id", "Saved search id", required = true) },
        ) { args ->
            val r = http.delete("$base/api/products/${args.str("id")!!.encodeURLPathPart()}")
            if (r.status.isSuccess()) "Deleted." else "Not deleted: ${r.bodyAsText()}"
        }

        tool(
            "check_model",
            "Say which of a saved search's models an offer is, after looking at its photos and text: settles a model the fit told from its photos alone (an alert says \"told from its photos alone, unchecked\"), and makes the offer an example the next offers' photos are told by. Leave model out for none of them, which rules the offer out as not a machine wanted; a good machine of a model the wishes do not list belongs in the fit wishes instead.",
            props = {
                string("saved_search_id", "Saved search id", required = true)
                string("listing_id", "Offer id", required = true)
                string("model", "The model's name as the search's fit wishes name it; left out for none of them")
            },
        ) { args ->
            val model = args.str("model")
            val r = http.put("$base/api/products/${args.str("saved_search_id")!!.encodeURLPathPart()}/model-checks/${args.str("listing_id")!!.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("model", model?.let(::JsonPrimitive) ?: JsonNull) }.toString())
            }
            if (r.status.isSuccess()) "Checked: ${model ?: "none of the search's models"}." else "Not checked: ${r.bodyAsText()}"
        }

        tool(
            "hide_offers", "Put offers away so no device shows them again, or bring them back.",
            props = { stringArray("listing_ids", "Offer ids", required = true); boolean("bring_back", "Show them again instead") },
        ) { args ->
            val ids = args.strs("listing_ids")
            val now = hiddenOffers()
            val next = if (args.bool("bring_back") == true) now - ids.toSet() else now + ids
            http.put("$base/api/state/hiddenOffers") { contentType(ContentType.Application.Json); setBody(JsonArray(next.sorted().map(::JsonPrimitive)).toString()) }
            if (args.bool("bring_back") == true) "${now.size - next.size} brought back; ${next.size} put away in all."
            else "${next.size - now.size} put away; ${next.size} in all."
        }

        tool(
            "block_dealer",
            "Block the seller of offers, so every search on every device drops whatever they sell on that market and lists it under \"a dealer you blocked\"; or unblock them. The seller is the one the offer's market names. The same dealer on another market is blocked by naming one of its offers there.",
            props = {
                stringArray("listing_ids", "Offer ids whose sellers to block", required = true)
                string("reason", "Why, in the user's words")
                boolean("unblock", "Unblock these sellers instead")
            },
        ) { args ->
            val now = blockedDealers()
            val lines = mutableListOf<String>()
            var next = now
            args.strs("listing_ids").forEach { id ->
                val listing = offer(id) ?: return@forEach run { lines += "$id: unknown offer; search first." }
                val dealer = BlockedDealer.of(listing, Clock.System.now(), args.str("reason"))
                    ?: return@forEach run { lines += "$id: ${listing.platformId.displayName} names no seller on this offer." }
                val same = { d: BlockedDealer -> d.platform == dealer.platform && d.sellerId == dealer.sellerId && (d.sellerId != null || dealerNameKey(d.name) == dealerNameKey(dealer.name)) }
                if (args.bool("unblock") == true) {
                    next = next.filterNot(same)
                    lines += "$id: unblocked ${dealer.name} on ${dealer.platform.displayName}."
                } else {
                    next = next.filterNot(same) + dealer
                    lines += "$id: blocked ${dealer.name} on ${dealer.platform.displayName}" + (dealer.sellerId?.let { " (seller $it)" } ?: " (by name)") + "."
                }
            }
            if (next != now) {
                val r = http.put("$base/api/state/blockedDealers") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(BlockedDealer.serializer()), next))
                }
                if (!r.status.isSuccess()) return@tool "Not changed: ${r.bodyAsText()}"
            }
            (lines + ("${next.size} dealers blocked: " + next.joinToString("; ") { "${it.name} on ${it.platform.displayName}" })).joinToString("\n")
        }

        tool(
            "note_offers",
            "Put a verdict and its reason on offers, shown to the user on the offer's row and page on every device. Use after reading an ad, so what was concluded is on their screen. A note replaces the one before on that offer.",
            props = {
                putJsonObject("notes") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("listing_id") { put("type", "string") }
                            putJsonObject("verdict") { put("type", "string"); putJsonArray("enum") { Verdict.entries.forEach { add(JsonPrimitive(it.name)) } } }
                            putJsonObject("text") { put("type", "string"); put("description", "The reason, short, in the user's language") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("listing_id")); add(JsonPrimitive("verdict")); add(JsonPrimitive("text")) }
                    }
                }
                required += "notes"
            },
        ) { args ->
            val now = ((getJsonElement("/api/state") as? JsonObject)?.get("offerNotes") as? JsonArray).orEmpty()
                .mapNotNull { runCatching { json.decodeFromJsonElement(OfferNote.serializer(), it) }.getOrNull() }
            val added = (args["notes"] as? JsonArray).orEmpty().map { it as JsonObject }.map {
                OfferNote(it.str("listing_id")!!, Verdict.valueOf(it.str("verdict")!!.uppercase()), it.str("text")!!, by = "Claude")
            }
            val next = now.filterNot { n -> added.any { it.listingId == n.listingId } } + added
            http.put("$base/api/state/offerNotes") { contentType(ContentType.Application.Json); setBody(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(OfferNote.serializer()), next)) }
            "Noted ${added.size} offers; ${next.size} notes in all."
        }

        tool("recent_searches", "The searches run lately on any device, newest first.") {
            val history = getJsonElement("/api/state").let { (it as? JsonObject)?.get("searchHistory") as? JsonArray }.orEmpty()
            history.joinToString("\n") { e ->
                val o = e as JsonObject
                "${o["name"]?.jsonPrimitive?.contentOrNull} (${o["lastRunAt"]?.jsonPrimitive?.contentOrNull})"
            }.ifEmpty { "Nothing run lately." }
        }

        // ── Talking to sellers ──

        tool("seller_account", "Whether Arbay is signed in to the user's account on each market it can sign in to (Kleinanzeigen, eBay, willhaben, Vinted).") {
            getJsonElement("/api/chat/accounts").toString()
        }

        tool(
            "ask_sign_in",
            "Push a request to the user's phone that opens the Arbay app on a market's sign-in page, or with payment on the " +
                "sign-in page of the payment service the market's checkout uses (Vinted's PayPal), for the user to sign in themselves. " +
                "With site instead (google.com, paypal.com), a sign-in of Neuland's account browser for buying at shops through " +
                "NeuAuth: its page goes to the phone, where the user gives the password; account is the one to sign in " +
                "(the one last used where left out), asker who wants it, in words the user knows.",
            props = {
                string("market", "The market, as KLEINANZEIGEN, EBAY_DE, WILLHABEN or VINTED_DE")
                boolean("payment", "The payment service's sign-in rather than the market's")
                string("site", "An account of Neuland's account browser instead of a market: google.com or paypal.com")
                string("account", "With site: the account to sign in, as nanea.biznes@gmail.com")
                string("asker", "With site: who wants the sign-in, shown to the user")
            },
        ) { args ->
            args.str("site")?.let { site ->
                val r = http.post("$base/api/accounts/$site/ask") {
                    args.str("account")?.let { parameter("account", it) }
                    args.str("asker")?.let { parameter("asker", it) }
                }
                return@tool if (!r.status.isSuccess()) "Not sent: ${r.bodyAsText()}" else "Sent to the phone through NeuAuth: ${r.bodyAsText()}"
            }
            if (args.str("market") == null) return@tool "Name a market or a site."
            val r = http.post("$base/api/chat/signin/ask") {
                parameter("market", args.str("market")!!)
                if (args.bool("payment") == true) parameter("payment", "true")
            }
            if (!r.status.isSuccess()) "Not sent: ${r.bodyAsText()}" else if ("true" in r.bodyAsText()) "Sent to the phone." else "Not sent: no push topic is set."
        }

        tool("list_texts", "The user's own texts for sellers, with their fill-ins.") {
            getJson<ChatSettings>("/api/chat/settings").templates.joinToString("\n\n") { "${it.id} \"${it.name}\":\n${it.text}" }.ifEmpty { "No texts kept." }
        }

        tool(
            "keep_text", "Keep a text for sellers exactly as the user dictated it, or change one.",
            props = { string("name", "What to call it", required = true); string("text", "The user's wording", required = true); string("id", "Change this text instead of adding one") },
        ) { args ->
            val settings = getJson<ChatSettings>("/api/chat/settings")
            val t = MessageTemplate(args.str("id") ?: "t${Clock.System.now().toEpochMilliseconds()}", args.str("name")!!, args.str("text")!!)
            val next = settings.copy(templates = settings.templates.filterNot { it.id == t.id } + t)
            http.put("$base/api/chat/settings") { contentType(ContentType.Application.Json); setBody(json.encodeToString(ChatSettings.serializer(), next)) }
            "Kept ${t.id} \"${t.name}\"."
        }

        tool(
            "draft_messages", "Put the user's text blocks together, in the order given, for each offer, filled in with the price that keeps everything within the all-in limit. Sends nothing.",
            props = {
                stringArray("listing_ids", "Offer ids from search", required = true)
                stringArray("text_ids", "Which of the user's text blocks, in order", required = true)
                number("all_in_eur", "Most the user pays, shipping and buyer protection included")
            },
        ) { args ->
            val all = getJson<ChatSettings>("/api/chat/settings").templates
            val blocks = args.strs("text_ids").map { id -> all.firstOrNull { it.id == id } ?: return@tool "No text block $id." }
            val text = composeBlocks(blocks)
            val allIn = args.num("all_in_eur")
            // Each ad's own page as it is now: its price, its shipping and whether it is still up.
            // What a search saw goes stale, and an offer worked out from it went above a lowered ask.
            val drafted = args.strs("listing_ids").mapNotNull { offer(it) }.associateBy { it.id }
            val now = drafted.values.associate { l -> l.id to adNow(l) }
            val pageShipping = drafted.values.associate { l -> l.id to (now[l.id]?.shipping ?: pageShipping(l)) }
            val toDoor = getJson<ChatSettings>("/api/chat/settings").toDoor
            val costs = io.github.tieo.arbay.judging.KleinanzeigenCosts.costs()
            args.strs("listing_ids").joinToString("\n\n") { id ->
                val l = drafted[id] ?: return@joinToString "$id: unknown offer; search first."
                if (!l.platformId.canMessage) return@joinToString "$id: only Kleinanzeigen sellers can be written to."
                now[id]?.state?.takeIf { it != io.github.tieo.arbay.model.AdState.LIVE }
                    ?.let { return@joinToString "$id ${l.title.tidyTitle()}: the ad is ${it.name.lowercase()}." }
                val shipping = pageShipping[id] ?: l.shipping
                val ask = now[id]?.price ?: l.price
                val offer = offerFor(l.platformId, (ask.amount / 100).toInt().takeIf { ask.currency == io.github.tieo.arbay.model.Currency.EUR }, shipping, allIn, toDoor, costs)
                val price = offer.price
                val note = offer.note?.let { " ($it)" } ?: offer.shippingEur?.takeIf { it > 0 }?.let { " (shipping %.2f €)".format(it) } ?: ""
                "$id ${l.title.tidyTitle()} → offer ${price ?: "?"} €$note\n" + fillIn(text, price, l.title.tidyTitle(), offer.shippingEur?.takeIf { it > 0 }?.let { "%.2f €".format(it) }, offer.direct)
            }
        }

        tool(
            "send_messages",
            "Queue messages to Kleinanzeigen sellers. They go out one by one, 30 to 90 seconds apart, through the user's account; waiting ones can be called off. Only send what the user approved.",
            destructive = true,
            props = {
                putJsonObject("messages") {
                    put("type", "array")
                    put("description", "One per seller")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("listing_id") { put("type", "string") }
                            putJsonObject("text") { put("type", "string") }
                            putJsonObject("text_ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "The text blocks the message was made of") }
                            putJsonObject("price") { put("type", "integer"); put("description", "The price the message offers") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("listing_id")); add(JsonPrimitive("text")) }
                    }
                }
                required += "messages"
                string("saved_search_id", "The saved search these came from, to remember the all-in limit")
                number("all_in_eur", "The all-in limit used")
            },
        ) { args ->
            val drafts = (args["messages"] as? JsonArray).orEmpty().map { it as JsonObject }.map { m ->
                val id = m["listing_id"]!!.jsonPrimitive.content
                SendRequest.Draft(
                    // Offers from before a restart are not among this session's searches; the archive has every one.
                    id, (seen[id] ?: io.github.tieo.arbay.repo.ListingArchive.get(id))?.title?.tidyTitle() ?: id, m["text"]!!.jsonPrimitive.content,
                    blockIds = (m["text_ids"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                    price = m["price"]?.jsonPrimitive?.intOrNull,
                )
            }
            val r = http.post("$base/api/chat/outbox") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SendRequest.serializer(), SendRequest(drafts, args.str("saved_search_id"), args.num("all_in_eur"))))
            }
            if (r.status.isSuccess()) "Queued ${drafts.size}. " + outboxText() else "Not queued: ${r.bodyAsText()}"
        }

        tool("outbox", "Messages on their way to sellers, sent, failed or called off.") { outboxText() }

        tool(
            "call_off_message", "Call off a message that is still waiting to go out.",
            props = { string("id", "Outbox message id", required = true) },
        ) { args -> http.delete("$base/api/chat/outbox/${args.str("id")!!.encodeURLPathPart()}").bodyAsText() }

        tool(
            "list_conversations",
            "The user's Kleinanzeigen conversations, newest first, with unread counts and whether each ad is still up, read off its page now.",
        ) {
            val list = getJsonElement("/api/chat/conversations") as? JsonArray ?: return@tool "Not signed in to Kleinanzeigen."
            val states = kotlinx.coroutines.coroutineScope {
                list.map { c -> async { adState((c as JsonObject).str("listingId")) } }.awaitAll()
            }
            list.zip(states).joinToString("\n") { (c, state) ->
                val o = c as JsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                "${s("id")}: ${s("adTitle")} · ${s("partner")} · ad ${state ?: "state unknown"} · ${s("unread")} unread · ${s("lastText")}"
            }.ifEmpty { "No conversations." }
        }

        tool(
            "read_conversation",
            "Every message in one conversation, oldest first, with photo links, and whether its ad is still up (adState, read off the ad's page now). " +
                "Marks the conversation read on Kleinanzeigen, as opening it in the app does.",
            props = { string("id", "Conversation id", required = true) },
        ) { args ->
            val path = "/api/chat/conversations/${args.str("id")!!.encodeURLPathPart()}"
            val c = getJsonElement(path) as? JsonObject
                ?: return@tool "No such conversation."
            // Read here is read: a conversation left unread after it was read reported its messages
            // as new to the user, who had already been told what they said.
            runCatching { http.post("$base$path/read") }
            JsonObject(c + ("adState" to JsonPrimitive(adState(c.str("listingId")) ?: "UNKNOWN"))).toString()
        }

        tool(
            "reply", "Send a reply in a conversation, now. Only send what the user approved.", destructive = true,
            props = { string("id", "Conversation id", required = true); string("text", "The reply", required = true) },
        ) { args ->
            val r = http.post("$base/api/chat/conversations/${args.str("id")!!.encodeURLPathPart()}/reply") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("text", args.str("text")!!) }.toString())
            }
            if (r.status.isSuccess()) "Sent." else "Not sent: ${r.bodyAsText()}"
        }

        tool(
            "fit_price_offer",
            "Work out a price offer through Kleinanzeigen's \"Sicher bezahlen\" in a conversation: the highest price whose payment " +
                "(price, buyer protection fee and the shipping the user picks) stays within the all-in limit, read off the " +
                "conversation's own offer dialog. Sends nothing.",
            props = {
                string("id", "Conversation id", required = true)
                number("all_in_eur", "Most the user pays, fee and shipping included", required = true)
            },
        ) { args ->
            val r = http.post("$base/api/chat/conversations/${args.str("id")!!.encodeURLPathPart()}/offer") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("allInEur", args.num("all_in_eur")!!) }.toString())
            }
            if (!r.status.isSuccess()) return@tool "Could not read the offer dialog: ${r.bodyAsText()}"
            json.decodeFromString<PriceOffer>(r.bodyAsText()).describe()
        }

        tool(
            "send_price_offer",
            "Send a price offer through \"Sicher bezahlen\" in a conversation, the price the user approved. Refused before " +
                "sending if the payment would pass the all-in limit; counts as sent only once it shows in the conversation.",
            destructive = true,
            props = {
                string("id", "Conversation id", required = true)
                number("price", "The price to offer", required = true)
                number("all_in_eur", "Most the user pays, fee and shipping included", required = true)
            },
        ) { args ->
            val r = http.post("$base/api/chat/conversations/${args.str("id")!!.encodeURLPathPart()}/offer") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("allInEur", args.num("all_in_eur")!!); put("price", args.num("price")!!); put("send", true)
                }.toString())
            }
            if (!r.status.isSuccess()) return@tool "Not sent: ${r.bodyAsText()}"
            val offer = json.decodeFromString<PriceOffer>(r.bodyAsText())
            (if (offer.sent) "Sent. " else "Not sent. ") + offer.describe()
        }

        tool(
            "checkout",
            "Read what buying an offer outright costs at its shop's own checkout, signed in to the user's account there: Vinted " +
                "(item, buyer protection fee, shipping to the door), eBay.de at a fixed price (item, shipping to the saved address, a " +
                "coupon the page shows) or alza (item, delivery to the user's pickup point); total and the payment method selected. " +
                "alza's and eBay's are read to their last step before ordering. A web shop's offer (one search lists with a checkout) is " +
                "read to the address and PayPal, together with other offers at the same shop so shipping counts once. Orders and pays nothing.",
            props = {
                string("listing_id", "A Vinted, fixed-price eBay.de, alza or web shop offer id from search", required = true)
                stringArray("together_with", "More offer ids at the same web shop, one of each in the same order")
            },
        ) { args -> purchase(args.str("listing_id")!!, Double.MAX_VALUE, pay = false, args.strs("together_with")) }

        tool(
            "buy",
            "Buy an offer outright, as the user approved, at its shop's own checkout: Vinted's delivered to the door, eBay.de's at " +
                "a fixed price to the saved address, alza's to the user's pickup point, paid with the account's saved payment; a web shop's " +
                "to the user's address as a guest, paid with PayPal, with the other offers named at that shop in the same order. Refused before ordering if the total " +
                "would pass the all-in limit; counts as bought only once the shop confirms it.",
            destructive = true,
            props = {
                string("listing_id", "A Vinted, fixed-price eBay.de, alza or web shop offer id from search", required = true)
                stringArray("together_with", "More offer ids at the same web shop, one of each in the same order")
                number("all_in_eur", "Most the user pays for the whole order, fee and shipping included, in euros (12.48 for 12,48 €)", required = true)
                string("payment", "A web shop's payment: \"card\" pays with the user's card, else PayPal")
            },
        ) { args ->
            val payment = args.str("payment")
            if (payment != null && payment != "card" && payment != "paypal") return@tool "Unknown payment \"$payment\"; card or paypal."
            purchase(args.str("listing_id")!!, args.num("all_in_eur")!!, pay = true, args.strs("together_with"), payment)
        }

        tool(
            "try_bid",
            "Read eBay's own review of a bid on an eBay.de auction without placing it, signed in to the user's eBay account: the bid " +
                "so far, the least eBay takes next, the bid as eBay reads it back, shipping and the total. Places nothing.",
            props = {
                string("listing_id", "An eBay.de auction id from search", required = true)
                number("all_in_eur", "Most the user pays, shipping included, in euros (160.00 for 160 €)", required = true)
            },
        ) { args -> auctionBid(args.str("listing_id")!!, args.num("all_in_eur")!!, place = false) }

        tool(
            "bid_at_end",
            "Bid on an eBay.de auction in its last seconds, as the user approved: up to the all-in limit less the auction's " +
                "shipping. One bid stands per purchase; a bid whose turn comes while another of its purchase stands is skipped. Neuland confirms it " +
                "only while eBay's review reads back the bid and totals no more than the limit.",
            destructive = true,
            props = {
                string("listing_id", "An eBay.de auction id from search", required = true)
                number("all_in_eur", "Most the user pays, shipping included, in euros (160.00 for 160 €)", required = true)
                string("purchase", "The one thing being bought, in letters, digits and hyphens: ssd-2tb, hdd-20tb. The same name for every bid towards it and never one per bid, since only one bid of a purchase can stand", required = true)
            },
        ) { args -> auctionBid(args.str("listing_id")!!, args.num("all_in_eur")!!, place = true, purchase = args.str("purchase")) }

        tool(
            "push_to_phone",
            "Push one message to the user's phone about an offer: name the offer and what they have to decide. Interrupts them, so only for a decision that is theirs.",
            props = {
                string("listing_id", "The offer, an id from search or new_offers", required = true)
                string("decide", "What the user has to decide, in a sentence with the price", required = true)
            },
        ) { args ->
            val l = offer(args.str("listing_id")!!) ?: return@tool "Unknown offer; search first."
            val r = http.post("$base/api/phone/push") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(io.github.tieo.arbay.model.PhonePush.serializer(), io.github.tieo.arbay.model.PhonePush(l.title.tidyTitle(), args.str("decide")!!, l.url)))
            }
            if (r.status.isSuccess() && r.bodyAsText().contains("true")) "Pushed to the phone." else "Not pushed: ${r.bodyAsText()}"
        }

        tool(
            "alert_decisions",
            "Why a saved search's fit alert told of an offer or did not, newest first: pushed, beaten by another offer, judged something else, under the floor. Kept across restarts.",
            props = { string("saved_search_id", "The saved search", required = true); integer("most", "How many (default 50)") },
        ) { args ->
            getJson<List<io.github.tieo.arbay.model.FitAlertDecision>>("/api/products/${args.str("saved_search_id")!!.encodeURLPathPart()}/alert-decisions?most=${args.int("most") ?: 50}")
                .joinToString("\n") { d -> "${d.at} · ${Math.round(d.score * 100)} % (floor ${Math.round(d.floor * 100)} %) · ${d.decision} · ${d.title.take(70)} · ${d.url}" }
                .ifEmpty { "No decisions kept yet." }
        }

        tool(
            "sold_prices",
            "What a model, a drive size or any words sold for on eBay.de lately: median and quartiles with shipping, auctions against fixed prices, new, refurbished and used, by month, each with its number of sales. Name one of the three. Sales to a hidden accepted offer and parts-only offers are left out. Takes up to a few minutes the first time.",
            props = {
                string("model", "A model number, ST18000NM000J")
                integer("capacity_gb", "A size in gigabytes, 18000 for 18 TB; bare drives only")
                string("words", "Anything else, as searched on eBay")
            },
        ) { args ->
            val r = http.get("$base/api/market/sold") {
                args.str("model")?.let { parameter("model", it) }
                args.int("capacity_gb")?.let { parameter("capacityGb", it) }
                args.str("words")?.let { parameter("words", it) }
            }
            if (!r.status.isSuccess()) return@tool "Could not read the sales: ${r.bodyAsText()}"
            json.decodeFromString(io.github.tieo.arbay.model.SoldPrices.serializer(), r.bodyAsText()).describe()
        }

        tool(
            "auctions",
            "The eBay.de auctions running now for a saved search, soonest end first: the bid so far with shipping, when it ends, the drive's facts, and what it can be expected to end at from sold auctions of the same model or size. An expected price still being read arrives if you ask again in a few minutes.",
            props = { string("saved_search_id", "The saved search", required = true) },
        ) { args ->
            val answer = getJson<io.github.tieo.arbay.model.LiveAuctions>("/api/products/${args.str("saved_search_id")!!.encodeURLPathPart()}/auctions")
            val list = answer.auctions
            list.forEach { seen[it.listing.id] = it.listing }
            (answer.problem?.let { "$it\n" } ?: if (answer.reading) "Still asking eBay; call again in a few minutes.\n" else "") + list.joinToString("\n") { a ->
                listOfNotNull(
                    "ends ${a.listing.auctionEndsAt}",
                    a.expectedEndEur?.let { "expected end $it € all in (${a.expectedFrom}, ${a.expectedSales} sales${if (a.expectedThin) ", thin data" else ""}" + (a.limitEur?.let { m -> ", limit $m €" } ?: "") + ")" } ?: "expected end still being read".takeIf { a.expectedPending } ?: "expected end not known",
                    a.listing.line(),
                ).joinToString(" | ")
            }.ifEmpty { if (answer.reading || answer.problem != null) "" else "No auctions running for this search." }
        }

        tool("auction_bids", "The bids set up on auctions, each with its purchase: waiting for the end, placed, ended and not yet settled, lost, won, skipped or not placed, and why. An ended bid blocks the later ones of its purchase until eBay shows it lost or won.") {
            getJson<List<io.github.tieo.arbay.model.AuctionBid>>("/api/auctions/bids").joinToString("\n") { it.describe() }.ifEmpty { "No bids." }
        }

        tool(
            "cancel_bid",
            "Take back a bid on an auction that is still waiting for the auction's end; a bid placed stands.",
            destructive = true,
            props = { string("listing_id", "The auction", required = true) },
        ) { args ->
            val r = http.delete("$base/api/auctions/bids/${args.str("listing_id")!!.encodeURLPathPart()}")
            if (r.bodyAsText().contains("true")) "Cancelled." else "Nothing waiting to cancel."
        }

        // ── Selling ──

        tool("ads_for_sale", "The user's ads for sale on Kleinanzeigen: drafts not posted yet, and the ones posted.") {
            getJson<List<AdDraft>>("/api/selling/drafts").joinToString("\n\n") { it.describe() }.ifEmpty { "No ads for sale." }
        }

        tool(
            "draft_ad",
            "Start an ad for sale like a Kleinanzeigen ad the user bought from: its photos (each cropped a little, so they are " +
                "not the seller's files), its text, category and details. Nothing is posted; the user sees the draft in Arbay.",
            props = { string("listing_id", "The Kleinanzeigen ad, as KLEINANZEIGEN:<ad id>", required = true) },
        ) { args ->
            val r = http.post("$base/api/selling/drafts") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(NewAdDraft.serializer(), NewAdDraft(args.str("listing_id")!!)))
            }
            if (!r.status.isSuccess()) return@tool "No draft: ${r.bodyAsText()}"
            val draft = json.decodeFromString(AdDraft.serializer(), r.bodyAsText())
            Live.show("/sell/${draft.id}")
            draft.describe()
        }

        tool(
            "take_down_ad",
            "Take a posted ad off Kleinanzeigen for good, as when the thing is sold: deletes it on the market, reads its page to confirm, and keeps the draft as taken down so it is never put up again. An ad already gone is only marked.",
            destructive = true,
            props = { string("id", "The draft of the posted ad", required = true) },
        ) { args ->
            val r = http.post("$base/api/selling/drafts/${args.str("id")!!.encodeURLPathPart()}/take-down")
            if (!r.status.isSuccess()) return@tool "Not taken down: ${r.bodyAsText()}"
            json.decodeFromString(AdDraft.serializer(), r.bodyAsText()).describe()
        }

        tool(
            "new_ad",
            "Start a blank ad of the user's own for sale, with a title and Kleinanzeigen's category number (173 is Handy & Telefon); text, price and details follow with change_ad and photos with add_ad_photo. Nothing is posted.",
            props = {
                string("title", "The ad's title", required = true)
                string("category_id", "Kleinanzeigen's category number", required = true)
            },
        ) { args ->
            val r = http.post("$base/api/selling/drafts") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(NewAdDraft.serializer(), NewAdDraft(title = args.str("title"), categoryId = args.str("category_id"))))
            }
            if (!r.status.isSuccess()) return@tool "No draft: ${r.bodyAsText()}"
            val draft = json.decodeFromString(AdDraft.serializer(), r.bodyAsText())
            Live.show("/sell/${draft.id}")
            draft.describe()
        }

        tool(
            "add_ad_photo",
            "Add one of the user's own photos to the end of a draft, from a public address or as base64. Name one of the two.",
            props = {
                string("id", "The draft", required = true)
                string("photo_url", "A public http(s) address of the photo")
                string("photo_base64", "The photo's bytes in base64")
            },
        ) { args ->
            val bytes = args.str("photo_base64")?.let { runCatching { java.util.Base64.getMimeDecoder().decode(it) }.getOrNull() ?: return@tool "The base64 does not read." }
                ?: args.str("photo_url")?.let { url ->
                    val checked = runCatching { io.github.tieo.arbay.routes.requirePublicHttpUrl(url) }.getOrNull() ?: return@tool "That is no public web address."
                    io.github.tieo.arbay.judging.PhotoPrints.downloadAt(checked) ?: return@tool "The photo did not arrive."
                }
                ?: return@tool "Name photo_url or photo_base64."
            val r = http.post("$base/api/selling/drafts/${args.str("id")!!.encodeURLPathPart()}/photos") {
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
            }
            if (!r.status.isSuccess()) return@tool "Not added: ${r.bodyAsText()}"
            json.decodeFromString(AdDraft.serializer(), r.bodyAsText()).describe()
        }

        tool(
            "change_ad",
            "Change an ad for sale: what is given replaces what it had. A draft is changed here; a posted ad is changed on Kleinanzeigen at once (title, text, price, price type, shipping, Direkt kaufen; its photos and details stay). Title and text only as the user worded or approved them.",
            destructive = true,
            props = {
                string("id", "The draft", required = true)
                string("title", "The ad's title"); string("text", "The ad's text")
                integer("price_eur", "The asking price in whole euros"); string("price_type", "FIXED, NEGOTIABLE (VB) or GIVE_AWAY")
                boolean("shipping", "Whether it ships; false is pickup only"); boolean("buy_now", "Offer Kleinanzeigen's Direkt kaufen")
                stringArray("photos", "The photos to keep, in the order the ad shows them (names from the draft)")
                stringArray("details", "Details to set, each as \"Label: option\" the way the post form words them (\"Farbe: Grau\"); an empty option drops that detail")
                integer("repost_every_days", "Every how many days the posted ad goes up again (posted anew, the old one taken down, so it lists with the newest); 0 leaves it as posted")
            },
        ) { args ->
            args.int("repost_every_days")?.let { days ->
                val r = http.put("$base/api/selling/drafts/${args.str("id")!!.encodeURLPathPart()}/repost") {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(RepostEvery.serializer(), RepostEvery(days.takeIf { it > 0 })))
                }
                if (!r.status.isSuccess()) return@tool "Not changed: ${r.bodyAsText()}"
            }
            val d = getJson<AdDraft>("/api/selling/drafts/${args.str("id")!!.encodeURLPathPart()}")
            val details = args.strs("details").map { it.substringBefore(':').trim() to it.substringAfter(':', "").trim() }
            val next = d.copy(
                title = args.str("title") ?: d.title,
                description = args.str("text") ?: d.description,
                priceEur = args.int("price_eur") ?: d.priceEur,
                priceType = args.str("price_type")?.let { AdPriceType.valueOf(it.uppercase()) } ?: d.priceType,
                shipping = args.bool("shipping") ?: d.shipping,
                buyNow = args.bool("buy_now") ?: d.buyNow,
                photos = args.strs("photos").takeIf { "photos" in args } ?: d.photos,
                attributes = (d.attributes + details.toMap()).filterValues { it.isNotEmpty() },
            )
            // A posted ad is changed on the market itself, through its edit form.
            val r = if (d.postedAdId == null) http.put("$base/api/selling/drafts/${d.id.encodeURLPathPart()}") {
                contentType(ContentType.Application.Json); setBody(json.encodeToString(AdDraft.serializer(), next))
            } else http.post("$base/api/selling/drafts/${d.id.encodeURLPathPart()}/update") {
                contentType(ContentType.Application.Json); setBody(json.encodeToString(AdDraft.serializer(), next))
            }
            if (!r.status.isSuccess()) return@tool "Not changed: ${r.bodyAsText()}"
            val kept = json.decodeFromString(AdDraft.serializer(), r.bodyAsText())
            (if (kept.postedAdId != null) "Changed on Kleinanzeigen: ${kept.postedUrl()}\n" else "") + kept.describe()
        }

        tool(
            "post_ad", "Post a draft ad on Kleinanzeigen through the user's account, now. Only a draft the user approved as it stands.",
            destructive = true,
            props = { string("id", "The draft", required = true) },
        ) { args ->
            val r = http.post("$base/api/selling/drafts/${args.str("id")!!.encodeURLPathPart()}/post")
            if (!r.status.isSuccess()) return@tool "Not posted: ${r.bodyAsText()}"
            val result = json.decodeFromString(PostAdResult.serializer(), r.bodyAsText())
            result.error?.let { "Not posted: $it" } ?: "Posted: ${result.draft.postedUrl()}"
        }

        // ── Settings every device shares ──

        tool(
            "set_look", "How Arbay looks on every device: the look, brightness, offers as rows or photos, and the currency prices are shown in.",
            props = {
                string("look", "receipt, studio or graphite"); string("brightness", "SYSTEM, LIGHT or DARK")
                string("offer_layout", "ROWS or PHOTOS"); string("currency", "EUR, USD, GBP or CHF")
            },
        ) { args ->
            val now = (getJsonElement("/api/state") as? JsonObject)?.get("look") as? JsonObject ?: JsonObject(emptyMap())
            val next = JsonObject(now + listOfNotNull(
                args.str("look")?.let { "look" to JsonPrimitive(it) },
                args.str("brightness")?.let { "brightness" to JsonPrimitive(it) },
                args.str("offer_layout")?.let { "offerLayout" to JsonPrimitive(it) },
                args.str("currency")?.let { "currency" to JsonPrimitive(it) },
            ))
            http.put("$base/api/state/look") { contentType(ContentType.Application.Json); setBody(next.toString()) }
            "Every device takes $next within a minute."
        }
    }

    private suspend fun adNow(l: Listing): io.github.tieo.arbay.model.AdNow? = runCatching {
        val r = http.get("$base/api/crawler/listing-state") { parameter("url", l.url); parameter("platform", l.platformId.name) }
        if (!r.status.isSuccess() || r.status.value == 204) null
        else json.decodeFromString(io.github.tieo.arbay.model.AdNow.serializer(), r.bodyAsText())
    }.getOrNull()

    /** Whether the ad is up, for offer_details, in words (read now, not checkable on this market, or a
     *  read that failed and why), with what the page said when it was read. */
    private suspend fun adNowSaid(l: Listing): Pair<String, io.github.tieo.arbay.model.AdNow?> = runCatching {
        val r = http.get("$base/api/crawler/listing-state") { parameter("url", l.url); parameter("platform", l.platformId.name) }
        when {
            r.status.value == 204 -> "Ad now: not checked on this market. " to null
            !r.status.isSuccess() -> "Ad now: could not be read (${r.bodyAsText().ifBlank { r.status.toString() }}). " to null
            else -> json.decodeFromString(io.github.tieo.arbay.model.AdNow.serializer(), r.bodyAsText()).let { "Ad now: ${it.state} (read just now). " to it }
        }
    }.getOrElse { "Ad now: could not be read (${it.message}). " to null }

    private suspend fun pageShipping(l: Listing): io.github.tieo.arbay.model.Shipping? = runCatching {
        val r = http.get("$base/api/crawler/listing-detail") { parameter("url", l.url); parameter("platform", l.platformId.name); parameter("id", l.id) }
        if (!r.status.isSuccess() || r.status.value == 204) null
        else json.decodeFromString(io.github.tieo.arbay.model.ListingDetail.serializer(), r.bodyAsText()).shipping
    }.getOrNull()

    /** Why the search removed an offer: the reason, and which criterion or word when it names one. */
    private fun DroppedListing.why(): String = why(reason, detail)

    private fun why(reason: DropReason, detail: String?): String = reason.label + (detail?.let { ": $it" } ?: "")

    /** What an offer from outside the EU costs once in: its import VAT, and a vehicle's customs duty. */
    private fun Listing.landedWords(): String? {
        val settings = io.github.tieo.arbay.repo.ImportSettingsStore.current
        val vat = importVat(settings) ?: return null
        val duty = importDuty(settings)
        return "lands at ${landedPrice(settings).amount / 100.0} ${price.currency} with " +
            (duty?.let { "10 % customs duty ${it.amount / 100.0} and " } ?: "") + "${settings.importVatPercent} % import VAT ${vat.amount / 100.0}" +
            (location?.country?.let { " (from $it)" } ?: "")
    }

    private fun Listing.line(foundAt: Instant? = null): String = listOfNotNull(
        id, title.tidyTitle(), priceWords(), extraCosts?.let { "+ ${it.amount / 100.0} ${it.currency} the dealer adds (${extraCostsSaid.orEmpty().take(120)}) = ${effectivePrice.amount / 100.0} with them" }, landedWords(), delivery?.let { "arrives ${it.describe()}" },
        coupon?.let { c -> listOfNotNull(c.code?.let { "with code $it" } ?: "with a coupon", oldPrice?.let { "${it.amount / 100.0} without" }).joinToString(", ") },
        platformId.name.lowercase(),
        location?.city, distanceKm?.let { "${it.toInt()} km" }, seller?.name?.let { name -> "sold by $name" + (seller?.rating?.let { " · $it★" } ?: "") },
        listingDate?.let { "online ${(Clock.System.now() - it).inWholeDays} days" },
        foundAt?.let { "found ${(Clock.System.now() - it).inWholeHours} h ago" },
        condition?.name?.lowercase(), saleType?.name?.lowercase(),
        checkout?.takeIf { platformId == io.github.tieo.arbay.model.PlatformId.WEB }?.let { "checkout at $it" }, vehicle?.summary(),
        drive?.driveLine(),
        matchScore?.let { "fit ${Math.round(it * 100)}%" },
        ownCost?.let { c -> "costs ${c.totalEur} € over ${c.years} years at ${c.kmPerYear} km/year (" + c.totalsByKm.entries.joinToString(", ") { "${it.key / 1000}k km: ${it.value} €" } + ")" },
        photosAlsoIn.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "LIKELY SCAM, its photos are also in: ") { c -> listOfNotNull(c.place, c.url).joinToString(" ") },
        url,
    ).joinToString(" | ")

    private fun io.github.tieo.arbay.model.SoldPrices.describe(): String {
        fun line(name: String, s: io.github.tieo.arbay.model.PriceSpread) =
            if (s.count == 0) "$name: no sales" else "$name: median ${s.medianEur} € (quartiles ${s.p25Eur} to ${s.p75Eur}), ${s.count} sales"
        return listOf(
            "Sold on eBay.de, $asked, with shipping:", line("all", all), line("auctions", auctions), line("buy now", buyNow),
            line("new", new), line("refurbished", refurbished), line("used", used),
        ).joinToString("\n") + "\n" + months.joinToString("\n") { line(it.month, it.spread) } +
            "\nLeft out: $leftOutHiddenPrice sold to a hidden offer, $leftOutBroken broken or for parts, $leftOutOther other size, other drive or a lot."
    }

    /** What a drive offer states, and how far its page and photos have been read. */
    private fun io.github.tieo.arbay.model.DriveFacts.driveLine(): String = (
        listOfNotNull(model) + summary() +
            listOfNotNull(
                failChance5y?.let { "${Math.round(it * 100)} % fails in 5 y" }, lifeBasis?.let { "from $it" }, healthNote,
                "page not read yet".takeUnless { pageRead }, "$photosPending photos being read".takeIf { photosPending > 0 },
            )
        ).joinToString(", ")

    /** A line for each of [drives] no other offer beats on both price per GB and life left, cheapest first. */
    private fun frontLines(drives: List<Listing>): String {
        val front = drives.driveFront()
        if (front.isEmpty()) return ""
        return "Trade-off front (no other offer is both cheaper per GB and longer lived), cheapest per GB first:\n" +
            drives.filter { it.id in front }.sortedBy { it.drive?.centsPerGb }.joinToString("\n") { "  " + it.line() } + "\n"
    }

    /**
     * The price as what it is: landed where the delivery charge is known, with the item and the
     * charge it adds up from, and the item alone where the market stated no charge. A bare number
     * left a reader unsure whether an eBay 11.99 already held its 5.99 shipping (it did not).
     */
    private fun Listing.priceWords(): String {
        val item = "${price.amount / 100.0} ${price.currency}"
        val s = shipping
        val cost = s?.cost?.takeIf { it.currency == price.currency }
        return when {
            s == null -> "$item item, shipping not stated"
            cost != null && cost.amount > 0 -> "${effectivePrice.amount / 100.0} ${price.currency} landed ($item item + ${cost.amount / 100.0} shipping)"
            s.free || cost != null -> "$item landed (free shipping)"
            !s.available && s.pickup -> "$item, pickup only"
            !s.available -> "$item item, not sent to Germany"
            s.cost != null -> "$item item + ${s.cost!!.amount / 100.0} ${s.cost!!.currency} shipping"
            else -> "$item item, ships, shipping not stated"
        }
    }

    /** What the SMART screenshots among a drive ad's photos say, one line each, as the offer screen shows it. */
    private suspend fun driveHealthLine(l: Listing, page: JsonObject): String {
        if (!io.github.tieo.arbay.model.looksLikeADrive(l.title)) return ""
        val photos = (page["photos"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.ifEmpty { l.imageUrls }
        val health = runCatching {
            val r = http.post("$base/api/crawler/drive-health") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(io.github.tieo.arbay.model.DriveHealthAsk.serializer(), io.github.tieo.arbay.model.DriveHealthAsk(l.id, l.title, photos)))
            }
            json.decodeFromString(io.github.tieo.arbay.model.DriveHealth.serializer(), r.bodyAsText())
        }.getOrNull() ?: return "Drive health: the photos could not be read.\n"
        val pending = if (health.photosPending > 0) "Drive health: ${health.photosPending} more photos are being read; ask again in a minute.\n" else ""
        if (health.readings.isEmpty()) return pending.ifEmpty {
            if (health.photosRead == 0) "Drive health: no photo of the ad could be read.\n"
            else "Drive health: none of its ${health.photosRead} photos shows a SMART screen.\n"
        }
        return pending + health.readings.joinToString("") { r ->
            "Drive health, photo ${r.photo}: ${r.verdict}" +
                listOfNotNull(r.powerOnHours?.let { "$it h" }, r.reallocated?.let { "$it reallocated" }, r.pending?.let { "$it pending" },
                    r.uncorrectable?.let { "$it uncorrectable" }, r.wearUsed?.let { "$it % worn" }, r.spareLeft?.let { "$it % spare" },
                    r.status?.let { "tool says $it" }).takeIf { it.isNotEmpty() }
                    ?.joinToString(", ", prefix = " (", postfix = ")").orEmpty() +
                r.findings.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = ": ").orEmpty() + "\n"
        }
    }

    /** Where a vehicle's fit comes from, each wish with the whole points it adds of those it could:
     *  "\nfit 76 %: size 27/27, price 7/21 (27.400 €), …". Empty for an offer without a fit. */
    /** What the vehicle costs to own, part by part, with what each estimate rests on. */
    private fun Listing.ownCostLines(): String = ownCost?.let { c ->
        "\nCost to own over ${c.years} years at ${c.kmPerYear} km/year: ${c.totalEur} € (" + c.totalsByKm.entries.joinToString(", ") { "${it.key} km: ${it.value} €" } + ")" +
            c.parts.joinToString("") { "\n  ${it.what}: ${it.eur} €" + (it.note?.let { n -> " ($n)" } ?: "") } +
            (if (c.assumptions.isEmpty()) "" else "\n  Assumed: " + c.assumptions.joinToString("; ")) +
            "\n  Not counted: " + c.notCounted.joinToString("; ") + (c.source?.let { "\n  Source: $it" } ?: "")
    }.orEmpty()

    private fun Listing.fitLine(): String = fit.takeIf { it.isNotEmpty() }?.let { parts ->
        parts.zip(parts.wholePoints()).joinToString(", ", prefix = "\nfit ${Math.round((matchScore ?: 0.0) * 100)} %: ") { (p, w) ->
            "${p.wish.label.lowercase()} ${w.first}/${w.second}" + (p.note?.let { " ($it)" } ?: "")
        }
    }.orEmpty()

    /** What a vehicle's card states, so a van can be judged from the list: its age, mileage,
     *  power, gearbox and size. The size is on the common scale (L1 short to L4 extra long, H1
     *  normal roof to H3 super high), where a Crafter's mittellang Hochdach, VW's own L3H3, is L2H2. */
    private fun io.github.tieo.arbay.model.VehicleInfo.summary(): String? {
        fun range(a: Int?, b: Int?) = a?.let { if (b != null && b != a) "$a-$b" else "$a" }
        return listOfNotNull(
            firstRegYear?.let { y -> firstRegMonth?.let { "%02d/%d".format(it, y) } ?: "$y" },
            mileageKm?.let { "%,d km".format(it).replace(',', '.') },
            powerKw?.let { "${kwToPs(it)} PS" },
            gearbox?.name?.lowercase(),
            listOfNotNull(range(vanLength, vanLengthMax)?.let { "L$it" }, range(vanHeight, vanHeightMax)?.let { "H$it" })
                .takeIf { it.isNotEmpty() }?.joinToString("", prefix = "size ", postfix = " (common scale)"),
            wheelbaseMm?.let { "wheelbase $it mm" },
            condition?.let {
                when (it) {
                    io.github.tieo.arbay.model.VehicleCondition.PRE_REGISTRATION -> "pre-registered"
                    io.github.tieo.arbay.model.VehicleCondition.DEMO -> "demonstrator"
                    io.github.tieo.arbay.model.VehicleCondition.ANNUAL_CAR -> "employee car"
                    else -> it.name.lowercase()
                }
            },
            trim,
            availability?.describe(),
            batteryKwh?.let { "battery ${it.toBigDecimal().stripTrailingZeros().toPlainString()} kWh" },
            rangeKm?.let { "WLTP range $it km" },
            consumptionKwhPer100Km?.let { "$it kWh/100 km" },
            fastChargeKw?.let { "DC ${it.toInt()} kW" },
            fastChargeMinutes?.let { "10-80 % in $it min" },
            heatPump?.takeIf { it }?.let { "heat pump" },
            towbar?.let { if (it) "towbar" else "no towbar" },
            vatDeductible?.takeIf { it }?.let { "VAT deductible" },
        ).joinToString(", ").ifEmpty { null }
    }

    private suspend fun outboxText(): String = (getJsonElement("/api/chat/outbox") as? JsonArray).orEmpty().takeLast(20).joinToString("\n") { m ->
        val o = m as JsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        "${s("id")}: ${s("title")} · ${s("state")} · at ${s("sendAt")}" + (s("error")?.let { " · $it" } ?: "")
    }.ifEmpty { "Nothing on its way." }

    private suspend fun blockedDealers(): List<BlockedDealer> =
        ((getJsonElement("/api/state") as? JsonObject)?.get("blockedDealers") as? JsonArray).orEmpty()
            .mapNotNull { runCatching { json.decodeFromJsonElement(BlockedDealer.serializer(), it) }.getOrNull() }

    /** An offer by id: one this session searched, else the newest copy the server holds, else the
     *  copy archived when it was first found. */
    /** The offer [id] as last stored, with its photos judged as they are now: a stored copy keeps
     *  the verdict of the day it was stored, and a Fellbach Crafter listed by one dealer on
     *  AutoScout24 and mobile.de still read as a likely scam days after copies at one address
     *  stopped counting. */
    private suspend fun offer(id: String): Listing? = (seen[id]
        ?: runCatching { getJson<Listing>("/api/listings/${id.encodeURLPathPart()}") }.getOrNull()
        ?: io.github.tieo.arbay.repo.ListingArchive.get(id))
        ?.let { it.copy(photosAlsoIn = io.github.tieo.arbay.judging.PhotoPrints.copiesOf(it)) }

    /** [id]'s checkout read, or bought within [allInEur] with [pay]; [together] are more offers at the same web shop, in the same order. */
    private suspend fun purchase(id: String, allInEur: Double, pay: Boolean, together: List<String> = emptyList(), payment: String? = null): String {
        val l = offer(id) ?: return "Unknown offer; search first."
        // A web offer found before its shop's checkout was known is asked about by the server itself.
        // Any other shop's offer is asked about by the server, which buys only where Neuland names a checkout.
        fun buyable(o: Listing) = io.github.tieo.arbay.model.checkoutSite(o) != null ||
            (o.saleType != io.github.tieo.arbay.model.SaleType.AUCTION && o.saleType != io.github.tieo.arbay.model.SaleType.RENT)
        val more = together.filter { it != id }.distinct().map { offer(it) ?: return "Unknown offer $it; search first." }
        (listOf(l) + more).forEach { o ->
            if (!buyable(o)) return "Arbay buys outright only at Vinted's, alza's and eBay.de's checkouts and at web shops Neuland checks out at, and an auction or a rental not at all; ${o.id} is at ${o.url}."
            if (o.price.currency != io.github.tieo.arbay.model.Currency.EUR) return "${o.id} is priced in ${o.price.currency}; Arbay buys in euros only."
        }
        val r = http.post("$base/api/chat/buy") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(io.github.tieo.arbay.model.PurchaseRequest.serializer(), io.github.tieo.arbay.model.PurchaseRequest(l.url, allInEur, pay, more.map { it.url }, payment)))
        }
        if (!r.status.isSuccess()) return "${if (pay) "Not bought" else "Could not read the checkout"}: ${r.bodyAsText()}"
        return json.decodeFromString(io.github.tieo.arbay.model.Purchase.serializer(), r.bodyAsText()).describe()
    }

    /** eBay's review of a bid on auction [id] within [allInEur], or the bid set up for its end with [place]. */
    private suspend fun auctionBid(id: String, allInEur: Double, place: Boolean, purchase: String? = null): String {
        val l = offer(id) ?: return "Unknown offer; search first."
        if (l.platformId != io.github.tieo.arbay.model.PlatformId.EBAY_DE) return "Arbay bids on eBay.de only; this offer is on ${l.platformId.displayName}."
        if (l.saleType != io.github.tieo.arbay.model.SaleType.AUCTION) return "This offer is no auction."
        val ends = l.auctionEndsAt ?: return "The auction's end is not known; read it again."
        val shipping = l.shipping?.let { if (it.free) io.github.tieo.arbay.model.Money(0, it.cost?.currency ?: io.github.tieo.arbay.model.Currency.EUR) else it.cost }
            ?: return "The auction's shipping is not known; a bid needs it to stay within the limit."
        if (shipping.currency != io.github.tieo.arbay.model.Currency.EUR) return "The auction ships for ${shipping.currency}, not euros."
        val request = io.github.tieo.arbay.model.AuctionBidRequest(l.id, l.url, l.title, ends, shipping.amount / 100.0, allInEur, purchase)
        val r = http.post("$base/api/auctions/bids${if (place) "" else "/try"}") {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(io.github.tieo.arbay.model.AuctionBidRequest.serializer(), request))
        }
        if (!r.status.isSuccess()) return "${if (place) "No bid set up" else "Could not read the bid form"}: ${r.bodyAsText()}"
        return if (place) json.decodeFromString(io.github.tieo.arbay.model.AuctionBid.serializer(), r.bodyAsText()).describe()
        else json.decodeFromString(io.github.tieo.arbay.model.BidReview.serializer(), r.bodyAsText()).describe()
    }

    private suspend fun hiddenOffers(): Set<String> =
        ((getJsonElement("/api/state") as? JsonObject)?.get("hiddenOffers") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()

    /** An ad's state now (LIVE, PAUSED, DELETED), off its own page; null when the page does not say.
     *  Kleinanzeigen answers its short ad address, so the listing id is enough to find the page. */
    private suspend fun adState(listingId: String?): String? {
        val adId = listingId?.substringAfter("KLEINANZEIGEN:", "")?.takeIf { it.isNotEmpty() } ?: return null
        val r = http.get("$base/api/crawler/listing-state") {
            parameter("platform", "KLEINANZEIGEN"); parameter("url", "https://www.kleinanzeigen.de/s-anzeige/$adId")
        }
        if (!r.status.isSuccess() || r.status.value == 204) return null
        return (Json.parseToJsonElement(r.bodyAsText()) as? JsonObject)?.str("state")
    }

    private suspend fun getJsonElement(path: String): JsonElement {
        val r = http.get("$base$path")
        val body = r.bodyAsText()
        if (!r.status.isSuccess()) throw ToolFailure(body.ifBlank { r.status.description })
        return json.parseToJsonElement(body)
    }

    private suspend inline fun <reified T> getJson(path: String): T = json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), getJsonElement(path))

    private class ToolFailure(message: String) : Exception(message)

    // ── Declaring tools ──

    private fun Server.tool(
        name: String,
        description: String,
        destructive: Boolean = false,
        props: McpProps.() -> Unit = {},
        run: suspend (JsonObject) -> String,
    ) = textTool(name, description, destructive, readOnly = !destructive && name.startsWith("list") || name in READ_ONLY, props, run)

    private companion object {
        val READ_ONLY = setOf("whats_on_screen", "show_on_screen", "search", "offer_details", "recent_searches", "seller_account", "outbox", "draft_messages", "checkout", "try_bid", "auction_bids", "sold_prices", "auctions", "alert_decisions")
    }
}

/** When a thing arrives, in a reader's words: the days the market names ("by Fri 6 Nov", "Tue 10 –
 *  Thu 12 Nov"), or its span of days ("in 2–3 days"), else the market's own words. */
internal fun io.github.tieo.arbay.model.Delivery.describe(): String {
    val day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)
    fun d(s: String?) = s?.let { runCatching { java.time.LocalDate.parse(it).format(day) }.getOrNull() }
    val from = d(earliest)
    val to = d(latest)
    val span = days
    return when {
        from != null && to != null && from != to -> "$from – $to"
        to != null -> if (from == null) "by $to" else "on $to"
        from != null -> "from $from"
        span != null && span.size == 2 -> if (span[0] == span[1]) "in ${span[0]} day${if (span[0] == 1) "" else "s"}" else "in ${span[0]}–${span[1]} days"
        else -> words
    }
}

internal fun io.github.tieo.arbay.model.AuctionBid.describe(): String =
    "$state · ${purchase ?: "no purchase"} · $title · up to $maxEur € + $shippingEur € shipping = $allInEur € all in · ends $endsAt · $url" +
        (problem?.let { " · $it" } ?: "") + (outcome?.let { " · eBay: ${it.replace(Regex("\\s+"), " ").take(300)}" } ?: "")

internal fun io.github.tieo.arbay.model.BidReview.describe(): String = when {
    !biddable -> "The item page shows no bid button: no auction, or ended."
    else -> listOfNotNull(
        currentEur?.let { "bid so far $it €" + (bids?.let { b -> " ($b bids)" } ?: "") },
        leastEur?.let { "eBay takes $it € or more" },
        maxEur?.let { "eBay reads the bid as $it €" },
        shippingEur?.let { "shipping $it €" },
        totalEur?.let { "total at most about $it €" },
        if (placed) "PLACED" + (standing?.let { st -> if (st) ", listed among the running bids" else ", NOT listed among the running bids" } ?: "") else "not placed",
        problem?.let { "stopped: $it" },
        outcome?.let { "eBay: ${it.replace(Regex("\\s+"), " ").take(300)}" },
    ).joinToString(" · ")
}

internal fun io.github.tieo.arbay.model.Purchase.describe(): String = when {
    !buyable -> problem ?: "The shop no longer offers to buy it."
    else -> listOfNotNull(
        if (paid) "Paid" + (order?.let { ", order $it." } ?: ".") else if (placed) "ORDER PLACED, not paid: check it in the shop's account." else "Not paid.",
        problem,
        "item ${itemEur ?: "?"} €" + (feeEur?.let { " + fee $it €" } ?: "") + " + ${delivery ?: "shipping"} ${shippingEur ?: "?"} €" + (discountEur?.let { " - discount $it €" } ?: "") + " = ${totalEur ?: "?"} €",
        payment?.let { "payment: $it" },
    ).joinToString(" · ")
}

/** A line per offer of a saved search's status that came down in price since it was last opened,
 *  biggest cut first, from the price before the first cut to the price now, each with how long
 *  ago its last cut was. Given [since], only the offers cut since then. */
private fun priceDropLines(status: JsonObject?, since: Instant? = null): String =
    (status?.get("priceDrops") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { d ->
        val at = d.str("at")?.let(Instant::parse)
        if (since != null && (at == null || at < since)) return@mapNotNull null
        "\n  price drop: ${d.str("listingId")} ${d.str("title")} ${d.num("fromEur")?.toLong()} € → ${d.num("toEur")?.toLong()} €" +
            (at?.let { " · cut ${hoursAgo(it.toEpochMilliseconds())}" } ?: "") + " ${d.str("url")}"
    }.joinToString("")

/** How long ago an epoch-millisecond time was, in whole hours: "5 h ago". */
private fun hoursAgo(millis: Long) = "${(Clock.System.now().toEpochMilliseconds() - millis) / 3_600_000L} h ago"

/** What the vehicle criteria parameter takes, named for the tools that carry it. */
private const val VEHICLE_ARG = "A vehicle search's criteria as JSON, the same the app's vehicle filters set: " +
    "{\"fuels\":[\"ELECTRIC\"],\"conditions\":[\"NEW\",\"PRE_REGISTRATION\",\"DEMO\"],\"bodyTypes\":[\"SUV\"],\"firstRegFromYear\":2024," +
    "\"maxMileageKm\":5000,\"minPowerKw\":100,\"transmission\":\"AUTOMATIC\",\"sellerType\":\"BUSINESS\",\"minSeats\":5,\"inStockOnly\":true,\"descriptionContains\":\"Wärmepumpe\"}. " +
    "inStockOnly leaves out a vehicle the dealer still orders from the maker (Bestellfahrzeug), or whose wait is counted in months or ends more than six weeks away. New means never registered. " +
    "Electric cars: atLeastSizeOf and idealSizeOf name a model by make and model (\"VW ID.3\", \"VW ID.4\"): every electric model at least " +
    "as long is searched too, shorter ones are left out, and the fit rises towards the ideal's length (lengths from ev-database.org); " +
    "idealRangeKm (WLTP), idealDcKw and wantsHeatPump weigh the fit, from the ad or else the model's catalogue entry. " +
    "towbar true or false keeps only cars with or without one, read from the ad's words and equipment list; a car whose ad does not say is kept as unchecked. " +
    "costKmPerYear (e.g. 10000) adds each car's cost to own over 10 years from ADAC's tables (purchase less value at the end, fixed costs, workshop, charging at the user's price mix, care), shown at 5,000, 10,000 and 20,000 km too; " +
    "idealTotalCostEur and maxTotalCostEur make that total the fit's main wish in place of the price. " +
    "Fuels: PETROL, DIESEL, ELECTRIC, HYBRID_PETROL, HYBRID_DIESEL, PLUGIN_HYBRID, MILD_HYBRID, LPG, CNG, HYDROGEN. " +
    "Conditions: NEW, USED, DEMO (Vorführwagen), PRE_REGISTRATION (Tageszulassung), ANNUAL_CAR (Jahreswagen), CLASSIC, DAMAGED. " +
    "Bodies: SMALL_CAR, SEDAN, ESTATE, SUV, COUPE, CONVERTIBLE, VAN, MINIVAN, PICKUP, TRANSPORTER. " +
    "A listing that does not state a criterion is kept and marked unchecked, unless strictUnknown is true. " +
    "The price band goes in min_price_eur and max_price_eur"

/** The vehicle criteria a tool call names, a field it does not know refused rather than ignored,
 *  so a misspelt criterion cannot quietly search without it. */
private val vehicleJson = Json { ignoreUnknownKeys = false; encodeDefaults = false }

private fun vehicleCriteria(args: JsonObject): Result<CarFilters?> {
    // Clients send the criteria either as a JSON object or as that object written into a string.
    val raw = (args["vehicle"] as? JsonObject)?.toString() ?: args.str("vehicle") ?: return Result.success(null)
    return runCatching { vehicleJson.decodeFromString(CarFilters.serializer(), raw) }
        .recoverCatching { throw IllegalArgumentException("vehicle is not readable: ${it.message?.take(300)}") }
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.strs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

/** An offer as its dialog added it up, in one line. */
private fun AdDraft.describe(): String = listOfNotNull(
    "$id: $title",
    takenDownAt?.let { "taken down $it" } ?: postedUrl()?.let { "posted: $it" } ?: problem()?.let { "not ready: $it" } ?: "ready to post",
    if (repostEveryDays == null) "stays as posted" else "goes up again every $repostEveryDays days" + (nextRepost()?.let { ", next $it" } ?: " once posted"),
    repostProblem,
    "price: ${priceEur?.let { "$it €" } ?: "none"} ${priceType.label}" + (if (shipping) ", ships" else ", pickup only") + (if (buyNow) ", Direkt kaufen" else ""),
    "category $categoryId; details: ${attributes.entries.joinToString(", ") { "${it.key}: ${it.value}" }.ifEmpty { "none" }}",
    "photos: ${photos.joinToString(", ")}",
    "text:\n$description",
).joinToString("\n")

private fun PriceOffer.describe(): String {
    fun eur(v: Double?) = v?.let { "%.2f €".format(it) } ?: "?"
    val ways = shipping.joinToString(", ") { "${it.name} ${eur(it.eur)}" }.ifEmpty { "not listed" }
    return "Offer ${eur(price)} + buyer protection ${eur(fee)} + shipping ${eur(shippingEur)} = paid ${eur(paid)}. " +
        "The seller ships: $ways."
}

/** A search's fit wishes in one line: "fit wishes: Lägler HUMMEL 100 %, PROFIT 80 % · 450–1.500 € · out: 400 V · plus: Schleifbänder". */
private fun wishesLine(w: io.github.tieo.arbay.model.ProductWishes): String = "fit wishes: " + listOfNotNull(
    w.models.takeIf { it.isNotEmpty() }?.joinToString(", ") { m -> "${m.name} ${(m.worth * 100).roundToInt()} %" + (listOfNotNull(m.idealPriceEur, m.maxPriceEur).takeIf { p -> p.isNotEmpty() }?.joinToString("–", prefix = " (", postfix = " €)") ?: "") },
    listOfNotNull(w.idealPriceEur, w.maxPriceEur).takeIf { it.isNotEmpty() }?.joinToString("–", postfix = " €"),
    w.ruledOutWords.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "out: "),
    w.plusWords.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "plus: "),
).joinToString(" · ")
