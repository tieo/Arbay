package io.github.tieo.arbay.debug

/**
 * What the app actually holds right now, as JSON, for a developer to pull instead of tapping
 * through a live session and screenshotting each state.
 *
 * A screen or view model registers a slice under a name; a dump reads whichever slices are
 * currently registered and stitches them into one object. Nothing here is ever fed back into the
 * app's own state, and nothing here runs unless a dump is actually requested — registering is one
 * map write, and building JSON only happens when [dumpJson] is called.
 */
object DebugRegistry {
    private val providers = LinkedHashMap<String, () -> String>()

    /** Register a slice, keyed by name (e.g. "results", "screen"). Re-registering the same key
     *  replaces it, so a screen that recomposes just keeps overwriting its own entry. */
    fun register(key: String, provider: () -> String) {
        providers[key] = provider
    }

    /** Register a slice whose value is already known, not lazily computed. */
    fun set(key: String, json: String) = register(key) { json }

    /** Drop a slice, e.g. when its view model is cleared. A dump after this simply omits it. */
    fun unregister(key: String) {
        providers.remove(key)
    }

    /** Every registered slice as one JSON object. A slice that throws while building is reported
     *  as its own error rather than losing every other slice. */
    fun dumpJson(): String {
        val body = providers.entries.joinToString(",\n") { (key, provider) ->
            val value = runCatching { provider() }
                .getOrElse { "{\"debugDumpError\":${it.message.orEmpty().jsonQuoted()}}" }
            "  ${key.jsonQuoted()}: $value"
        }
        return "{\n$body\n}"
    }

    private fun String.jsonQuoted(): String =
        "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
}
