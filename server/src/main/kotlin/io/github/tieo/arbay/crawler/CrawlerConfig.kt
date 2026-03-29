package io.github.tieo.arbay.crawler

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CrawlerConfig(
    val maxPages: Int = 5,
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
