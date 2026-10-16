package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.VehicleField

/**
 * What a seller wrote in the ad over what they ticked or typed into the market's form.
 *
 * A form field is filled in once and quickly; the text is what the seller says about the thing.
 * Live, 2026-10-16: a Kleinanzeigen Crafter's mileage field read 29,700 km and its text
 * "Laufleistung: 297000", and its price 12,000 € with the text "12.000€ Nettopreis (Brutto
 * 14.280€)". Read off the form, a van with ten times the mileage and 2,280 € more to pay ranked
 * second of a hundred.
 */
object SellersWords {
    // The mileage under its own label, with or without a unit: "Laufleistung: 297000",
    // "Kilometerstand 185.000 km", "km-Stand: 98.500".
    private val statedMileage = Regex(
        """(?:laufleistung|kilometerstand|km[- ]?stand|tachostand)\W{0,4}(?:ca\.?\s*)?(\d{1,3}(?:[.\s]\d{3})+|\d{3,7})(?!\s*(?:€|eur|,-))""",
        RegexOption.IGNORE_CASE,
    )
    // The asking price called net, and the gross beside it: "12.000€ Nettopreis (Brutto 14.280€)",
    // "12.000 € netto, 14.280 € brutto", "Brutto: 14.280 €".
    private val net = Regex("""\b(?:netto\w*|zzgl\.?\s*(?:19\s*%\s*)?mwst\.?|zuzüglich\s+(?:der\s+)?mwst)""", RegexOption.IGNORE_CASE)
    private val gross = Regex("""brutto\W{0,4}(\d{1,3}(?:[.\s]\d{3})+|\d{3,7})|(\d{1,3}(?:[.\s]\d{3})+|\d{3,7})\s*(?:€|eur)?\s*brutto""", RegexOption.IGNORE_CASE)

    private fun number(s: String) = s.replace(Regex("""[.\s]"""), "").toIntOrNull()

    fun apply(listing: Listing): Listing {
        val text = "${listing.title}\n${listing.description.orEmpty()}"
        return listing.withStatedMileage(text).withGrossPrice(text)
    }

    private fun Listing.withStatedMileage(text: String): Listing {
        val v = vehicle ?: return this
        val stated = statedMileage.find(text)?.groupValues?.get(1)?.let(::number)?.takeIf { it in 1..2_000_000 } ?: return this
        if (stated == v.mileageKm) return this
        return copy(vehicle = v.copy(mileageKm = stated, verified = v.verified - VehicleField.MILEAGE))
    }

    /** The gross a private buyer pays, where the text calls the asking price net and states it.
     *  Only a gross within VAT's reach of the asking price counts: up to a fifth more. */
    private fun Listing.withGrossPrice(text: String): Listing {
        if (price.currency != Currency.EUR || !net.containsMatchIn(text)) return this
        val asked = price.amount / 100
        val brutto = gross.findAll(text).mapNotNull { m -> number(m.groupValues[1].ifEmpty { m.groupValues[2] }) }
            .firstOrNull { it > asked && it <= asked * 1.2 } ?: return this
        return copy(price = Money(brutto * 100L, Currency.EUR))
    }
}
