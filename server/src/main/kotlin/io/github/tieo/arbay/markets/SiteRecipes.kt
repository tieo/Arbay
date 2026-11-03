package io.github.tieo.arbay.markets

import io.github.tieo.arbay.neuland.Neuland
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The recipes the web market's sites are read by, kept and read by Neuland: one per site (or per
 * kind of page of a site), written by the agent the [SiteGaps] feed reaches and checked against the
 * real page before it is kept. Arbay asks them for the sites' own searches it puts the words to.
 */
object SiteRecipes {
    private val json = Json { ignoreUnknownKeys = true }

    /** What a search needs of a recipe: its site and the site's own search, `{q}` for the words. */
    @Serializable
    data class Summary(val domain: String, val searchUrl: String? = null)

    @Volatile private var cached: Pair<Long, List<Summary>>? = null

    /** Every recipe's site and search, asked of Neuland at most once a minute; none where it did not answer. */
    suspend fun all(): List<Summary> {
        cached?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        val read = runCatching { json.decodeFromJsonElement(ListSerializer(Summary.serializer()), recipes()) }.getOrNull() ?: return cached?.second.orEmpty()
        cached = System.currentTimeMillis() to read
        return read
    }

    /** Every recipe whole, as Neuland keeps them. */
    suspend fun recipes(): JsonElement = Neuland.request(HttpMethod.Get, "recipes", limitS = 30)

    /** Keeps [recipe], replacing the site's recipe for the same pages; a [Neuland.Refusal] where it is not one. */
    suspend fun put(recipe: JsonObject) {
        Neuland.request(HttpMethod.Put, "recipes", recipe, limitS = 30)
        cached = null
    }

    suspend fun remove(domain: String) {
        Neuland.request(HttpMethod.Delete, "recipes/${java.net.URLEncoder.encode(domain, Charsets.UTF_8)}", limitS = 30)
        cached = null
    }

    /** [recipe] (the site's own where null) tried on the page at [url], or on [html] where given. */
    suspend fun tryOn(url: String, recipe: JsonElement?, html: String?): JsonElement =
        Neuland.request(HttpMethod.Post, "shops/try", JsonObject(buildMap {
            put("url", kotlinx.serialization.json.JsonPrimitive(url))
            recipe?.let { put("recipe", it) }
            html?.let { put("html", kotlinx.serialization.json.JsonPrimitive(it)) }
        }), limitS = 120)

    /** The host of an address without "www.". */
    fun domainOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.").lowercase()
}
