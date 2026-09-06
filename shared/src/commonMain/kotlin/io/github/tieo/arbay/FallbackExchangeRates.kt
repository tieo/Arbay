package io.github.tieo.arbay

/**
 * Units per 1 EUR, used only until live rates arrive.
 *
 * Both sides convert prices — the server when it filters and compares, the app when it shows them —
 * and each used to carry its own approximate table. They disagreed (CHF 0.95 against 0.94, NOK
 * 11.07 against 11.70, and so on), so before the first refresh the same listing could be worth two
 * different amounts depending on which side did the arithmetic. One table, one answer.
 *
 * These are approximations of a real rate and go out of date on their own; they exist so a price is
 * never shown wildly wrong (169 900 PLN as "€169,900") while the live rates are still on their way.
 */
val FALLBACK_RATES_PER_EUR: Map<String, Double> = mapOf(
    "EUR" to 1.0,
    "USD" to 1.16,
    "GBP" to 0.86,
    "CHF" to 0.94,
    "PLN" to 4.31,
    "DKK" to 7.47,
    "SEK" to 11.11,
    "CZK" to 24.20,
    "NOK" to 10.81,
    "RSD" to 117.34,
)
