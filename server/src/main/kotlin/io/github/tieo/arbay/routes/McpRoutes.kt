package io.github.tieo.arbay.routes

import io.github.tieo.arbay.mcp.ArbayMcp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
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
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.http.ContentType

/**
 * Arbay for an LLM, over MCP's Streamable HTTP at /mcp (see [ArbayMcp]).
 *
 * Every request is answered by a transport of its own over the one [ArbayMcp], which holds what a
 * client builds up (the offers it has seen, the searches it started). Sessions lived in this
 * server's memory, so every restart, every deploy, told the client its session had expired and
 * cost it a failed call. Who may reach the endpoint is decided in front of the server, as for
 * every other route.
 *
 * The session id a client is given is the tools it was shown: a digest of their definitions. A
 * client lists the tools once, when it starts its session, and kept calling a tool by the schema
 * of an older deploy, without a parameter added since. A request naming another digest is told its
 * session is gone, and the client starts again and lists the tools anew; a deploy that leaves the
 * tools as they were costs nothing.
 */
fun Route.mcpRoutes(selfUrl: String, http: HttpClient = loopbackClient()) {
    val arbay = ArbayMcp(http, selfUrl)
    mcpEndpoint("/mcp") { arbay.server() }
    // A search of the web on its own, so a session researching something sees no shopping tools.
    val web = io.github.tieo.arbay.mcp.WebSearchMcp(http, selfUrl)
    mcpEndpoint("/web-mcp") { web.server() }
}

/** One MCP server at [path], a server of [server]'s tools answering each request. */
private fun Route.mcpEndpoint(path: String, server: () -> io.modelcontextprotocol.kotlin.sdk.server.Server) {
    val toolsId = toolsDigest(server())

    route(path) {
        // JSON-RPC as the MCP SDK writes it: Arbay's own JSON settings drop the "jsonrpc" field
        // and add nulls, and strict clients refuse the reply.
        install(ContentNegotiation) { json(McpJson) }
        post {
            // A client holding no id is answered: the spec has a client start again only after a
            // 404 to a request that carried one, and one connected before ids were given out
            // would otherwise fail every call until it is restarted by hand.
            val sent = call.request.headers[SESSION_HEADER]
            if (sent != null && sent != toolsId) {
                call.respondText(
                    """{"jsonrpc":"2.0","error":{"code":-32001,"message":"Session not found: the tools changed, start a new session"},"id":null}""",
                    ContentType.Application.Json, HttpStatusCode.NotFound,
                )
                return@post
            }
            call.response.header(SESSION_HEADER, toolsId)
            val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(enableJsonResponse = true))
            transport.setSessionIdGenerator(null)
            server().createSession(transport)
            transport.handleRequest(null, call)
        }
        // Nothing streams from the server on its own, and there is no session to end.
        get { call.respond(HttpStatusCode.MethodNotAllowed) }
        delete { call.respond(HttpStatusCode.OK) }
    }
}

private const val SESSION_HEADER = "Mcp-Session-Id"

/** A digest of every tool's name, description and schemas, the same for the same tools. */
private fun toolsDigest(server: io.modelcontextprotocol.kotlin.sdk.server.Server): String {
    val tools = server.tools.values.map { it.tool }.sortedBy { it.name }
    val text = McpJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(io.modelcontextprotocol.kotlin.sdk.types.Tool.serializer()), tools)
    return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)
}

/** For calling this server's own API, as the MCP tools and the saved-search fit alert do; a search
 *  runs for minutes, so no overall limit: each market ends on its own crawl clock, and a stream
 *  that stops sending ends on the socket limit. A 15 minute cap failed every run of the parquet
 *  sander watch, whose fourteen other names across eighteen markets read for longer, and a run
 *  given up on records nothing. */
internal fun loopbackClient() = HttpClient(CIO) {
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = 5 * 60_000
    }
}
