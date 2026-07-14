package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

class KleinanzeigenCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.KLEINANZEIGEN

    private val log = LoggerFactory.getLogger(KleinanzeigenCrawler::class.java)

    /**
     * Resolves a city name to a Kleinanzeigen location ID and slug via their autocomplete API.
     * Returns (locationId, citySlug) or null if lookup fails.
     * Example: "Frankfurt (Oder)" → ("1234", "frankfurt-%28oder%29")
     */
    private suspend fun resolveLocation(location: String): Pair<String, String>? {
        return try {
            val url = "https://www.kleinanzeigen.de/s-ort-empfehlungen.json?query=${location.encodeUrl()}"
            val response = client.get(url) {
                headers {
                    append("User-Agent", USER_AGENT)
                    append("Accept", "application/json")
                    append("Accept-Language", "de-DE,de;q=0.9")
                    append("Referer", "https://www.kleinanzeigen.de/")
                }
            }
            if (response.status.value != 200) return null
            val body = response.bodyAsText()
            val json = body.trim()
            val regex = Regex(""""_(\d+)"\s*:""")
            val locationId = regex.findAll(json)
                .map { it.groupValues[1] }
                .firstOrNull { it != "0" }
                ?: return null
            val citySlug = KleinanzeigenUrlBuilder.citySlug(location)
            locationId to citySlug
        } catch (e: Exception) {
            log.debug("Location lookup failed for '{}': {}", location, e.message)
            null
        }
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        if (query.freeOnly) return searchFreeItems(query)
        return searchRegular(query)
    }

    /**
     * Streaming free-item search — emits results per page via [onPageResults].
     * Returns total result count and whether there are more pages.
     */
    suspend fun searchFreeItemsStreaming(
        query: SearchQuery,
        onPageResults: suspend (page: Int, results: List<Listing>, totalSoFar: Int) -> Unit,
    ): Pair<Int, Boolean> {
        val seenIds = mutableSetOf<String>()
        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages
        var total = 0
        var hasMore = false

        val locationStr = query.location
        val resolved = if (locationStr != null) resolveLocation(locationStr) else null
        val locationId = resolved?.first
        val citySlug = resolved?.second
        val radiusKm = query.radiusKm

        val endPage = query.startPage + maxPages - 1
        for (page in query.startPage..endPage) {
            val url = KleinanzeigenUrlBuilder.freeItems(
                locationId = locationId,
                citySlug = citySlug,
                radiusKm = if (locationId != null) radiusKm else null,
                page = page,
            )

            val html = try {
                fetchWithFallback(client, url, "Kleinanzeigen", waitSelector = "article.aditem")
            } catch (e: CrawlerBlockedException) {
                if (page == query.startPage) throw e
                break
            }

            val doc = Jsoup.parse(html)
            val rawItemCount = doc.select("article.aditem").size
            if (rawItemCount == 0) break

            val pageResults = parseSearchResults(html, freeOnly = true)
            val newResults = pageResults.filter { seenIds.add(it.externalId) }
            total += newResults.size

            log.info("Free items page {}: {} raw, {} free, {} new, total={}",
                page, rawItemCount, pageResults.size, newResults.size, total)

            if (newResults.isNotEmpty()) {
                onPageResults(page, newResults, total)
            }

            if (pageResults.size < 3) break
            hasMore = page < endPage
        }

        return total to hasMore
    }

    /**
     * Free-item search using Kleinanzeigen's preis::0 filter for guaranteed free-only results.
     * This filter works across all pages, so we can paginate normally.
     */
    private suspend fun searchFreeItems(query: SearchQuery): List<Listing> {
        val allResults = mutableListOf<Listing>()
        searchFreeItemsStreaming(query) { _, results, _ ->
            allResults.addAll(results)
        }
        return allResults
    }

    private suspend fun searchRegular(query: SearchQuery): List<Listing> {
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages

        val locationStr = query.location
        val resolved = if (locationStr != null) resolveLocation(locationStr) else null
        val locationId = resolved?.first

        // Car queries go to the Autos category (c216) with a make/model slug,
        // which surfaces real car ads instead of accessories and parts.
        val carQuery = CarQueryResolver.resolve(query.positiveText)

        val endPage = query.startPage + maxPages - 1
        for (page in query.startPage..endPage) {
            val url = if (carQuery != null) {
                KleinanzeigenUrlBuilder.carSearch(
                    query = listOfNotNull(carQuery.makeSlug, carQuery.modelSlug).joinToString(" "),
                    page = page,
                    locationId = locationId,
                    radiusKm = if (locationId != null) query.radiusKm else null,
                    minPriceCents = query.minPrice?.amount,
                    maxPriceCents = query.maxPrice?.amount,
                )
            } else {
                KleinanzeigenUrlBuilder.regularSearch(
                    query = query.positiveText,
                    page = page,
                    locationId = locationId,
                    radiusKm = if (locationId != null) query.radiusKm else null,
                    minPriceCents = query.minPrice?.amount,
                    maxPriceCents = query.maxPrice?.amount,
                )
            }

            val html = try {
                fetchWithFallback(client, url, "Kleinanzeigen", waitSelector = "article.aditem")
            } catch (e: CrawlerBlockedException) {
                if (page == query.startPage) throw e
                break
            }

            val doc = Jsoup.parse(html)
            val rawItemCount = doc.select("article.aditem").size
            if (rawItemCount == 0) break

            val pageResults = parseSearchResults(html, freeOnly = false)
            val newResults = pageResults.filter { seenIds.add(it.externalId) }
            allResults.addAll(newResults)

            if (rawItemCount < 10) break
            if (allResults.size >= CrawlerConfig.current.maxResultsPerPlatform) break
        }

        return allResults
    }

    internal fun parseSearchResults(html: String, freeOnly: Boolean = false): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Cards only carry a truncated snippet; the full description sits in per-listing
        // ld+json (keyed by title). Harvesting it powers find-in-description and gives the
        // vehicle enricher the whole spec text, at no extra request.
        val fullDescriptions = parseJsonLdDescriptions(doc)

        val items = doc.select("article.aditem")

        return items.mapNotNull { item ->
            val adId = item.attr("data-adid").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val titleEl = item.selectFirst("h2.text-module-begin a.ellipsis")
                ?: item.selectFirst("a.ellipsis")
                ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val href = titleEl.attr("href").takeIf { it.isNotBlank() }
                ?: item.attr("data-href")
            val url = "https://www.kleinanzeigen.de$href"

            // Skip "Vermisste Tiere" (category 283) — lost pet notices, not items to pick up
            if (freeOnly) {
                val catMatch = Regex("""/\d+-(\d+)-\d+$""").find(href)
                val mainCat = catMatch?.groupValues?.get(1)
                if (mainCat == "283") return@mapNotNull null
            }

            val priceText = item.selectFirst("p.aditem-main--middle--price-shipping--price")?.text()
            if (priceText == null && !freeOnly) return@mapNotNull null
            val negotiable = priceText?.contains("VB", ignoreCase = true) ?: false
            val isFreeItem = priceText.isNullOrBlank() ||
                priceText.contains("verschenken", ignoreCase = true) ||
                priceText.contains("kostenlos", ignoreCase = true) ||
                priceText.trim() == "0 €" || priceText.trim() == "0€" ||
                priceText.trim() == "Zu verschenken"
            // When searching free items, drop anything with a price tag
            if (freeOnly && !isFreeItem) return@mapNotNull null
            val price = if (isFreeItem) Money.cents(0) else Money.parse(priceText ?: "") ?: return@mapNotNull null

            val locationText = item.selectFirst("div.aditem-main--top--left")?.text()?.trim()
            val location = locationText?.let { Location.parse(it) }

            val imageUrl = item.selectFirst("div.aditem-image img")?.let {
                val src = it.attr("src")
                val srcset = it.attr("srcset")
                srcset.takeIf { s -> s.isNotBlank() } ?: src.takeIf { s -> s.startsWith("http") }
            }

            val snippet = item.selectFirst("p.aditem-main--middle--description")?.text()
            val descriptionSnippet = fullDescriptions[title] ?: snippet

            // Car cards carry attribute chips ("228.076 km", "EZ 11/2012") in .simpletag spans.
            // Mileage and first registration from the chips are structured, so mark them
            // verified — they may exclude. Fold them into the description too for the enricher.
            val tags = item.select("span.simpletag").eachText().map { it.trim() }.filter { it.isNotBlank() }
            val chipVehicle = parseChips(tags)
            val descriptionWithTags = (listOfNotNull(descriptionSnippet) + tags)
                .joinToString(" · ").takeIf { it.isNotBlank() }

            val shippingText = item.selectFirst("p.aditem-main--middle--price-shipping--shipping")?.text()
                ?: item.selectFirst("[class*=shipping]")?.text()
            val shipping = when {
                shippingText == null -> null
                shippingText.contains("Versand", true) -> {
                    val cost = Money.parse(shippingText)
                    if (cost != null) Shipping(cost = cost) else Shipping(available = true)
                }
                else -> null
            }

            Listing(
                id = "${platformId.name}:$adId",
                platformId = platformId,
                externalId = adId,
                url = url,
                title = title,
                price = price,
                shipping = shipping,
                negotiable = negotiable,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                description = descriptionWithTags,
                scrapedAt = now,
                vehicle = chipVehicle,
            )
        }
    }

    /** Verified mileage/first-registration from the card's attribute chips. */
    private fun parseChips(tags: List<String>): VehicleInfo? {
        var mileageKm: Int? = null
        var year: Int? = null
        var month: Int? = null
        for (t in tags) {
            Regex("""([0-9][0-9.]{2,})\s*km""").find(t)?.let {
                mileageKm = it.groupValues[1].replace(".", "").toIntOrNull()?.takeIf { km -> km in 1..2_000_000 }
            }
            Regex("""EZ\s*(?:(\d{1,2})/)?(\d{4})""").find(t)?.let {
                month = it.groupValues[1].toIntOrNull()
                year = it.groupValues[2].toIntOrNull()?.takeIf { y -> y in 1980..2035 }
            }
        }
        if (mileageKm == null && year == null) return null
        return VehicleTextParser.verifiedByPresence(
            VehicleInfo(firstRegYear = year, firstRegMonth = month, mileageKm = mileageKm),
        )
    }

    /** Full descriptions from the page's ld+json image blocks, keyed by listing title. */
    private fun parseJsonLdDescriptions(doc: org.jsoup.nodes.Document): Map<String, String> {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val map = HashMap<String, String>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val el = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            val objects = when (el) {
                is kotlinx.serialization.json.JsonArray -> el
                is kotlinx.serialization.json.JsonObject -> listOf(el)
                else -> emptyList()
            }
            for (o in objects) {
                val obj = o as? kotlinx.serialization.json.JsonObject ?: continue
                val title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                val desc = (obj["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
                if (title.isNotBlank() && desc.isNotBlank()) map.putIfAbsent(title.trim(), desc.trim())
            }
        }
        return map
    }
}
