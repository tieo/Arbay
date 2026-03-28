package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class BackMarketCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.BACKMARKET_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.backmarket.de/de-de/search?q=${query.positiveText.encodeUrl()}"
        // Cloudflare Bot Management blocks Playwright headless Chromium (TLS/canvas fingerprint).
        // curl_cffi with Chrome 131 TLS impersonation passes CF with homepage session priming.
        val html = CurlCffiClient.fetch(url, primeUrl = "https://www.backmarket.de/de-de")
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Back Market search renders [data-qa=productCard] elements with attributes:
        //   image-alt = product title, id = UUID, brand/model attrs
        // Price is in a nested [data-qa=productCardPrice] element.
        val cards = doc.select("[data-qa=productCard]")

        return cards.mapNotNull { card ->
            val imageAlt = card.attr("image-alt").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Prepend brand if it's not already in the title (e.g. "iPhone 15" → "Apple iPhone 15")
            val brand = card.attr("brand").replaceFirstChar { it.uppercase() }
            val title = if (brand.isNotBlank() && !imageAlt.lowercase().contains(brand.lowercase())) {
                "$brand $imageAlt"
            } else {
                imageAlt
            }

            val href = card.selectFirst("a[href*='/de-de/p/']")?.attr("href") ?: return@mapNotNull null
            val url = if (href.startsWith("http")) href else "https://www.backmarket.de$href"

            val externalId = card.id().takeIf { it.isNotBlank() }
                ?: Regex("""/p/([^/?#]+)""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null

            val priceText = card.selectFirst("[data-qa=productCardPrice]")?.text() ?: return@mapNotNull null
            // Price is like "473,00" (no € symbol in element) — append € so Money.parse sets EUR
            val price = Money.parse("$priceText €") ?: return@mapNotNull null

            // Image: srcset contains relative /cdn-cgi/image/.../https://cloudfront.net/... paths
            val srcset = card.selectFirst("img[srcset]")?.attr("srcset") ?: ""
            val imageUrl = Regex("""https://[^/]*cloudfront\.net/[^\s,]+\.(?:jpg|jpeg|webp|png)""")
                .find(srcset)?.value

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                condition = Condition.REFURBISHED,
                imageUrls = listOfNotNull(imageUrl),
                shipping = Shipping(free = true), // Back Market includes free shipping
                scrapedAt = now,
            )
        }
    }
}
