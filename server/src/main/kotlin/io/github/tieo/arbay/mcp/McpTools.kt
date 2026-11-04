package io.github.tieo.arbay.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotification
import io.modelcontextprotocol.kotlin.sdk.types.ProgressNotificationParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The parameters of a tool, as its input schema names them. */
internal class McpProps {
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

/**
 * A tool call's progress, sent to its client where the client asked for it with a progress token:
 * a client gives up on a call that sends nothing for a while (Claude Code after 300 s), so a call
 * that waits long, as a search for every market does, says how far it is. Found in the call's
 * coroutine context.
 */
internal class McpProgress(private val connection: ClientConnection, private val token: RequestId) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<McpProgress>

    private var sent = 0.0

    /** Says how far the call is, in [message]; each report counts one step further. */
    suspend fun report(message: String) {
        sent += 1
        connection.notification(ProgressNotification(ProgressNotificationParams(token, sent, null, message)))
    }
}

/** A tool answering in text; a failure is answered as the tool's error rather than thrown at the client. */
internal fun Server.textTool(
    name: String,
    description: String,
    destructive: Boolean = false,
    readOnly: Boolean = false,
    props: McpProps.() -> Unit = {},
    run: suspend (JsonObject) -> String,
) {
    val p = McpProps().apply(props)
    addTool(
        name = name,
        description = description,
        inputSchema = ToolSchema(properties = JsonObject(p.properties), required = p.required),
        toolAnnotations = ToolAnnotations(destructiveHint = destructive, readOnlyHint = readOnly),
    ) { request ->
        try {
            val token = request.meta?.progressToken
            val answer = if (token == null) run(request.arguments ?: JsonObject(emptyMap()))
            else kotlinx.coroutines.withContext(McpProgress(this, token)) { run(request.arguments ?: JsonObject(emptyMap())) }
            CallToolResult(content = listOf(TextContent(answer)))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Failed: ${e.message}")), isError = true)
        }
    }
}
