package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*

/**
 * Crawler for finn.no, Norway's dominant marketplace and its main used-vehicle source. finn.no runs
 * on Schibsted's "mobility" platform — the same JSON search API and native filter scheme as dba.dk —
 * so it filters at the source (year/mileage/power/price/gearbox) via the shared [MobilityApi] and
 * needs only its host and currency (NOK). See [DbaCrawler].
 */
class FinnCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.FINN

    private val host = "www.finn.no"

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        val text = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }
        return paginate(query) { page ->
            val url = MobilityApi.searchUrl(host, text, query, page, Currency.NOK)
            MobilityApi.parseDocs(fetchJson(url), platformId, Currency.NOK, "NO", host) ?: emptyList()
        }
    }

    private suspend fun fetchJson(url: String): String =
        client.get(url) {
            headers {
                append("User-Agent", USER_AGENT)
                append("Accept", "application/json")
            }
        }.body()
}
