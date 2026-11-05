package io.github.tieo.arbay.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
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
            CallToolResult(content = listOf(TextContent(run(request.arguments ?: JsonObject(emptyMap())))))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CallToolResult(content = listOf(TextContent("Failed: ${e.message}")), isError = true)
        }
    }
}
