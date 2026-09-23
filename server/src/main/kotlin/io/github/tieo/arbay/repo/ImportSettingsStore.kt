package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.ImportSettings
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    private val file = DataDir.file("import_settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    @Volatile
    var current: ImportSettings = load()
        private set

    private fun load(): ImportSettings =
        file.readStore(log) { json.decodeFromString<ImportSettings>(it) } ?: ImportSettings()

    // One update at a time, so the settings in memory and the ones on disk are the same ones.
    @Synchronized
    fun update(settings: ImportSettings): ImportSettings {
        current = settings
        try {
            file.writeTextAtomically(json.encodeToString(settings))
        } catch (e: Exception) {
            log.error("Could not save import settings: {}", e.message)
        }
        return current
    }
}
