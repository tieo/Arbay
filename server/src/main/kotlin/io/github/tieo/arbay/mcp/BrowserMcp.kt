package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.neuland.Neuland
import io.ktor.http.HttpMethod
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Filling in a form on any site through Neuland's browsers, over MCP at /browser-mcp: a tab held in
 * a browser over many calls, the page read as its fields and buttons, each field set and each
 * button clicked as a person does it, in Neuland (`/forms`). Apart from Arbay's tools for buying and
 * selling, as the web search is.
 */
class BrowserMcp {
    fun server(): Server = Server(
        Implementation(name = "browser", version = "1.0.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = """
            Fills in forms on any website in Neuland's browsers on the home server, as a person would: form_open
            holds a tab in a browser ("accounts" holds the user's own sign-ins), form_read gives the page as its
            fields (each with an id, kind, label, the question its group asks, options, value), buttons, links,
            errors and text, form_set sets one field and says whether the page took the value, form_click
            clicks a button or link, form_screenshot shows the tab. Fields are set with real input (a click on
            the field or a visible point of its label that is no link, keys for a select, typing for text), so
            hidden radios and selects that ignore scripted values work. Read the page again after an action:
            ids stay for an element, but a page may replace its elements. A window the page opens (a popup, a
            login in a new tab) is listed under tabs; pass its id as tab. A page that hands an identification
            to the ID card's eID client is answered by Neuland itself: the user allows it and types the PIN on
            their phone, and the tab goes on by itself; read it until it has. A password or code only the user
            knows goes in with form_ask_secret: the user types it on their phone and Neuland types it into the
            field, so it never reaches you; the user's payment card goes in with form_card_fill. A file for a file field is
            uploaded first, its content as the body of a POST to /api/forms/<session>/files?name=<name> on the
            server this MCP is on (its address without /browser-mcp, with the same Authorization header), and
            then named in form_set's files. Every action keeps the page (HTML, picture, log) on the server.
            Nothing is paid or sent without the user's approval: a final submit, a payment or a signature is
            the user's to approve first.
        """.trimIndent(),
    ).apply {
        tool(
            "form_open",
            "Holds a tab in one of Neuland's browsers for filling in a form over many calls, and opens url in it. Answers the session id and the tabs. Tabs a DevTools client opened in that browser can be taken over by their target ids (adopt). The session ends with form_close, or after 45 minutes nobody acted in it.",
            props = {
                string("browser", "The browser: \"accounts\" (the user's own sign-ins), \"render\"; accounts when left out")
                string("url", "Where the held tab goes first")
                stringArray("adopt", "Target ids of tabs of that browser to take over (a DevTools client's tabs)")
            },
        ) { args ->
            val body = buildJsonObject {
                put("browser", args.text("browser") ?: "accounts")
                args.text("url")?.let { put("url", it) }
                (args["adopt"] as? JsonArray)?.let { put("adopt", it) }
            }
            val answer = Neuland.request(HttpMethod.Post, "forms", body, limitS = 120).jsonObject
            "Session ${answer.text("session")}\n" + tabs(answer["tabs"])
        }
        tool(
            "form_read",
            "The page a tab of the session shows, its iframes and open shadow roots included under one set of ids: every field (id, kind, label, the question its group asks, options, value, required), the buttons, links and other things that take a click (id, text), the errors it shows, its text, and every tab of the session.",
            readOnly = true,
            props = {
                string("session", "The session", required = true)
                string("tab", "The tab, by its id; the held tab when left out")
            },
        ) { args ->
            page(Neuland.request(HttpMethod.Get, "forms/${args.text("session")}" + (args.text("tab")?.let { "?tab=$it" } ?: ""), limitS = 60).jsonObject)
        }
        tool(
            "form_set",
            "Sets one field as a person does (text typed, a select's option chosen with the keys, a checkbox or radio clicked, files chosen) and answers whether the page holds the value after, with the field as it stands.",
            props = {
                string("session", "The session", required = true)
                string("field", "The field's id from form_read", required = true)
                string("value", "The text to type, or the select's option by its text")
                boolean("checked", "For a checkbox, radio or switch: whether it ends up ticked (true when left out)")
                stringArray("files", "For a file field: the names the files were uploaded to the session under")
                string("tab", "The tab, by its id; the held tab when left out")
            },
        ) { args ->
            val body = buildJsonObject {
                put("field", args.text("field"))
                args.text("value")?.let { put("value", it) }
                args["checked"]?.jsonPrimitive?.booleanOrNull?.let { put("checked", it) }
                (args["files"] as? JsonArray)?.let { put("files", it) }
                args.text("tab")?.let { put("tab", it) }
            }
            val answer = Neuland.request(HttpMethod.Post, "forms/${args.text("session")}/set", body, limitS = 60).jsonObject
            val taken = answer["taken"]?.jsonPrimitive?.booleanOrNull == true
            (if (taken) "Taken." else "NOT taken: ${answer.text("note") ?: "the page holds something else"}") + "\n" + fieldLine(answer["field"])
        }
        tool(
            "form_click",
            "Clicks a button, link or anything clickable with the mouse at a point of it the page shows uncovered, by its id or its visible text, or a point x, y of form_screenshot's picture for what form_read does not list. Answers the page after (a window it opened shows under tabs).",
            destructive = true,
            props = {
                string("session", "The session", required = true)
                string("field", "The button's or link's id from form_read")
                string("text", "Or its visible text, exactly")
                number("x", "Or a point of the tab's picture: its x")
                number("y", "And its y")
                string("tab", "The tab, by its id; the held tab when left out")
            },
        ) { args ->
            val body = buildJsonObject {
                args.text("field")?.let { put("field", it) }
                args.text("text")?.let { put("text", it) }
                args["x"]?.jsonPrimitive?.doubleOrNull?.let { put("x", it) }
                args["y"]?.jsonPrimitive?.doubleOrNull?.let { put("y", it) }
                args.text("tab")?.let { put("tab", it) }
            }
            page(Neuland.request(HttpMethod.Post, "forms/${args.text("session")}/click", body, limitS = 90).jsonObject)
        }
        tool(
            "form_go",
            "Sends a tab of the session to an address and answers the page.",
            props = {
                string("session", "The session", required = true)
                string("url", "The address", required = true)
                string("tab", "The tab, by its id; the held tab when left out")
            },
        ) { args ->
            val body = buildJsonObject {
                put("url", args.text("url"))
                args.text("tab")?.let { put("tab", it) }
            }
            page(Neuland.request(HttpMethod.Post, "forms/${args.text("session")}/go", body, limitS = 90).jsonObject)
        }
        tool(
            "form_key",
            "Presses a key on the focused element: Enter, Escape, Tab, Space, ArrowDown, ArrowUp, Home, End, PageDown, PageUp, Backspace. Answers the page after.",
            props = {
                string("session", "The session", required = true)
                string("key", "The key", required = true)
                string("tab", "The tab, by its id; the held tab when left out")
            },
        ) { args ->
            val body = buildJsonObject {
                put("key", args.text("key"))
                args.text("tab")?.let { put("tab", it) }
            }
            page(Neuland.request(HttpMethod.Post, "forms/${args.text("session")}/key", body, limitS = 60).jsonObject)
        }
        tool(
            "form_ask_secret",
            "Asks the user on their phone (NeuAuth) for a password or a code the page wants in a field, and types what they give into it: the secret goes from the phone into the page and never to you; form_read shows the field as filled only. The user sees the title, the account, your words and the page's host; it is typed only while the tab still shows that host. Waits up to 4 minutes for the user and answers whether the page took it; still waiting then, it answers the ask's id: call it again with session and ask to wait on. The user has 10 minutes from the ask.",
            destructive = true,
            props = {
                string("session", "The session", required = true)
                string("field", "The password or code field's id from form_read")
                string("secret", "\"password\" or \"code\"; password when left out")
                string("title", "The site as the user knows it (\"OVHcloud\"); the page's host when left out")
                string("account", "The account it is for, as the user knows it")
                string("hint", "For a code: where the page says it went (\"sent to p***@gmail.com\")")
                string("asker", "Who asks and for what, in a few words (\"tldr, to cancel the VPS\")")
                string("tab", "The tab, by its id; the held tab when left out")
                string("ask", "The id of an ask still waiting, as an earlier call answered it: waits for it again instead of asking anew")
            },
        ) { args ->
            val session = args.text("session")
            val ask = args.text("ask") ?: run {
                val body = buildJsonObject {
                    put("field", args.text("field") ?: throw IllegalArgumentException("a new ask names its field"))
                    put("secret", args.text("secret") ?: "password")
                    args.text("title")?.let { put("title", it) }
                    args.text("account")?.let { put("account", it) }
                    args.text("hint")?.let { put("hint", it) }
                    args.text("asker")?.let { put("asker", it) }
                    args.text("tab")?.let { put("tab", it) }
                }
                Neuland.request(HttpMethod.Post, "forms/$session/ask", body, limitS = 60).jsonObject.text("ask")
                    ?: throw IllegalStateException("Neuland gave the ask no id")
            }
            // Answered within 4 minutes: the reverse proxy in front of the MCP ends a call after 6.
            val answer = Neuland.request(HttpMethod.Get, "forms/$session/ask/$ask?wait=240", limitS = 260).jsonObject
            when (answer.text("state")) {
                "typed" -> "Typed in; the page holds it.\n" + fieldLine(answer["field"])
                "failed" -> "NOT typed in: ${answer.text("error")}\n" + fieldLine(answer["field"])
                "called-off" -> "The user called it off."
                "timed-out" -> "The user did not answer within 10 minutes."
                else -> "Still waiting for the user. Call form_ask_secret again with session \"$session\" and ask \"$ask\" to wait on."
            }
        }
        tool(
            "form_card_fill",
            "Types the user's payment card into the checkout the tab shows, after the user allowed it on their phone (NeuAuth) seeing the amount and where the card goes. You give the amount; Neuland finds the card's fields, also inside a payment provider's iframe, and the card never reaches you, a read or a record. Refused when the page does not show that amount, when it is more than Neuland may pay, or when the card's fields cannot be told apart. Pressing pay stays yours, and the bank asks the user to confirm the charge. Waits up to 4 minutes; still waiting then, call it again with session and ask.",
            destructive = true,
            props = {
                string("session", "The session", required = true)
                integer("amount_cents", "What the payment comes to, in cents: 3900 for 39,00 €")
                string("merchant", "Who is paid, as the user knows them; the page's host when left out")
                string("asker", "Who asks and for what, in a few words")
                string("tab", "The tab, by its id; the held tab when left out")
                string("ask", "The id of an ask still waiting, as an earlier call answered it: waits for it again instead of asking anew")
            },
        ) { args ->
            val session = args.text("session")
            val ask = args.text("ask") ?: run {
                val body = buildJsonObject {
                    put("amount_cents", args["amount_cents"]?.jsonPrimitive?.longOrNull ?: throw IllegalArgumentException("a new ask names its amount_cents"))
                    args.text("merchant")?.let { put("merchant", it) }
                    args.text("asker")?.let { put("asker", it) }
                    args.text("tab")?.let { put("tab", it) }
                }
                Neuland.request(HttpMethod.Post, "forms/$session/card", body, limitS = 60).jsonObject.text("ask")
                    ?: throw IllegalStateException("Neuland gave the ask no id")
            }
            val answer = Neuland.request(HttpMethod.Get, "forms/$session/ask/$ask?wait=240", limitS = 260).jsonObject
            when (answer.text("state")) {
                "typed" -> "The card is typed in (${(answer["filled"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content }}). Nothing is paid until the pay button is pressed and the bank's confirmation given."
                "failed" -> "NOT typed in: ${answer.text("error")}"
                "refused" -> "The user refused it."
                "called-off" -> "The user called it off."
                "timed-out" -> "The user did not answer within 10 minutes."
                else -> "Still waiting for the user. Call form_card_fill again with session \"$session\" and ask \"$ask\" to wait on."
            }
        }
        tool(
            "form_close",
            "Ends the session: its tab and the windows it opened closed, its uploaded files deleted. Tabs taken over stay open.",
            props = { string("session", "The session", required = true) },
        ) { args ->
            Neuland.passOn(HttpMethod.Delete, "forms/${args.text("session")}", null)?.let { (status, text) ->
                if (status in 200..299) "Closed." else throw IllegalStateException(text.ifBlank { "Neuland answered $status" })
            } ?: throw IllegalStateException("Neuland did not answer")
        }
        val screenshot = McpProps().apply {
            string("session", "The session", required = true)
            string("tab", "The tab, by its id; the held tab when left out")
        }
        addTool(
            name = "form_screenshot",
            description = "A picture of what a tab of the session shows.",
            inputSchema = ToolSchema(properties = JsonObject(screenshot.properties), required = screenshot.required),
            toolAnnotations = ToolAnnotations(readOnlyHint = true),
        ) { request ->
            val args = request.arguments ?: JsonObject(emptyMap())
            try {
                val png = Neuland.bytes("forms/${args.text("session")}/screenshot" + (args.text("tab")?.let { "?tab=$it" } ?: ""))
                CallToolResult(content = listOf(ImageContent(java.util.Base64.getEncoder().encodeToString(png), "image/png")))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CallToolResult(content = listOf(TextContent("Failed: ${e.message}")), isError = true)
            }
        }
    }

    private fun Server.tool(name: String, description: String, destructive: Boolean = false, readOnly: Boolean = false, props: McpProps.() -> Unit, run: suspend (JsonObject) -> String) =
        textTool(name, description, destructive = destructive, readOnly = readOnly, props = props) { args ->
            try {
                run(args)
            } catch (e: Neuland.Refusal) {
                throw IllegalStateException(e.message)
            }
        }

    private fun JsonObject.text(name: String): String? = this[name]?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun tabs(tabs: JsonElement?): String =
        (tabs as? JsonArray).orEmpty().joinToString("\n", prefix = "Tabs:\n") {
            val t = it.jsonObject
            "  ${t.text("tab")} (${t.text("how")}) ${t.text("title").orEmpty()} | ${t.text("url").orEmpty()}"
        }

    private fun fieldLine(field: JsonElement?): String {
        val f = field as? JsonObject ?: return "  (the field no longer shows)"
        val kind = f.text("kind").orEmpty()
        val state = when {
            f["checked"] != null -> if (f["checked"]?.jsonPrimitive?.booleanOrNull == true) "[x]" else "[ ]"
            else -> "= ${f.text("value")?.let { "\"$it\"" } ?: "-"}"
        }
        val options = (f["options"] as? JsonArray)?.joinToString(" | ") { it.jsonPrimitive.content }?.let { "  options: $it" }.orEmpty()
        val flags = listOfNotNull(
            "required".takeIf { f["required"]?.jsonPrimitive?.booleanOrNull == true },
            "disabled".takeIf { f["disabled"]?.jsonPrimitive?.booleanOrNull == true },
            "invalid".takeIf { f["invalid"]?.jsonPrimitive?.booleanOrNull == true },
        ).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
        return "  ${f.text("field")} [$kind] ${f.text("label").orEmpty()}$flags $state$options"
    }

    private fun page(page: JsonObject): String = buildString {
        appendLine("${page.text("title").orEmpty()} | ${page.text("url").orEmpty()} (tab ${page.text("tab")})")
        (page["errors"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { e -> appendLine("Errors: " + e.joinToString(" / ") { it.jsonPrimitive.content }) }
        appendLine("Fields:")
        var group: String? = null
        for (f in (page["fields"] as? JsonArray).orEmpty()) {
            val g = f.jsonObject.text("group")
            if (g != null && g != group) appendLine(" $g")
            group = g
            appendLine(fieldLine(f))
        }
        appendLine("Buttons and links:")
        for (b in (page["buttons"] as? JsonArray).orEmpty()) {
            val o = b.jsonObject
            append("  ${o.text("field")} [${o.text("kind")}] ${o.text("text")}")
            if (o["disabled"]?.jsonPrimitive?.booleanOrNull == true) append(" (disabled)")
            o.text("href")?.let { append(" -> $it") }
            appendLine()
        }
        appendLine(tabs(page["tabs"]))
        appendLine("Text:")
        append(page.text("text").orEmpty())
    }
}
