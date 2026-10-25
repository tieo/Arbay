package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.RentPeriod
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery

/**
 * Whether an offer hires the thing out rather than selling it, read the same way on every market:
 * a Kleinanzeigen ad "Parkettschleifmaschine - ZU VERMIETEN" is listed at a fixed price like any
 * sale, and a shop's "Parkett Eckenschleifer zur Miete" is read off its page like any product. Its
 * price is a rate, not what the thing costs to buy.
 */
object Rentals {
    /** Words saying the thing is hired out: "mieten", "zu vermieten", "Vermiete …", "zum Verleih". */
    private val HIRED_OUT = Regex(
        """mieten\b|vermietung|\bvermiete\b|verleih|\bzu\s+leihen\b|\bleihen\b|zur.miete|mietger|\brental\b|\bfor.(hire|rent)\b""",
    )

    /** A machine sold out of a rental fleet is sold: "Rüttelplatte aus der Vermietung". */
    private val SOLD_FROM_FLEET = Regex("""aus.(der.)?(vermietung|miete)|ex.?(vermietung|miet)|mietr(ü|ue)ckl(ä|ae)ufer|gebraucht""")

    /** A site named for hiring out at its start (miet24.de, rentinorio.de) hires out what it lists;
     *  a Verleih elsewhere in the name (verleihnix.de) may sell the consumables beside its machines. */
    private val RENTAL_SITE = Regex("""^(miet|rent(al|in|o))|rental""")

    /** The stretch of time a rental's price is stated for, in the words sellers use. */
    private val PERIODS = listOf(
        RentPeriod.HALF_DAY to Regex("""halber?\s+tag|halbtag|\b4\s*(std|stunden)\b"""),
        RentPeriod.WEEKEND to Regex("""wochenende|\bwe[-\s]?(preis|miete|tarif)"""),
        RentPeriod.WEEK to Regex("""pro\s+woche|/\s?woche\b|wochenmiete|wochenpreis|je\s+woche"""),
        RentPeriod.MONTH to Regex("""pro\s+monat|/\s?monat\b|monatsmiete|im\s+monat"""),
        RentPeriod.DAY to Regex("""pro\s+tag|/\s?tag\b|am\s+tag|je\s+tag|tagesmiet|tagespreis|mietpreis.{0,12}tag|\b24\s*h\b|täglich"""),
    )

    /** Whether [text] (an offer's title, and its address where it has one) says it is hired out. */
    fun hiredOut(text: String): Boolean {
        val said = text.lowercase()
        return HIRED_OUT.containsMatchIn(said) && !SOLD_FROM_FLEET.containsMatchIn(said)
    }

    /** Whether a site is named for hiring out, by its name without the country ("miet24"). */
    fun rentalSite(siteName: String): Boolean = RENTAL_SITE.containsMatchIn(siteName.lowercase())

    /** The period [text] states a rental's price for, the first one it names; null where it names none. */
    fun periodStated(text: String): RentPeriod? {
        val said = text.lowercase()
        return PERIODS.mapNotNull { (period, words) -> words.find(said)?.let { it.range.first to period } }
            .minByOrNull { it.first }?.second
    }
}

/**
 * A listing whose title or address says it is hired out carries that as its sale type, with the
 * period its price is for where the title or text states one. Only a period the ad states: a rate
 * read as a day's that is a weekend's would compare wrongly with every other rental. A flat or a
 * house to let is what a real-estate search looks for, and is left as the market lists it.
 */
internal fun List<Listing>.markRented(query: SearchQuery): List<Listing> = if (query.category == MarketGroup.REAL_ESTATE) this else map { l ->
    if (l.saleType == SaleType.RENT || !Rentals.hiredOut(l.title + " " + l.url.substringBefore('?'))) l
    else l.copy(
        saleType = SaleType.RENT,
        rentPer = l.rentPer ?: Rentals.periodStated(l.title) ?: l.description?.let(Rentals::periodStated),
    )
}
