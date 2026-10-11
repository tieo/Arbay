package io.github.tieo.arbay.routes

import io.github.tieo.arbay.mcp.ArbayMcp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/**
 * Arbay for an LLM, over MCP's Streamable HTTP at /mcp (see [ArbayMcp]), without sessions.
 *
 * Every request is answered by a transport of its own over the one [ArbayMcp], which holds what a
 * client builds up (the offers it has seen, the searches it started). Sessions lived in this
 * server's memory, so every restart, every deploy, told the client its session had expired and
 * cost it a failed call. Who may reach the endpoint is decided in front of the server, as for
 * every other route.
 */
fun Route.mcpRoutes(selfUrl: String, http: HttpClient = loopbackClient()) {
    val mcp = ArbayMcp(http, selfUrl)

    route("/mcp") {
        // JSON-RPC as the MCP SDK writes it: Arbay's own JSON settings drop the "jsonrpc" field
        // and add nulls, and strict clients refuse the reply.
        install(ContentNegotiation) { json(McpJson) }
        post {
            val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
            transport.setSessionIdGenerator(null)
            mcp.server().createSession(transport)
            transport.handleRequest(null, call)
        }
        // Nothing streams from the server on its own, and there is no session to end.
        get { call.respond(HttpStatusCode.MethodNotAllowed) }
        delete { call.respond(HttpStatusCode.OK) }
    }
}

/** For calling this server's own API, as the MCP tools and the saved-search fit alert do; a search
 *  runs for minutes, so no overall limit. */
internal fun loopbackClient() = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 15 * 60_000
        socketTimeoutMillis = 5 * 60_000
    }
}
