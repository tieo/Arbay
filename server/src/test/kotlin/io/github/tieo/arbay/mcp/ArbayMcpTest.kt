package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.plugins.configureSerialization
import io.github.tieo.arbay.plugins.configureStatusPages
import io.github.tieo.arbay.routes.chatRoutes
import io.github.tieo.arbay.routes.mcpRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.github.tieo.arbay.routes.userStateRoutes
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** An MCP client of the SDK's own, talking to Arbay's /mcp through the server's real routes. */
class ArbayMcpTest {

    @Test
    fun aClientSeesEveryToolAndCanCallOne() = testApplication {
        application {
            install(SSE)
            configureSerialization()
            configureStatusPages()
        }
        routing {
            chatRoutes()
            userStateRoutes()
            mcpRoutes("", client)
        }
        val mcp = createClient { install(ClientSSE) }.mcpStreamableHttp("/mcp")

        val tools = mcp.listTools().tools.map { it.name }
        for (expected in listOf("search", "list_saved_searches", "send_messages", "draft_messages", "reply", "set_look", "list_texts")) {
            assertContains(tools, expected)
        }

        val texts = mcp.callTool(name = "list_texts", arguments = emptyMap())
        val text = (texts.content.single() as TextContent).text
        assertTrue(text.isNotBlank())
        assertNotEquals(true, texts.isError)

        val recent = mcp.callTool(name = "recent_searches", arguments = emptyMap())
        assertNotEquals(true, recent.isError, (recent.content.single() as TextContent).text)
        mcp.close()
    }

    @Test
    fun repliesAreJsonRpcAsStrictClientsReadThem() = testApplication {
        application {
            install(SSE)
            configureSerialization()
            configureStatusPages()
        }
        routing { mcpRoutes("", client) }
        val reply = client.post("/mcp") {
            header("Accept", "application/json, text/event-stream")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        }
        val body = Json.parseToJsonElement(reply.bodyAsText()).jsonObject
        assertEquals("2.0", body["jsonrpc"]?.jsonPrimitive?.content, reply.bodyAsText())
        assertTrue("error" !in body, reply.bodyAsText())
    }

    /** A client that connected before a restart keeps sending its old session id; the server has
     *  no memory of it, and the call is answered all the same. */
    @Test
    fun aCallFromBeforeARestartIsAnswered() = testApplication {
        application {
            install(SSE)
            configureSerialization()
            configureStatusPages()
        }
        routing { chatRoutes(); mcpRoutes("", client) }
        val reply = client.post("/mcp") {
            header("Accept", "application/json, text/event-stream")
            header("mcp-session-id", "a-session-from-before-the-restart")
            contentType(ContentType.Application.Json)
            setBody("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"list_texts","arguments":{}}}""")
        }
        assertEquals(200, reply.status.value, reply.bodyAsText())
        val body = Json.parseToJsonElement(reply.bodyAsText()).jsonObject
        assertTrue("error" !in body, reply.bodyAsText())
    }

    @Test
    fun aSearchSavedOverMcpIsKept() = testApplication {
        application {
            install(SSE)
            configureSerialization()
            configureStatusPages()
        }
        val products = io.github.tieo.arbay.repo.ProductRepo()
        routing {
            productRoutes(products, io.github.tieo.arbay.crawler.SavedSearchMonitor(products, io.github.tieo.arbay.repo.ListingRepo()))
            mcpRoutes("", client)
        }
        val mcp = createClient { install(ClientSSE) }.mcpStreamableHttp("/mcp")
        val saved = mcp.callTool(name = "save_search", arguments = mapOf("words" to "mcp saved search test"))
        val text = (saved.content.single() as TextContent).text
        assertTrue(text.startsWith("Saved: "), text)
        val id = text.removePrefix("Saved: ").substringBefore(':')
        assertTrue(id.isNotBlank(), text)
        val other = (mcp.callTool(name = "save_search", arguments = mapOf("words" to "another mcp test")).content.single() as TextContent).text
        assertNotEquals(id, other.removePrefix("Saved: ").substringBefore(':'))
        products.delete(id)
        products.delete(other.removePrefix("Saved: ").substringBefore(':'))
        mcp.close()
    }

    /** A watch reviewed over MCP needs the bar an alert rule learned and the whole list of new
     *  offers, both of which only the app showed before. */
    @Test
    fun aSavedSearchShowsItsLearnedBarAndItsNewOffers() = testApplication {
        application {
            install(SSE)
            configureSerialization()
            configureStatusPages()
        }
        val products = io.github.tieo.arbay.repo.ProductRepo()
        routing {
            productRoutes(products, io.github.tieo.arbay.crawler.SavedSearchMonitor(products, io.github.tieo.arbay.repo.ListingRepo()))
            mcpRoutes("", client)
        }
        val saved = products.create(io.github.tieo.arbay.model.TrackedProduct(
            id = "",
            name = "learned bar test",
            searchQuery = io.github.tieo.arbay.model.SearchQuery(text = "learned bar test", category = io.github.tieo.arbay.model.MarketGroup.GENERAL),
            createdAt = kotlin.time.Clock.System.now(),
            notificationSubfilters = listOf(io.github.tieo.arbay.model.NotificationSubfilter(
                id = "r1", maxPriceEur = 216, learnUntil = kotlin.time.Instant.parse("2026-10-22T12:34:56Z"), bestSeenEur = 140,
            )),
        ))
        val mcp = createClient { install(ClientSSE) }.mcpStreamableHttp("/mcp")
        val list = (mcp.callTool(name = "list_saved_searches", arguments = emptyMap()).content.single() as TextContent).text
        assertContains(list, "alert rule r1: up to €216 · cheaper than the best until 2026-10-22 (€140 so far)")
        val new = mcp.callTool(name = "new_offers", arguments = mapOf("saved_search_id" to saved.id))
        assertEquals("Nothing new in this saved search.", (new.content.single() as TextContent).text)
        products.delete(saved.id)
        mcp.close()
    }
}
