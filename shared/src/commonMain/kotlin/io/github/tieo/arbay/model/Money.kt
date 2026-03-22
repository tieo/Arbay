package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Money(
    val amount: Long,
    val currency: Currency = Currency.EUR,
) {
    companion object {
        fun cents(cents: Long, currency: Currency = Currency.EUR) = Money(cents, currency)

        fun parse(text: String): Money? {
            val cleaned = text
                .replace(".", "")
                .replace(",", ".")
                .replace(" ", "")

            val amount = Regex("""(\d+(?:\.\d{1,2})?)""")
                .find(cleaned)
                ?.groupValues?.get(1)
                ?.toDoubleOrNull()
                ?: return null

            val currency = when {
                "€" in text || "EUR" in text -> Currency.EUR
                "$" in text || "USD" in text -> Currency.USD
                "CHF" in text -> Currency.CHF
                "£" in text || "GBP" in text -> Currency.GBP
                else -> Currency.EUR
            }

            return Money((amount * 100).toLong(), currency)
        }
    }
}

@Serializable
enum class Currency {
    EUR, USD, CHF, GBP
}
