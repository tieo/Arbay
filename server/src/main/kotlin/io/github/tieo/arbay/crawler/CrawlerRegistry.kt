package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.PlatformId
import io.ktor.client.*
import io.ktor.client.engine.cio.*

object CrawlerRegistry {
    internal val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30_000
        }
        followRedirects = true
    }

    private val crawlers: Map<PlatformId, Crawler> = mapOf(
        PlatformId.EBAY_DE to EbayDeCrawler(httpClient),
        PlatformId.EBAY_COM to EbayDeCrawler(httpClient, PlatformId.EBAY_COM, "ebay.com"),
        PlatformId.KLEINANZEIGEN to KleinanzeigenCrawler(httpClient),
        PlatformId.AMAZON_DE to AmazonDeCrawler(httpClient),
        PlatformId.IDEALO to IdealoCrawler(httpClient),
        PlatformId.GEIZHALS to GeizhalsCrawler(httpClient),
        PlatformId.VINTED_DE to VintedDeCrawler(httpClient),
        PlatformId.BACKMARKET_DE to BackMarketCrawler(httpClient),
        PlatformId.REBUY to RebuyCrawler(httpClient),
        PlatformId.REFURBED to RefurbedCrawler(httpClient),
        PlatformId.MARKTPLAATS to MarktplaatsCrawler(httpClient),
        PlatformId.AUTOSCOUT24 to AutoScout24Crawler(httpClient),
        PlatformId.MOBILE_DE to MobileDeCrawler(httpClient),
        PlatformId.WILLHABEN to WillhabenCrawler(httpClient),
        PlatformId.IMMOSCOUT24 to ImmoScout24Crawler(httpClient),
        PlatformId.TRUCKSCOUT24 to TruckScout24Crawler(httpClient),
        PlatformId.OTOMOTO to OtomotoCrawler(httpClient),
        PlatformId.DBA to DbaCrawler(httpClient),
        PlatformId.BILBASEN to BilbasenCrawler(httpClient),
        PlatformId.BYTBIL to BytbilCrawler(httpClient),
        PlatformId.SAUTO to SautoCrawler(httpClient),
    )

    fun crawlerFor(platformId: PlatformId): Crawler? = crawlers[platformId]

    fun supportedPlatforms(): Set<PlatformId> = crawlers.keys
}
