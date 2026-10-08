package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.plugins.configureSerialization
import io.github.tieo.arbay.plugins.configureStatusPages
import io.github.tieo.arbay.routes.chatRoutes
import io.github.tieo.arbay.routes.mcpRoutes
import io.github.tieo.arbay.routes.userStateRoutes
import io.ktor.client.plugins.sse.SSE as ClientSSE
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
        for (expected in listOf("search", "list_saved_searches", "send_messages", "draft_messages", "reply", "set_look")) {
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
}
