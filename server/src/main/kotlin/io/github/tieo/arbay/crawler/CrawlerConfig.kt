package io.github.tieo.arbay.crawler

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CrawlerConfig(
    // Target listings to collect per marketplace. This is the user-facing knob: it reads
    // the same across sites regardless of their page size. Crawlers paginate until they
    // reach it (or run dry).
    val maxResultsPerPlatform: Int = 60,
    // Hard upper bound on pages fetched per marketplace, so a site with a tiny page size
    // can't spin the pager forever chasing the result target. Not surfaced in the app.
    val maxPages: Int = 8,
    val ebayItemsPerPage: Int = 120,
    val sortByPrice: Boolean = true,
) {
    companion object {
        private val file = File(System.getProperty("user.home"), ".arbay/crawler_config.json")
        private val json = Json { prettyPrint = true; encodeDefaults = true }

        @Volatile
        var current: CrawlerConfig = load()
            private set

        fun update(config: CrawlerConfig) {
            current = config
            try {
                file.parentFile.mkdirs()
                file.writeText(json.encodeToString(config))
            } catch (_: Exception) {}
        }

        private fun load(): CrawlerConfig {
            return try {
                if (!file.exists()) return CrawlerConfig()
                json.decodeFromString<CrawlerConfig>(file.readText())
            } catch (_: Exception) { CrawlerConfig() }
        }
    }
}
