package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.crawler.ImageResult
import io.github.tieo.arbay.crawler.WebSearch
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A search of the web on any words, over MCP at /web-mcp: the search engines Arbay asks for its web
 * market ([io.github.tieo.arbay.crawler.SearchEngines]), offered on their own, apart from Arbay's
 * tools for buying and selling.
 */
class WebSearchMcp(private val http: HttpClient, private val base: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun server(): Server = Server(
        Implementation(name = "websearch", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = """
            Searches the web on any words with several engines at once (Bing, DuckDuckGo, Google, Qwant, Brave,
            Startpage, and the independent indexes of Yandex, Mojeek and Marginalia, the last for the small, old
            and technical web) from a home server, and gives every result's title, address and snippet, the
            addresses most engines list first. The engines search in German. image_search finds pictures
            (Bing Images) with the page each stands on.
        """.trimIndent(),
    ).apply {
        textTool(
            "web_search",
            "What the search engines (Bing, DuckDuckGo, Google, Qwant, Brave, Startpage, Yandex, Mojeek, Marginalia) list for any words: every result with its title, address and snippet, merged by address, the ones most engines list first, and how each engine answered. Answers within a minute, with the engines done by then.",
            readOnly = true,
            props = {
                string("query", "The words, as typed into a search engine (quotes and operators pass through)", required = true)
                stringArray("engines", "Only these engines, by name; all when left out")
            },
        ) { args ->
            val response = http.get("$base/api/web/serp") {
                parameter("q", args["query"]?.jsonPrimitive?.contentOrNull)
                (args["engines"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.forEach { parameter("engine", it) }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw IllegalStateException(body.ifBlank { response.status.description })
            val found = json.decodeFromString(WebSearch.serializer(), body)
            found.engines.joinToString(", ", prefix = "Engines: ") { "${it.engine} ${it.results.size} (${it.status})" } + "\n" +
                found.results.withIndex().joinToString("\n") { (i, r) ->
                    "${i + 1}. ${r.title} | ${r.url} | ${r.engines.joinToString("+")} best #${r.bestRank}" + (if (r.snippet.isNotBlank()) "\n   ${r.snippet}" else "")
                }
        }
        textTool(
            "image_search",
            "Pictures Bing Images finds for any words: each picture's address, the page it stands on, its title and size, 35 a page in Bing's order. Fetch a picture's address to look at it.",
            readOnly = true,
            props = {
                string("query", "The words, as typed into an image search", required = true)
                integer("pages", "Pages of 35 pictures to read, 1 to 10; 3 when left out")
            },
        ) { args ->
            val response = http.get("$base/api/web/images") {
                parameter("q", args["query"]?.jsonPrimitive?.contentOrNull)
                args["pages"]?.jsonPrimitive?.contentOrNull?.let { parameter("pages", it) }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) throw IllegalStateException(body.ifBlank { response.status.description })
            val found = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ImageResult.serializer()), body)
            if (found.isEmpty()) "No pictures found."
            else found.joinToString("\n") { r -> "${r.rank}. ${r.title}${r.size?.let { " ($it)" }.orEmpty()}\n   image: ${r.image}\n   page: ${r.page}" }
        }
    }
}
