package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.ImportSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Where the buyer is and what import VAT they pay.
 *
 * Held by the server rather than on the device because both sides have to reach the same number:
 * the app compares prices with it, and a saved search's notification subfilter ("under €149")
 * decides with it too. Split across the two, a listing could clear the filter and then show as too
 * expensive on the screen it opened.
 */
object ImportSettingsStore {
    private val log = LoggerFactory.getLogger(ImportSettingsStore::class.java)
    private val file = File(System.getProperty("user.home"), ".arbay/import_settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    var current: ImportSettings = load()
        private set

    private fun load(): ImportSettings = try {
        if (file.exists()) json.decodeFromString(file.readText()) else ImportSettings()
    } catch (e: Exception) {
        log.warn("Could not read import settings, using defaults: {}", e.message)
        ImportSettings()
    }

    fun update(settings: ImportSettings): ImportSettings {
        current = settings
        try {
            file.writeTextAtomically(json.encodeToString(settings))
        } catch (e: Exception) {
            log.warn("Could not save import settings: {}", e.message)
        }
        return current
    }
}
