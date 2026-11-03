package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.neuland.Neuland
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** Euro rates of the currencies the markets ask in, as Neuland reads them (units per euro). */
object ExchangeRates {
    private val log = LoggerFactory.getLogger(ExchangeRates::class.java)

    private val SUPPORTED = setOf("EUR", "USD", "GBP", "CHF", "PLN", "DKK", "SEK", "CZK", "NOK", "RSD")

    // Until the first refresh, the same approximations the app falls back to.
    @Volatile var rates: Map<String, Double> = io.github.tieo.arbay.FALLBACK_RATES_PER_EUR
        private set
    @Volatile var lastUpdate: Long = 0

    suspend fun refresh() {
        try {
            val fetched = Neuland.request(HttpMethod.Get, "rates", limitS = 60).jsonObject["rates"]?.jsonObject
                ?.mapValues { it.value.jsonPrimitive.double } ?: return
            rates = fetched.filterKeys { it in SUPPORTED } + ("EUR" to 1.0)
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
