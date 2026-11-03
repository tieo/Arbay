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
        PlatformId.IDEALO to IdealoCrawler(),
        PlatformId.GEIZHALS to GeizhalsCrawler(),
        PlatformId.VINTED_DE to VintedDeCrawler(httpClient),
        PlatformId.BACKMARKET_DE to BackMarketCrawler(),
        PlatformId.REBUY to RebuyCrawler(),
        PlatformId.REFURBED to RefurbedCrawler(httpClient),
        PlatformId.MARKTPLAATS to MarktplaatsCrawler(httpClient),
        // Home markets on one chip; the neighbouring countries AutoScout24 also serves are
        // split into their own selectable markets so their coverage is visible, not buried.
        PlatformId.AUTOSCOUT24 to AutoScout24Crawler(listOf("DE", "AT", "LU", "NL")),
        PlatformId.AUTOSCOUT24_IT to AutoScout24Crawler(listOf("IT"), PlatformId.AUTOSCOUT24_IT),
        PlatformId.AUTOSCOUT24_FR to AutoScout24Crawler(listOf("FR"), PlatformId.AUTOSCOUT24_FR),
        PlatformId.AUTOSCOUT24_ES to AutoScout24Crawler(listOf("ES"), PlatformId.AUTOSCOUT24_ES),
        PlatformId.AUTOSCOUT24_BE to AutoScout24Crawler(listOf("BE"), PlatformId.AUTOSCOUT24_BE),
        PlatformId.MOBILE_DE to MobileDeCrawler(),
        PlatformId.WILLHABEN to WillhabenCrawler(),
        PlatformId.IMMOSCOUT24 to ImmoScout24Crawler(httpClient),
        PlatformId.TRUCKSCOUT24 to TruckScout24Crawler(),
        PlatformId.OTOMOTO to OtomotoCrawler(),
        PlatformId.DBA to DbaCrawler(),
        PlatformId.BILBASEN to BilbasenCrawler(),
        PlatformId.BYTBIL to BytbilCrawler(),
        PlatformId.SAUTO to SautoCrawler(),
        PlatformId.RICARDO to RicardoCrawler(httpClient),
        PlatformId.SUBITO to SubitoCrawler(),
        PlatformId.AUTOPLIUS to AutopliusCrawler(),
        PlatformId.NETTIAUTO to NettiautoCrawler(),
        PlatformId.FINN to FinnCrawler(),
        PlatformId.OLX_PT to OlxCrawler(),
        PlatformId.KUPUJEM to KupujemProdajemCrawler(),
        PlatformId.WEB to WebCrawler(httpClient),
        // 2dehands.be is the Belgian sibling of marktplaats.nl on the same Adevinta stack.
        PlatformId.TWEEDEHANDS to MarktplaatsCrawler(
            httpClient,
            platformId = PlatformId.TWEEDEHANDS,
            host = "https://www.2dehands.be",
        ),
        // Autovit.ro (Romania) runs the same OLX/Otomoto stack; only host, category and currency differ.
        PlatformId.AUTOVIT to OtomotoCrawler(
            platformId = PlatformId.AUTOVIT,
            site = "autovit.ro",
            countryCode = "RO",
            siteLabel = "Autovit",
        ),
    )

    fun crawlerFor(platformId: PlatformId): Crawler? = crawlers[platformId]

    fun supportedPlatforms(): Set<PlatformId> = crawlers.keys
}
