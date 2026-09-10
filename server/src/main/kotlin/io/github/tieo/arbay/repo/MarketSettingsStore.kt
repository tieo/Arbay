package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.MarketSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Which countries a search covers when it names no markets of its own.
 *
 * Held by the server because the background watch runs there: a saved search checked while the
 * phone is asleep has to cover the same countries as the same search opened by hand, or the
 * notification and the screen disagree about what exists.
 */
object MarketSettingsStore {
    private val log = LoggerFactory.getLogger(MarketSettingsStore::class.java)
    private val file = File(System.getProperty("user.home"), ".arbay/market_settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    var current: MarketSettings = load()
        private set

    private fun load(): MarketSettings = try {
        if (file.exists()) json.decodeFromString(file.readText()) else MarketSettings()
    } catch (e: Exception) {
        log.warn("Could not read market settings, using defaults: {}", e.message)
        MarketSettings()
    }

    fun update(settings: MarketSettings): MarketSettings {
        current = settings
        try {
            file.writeTextAtomically(json.encodeToString(settings))
        } catch (e: Exception) {
            log.warn("Could not save market settings: {}", e.message)
        }
        return current
    }
}
