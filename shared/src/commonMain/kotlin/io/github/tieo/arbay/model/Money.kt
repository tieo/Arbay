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
            val currency = when {
                "€" in text || "EUR" in text -> Currency.EUR
                "$" in text || "USD" in text -> Currency.USD
                "CHF" in text -> Currency.CHF
                "£" in text || "GBP" in text -> Currency.GBP
                else -> Currency.EUR
            }

            val stripped = text.replace(Regex("[^\\d.,]"), "")
            if (stripped.isBlank()) return null

            val hasDot = '.' in stripped
            val hasComma = ',' in stripped

            // Determine which character (if any) is the decimal separator.
            // Rules:
            //   1) Both dot and comma present → the LAST one is the decimal separator
            //      "1.234,56" → comma is decimal (EU)   "1,234.56" → dot is decimal (US)
            //   2) Only comma → decimal if ≤2 digits follow it: "99,99" = 99.99; "1,234" = 1234
            //   3) Only dot   → decimal if ≤2 digits follow it: "99.99" = 99.99; "1.234" = 1234
            //   4) Neither    → whole number
            val normalized: String = when {
                hasDot && hasComma -> {
                    val lastDot = stripped.lastIndexOf('.')
                    val lastComma = stripped.lastIndexOf(',')
                    if (lastComma > lastDot) {
                        // comma is decimal: "1.234,56" → "1234.56"
                        stripped.replace(".", "").replace(',', '.')
                    } else {
                        // dot is decimal: "1,234.56" → "1234.56"
                        stripped.replace(",", "")
                    }
                }
                hasComma -> {
                    val afterComma = stripped.substringAfterLast(',')
                    if (afterComma.length <= 2) {
                        // "99,99" → "99.99"  (decimal comma)
                        stripped.replace(",", ".")
                    } else {
                        // "1,234" → "1234"  (thousands comma)
                        stripped.replace(",", "")
                    }
                }
                hasDot -> {
                    val afterDot = stripped.substringAfterLast('.')
                    if (afterDot.length <= 2) {
                        // "99.99" → keep as-is (decimal dot)
                        stripped
                    } else {
                        // "1.234" → "1234"  (thousands dot)
                        stripped.replace(".", "")
                    }
                }
                else -> stripped
            }

            val amount = normalized.toDoubleOrNull() ?: return null
            return Money((amount * 100).toLong(), currency)
        }
    }
}

@Serializable
enum class Currency {
    EUR, USD, CHF, GBP
}
