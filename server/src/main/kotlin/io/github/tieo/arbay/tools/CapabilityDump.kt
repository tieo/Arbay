package io.github.tieo.arbay.tools

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.MarketCapabilities
import io.github.tieo.arbay.model.PlatformId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

/**
 * Prints what every market can do, as the crawlers themselves declare it. The model site renders
 * its market objects from this, so a capability gained or lost in the code shows up in the model
 * without anyone editing it by hand.
 */
@Serializable
private data class MarketRecord(
    val id: String,
    val name: String,
    val baseUrl: String,
    val country: String?,
    val searchLanguage: String,
    val crawler: String,
    val paginates: Boolean,
    val pageLimit: Int?,
    val nativeCriteria: List<String>,
    val soldListings: Boolean,
    val relatedSearches: Boolean,
    val listingAge: Boolean,
    val location: Boolean,
    val detailSpecs: Boolean,
    val currency: String?,
)

@Serializable
private data class Offered(val id: String, val name: String)

@Serializable
private data class Dump(val markets: List<MarketRecord>, val withoutCrawler: List<Offered>)

fun main() {
    val records = PlatformId.entries.mapNotNull { platform ->
        val crawler = CrawlerRegistry.crawlerFor(platform) ?: return@mapNotNull null
        val capabilities = MarketCapabilities.of(crawler)
        MarketRecord(
            id = platform.name,
            name = platform.displayName,
            baseUrl = platform.baseUrl,
            country = platform.country,
            searchLanguage = platform.searchLanguage,
            crawler = crawler::class.simpleName ?: "?",
            paginates = capabilities.paginates,
            pageLimit = capabilities.pageLimit,
            nativeCriteria = capabilities.nativeCriteria.map { it.name }.sorted(),
            soldListings = capabilities.soldListings,
            relatedSearches = capabilities.relatedSearches,
            listingAge = capabilities.listingAge,
            location = capabilities.location,
            detailSpecs = capabilities.detailSpecs,
            currency = capabilities.currency?.name,
        )
    }
    val offered = PlatformId.entries
        .filter { CrawlerRegistry.crawlerFor(it) == null }
        .map { Offered(it.name, it.displayName) }
    println(Json { prettyPrint = true }.encodeToString(Dump(records, offered)))
    // The registry holds an HTTP client with a live connection pool, which keeps the JVM alive.
    exitProcess(0)
}
