package io.github.tieo.arbay.relevance

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.EU_VAT_AREA
import io.github.tieo.arbay.model.STANDARD_VAT
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.originCountry

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
    // VAT left out, as a Dutch or English ad says it: "prijzen zijn ex. BTW", "excl. BTW",
    // "exclusief btw", "excl. VAT", "plus VAT".
    private const val EX_VAT = """(?:ex(?:cl)?\.?|exclusief)\s*btw\b|\b(?:ex(?:cl)?\.?|excluding|plus)\s*vat\b"""
    // VAT put in: "Brutto", "incl. BTW", "inc btw", "inclusief btw".
    private const val WITH_VAT = """(?:brutto|inc(?:l(?:usief)?)?\.?\s*btw)"""
    private const val AMOUNT = """(\d{1,3}(?:[.\s]\d{3})+|\d{3,7})"""
    // What may stand between an amount and the word after it: "1.195,- excl. btw", "42.338 euro inc btw".
    private const val UNIT = """(?:,-|,00)?\s*(?:€|euro?)?"""

    // The asking price called net, and the gross beside it: "12.000€ Nettopreis (Brutto 14.280€)",
    // "12.000 € netto, 14.280 € brutto", "Brutto: 14.280 €", "€ 32.138 incl. BTW".
    private val net = Regex("""\b(?:netto\w*|zzgl\.?\s*(?:19\s*%\s*)?mwst\.?|zuzüglich\s+(?:der\s+)?mwst|$EX_VAT)""", RegexOption.IGNORE_CASE)
    private val gross = Regex("""$WITH_VAT\W{0,4}$AMOUNT|$AMOUNT$UNIT\s*$WITH_VAT""", RegexOption.IGNORE_CASE)

    // The asking price said to have VAT on top, with no gross written anywhere: "Preis Netto zzgl
    // MwSt.", "Preis zzgl. 19% MwSt". A net figure of its own ("Ihr Nettopreis 20.672 €" under a
    // gross asking price of 23,900 €) is no such statement, and an export price ("Export Preis
    // netto: 15.950,- E") is a net price for another buyer.
    // A Dutch dealer's "prijzen zijn ex. BTW" says the same of every price in the ad, and an
    // Austrian one's "Alle Preise sind NETTO und FIXPREISE!" too.
    private val priceCalledNet = Regex(
        """(?<!export\W{0,3})\b(?:preis|netto)\W{0,3}(?:netto\W{0,3})?(?:zzgl\.?|zuzüglich)\s*(?:\d+\s*%\s*)?mwst|(?<!export\W{0,3})\b$EX_VAT|""" +
            """\b(?:alle\s+)?preise?\s+(?:sind|ist|verstehen\s+sich)\s+(?:alle\s+)?netto\b""",
        RegexOption.IGNORE_CASE,
    )
    // A net amount written out: "netto: 15.950,-", "Nettopreis 20.672 €", "15.950 € netto",
    // "€ 21.950 excl. BTW", and a warranty's "12 maanden € 495,- excl. btw", which says the van's
    // own price is the gross.
    private val statedNet = Regex("""netto\w*\W{0,4}$AMOUNT|$AMOUNT$UNIT\s*(?:netto|$EX_VAT)""", RegexOption.IGNORE_CASE)
    // VAT in the price already, or none to pay on it: a margin-scheme van ("marge") carries no
    // VAT a buyer adds, and one whose VAT is "verrekenbaar" has it inside the price.
    private val vatIncluded = Regex("""mwst\.?\s*ausweisbar|inkl\.?\s*(?:19\s*%\s*)?mwst|inc(?:l(?:usief)?)?\.?\s*btw|btw[\s-]*verrekenbaar|\bmarge\b""", RegexOption.IGNORE_CASE)

    // The gearbox the text names under its own heading or by its gears: "Getriebe\n- 6-Gang-
    // Schaltgetriebe", "Getriebe: Schaltgetriebe", "8-Gang-Automatikgetriebe". Equipment that only
    // works automatically ("Start-Stop-Automatik") names no gearbox.
    private val statedManual = Regex("""\bgetriebe\W{0,6}(?:\d-gang-)?schaltgetriebe\b|\b\d-gang-schaltgetriebe\b""", RegexOption.IGNORE_CASE)
    private val statedAutomatic = Regex("""\bgetriebe\W{0,6}(?:\d-gang-)?automatik|\b\d-gang-automatik|\bautomatikgetriebe\b|\bdsg\b""", RegexOption.IGNORE_CASE)

    // Damage the text states outright: "Das Fahrzeug hat einen leichtenSeitenschaden!", "Unfallwagen",
    // "Fahrzeug mit Beschädigungen siehe Bilder". Denied or repaired it is none: "kein Unfallschaden",
    // "Unfallschaden: Nein", "Unfallschaden: Unfallfrei", "Reparierter Unfallschaden", "Hagelschaden
    // fachgerecht behoben".
    private val statedDamage = Regex(
        """(?<!kein\s)(?<!keine\s)(?<!keinen\s)(?<!ohne\s)(?<!nicht\s)(?<!repariert\w{0,3}\s)(?<!behoben\w{0,3}\s)(?:""" +
            // Run into the word before it as typed: "einen leichtenSeitenschaden".
            """(?:unfall|seiten|front|heck|hagel|blech|karosserie|motor|getriebe)schaden|\bunfall(?:fahrzeug|wagen|auto)|""" +
            // A wreck's own list: "Motor defekt, Airbags offen, Kühler defekt".
            """\b(?:motor|getriebe)\s+(?:ist\s+)?defekt|\bairbags?\s+(?:sind\s+|ist\s+)?(?:offen|ausgel(?:ö|oe)st)|""" +
            """\bfahrzeug\s+mit\s+(?:besch(?:ä|ae)digungen|sch(?:ä|ae)den))\b""" +
            """(?!\W{0,3}(?:nein|keine?n?|(?:unfall)?frei|behoben|repariert|instand\w*|beseitigt|fachgerecht|wurde))""",
        RegexOption.IGNORE_CASE,
    )

    private fun number(s: String) = s.replace(Regex("""[.\s]"""), "").toIntOrNull()

    /** [apply] over the ad's whole text where its page was read before, since a card's snippet
     *  rarely reaches the price line; the listing keeps its own description. */
    fun applyWithPageText(listing: Listing): Listing {
        val page = io.github.tieo.arbay.markets.DetailCache.get(listing.id)?.description
        if (page == null || page.length <= (listing.description?.length ?: 0)) return apply(listing)
        return apply(listing.copy(description = page)).copy(description = listing.description)
    }

    fun apply(listing: Listing): Listing {
        val text = "${listing.title}\n${listing.description.orEmpty()}"
        return listing.withStatedPrice(text).withStatedMileage(text).withStatedGearbox(text).withStatedDamage(text).withGrossPrice(text).withDealerExtras(text)
    }

    // A dealer's charge on top of the price, named and priced: "Überführungskosten: 990 €",
    // "zzgl. Überführung 1.190,- €", "Zulassungspaket 249 €", "Bereitstellungskosten von 899 EUR".
    // "inkl. Überführung" names one already in the price.
    private val dealerExtra = Regex(
        """(?<!\p{L})(überführung|ueberfuehrung|zulassung|bereitstellung|auslieferung)\w*""" +
            """\W{0,4}(?:(?:kosten|gebühr|pauschale|paket)\W{0,4})?(?:von|in\s+höhe\s+von|i\.\s?h\.\s?v\.|beträgt|betragen)?\W{0,4}(?:€\s*)?""" +
            """(\d{1,2}\.\d{3}|\d{2,4})(?:,-|,00)?\s*(?:€|eur\b|euro\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val includedBefore = Regex("""(?i)\b(inkl|inklusive|incl|einschließlich|samt)\b\.?[^.\n]*$""")
    private val includedAfter = Regex("""(?i)^[^.\n]*\b(enthalten|inklusive|inbegriffen)\b""")

    /** What the dealer charges on top of the asking price where the text names it, each kind once
     *  (a transfer and a registration both count). Read only for vehicles, and a sum from 50 to
     *  3,000 € each: a larger "Überführung" figure is a price of something else. */
    private fun Listing.withDealerExtras(text: String): Listing {
        if (vehicle == null || extraCosts != null || price.currency != Currency.EUR) return this
        val byKind = dealerExtra.findAll(text).filterNot { m ->
            // "inkl. Überführung und Zulassung 990 €", "Überführung 990 € im Preis enthalten".
            includedBefore.containsMatchIn(text.substring((m.range.first - 30).coerceAtLeast(0), m.range.first)) ||
                includedAfter.containsMatchIn(text.substring(m.range.last + 1, (m.range.last + 30).coerceAtMost(text.length)))
        }.mapNotNull { m ->
            val euros = number(m.groupValues[2]) ?: return@mapNotNull null
            if (euros !in 50..3_000) return@mapNotNull null
            m.groupValues[1].lowercase().replace("ue", "ü").take(5) to (euros to m.value.trim())
        }.distinctBy { it.first }.toList()
        if (byKind.isEmpty()) return this
        return copy(
            extraCosts = Money(byKind.sumOf { it.second.first } * 100L, Currency.EUR),
            extraCostsSaid = byKind.joinToString("; ") { it.second.second },
        )
    }

    /** Damage the text states where the form says the vehicle is whole: an Amstetten Crafter at
     *  15,000 € was "used" on AutoScout24, and its text said "Das Fahrzeug hat einen leichten
     *  Seitenschaden! Preis ohne Schaden: 21.000 €". */
    private fun Listing.withStatedDamage(text: String): Listing {
        val v = vehicle ?: return this
        if (v.condition == io.github.tieo.arbay.model.VehicleCondition.DAMAGED || !statedDamage.containsMatchIn(text)) return this
        return copy(vehicle = v.copy(condition = io.github.tieo.arbay.model.VehicleCondition.DAMAGED, verified = v.verified - VehicleField.CONDITION))
    }

    // An amount in euros as the text writes it: "56.999€ VB", "52.000 €", "24900 Euro".
    private val statedEuros = Regex("""(\d{1,3}(?:\.\d{3})+|\d{4,7})(?:,-|,00)?\s*(?:€|eur\b|euro\b)""", RegexOption.IGNORE_CASE)

    /** The price the text asks where the form's price only fills the field: digits counted up from
     *  one ("1234", "12345"). A mobile.de Grand California 600 at 12,345 € read "56.999€ VB bei
     *  zeitnaher Übernahme 52.000€ möglich", and ranked among 25,000 € vans. */
    private fun Listing.withStatedPrice(text: String): Listing {
        if (price.currency != Currency.EUR) return this
        val asked = price.amount / 100
        val digits = asked.toString()
        if (price.amount % 100 != 0L || digits.length < 4 || !"123456789".startsWith(digits)) return this
        val stated = statedEuros.findAll(text).mapNotNull { m -> number(m.groupValues[1]) }.firstOrNull { it > asked } ?: return this
        return copy(price = Money(stated * 100L, Currency.EUR))
    }

    private fun Listing.withStatedMileage(text: String): Listing {
        val v = vehicle ?: return this
        val stated = statedMileage.find(text)?.groupValues?.get(1)?.let(::number)?.takeIf { it in 1..2_000_000 } ?: return this
        if (stated == v.mileageKm) return this
        return copy(vehicle = v.copy(mileageKm = stated, verified = v.verified - VehicleField.MILEAGE))
    }

    /** The gearbox the text states where the form says the other: a Fellbach dealer's Crafter read
     *  automatic on AutoScout24, mobile.de and Kleinanzeigen, and its equipment list said "6-Gang-
     *  Schaltgetriebe". Of the archived ads whose form said automatic and whose text stated a manual,
     *  every one was a manual. A text naming both says nothing. */
    private fun Listing.withStatedGearbox(text: String): Listing {
        val v = vehicle ?: return this
        val manual = statedManual.containsMatchIn(text)
        val automatic = statedAutomatic.containsMatchIn(text)
        val stated = when {
            manual && !automatic -> io.github.tieo.arbay.model.Transmission.MANUAL
            automatic && !manual -> io.github.tieo.arbay.model.Transmission.AUTOMATIC
            else -> return this
        }
        if (v.gearbox == null || v.gearbox == stated) return this
        return copy(vehicle = v.copy(gearbox = stated, verified = v.verified - VehicleField.GEARBOX))
    }

    /** The gross a private buyer pays, where the text calls the asking price net: the gross it
     *  states, within VAT's reach of the asking price, else the asking price with the VAT the
     *  seller's country charges a private buyer: the buyer's own rate at home, the seller's
     *  standard rate elsewhere in the EU. Outside the EU the import VAT on the landed price is the
     *  tax. A Bassenheim Crafter at 27,900 € said only "Preis Netto zzgl MwSt.", and cost 33,201 €;
     *  an Enkhuizen dealer's at 26,560 € said "prijzen zijn ex. BTW", and cost 32,138 €. */
    private fun Listing.withGrossPrice(text: String): Listing {
        if (netPrice != null || price.currency != Currency.EUR || !net.containsMatchIn(text)) return this
        val settings = io.github.tieo.arbay.repo.ImportSettingsStore.current
        val home = settings.homeCountry.uppercase()
        val origin = originCountry(home).uppercase()
        val percent = when {
            origin == home -> settings.importVatPercent.toDouble()
            origin in EU_VAT_AREA -> STANDARD_VAT[origin]?.times(100) ?: return this
            else -> return this
        }
        val asked = price.amount / 100
        val stated = gross.findAll(text).mapNotNull { m -> number(m.groupValues[1].ifEmpty { m.groupValues[2] }) }.toList()
        stated.firstOrNull { it > asked && it <= asked * (1 + percent / 100) * 1.01 }?.let { return copy(price = Money(it * 100L, Currency.EUR), netPrice = price) }
        if (stated.isNotEmpty() || vatIncluded.containsMatchIn(text) || !priceCalledNet.containsMatchIn(text)) return this
        // A net amount below the asking price makes the asking price the gross.
        if (statedNet.findAll(text).any { m -> number(m.groupValues[1].ifEmpty { m.groupValues[2] })?.let { it < asked } == true }) return this
        if (!settings.enabled) return this
        return copy(price = Money(price.amount + Math.round(price.amount * percent / 100), Currency.EUR), netPrice = price)
    }
}
