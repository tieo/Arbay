package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.neuland.Neuland
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The search engines (Bing, DuckDuckGo, Google, Qwant, Brave, Startpage, Yandex, Mojeek, Marginalia),
 * asked by Neuland: the pages they list for some words, for the web market to read for offers
 * ([links]), their results as a person reads them for a search of the web on any words ([serp]), and
 * Bing Images' pictures ([images]).
 */
class SearchEngines {
    private val json = Json { ignoreUnknownKeys = true }

    /** The pages every engine that reads for offers lists for [words]; none where Neuland did not answer. */
    suspend fun links(words: String): List<String> = runCatching {
        json.decodeFromJsonElement(ListSerializer(String.serializer()), Neuland.request(HttpMethod.Get, "web/links?q=${words.encodeURLParameter()}", limitS = 180))
    }.getOrDefault(emptyList())

    /** What each engine lists for [words], and all of them merged by address; [engines] picks engines
     *  by name, none picks every one. */
    suspend fun serp(words: String, engines: Set<String> = emptySet()): WebSearch {
        val picked = if (engines.isEmpty()) "" else "&engines=${engines.joinToString(",").encodeURLParameter()}"
        return json.decodeFromJsonElement(WebSearch.serializer(), Neuland.request(HttpMethod.Get, "web/search?q=${words.encodeURLParameter()}$picked", limitS = 120))
    }

    /** The pictures Bing Images finds for [words], [pages] pages of 35. */
    suspend fun images(words: String, pages: Int = 3): List<ImageResult> =
        json.decodeFromJsonElement(ListSerializer(ImageResult.serializer()), Neuland.request(HttpMethod.Get, "web/images?q=${words.encodeURLParameter()}&pages=$pages", limitS = 120))

    companion object {
        /** Ad networks an engine's ads pass through on their way to the shop. */
        internal val AD_HOSTS = listOf("admarketplace.", "doubleclick.", "googleadservices.", "adservice.", "awin1.", "tradedoubler.", "clickserve.", "ad.atdmt.", "partners.webmasterplan.", "pvn.", "tc.tradetracker.", "go.skimresources.")
    }
}

/** One result as an engine lists it: its place in the engine's list, from 1. */
@kotlinx.serialization.Serializable
data class SerpResult(val rank: Int, val title: String, val url: String, val snippet: String)

/** One engine's results for a search, or why it gave none. */
@kotlinx.serialization.Serializable
data class EngineResults(val engine: String, val status: String, val results: List<SerpResult>)

/** One address every engine that listed it agrees on, with the best place any gave it. */
@kotlinx.serialization.Serializable
data class WebResult(val url: String, val title: String, val snippet: String, val engines: List<String>, @kotlinx.serialization.SerialName("best_rank") val bestRank: Int)

/** A web search's raw results: each engine's list, and all of them merged by address, the
 *  addresses most engines list first. */
@kotlinx.serialization.Serializable
data class WebSearch(val query: String, val engines: List<EngineResults>, val results: List<WebResult>)

/** One picture an image search found: its place in the engine's list from 1, its title, the
 *  picture's address, the page it stands on, a thumbnail, and its size as the engine states it. */
@kotlinx.serialization.Serializable
data class ImageResult(val rank: Int, val title: String, val image: String, val page: String, val thumbnail: String? = null, val size: String? = null)
