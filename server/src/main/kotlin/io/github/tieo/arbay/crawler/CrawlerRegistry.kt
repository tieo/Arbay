package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
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
        PlatformId.EBAY_IT to EbayDeCrawler(httpClient, PlatformId.EBAY_IT, "ebay.it"),
        PlatformId.EBAY_FR to EbayDeCrawler(httpClient, PlatformId.EBAY_FR, "ebay.fr"),
        PlatformId.EBAY_ES to EbayDeCrawler(httpClient, PlatformId.EBAY_ES, "ebay.es"),
        PlatformId.KLEINANZEIGEN to KleinanzeigenCrawler(httpClient),
        PlatformId.AMAZON_DE to AmazonDeCrawler(httpClient),
        PlatformId.IDEALO to IdealoCrawler(httpClient),
        PlatformId.GEIZHALS to GeizhalsCrawler(httpClient),
        PlatformId.VINTED_DE to VintedDeCrawler(httpClient),
        PlatformId.BACKMARKET_DE to BackMarketCrawler(httpClient),
        PlatformId.REBUY to RebuyCrawler(httpClient),
        PlatformId.REFURBED to RefurbedCrawler(httpClient),
        PlatformId.MARKTPLAATS to MarktplaatsCrawler(httpClient),
        // Home markets on one chip; the neighbouring countries AutoScout24 also serves are
        // split into their own selectable markets so their coverage is visible, not buried.
        PlatformId.AUTOSCOUT24 to AutoScout24Crawler(httpClient, listOf("D", "A", "L", "NL")),
        PlatformId.AUTOSCOUT24_IT to AutoScout24Crawler(httpClient, listOf("I"), PlatformId.AUTOSCOUT24_IT),
        PlatformId.AUTOSCOUT24_FR to AutoScout24Crawler(httpClient, listOf("F"), PlatformId.AUTOSCOUT24_FR),
        PlatformId.AUTOSCOUT24_ES to AutoScout24Crawler(httpClient, listOf("E"), PlatformId.AUTOSCOUT24_ES),
        PlatformId.AUTOSCOUT24_BE to AutoScout24Crawler(httpClient, listOf("B"), PlatformId.AUTOSCOUT24_BE),
        PlatformId.MOBILE_DE to MobileDeCrawler(httpClient),
        PlatformId.WILLHABEN to WillhabenCrawler(httpClient),
        PlatformId.IMMOSCOUT24 to ImmoScout24Crawler(httpClient),
        PlatformId.TRUCKSCOUT24 to TruckScout24Crawler(httpClient),
        PlatformId.OTOMOTO to OtomotoCrawler(httpClient),
        PlatformId.DBA to DbaCrawler(httpClient),
        PlatformId.BILBASEN to BilbasenCrawler(httpClient),
        PlatformId.BYTBIL to BytbilCrawler(httpClient),
        PlatformId.SAUTO to SautoCrawler(httpClient),
        PlatformId.RICARDO to RicardoCrawler(httpClient),
        // Autovit.ro (Romania) runs the same OLX/Otomoto stack; only host, category and currency differ.
        PlatformId.AUTOVIT to OtomotoCrawler(
            httpClient,
            platformId = PlatformId.AUTOVIT,
            host = "https://www.autovit.ro",
            categoryPath = "autoturisme",
            siteCurrency = Currency.EUR,
            countryCode = "RO",
            siteLabel = "Autovit",
        ),
    )

    fun crawlerFor(platformId: PlatformId): Crawler? = crawlers[platformId]

    fun supportedPlatforms(): Set<PlatformId> = crawlers.keys
}
