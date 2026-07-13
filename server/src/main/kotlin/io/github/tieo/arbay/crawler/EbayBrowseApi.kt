package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * Official eBay Browse API (free): OAuth2 client-credentials token + item_summary/search.
 * Replaces the fragile HTML/PoW scraping when credentials are configured, falling back to
 * scraping otherwise.
 *
 * Configure via server env: EBAY_CLIENT_ID + EBAY_CLIENT_SECRET (a Production keyset from
 * developer.ebay.com). Optional EBAY_OAUTH_ENV=sandbox to use the sandbox host.
 *
 * NOT YET VERIFIED LIVE — needs a real keyset. The response mapping follows eBay's
 * documented Browse API schema; confirm against a live call before trusting the results.
 */
object EbayBrowseApi {

    private val log = LoggerFactory.getLogger(EbayBrowseApi::class.java)
    private val clientId = System.getenv("EBAY_CLIENT_ID")?.takeIf { it.isNotBlank() }
    private val clientSecret = System.getenv("EBAY_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
    private val sandbox = System.getenv("EBAY_OAUTH_ENV")?.equals("sandbox", true) == true

    private val apiBase = if (sandbox) "https://api.sandbox.ebay.com" else "https://api.ebay.com"
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = clientId != null && clientSecret != null

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMs: Long = 0

    /** Marketplace + currency per eBay site. */
    private fun marketplaceFor(domain: String): Pair<String, Currency> = when {
        domain.endsWith(".de") -> "EBAY_DE" to Currency.EUR
        domain.endsWith(".co.uk") -> "EBAY_GB" to Currency.GBP
        else -> "EBAY_US" to Currency.USD
    }

    suspend fun search(
        client: HttpClient,
        query: SearchQuery,
        platformId: PlatformId,
        domain: String,
        limit: Int,
    ): List<Listing>? {
        if (!isConfigured) return null
        val token = token(client) ?: return null
        val (marketplace, fallbackCurrency) = marketplaceFor(domain)

        val response = client.get("$apiBase/buy/browse/v1/item_summary/search") {
            parameter("q", query.positiveText)
            parameter("limit", limit.coerceIn(1, 200))
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-EBAY-C-MARKETPLACE-ID", marketplace)
        }
        if (!response.status.isSuccess()) {
            log.warn("eBay Browse API {}: {}", response.status, response.bodyAsText().take(200))
            return null
        }
        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val items = root["itemSummaries"]?.jsonArray ?: return emptyList()
        val now = Clock.System.now()

        return items.mapNotNull { el ->
            val o = el.jsonObject
            val externalId = o["itemId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val priceObj = o["price"]?.jsonObject
            val amount = priceObj?.get("value")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val currency = priceObj["currency"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Currency.valueOf(it) }.getOrNull() } ?: fallbackCurrency
            val url = o["itemWebUrl"]?.jsonPrimitive?.contentOrNull ?: "https://www.$domain/itm/$externalId"
            val image = o["image"]?.jsonObject?.get("imageUrl")?.jsonPrimitive?.contentOrNull
            val condition = o["condition"]?.jsonPrimitive?.contentOrNull?.let {
                if (it.equals("New", true)) Condition.NEW else Condition.USED
            }
            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = Money((amount * 100).toLong(), currency),
                imageUrls = listOfNotNull(image?.takeIf { it.startsWith("http") }),
                condition = condition,
                scrapedAt = now,
            )
        }
    }

    private suspend fun token(client: HttpClient): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiryMs - 60_000) return it }
        val id = clientId ?: return null
        val secret = clientSecret ?: return null
        val basic = Base64.getEncoder().encodeToString("$id:$secret".toByteArray())

        val response = client.post("$apiBase/identity/v1/oauth2/token") {
            header(HttpHeaders.Authorization, "Basic $basic")
            header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            setBody("grant_type=client_credentials&scope=${"https://api.ebay.com/oauth/api_scope".encodeURLParameter()}")
        }
        if (!response.status.isSuccess()) {
            log.warn("eBay OAuth {}: {}", response.status, response.bodyAsText().take(200))
            return null
        }
        val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val tok = obj["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 7200
        cachedToken = tok
        tokenExpiryMs = now + expiresIn * 1000L
        return tok
    }
}
