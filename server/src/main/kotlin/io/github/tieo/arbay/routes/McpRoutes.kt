package io.github.tieo.arbay.routes

import io.github.tieo.arbay.mcp.ArbayMcp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import java.util.concurrent.ConcurrentHashMap

private const val SESSION_HEADER = "mcp-session-id"

/**
 * Arbay for an LLM, over MCP's Streamable HTTP at /mcp (see [ArbayMcp]). Each client session gets
 * its own transport, found again by the session id the client sends with every later request.
 * Who may reach it is decided in front of the server, as for every other route.
 */
fun Route.mcpRoutes(selfUrl: String, http: HttpClient = loopbackClient()) {
    val mcp = ArbayMcp(http, selfUrl)
    val transports = ConcurrentHashMap<String, StreamableHttpServerTransport>()

    route("/mcp") {
        // JSON-RPC as the MCP SDK writes it: Arbay's own JSON settings drop the "jsonrpc" field
        // and add nulls, and strict clients refuse the reply.
        install(ContentNegotiation) { json(McpJson) }
        sse {
            val id = call.request.header(SESSION_HEADER)
            val transport = id?.let { transports[it] } ?: return@sse call.respond(HttpStatusCode.NotFound, "No such session")
            transport.handleRequest(this, call)
        }
        post {
            val id = call.request.header(SESSION_HEADER)
            val transport = if (id != null) {
                transports[id] ?: return@post call.respond(HttpStatusCode.NotFound, "No such session")
            } else {
                StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true)).also { t ->
                    t.setOnSessionInitialized { transports[it] = t }
                    t.setOnSessionClosed { transports.remove(it) }
                    val server = mcp.server()
                    server.onClose { t.sessionId?.let(transports::remove) }
                    server.createSession(t)
                }
            }
            transport.handleRequest(null, call)
        }
        delete {
            val id = call.request.header(SESSION_HEADER)
            val transport = id?.let { transports[it] } ?: return@delete call.respond(HttpStatusCode.NotFound, "No such session")
            transport.handleRequest(null, call)
        }
    }
}

/** The tools call this server's own API; a search runs for minutes, so no overall limit. */
private fun loopbackClient() = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15 * 60_000
        socketTimeoutMillis = 5 * 60_000
    }
}
