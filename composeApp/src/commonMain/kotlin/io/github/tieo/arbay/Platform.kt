package io.github.tieo.arbay

expect fun openBrowser(url: String)

/**
 * Returns a function that, when invoked, detects the device's current city
 * and calls [onCity] with the result (null if unavailable or permission denied).
 */
@androidx.compose.runtime.Composable
expect fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit

expect fun loadBannedIds(): Set<String>
expect fun saveBannedIds(ids: Set<String>)

/** Show a local notification for new free item matches. No-op on unsupported platforms. */
expect fun showMatchNotification(title: String, body: String)

/** Schedule/reschedule background polling with given interval. No-op on non-Android. */
expect fun schedulePolling(intervalMinutes: Int)

/** Cancel background polling. No-op on non-Android. */
expect fun cancelPolling()

/** Global display currency + exchange rates for the app */
object DisplayCurrency {
    var current: String = "EUR"
    var rates: Map<String, Double> = mapOf("EUR" to 1.0, "USD" to 1.10, "GBP" to 0.86, "CHF" to 0.95)

    fun convert(amountCents: Long, fromCurrency: String): Long {
        if (fromCurrency == current) return amountCents
        val fromRate = rates[fromCurrency] ?: return amountCents
        val toRate = rates[current] ?: return amountCents
        return (amountCents.toDouble() / fromRate * toRate).toLong()
    }
}
