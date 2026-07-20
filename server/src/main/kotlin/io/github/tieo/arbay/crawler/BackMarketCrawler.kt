package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class BackMarketCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.BACKMARKET_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.backmarket.de/de-de/search?q=${query.positiveText.encodeUrl()}"
        // Cloudflare serves a challenge to curl_cffi and to Playwright Chromium; the zendriver
        // real-Chrome stealth tier passes it. The product grid is client-rendered after the
        // challenge clears, so wait for the productCard marker rather than the bare shell.
        // Require several card matches so a lone preload reference to the selector does not satisfy
        // the wait before the grid paints.
        val html = StealthBrowserClient.fetchRendered(url, waitMarker = "data-qa=\"productCard\"", waitSeconds = 30, minMatches = 3)
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

            // The price element reads "Preis des erneuerten Produkts: 209 ,00 € 379,99 € neu …":
            // an sr-only label, the refurbished price, then the struck-through new price. Drop the
            // label, then take the first "euros,cents" amount (the refurbished price). The euros and
            // cents can be split by an element boundary, so allow whitespace around the comma.
            val priceText = card.selectFirst("[data-qa=productCardPrice]")?.text()
                ?.substringAfter(':')?.replace(Regex("""\s+"""), " ") ?: return@mapNotNull null
            val amount = Regex("""(\d[\d.]*)\s*,\s*(\d{2})""").find(priceText) ?: return@mapNotNull null
            val euros = amount.groupValues[1].replace(".", "").toLongOrNull() ?: return@mapNotNull null
            val price = Money(euros * 100 + amount.groupValues[2].toLong(), Currency.EUR)

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
