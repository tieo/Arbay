package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.MarketSettings
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Which countries a search covers when it names no markets of its own.
 *
 * Held by the server because the background watch runs there: a saved search checked while the
 * phone is asleep has to cover the same countries as the same search opened by hand, or the
 * notification and the screen disagree about what exists.
 */
object MarketSettingsStore {
    private val log = LoggerFactory.getLogger(MarketSettingsStore::class.java)
    private val file = DataDir.file("market_settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    var current: MarketSettings = load()
        private set

    private fun load(): MarketSettings =
        file.readStore(log) { json.decodeFromString<MarketSettings>(it) } ?: MarketSettings()

    // One update at a time, so the settings in memory and the ones on disk are the same ones.
    @Synchronized
    fun update(settings: MarketSettings): MarketSettings {
        current = settings
        try {
            file.writeTextAtomically(json.encodeToString(settings))
        } catch (e: Exception) {
            log.error("Could not save market settings: {}", e.message)
        }
        return current
    }
}
