package io.github.tieo.arbay.crawler

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

@Serializable
data class ExchangeRateResponse(
    val rates: Map<String, Double>,
    val base: String = "EUR",
)

object ExchangeRates {
    private val log = LoggerFactory.getLogger(ExchangeRates::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient()

    private val SUPPORTED = setOf("EUR", "USD", "GBP", "CHF", "PLN", "DKK", "SEK", "CZK", "NOK", "RSD")

    // Until the first refresh, the same approximations the app falls back to.
    @Volatile var rates: Map<String, Double> = io.github.tieo.arbay.FALLBACK_RATES_PER_EUR
        private set
    @Volatile var lastUpdate: Long = 0

    suspend fun refresh() {
        try {
            val response: String = client.get("https://open.er-api.com/v6/latest/EUR").body()
            val parsed = json.parseToJsonElement(response).jsonObject
            val fetchedRates = parsed["rates"]?.jsonObject?.mapValues { it.value.jsonPrimitive.double } ?: return
            rates = fetchedRates.filterKeys { it in SUPPORTED } + ("EUR" to 1.0)
            lastUpdate = System.currentTimeMillis()
            log.info("Exchange rates updated: {}", rates)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.warn("Failed to fetch exchange rates: {}", e.message)
        }
    }

    fun convert(amountCents: Long, from: String, to: String): Long {
        if (from == to) return amountCents
        val fromRate = rates[from] ?: return amountCents
        val toRate = rates[to] ?: return amountCents
        return (amountCents.toDouble() / fromRate * toRate).toLong()
    }
}
