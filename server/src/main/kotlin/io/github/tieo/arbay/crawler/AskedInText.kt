package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money

/**
 * The price a seller writes into the ad's text, where it is not the one the market lists.
 *
 * A market's price field is whatever the seller typed into it, and some type a token amount to
 * be found among the cheap offers: Subito lists "LAGLER HUMMEL" at 24 € while its text reads
 * "Prezzo 2400€". Only a price the text labels as the ask counts, never a new price ("Neupreis",
 * "NP", "prezzo nuovo") or a figure standing alone, which is as often a power rating or a year.
 */
object AskedInText {
    private val LABELLED = Regex(
        """(?<![\p{L}])(prezzo|preis|price|prix|precio|prijs|festpreis|verkaufspreis|vb|vhb)\s*[:=]?\s*(€\s*)?(\d{1,3}(?:[.\s]\d{3})+|\d+)(?:,\d{1,2})?\s*(€|eur\b|euro)?""",
        RegexOption.IGNORE_CASE,
    )

    /** The ask [text] labels, in whole euros; null where it labels none, or several that differ. */
    fun price(text: String): Long? {
        val asks = LABELLED.findAll(text)
            // With its currency, before or after: "Preis 2019 gekauft" names a year.
            .filter { m -> m.groupValues[2].isNotBlank() || m.groupValues[4].isNotBlank() }
            .mapNotNull { m -> m.groupValues[3].replace(".", "").replace(" ", "").toLongOrNull() }
            .filter { it > 0 }
            .distinct().toList()
        return asks.singleOrNull()
    }

    /** How many times the listed price the text's ask has to be before the listed one is a token. */
    const val TOKEN_FACTOR = 3
}

/** A listing whose own text asks far more than its market lists carries the asked price. */
internal fun List<Listing>.markAskedInText(): List<Listing> = map { l ->
    val text = l.description ?: return@map l
    val asked = AskedInText.price(text) ?: return@map l
    if (l.price.currency != Currency.EUR || asked * 100 < l.price.amount * AskedInText.TOKEN_FACTOR) l
    else l.copy(price = Money(asked * 100, Currency.EUR))
}
