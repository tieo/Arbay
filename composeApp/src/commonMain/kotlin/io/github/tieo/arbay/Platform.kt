package io.github.tieo.arbay

expect fun openBrowser(url: String)

expect fun loadBannedIds(): Set<String>
expect fun saveBannedIds(ids: Set<String>)

expect fun loadBlockedTerms(query: String): Set<String>
expect fun saveBlockedTerms(query: String, terms: Set<String>)

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
