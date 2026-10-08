package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.CrawlerEventType
import io.github.tieo.arbay.model.CrawlerSearchEvent
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.OfferNote
import io.github.tieo.arbay.model.Verdict
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.canMessage
import io.github.tieo.arbay.model.composeBlocks
import io.github.tieo.arbay.model.fillIn
import io.github.tieo.arbay.model.offerFor
import io.github.tieo.arbay.model.tidyTitle
import io.ktor.client.HttpClient
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
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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
import kotlinx.serialization.json.jsonArray
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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Listings returned by searches in this session, so a later tool can name them by id. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Listing>()

    fun server(): Server = Server(
        Implementation(name = "arbay", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = """
            Arbay searches second-hand markets (Kleinanzeigen, eBay, Vinted, willhaben and more) for the user,
            keeps saved searches, and talks to Kleinanzeigen sellers through the user's own account.
            Prices are landed prices in EUR. Messages to sellers are always the user's own texts; fill their
            fill-ins ({preis}, {titel}, {versand}) and never write wording of your own unless the user dictates it.
        """.trimIndent(),
    ).apply { registerTools() }

    private fun Server.registerTools() {
        tool("list_saved_searches", "Every saved search with its id, words, markets, and how many new offers it found since last opened.") {
            val products = getJson<List<TrackedProduct>>("/api/products")
            val status = getJsonElement("/api/products/status").let { it as? JsonArray }.orEmpty()
                .associateBy({ (it as JsonObject)["productId"]?.jsonPrimitive?.contentOrNull }, { it as JsonObject })
            products.joinToString("\n") { p ->
                val s = status[p.id]
                listOfNotNull(
                    "${p.id}: ${p.name}",
                    "words \"${p.searchQuery.text}\"".takeIf { p.searchQuery.text != p.name },
                    "${p.searchQuery.platforms.size} markets",
                    p.searchQuery.maxPrice?.let { "up to ${it.amount / 100} €" },
                    s?.get("newSinceOpened")?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }?.let { "$it new" },
                ).joinToString(" · ")
            }.ifEmpty { "No saved searches." }
        }

        tool(
            "search", "Search the markets now and list the offers, cheapest first, each with an id for other tools.",
            props = {
                string("words", "What to search for, as the user would type it")
                string("saved_search_id", "Search with a saved search's words, markets and settings instead")
                integer("limit", "How many offers to list (default 40)")
                number("max_price_eur", "Leave out offers above this landed price")
                number("min_price_eur", "Leave out offers below this landed price, such as accessories for the thing searched")
            },
        ) { args ->
            val saved = args.str("saved_search_id")?.let { getJson<TrackedProduct>("/api/products/${it.encodeURLPathPart()}") }
            val words = args.str("words") ?: saved?.searchQuery?.text ?: return@tool "Name the words or a saved search."
            val q = saved?.searchQuery
            val listings = mutableListOf<Listing>()
            val problems = mutableListOf<String>()
            http.prepareGet("$base/api/crawler/search/stream") {
                parameter("q", words)
                q?.platforms?.takeIf { it.isNotEmpty() }?.let { parameter("platforms", it.joinToString(",") { p -> p.name }) }
                q?.excludeKeywords?.takeIf { it.isNotEmpty() }?.let { parameter("excludeKeywords", it.joinToString(",")) }
                q?.aliases?.takeIf { it.isNotEmpty() }?.let { parameter("aliases", it.joinToString(",")) }
                q?.location?.let { parameter("near", it) }
                q?.radiusKm?.takeIf { it > 0 }?.let { parameter("radiusKm", it) }
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute problems.add(response.bodyAsText())
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    if (line.isBlank()) continue
                    val event = runCatching { json.decodeFromString<CrawlerSearchEvent>(line) }.getOrNull() ?: continue
                    listings += event.listings
                    if (event.type == CrawlerEventType.PLATFORM_ERROR) problems += "${event.platformName.ifEmpty { event.platform }}: ${event.error ?: event.errorType}"
                    if (event.type == CrawlerEventType.SEARCH_COMPLETE) break
                }
            }
            val hidden = hiddenOffers()
            val max = args.num("max_price_eur") ?: q?.maxPrice?.amount?.div(100.0)
            val min = args.num("min_price_eur") ?: q?.minPrice?.amount?.div(100.0)
            val shown = listings.distinctBy { it.id }
                .filter { it.id !in hidden }
                .filter { max == null || it.effectivePrice.amount / 100.0 <= max }
                .filter { min == null || it.effectivePrice.amount / 100.0 >= min }
                .sortedBy { it.effectivePrice.amount }
                .take(args.int("limit") ?: 40)
            shown.forEach { seen[it.id] = it }
            val views = io.github.tieo.arbay.crawler.ViewCounts.of(shown.map { it.id })
            // The user sees what is being talked about: the same search opens on their screens.
            Live.show(args.str("saved_search_id")?.let { "/saved/${it.encodeURLPathPart()}" } ?: "/search/${words.encodeURLPathPart()}")
            buildString {
                appendLine("${shown.size} of ${listings.distinctBy { it.id }.size} offers for \"$words\":")
                shown.forEach { appendLine(it.line() + (views[it.id]?.let { v -> " | seen $v times" } ?: "")) }
                if (problems.isNotEmpty()) appendLine("Markets that failed: " + problems.joinToString("; "))
            }
        }

        tool(
            "offer_details", "The seller's full description and details of one offer from a search.",
            props = { string("listing_id", "An offer id from search", required = true) },
        ) { args ->
            val l = seen[args.str("listing_id")] ?: return@tool "Unknown offer; search first."
            val detail = http.get("$base/api/crawler/listing-detail") {
                parameter("url", l.url); parameter("platform", l.platformId.name); parameter("id", l.id)
            }
            if (!detail.status.isSuccess() || detail.status.value == 204) return@tool l.line() + "\nThe market gave no more than this."
            l.line() + "\n" + detail.bodyAsText()
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
                string("place", "Or one of: searches, messages, free_items, settings, vehicle_search")
            },
        ) { args ->
            val search = args.str("saved_search_id")?.let { "/saved/${it.encodeURLPathPart()}" }
                ?: args.str("words")?.let { "/search/${it.encodeURLPathPart()}" }
            val path = when {
                args.str("conversation_id") != null -> "/inbox/${args.str("conversation_id")!!.encodeURLPathPart()}"
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
            props = { string("words", "What to search for", required = true); string("name", "What to call it (defaults to the words)") },
        ) { args ->
            val words = args.str("words")!!
            val body = buildJsonObject {
                put("id", ""); put("name", args.str("name") ?: words); put("createdAt", Clock.System.now().toString())
                putJsonObject("searchQuery") { put("text", words) }
            }
            val r = http.post("$base/api/products") { contentType(ContentType.Application.Json); setBody(body.toString()) }
            if (r.status.isSuccess()) "Saved: " + json.decodeFromString<TrackedProduct>(r.bodyAsText()).let { "${it.id}: ${it.name}" } else "Not saved: ${r.bodyAsText()}"
        }

        tool(
            "change_saved_search",
            "Change a saved search: its name, its words, other words the same thing is sold under (asked of the markets too), and the price band it shows. Opens it on the user's screens.",
            props = {
                string("id", "Saved search id", required = true); string("name", "New name"); string("words", "New words")
                stringArray("other_words", "Other words for the same thing, each searched as well, e.g. \"256GB\" next to \"256 GB\"; replaces the list")
                number("min_price_eur", "Show nothing cheaper (0 clears)"); number("max_price_eur", "Show nothing dearer (0 clears)")
            },
        ) { args ->
            val id = args.str("id")!!
            val p = getJson<TrackedProduct>("/api/products/${id.encodeURLPathPart()}")
            fun band(key: String, now: Money?) = args.num(key)?.let { if (it <= 0) null else Money((it * 100).toLong()) } ?: now
            val next = p.copy(
                name = args.str("name") ?: p.name,
                searchQuery = p.searchQuery.copy(
                    text = args.str("words") ?: p.searchQuery.text,
                    aliases = if ("other_words" in args) args.strs("other_words") else p.searchQuery.aliases,
                    minPrice = band("min_price_eur", p.searchQuery.minPrice),
                    maxPrice = band("max_price_eur", p.searchQuery.maxPrice),
                ),
            )
            val r = http.put("$base/api/products/${id.encodeURLPathPart()}") { contentType(ContentType.Application.Json); setBody(json.encodeToString(TrackedProduct.serializer(), next)) }
            if (!r.status.isSuccess()) return@tool "Not changed: ${r.bodyAsText()}"
            Live.show("/saved/${id.encodeURLPathPart()}")
            "Changed: ${next.name} (\"${next.searchQuery.text}\")"
        }

        tool(
            "delete_saved_search", "Stop keeping a saved search.", destructive = true,
            props = { string("id", "Saved search id", required = true) },
        ) { args ->
            val r = http.delete("$base/api/products/${args.str("id")!!.encodeURLPathPart()}")
            if (r.status.isSuccess()) "Deleted." else "Not deleted: ${r.bodyAsText()}"
        }

        tool(
            "hide_offers", "Put offers away so no device shows them again, or bring them back.",
            props = { stringArray("listing_ids", "Offer ids", required = true); boolean("bring_back", "Show them again instead") },
        ) { args ->
            val ids = args.strs("listing_ids")
            val now = hiddenOffers()
            val next = if (args.bool("bring_back") == true) now - ids.toSet() else now + ids
            http.put("$base/api/state/hiddenOffers") { contentType(ContentType.Application.Json); setBody(JsonArray(next.sorted().map(::JsonPrimitive)).toString()) }
            "${next.size} offers put away."
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

        tool("seller_account", "Whether Arbay is signed in to the user's Kleinanzeigen account.") {
            getJsonElement("/api/chat/account").toString()
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
            // Each ad's own page, for the shipping its card leaves out ("Versand ab 6,19 €").
            val pageShipping = args.strs("listing_ids").mapNotNull { seen[it] }.associate { l -> l.id to pageShipping(l) }
            val toDoor = getJson<ChatSettings>("/api/chat/settings").toDoor
            val costs = io.github.tieo.arbay.crawler.KleinanzeigenCosts.costs()
            args.strs("listing_ids").joinToString("\n\n") { id ->
                val l = seen[id] ?: return@joinToString "$id: unknown offer; search first."
                if (!l.platformId.canMessage) return@joinToString "$id: only Kleinanzeigen sellers can be written to."
                val shipping = pageShipping[id] ?: l.shipping
                val offer = offerFor(l.platformId, (l.price.amount / 100).toInt().takeIf { l.price.currency == io.github.tieo.arbay.model.Currency.EUR }, shipping, allIn, toDoor, costs)
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

        tool("list_conversations", "The user's Kleinanzeigen conversations, newest first, with unread counts.") {
            val list = getJsonElement("/api/chat/conversations") as? JsonArray ?: return@tool "Not signed in to Kleinanzeigen."
            list.joinToString("\n") { c ->
                val o = c as JsonObject
                fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
                "${s("id")}: ${s("adTitle")} · ${s("partner")} · ${s("unread")} unread · ${s("lastText")}"
            }.ifEmpty { "No conversations." }
        }

        tool(
            "read_conversation", "Every message in one conversation, oldest first, with photo links.",
            props = { string("id", "Conversation id", required = true) },
        ) { args -> getJsonElement("/api/chat/conversations/${args.str("id")!!.encodeURLPathPart()}").toString() }

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

    private suspend fun pageShipping(l: Listing): io.github.tieo.arbay.model.Shipping? = runCatching {
        val r = http.get("$base/api/crawler/listing-detail") { parameter("url", l.url); parameter("platform", l.platformId.name); parameter("id", l.id) }
        if (!r.status.isSuccess() || r.status.value == 204) null
        else json.decodeFromString(io.github.tieo.arbay.model.ListingDetail.serializer(), r.bodyAsText()).shipping
    }.getOrNull()

    private fun Listing.line(): String = listOfNotNull(
        id, title.tidyTitle(), "${effectivePrice.amount / 100.0} ${effectivePrice.currency}",
        platformId.name.lowercase(),
        location?.city, distanceKm?.let { "${it.toInt()} km" },
        shipping?.let { s -> s.cost?.let { "shipping ${it.amount / 100.0}" } ?: if (s.available) "ships" else "pickup" },
        listingDate?.let { "online ${(Clock.System.now() - it).inWholeDays} days" },
        condition?.name?.lowercase(), saleType?.name?.lowercase(), url,
    ).joinToString(" | ")

    private suspend fun outboxText(): String = (getJsonElement("/api/chat/outbox") as? JsonArray).orEmpty().takeLast(20).joinToString("\n") { m ->
        val o = m as JsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        "${s("id")}: ${s("title")} · ${s("state")} · at ${s("sendAt")}" + (s("error")?.let { " · $it" } ?: "")
    }.ifEmpty { "Nothing on its way." }

    private suspend fun hiddenOffers(): Set<String> =
        ((getJsonElement("/api/state") as? JsonObject)?.get("hiddenOffers") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()

    private suspend fun getJsonElement(path: String): JsonElement {
        val r = http.get("$base$path")
        val body = r.bodyAsText()
        if (!r.status.isSuccess()) throw ToolFailure(body.ifBlank { r.status.description })
        return json.parseToJsonElement(body)
    }

    private suspend inline fun <reified T> getJson(path: String): T = json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), getJsonElement(path))

    private class ToolFailure(message: String) : Exception(message)

    // ── Declaring tools ──

    private class Props {
        val properties = mutableMapOf<String, JsonObject>()
        val required = mutableListOf<String>()
        private fun add(name: String, type: String, description: String, required: Boolean, extra: Map<String, JsonElement> = emptyMap()) {
            properties[name] = JsonObject(mapOf("type" to JsonPrimitive(type), "description" to JsonPrimitive(description)) + extra)
            if (required) this.required += name
        }
        fun string(name: String, description: String, required: Boolean = false) = add(name, "string", description, required)
        fun number(name: String, description: String, required: Boolean = false) = add(name, "number", description, required)
        fun integer(name: String, description: String, required: Boolean = false) = add(name, "integer", description, required)
        fun boolean(name: String, description: String, required: Boolean = false) = add(name, "boolean", description, required)
        fun stringArray(name: String, description: String, required: Boolean = false) =
            add(name, "array", description, required, mapOf("items" to JsonObject(mapOf("type" to JsonPrimitive("string")))))
        fun putJsonObject(name: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) { properties[name] = buildJsonObject(build) }
    }

    private fun Server.tool(
        name: String,
        description: String,
        destructive: Boolean = false,
        props: Props.() -> Unit = {},
        run: suspend (JsonObject) -> String,
    ) {
        val p = Props().apply(props)
        addTool(
            name = name,
            description = description,
            inputSchema = ToolSchema(properties = JsonObject(p.properties), required = p.required),
            toolAnnotations = ToolAnnotations(destructiveHint = destructive, readOnlyHint = !destructive && name.startsWith("list") || name in READ_ONLY),
        ) { request ->
            try {
                CallToolResult(content = listOf(TextContent(run(request.arguments ?: JsonObject(emptyMap())))))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Failed: ${e.message}")), isError = true)
            }
        }
    }

    private companion object {
        val READ_ONLY = setOf("whats_on_screen", "show_on_screen", "search", "offer_details", "recent_searches", "seller_account", "outbox", "read_conversation", "draft_messages")
    }
}

private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.int(k: String) = this[k]?.jsonPrimitive?.intOrNull
private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
private fun JsonObject.strs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
