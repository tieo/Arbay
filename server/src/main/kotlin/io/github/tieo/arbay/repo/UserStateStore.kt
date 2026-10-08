package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

/**
 * What the user chose and did that is not a saved search: the searches they ran lately, the offers
 * they put away, how the app looks and which currency prices are shown in.
 *
 * Held here rather than on each device, so the phone, the browser on any computer and anything that
 * drives Arbay through its API all see the same. Each entry is whole JSON under a name, written as
 * the device that changed it sends it; the devices keep a copy only to start fast.
 */
object UserStateStore {
    private val log = LoggerFactory.getLogger(UserStateStore::class.java)
    private val file = DataDir.file("user_state.json")
    private val json = Json { prettyPrint = true }
    private val values = ConcurrentHashMap<String, JsonElement>()

    /** The names a device may write, so a typo cannot grow the store with entries nobody reads. */
    val KEYS = setOf("searchHistory", "hiddenOffers", "look")

    init {
        file.readStore(log) { json.decodeFromString<Map<String, JsonElement>>(it) }?.let(values::putAll)
    }

    fun all(): Map<String, JsonElement> = values.toMap()

    fun put(key: String, value: JsonElement) {
        require(key in KEYS) { "No such state: $key" }
        values[key] = value
        synchronized(file) {
            runCatching { file.writeTextAtomically(json.encodeToString(values.toMap())) }
                .onFailure { log.error("Could not save user state: {}", it.message) }
        }
    }
}
