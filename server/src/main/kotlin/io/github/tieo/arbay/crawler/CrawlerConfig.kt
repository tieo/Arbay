package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    // eBay.de category id (_sacat) constraining a car query to whole vehicles instead of the
    // parts/accessories that dominate an all-category keyword search. Null = no constraint.
    val ebayDeCarCategory: String? = "9800",
) {
    companion object {
        private val file = DataDir.file("crawler_config.json")
        // A settings file written by another version carries keys this build does not know;
        // rejecting it would leave every crawler without its limits.
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        private val log = LoggerFactory.getLogger(CrawlerConfig::class.java)

        @Volatile
        var current: CrawlerConfig = load()
            private set

        fun update(config: CrawlerConfig) {
            current = config
            try {
                file.writeTextAtomically(json.encodeToString(config))
            } catch (e: Exception) {
                // The setting holds for this run either way; saying so is the only way anyone
                // learns why it is back to its old value after a restart.
                log.error("Crawler settings could not be written to {}: {}", file, e.message)
            }
        }

        private fun load(): CrawlerConfig {
            if (!file.exists()) return CrawlerConfig()
            return parse(file.readText())
        }

        internal fun parse(text: String): CrawlerConfig = try {
            json.decodeFromString<CrawlerConfig>(text)
        } catch (e: Exception) {
            // Every setting is about to silently become its default, which is worth a line.
            log.error("Crawler settings at {} could not be read, using defaults: {}", file, e.message)
            CrawlerConfig()
        }
    }
}
