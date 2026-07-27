package io.github.tieo.arbay

expect fun openBrowser(url: String)

/**
 * Returns a function that, when invoked, detects the device's current city
 * and calls [onCity] with the result (null if unavailable or permission denied).
 */
@androidx.compose.runtime.Composable
expect fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit

/** Returns a trigger that fetches the device's coordinates (for nearest-first car results) and calls
 *  back with lat/lon, or (null, null) if unavailable/denied. */
@androidx.compose.runtime.Composable
expect fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit

expect fun loadBannedIds(): Set<String>
expect fun saveBannedIds(ids: Set<String>)

/** Settings that belong to this device rather than to the server it talks to: which server that is,
 *  and which currency to show prices in. Kept here so a choice made in Settings survives a restart. */
expect fun loadDeviceSettings(): Map<String, String>
expect fun saveDeviceSettings(settings: Map<String, String>)

/** Show a local notification for new free item matches. No-op on unsupported platforms. */
expect fun showMatchNotification(title: String, body: String)

/** Schedule/reschedule background polling with given interval. No-op on non-Android. */
expect fun schedulePolling(intervalMinutes: Int)

/** Cancel background polling. No-op on non-Android. */
expect fun cancelPolling()

/** Global display currency + exchange rates for the app */
object DisplayCurrency {
    var current: String = loadDeviceSettings()["currency"] ?: "EUR"
    // Units per 1 EUR. Covers every currency the crawlers can return, so a listing from a
    // cross-border market converts sensibly even before the live rates load (or if that fetch
    // fails) — a missing rate would otherwise render, say, 169 900 PLN as "€169,900".
    // Overwritten at startup by the server's live rates.
    var rates: Map<String, Double> = mapOf(
        "EUR" to 1.0, "USD" to 1.10, "GBP" to 0.86, "CHF" to 0.95,
        "PLN" to 4.32, "SEK" to 11.0, "DKK" to 7.46, "CZK" to 24.2, "NOK" to 11.07,
    )

    /** True when the amount can be shown in [current] without mislabelling — both currencies
     *  have a known rate (or they are the same). */
    fun canConvert(fromCurrency: String): Boolean =
        fromCurrency == current || (rates.containsKey(fromCurrency) && rates.containsKey(current))

    fun convert(amountCents: Long, fromCurrency: String): Long {
        if (fromCurrency == current) return amountCents
        val fromRate = rates[fromCurrency] ?: return amountCents
        val toRate = rates[current] ?: return amountCents
        return (amountCents.toDouble() / fromRate * toRate).toLong()
    }
}
