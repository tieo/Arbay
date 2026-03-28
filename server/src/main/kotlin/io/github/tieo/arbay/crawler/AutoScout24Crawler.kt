package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class AutoScout24Crawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.AUTOSCOUT24

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.autoscout24.de/lst?atype=C&cy=D&desc=0&sort=standard&ustate=N%2CU&query=${query.positiveText.encodeUrl()}"
        val html = fetchWithFallback(client, url, "AutoScout24", waitSelector = "article")

        // Try __NEXT_DATA__ JSON first (most reliable), fall back to HTML parsing
        return parseFromNextData(html) ?: parseFromHtml(html)
    }

    private fun parseFromNextData(html: String): List<Listing>? {
        val doc = Jsoup.parse(html)
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val now = Clock.System.now()

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(nextDataScript).jsonObject
            val listings = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("listings")?.jsonArray
                ?: return null

            listings.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                val vehicleTitle = buildString {
                    obj["vehicle"]?.jsonObject?.let { v ->
                        v["make"]?.jsonPrimitive?.contentOrNull?.let { append(it) }
                        v["model"]?.jsonPrimitive?.contentOrNull?.let { append(" $it") }
                        v["modelVersionInput"]?.jsonPrimitive?.contentOrNull?.let { append(" $it") }
                    }
                }.trim()

                val title = vehicleTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                // Price is in tracking.price (raw integer) or price.priceFormatted
                val trackingPrice = obj["tracking"]?.jsonObject?.get("price")?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull()
                val price = if (trackingPrice != null) {
                    Money(trackingPrice * 100, Currency.EUR)
                } else {
                    val formatted = obj["price"]?.jsonObject?.get("priceFormatted")?.jsonPrimitive?.contentOrNull
                    formatted?.let { Money.parse(it) }
                } ?: return@mapNotNull null

                val relUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: "/angebote/$id"
                val url = "https://www.autoscout24.de$relUrl"

                // Images is a list of URL strings
                val imageUrl = obj["images"]?.jsonArray?.firstOrNull()?.let { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull
                        is JsonObject -> el["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }

                val locationObj = obj["location"]?.jsonObject
                val location = locationObj?.let {
                    Location(
                        city = it["city"]?.jsonPrimitive?.contentOrNull,
                        zip = it["zip"]?.jsonPrimitive?.contentOrNull,
                        country = it["countryCode"]?.jsonPrimitive?.contentOrNull,
                    )
                }

                val tracking = obj["tracking"]?.jsonObject
                val mileageText = tracking?.get("mileage")?.jsonPrimitive?.contentOrNull
                val yearText = tracking?.get("firstRegistration")?.jsonPrimitive?.contentOrNull

                val description = buildString {
                    yearText?.let { append("EZ: $it") }
                    mileageText?.let {
                        if (isNotEmpty()) append(" | ")
                        append("$it km")
                    }
                }.takeIf { it.isNotBlank() }

                Listing(
                    id = "${platformId.name}:$id",
                    platformId = platformId,
                    externalId = id,
                    url = url,
                    title = title,
                    price = price,
                    imageUrls = listOfNotNull(imageUrl),
                    location = location,
                    description = description,
                    scrapedAt = now,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFromHtml(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("article[data-testid=list-item]")

        return items.mapNotNull { item ->
            val titleEl = item.selectFirst("h2 span") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            // Data attributes on the article element
            val mileage = item.attr("data-mileage").takeIf { it.isNotBlank() && it != "unknown" }
            val registration = item.attr("data-first-registration").takeIf { it.isNotBlank() && it != "new" }
            val zipCode = item.attr("data-listing-zip-code").takeIf { it.isNotBlank() }

            // Construct ID from href or use index
            val linkEl = item.selectFirst("a[href*='/angebote/']")
            val href = linkEl?.attr("href") ?: ""
            val externalId = Regex("""/angebote/([a-f0-9-]+)""").find(href)?.groupValues?.get(1)
                ?: item.hashCode().toString()
            val url = if (href.startsWith("http")) href else "https://www.autoscout24.de$href"

            val priceEl = item.selectFirst("[class*=price]")
            val priceText = priceEl?.text() ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val imageUrl = item.selectFirst("picture source")?.attr("srcset")
                ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()

            val location = zipCode?.let { Location(zip = it) }

            val description = buildString {
                registration?.let { append("EZ: $it") }
                mileage?.let {
                    if (isNotEmpty()) append(" | ")
                    append("$it km")
                }
            }.takeIf { it.isNotBlank() }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                description = description,
                scrapedAt = now,
            )
        }
    }
}
