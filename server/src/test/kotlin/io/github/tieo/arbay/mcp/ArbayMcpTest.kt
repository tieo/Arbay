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
import kotlin.test.assertNotNull
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

    /** A client keeps the session id it was given when it listed the tools. Across a restart with
     *  the same tools its calls are answered; a client holding the id of other tools is told its
     *  session is gone, so it starts again and lists the tools as they are now. */
    @Test
    fun aCallFromBeforeARestartIsAnsweredWhileTheToolsAreTheSame() = testApplication {
        application {
            install(SSE)
            configureSerialization()
            configureStatusPages()
        }
        routing { chatRoutes(); mcpRoutes("", client) }
        suspend fun post(session: String?, body: String) = client.post("/mcp") {
            header("Accept", "application/json, text/event-stream")
            session?.let { header("mcp-session-id", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val started = post(null, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        val session = assertNotNull(started.headers["mcp-session-id"], "the tools' session id")
        val call = """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"list_texts","arguments":{}}}"""
        val answered = post(session, call)
        assertEquals(200, answered.status.value, answered.bodyAsText())
        assertTrue("error" !in Json.parseToJsonElement(answered.bodyAsText()).jsonObject, answered.bodyAsText())
        assertEquals(404, post("a-session-from-other-tools", call).status.value)
        // A client that was never given an id is answered, since only an id makes it start again.
        assertEquals(200, post(null, call).status.value)
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

    /** How often a watch looks and which finds it pushes are set over MCP as in the app's panel. */
    @Test
    fun aWatchsIntervalAndAlertRulesAreChanged() = testApplication {
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
            name = "watch change test",
            searchQuery = io.github.tieo.arbay.model.SearchQuery(text = "watch change test", category = io.github.tieo.arbay.model.MarketGroup.GENERAL),
            createdAt = kotlin.time.Clock.System.now(),
            autoFetch = io.github.tieo.arbay.model.AutoFetchSettings(enabled = true, intervalMinutes = 180),
            notificationSubfilters = listOf(io.github.tieo.arbay.model.NotificationSubfilter(
                id = "r1", minPriceEur = 80, learnUntil = kotlin.time.Instant.parse("2026-10-22T12:34:56Z"), bestSeenEur = 85,
            )),
        ))
        val mcp = createClient { install(ClientSSE) }.mcpStreamableHttp("/mcp")
        val changed = (mcp.callTool(name = "change_saved_search", arguments = mapOf(
            "id" to saved.id, "look_every_minutes" to 60, "remove_alert_rules" to listOf("r1"), "alert_from_eur" to 60, "alert_up_to_eur" to 130,
        )).content.single() as TextContent).text
        assertContains(changed, "looks every 60 min")
        assertContains(changed, ": €60–130")
        val after = products.getById(saved.id)!!
        assertEquals(60, after.autoFetch.intervalMinutes)
        assertEquals(listOf(60 to 130), after.notificationSubfilters.map { it.minPriceEur to it.maxPriceEur })
        assertEquals(null, after.notificationSubfilters.single().learnUntil)
        val refused = (mcp.callTool(name = "change_saved_search", arguments = mapOf("id" to saved.id, "look_every_minutes" to 45)).content.single() as TextContent).text
        assertTrue(refused.startsWith("Not changed"), refused)
        products.delete(saved.id)
        mcp.close()
    }

    /** The fit a vehicle search pushes from is set over MCP as in the app's panel, and refused
     *  for a search without vehicle wishes. */
    @Test
    fun aVehicleSearchsFitAlertLevelIsSet() = testApplication {
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
        val van = products.create(io.github.tieo.arbay.model.TrackedProduct(
            id = "", name = "fit level test",
            searchQuery = io.github.tieo.arbay.model.SearchQuery(text = "fit level test", category = io.github.tieo.arbay.model.MarketGroup.GENERAL,
                carFilters = io.github.tieo.arbay.model.CarFilters(maxMileageKm = 150_000)),
            createdAt = kotlin.time.Clock.System.now(),
            fitAlert = io.github.tieo.arbay.model.FitAlertSettings(enabled = true, minScore = 0.6),
        ))
        val drive = products.create(io.github.tieo.arbay.model.TrackedProduct(
            id = "", name = "no fit test",
            searchQuery = io.github.tieo.arbay.model.SearchQuery(text = "no fit test", category = io.github.tieo.arbay.model.MarketGroup.GENERAL),
            createdAt = kotlin.time.Clock.System.now(),
        ))
        val mcp = createClient { install(ClientSSE) }.mcpStreamableHttp("/mcp")
        val set = (mcp.callTool(name = "change_saved_search", arguments = mapOf("id" to van.id, "fit_alert_percent" to 82)).content.single() as TextContent).text
        assertContains(set, "pushes a fit of 82 % or more")
        assertEquals(0.82, products.getById(van.id)!!.fitAlert.minScore, 0.001)
        val list = (mcp.callTool(name = "list_saved_searches", arguments = emptyMap()).content.single() as TextContent).text
        assertContains(list, "fits 82 % or more")
        val refused = (mcp.callTool(name = "change_saved_search", arguments = mapOf("id" to drive.id, "fit_alert_percent" to 80)).content.single() as TextContent).text
        assertTrue(refused.startsWith("Not changed"), refused)
        mcp.callTool(name = "change_saved_search", arguments = mapOf("id" to van.id, "fit_alert_percent" to 0))
        assertEquals(false, products.getById(van.id)!!.fitAlert.enabled)
        // A search for a thing weighs a fit once it is given wishes, in the same call as its level.
        val sanders = """{"models":[{"name":"Lägler HUMMEL","words":["hummel"],"worth":1.0}],"idealPriceEur":450,"maxPriceEur":1500,"ruledOutWords":["400 V"]}"""
        val thing = (mcp.callTool(name = "change_saved_search", arguments = mapOf("id" to drive.id, "fit_wishes" to sanders, "fit_alert_percent" to 81)).content.single() as TextContent).text
        assertContains(thing, "pushes a fit of 81 % or more")
        assertContains(thing, "Lägler HUMMEL 100 %")
        assertEquals(1500, products.getById(drive.id)!!.searchQuery.productWishes?.maxPriceEur)
        val unreadable = (mcp.callTool(name = "change_saved_search", arguments = mapOf("id" to drive.id, "fit_wishes" to "{models")).content.single() as TextContent).text
        assertTrue(unreadable.startsWith("Not changed"), unreadable)
        products.delete(van.id); products.delete(drive.id)
        mcp.close()
    }
}
