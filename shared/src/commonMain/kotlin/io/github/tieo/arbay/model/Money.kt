package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Money(
    val amount: Long,
    val currency: Currency = Currency.EUR,
) {
    companion object {
        fun cents(cents: Long, currency: Currency = Currency.EUR) = Money(cents, currency)

        /** One written number: digits, and the separators that sit between digits. */
        private val AMOUNT = Regex("""\d+(?:[ \u00A0.,]\d+)*""")

        fun parse(text: String): Money? {
            val currency = when {
                "€" in text || "EUR" in text -> Currency.EUR
                "$" in text || "USD" in text -> Currency.USD
                "CHF" in text -> Currency.CHF
                "£" in text || "GBP" in text -> Currency.GBP
                "zł" in text || "PLN" in text -> Currency.PLN
                "Kč" in text || "CZK" in text -> Currency.CZK
                "DKK" in text -> Currency.DKK
                "SEK" in text -> Currency.SEK
                "NOK" in text -> Currency.NOK
                else -> Currency.EUR
            }
            return parse(text, currency)
        }

        /** Parse the numeric amount from [text] and attach [currency] regardless of any
         *  symbol in the text. Used by crawlers on sites with a single known currency
         *  whose symbol ("kr") is ambiguous across markets. */
        fun parse(text: String, currency: Currency): Money? {
            // One number, the first one, with only the characters that belong to it. Removing every
            // non-digit from the whole text instead glued separate numbers together: an eBay card
            // titled "Kein Versand !!!!Apple iPhone 11 Schwarz 64GB A2221 MWLT2ZD/A" produced a
            // delivery charge of 116422212, which is 11, 64, 2221 and 2 run into one number.
            // A space inside a number stays, since "49 000 kr" writes its thousands that way.
            val token = AMOUNT.find(text)?.value ?: return null
            // Trailing/leading separators are not part of the number: "469.995 kr." strips to
            // "469.995." and "kr.-" style suffixes leave a dangling dot that breaks the parse.
            val stripped = token.replace(Regex("[^\\d.,]"), "").trim('.', ',')
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
    EUR, USD, CHF, GBP, PLN, DKK, SEK, CZK, NOK, RSD
}
