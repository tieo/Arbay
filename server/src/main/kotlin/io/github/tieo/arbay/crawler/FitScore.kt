package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Drivetrain
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Transmission
import kotlin.time.Clock

/**
 * How well one offer fits a search as a whole, from 0 to 1.
 *
 * A search's vehicle criteria are read as wishes, each scored from 0 (missed by a lot) to 1 (met)
 * and weighed against the others, because a buyer trades them off: a van over budget can still be
 * the best one if everything else is right, and one that scrapes past every filter with several
 * small misses is not. Only a few things rule an offer out entirely: it is not the kind of vehicle
 * wanted at all, it is offered to traders or for export only, its dealer has under four stars
 * on the market's own reviews, or it shows another ad's photos.
 *
 * The weights were set with the user on a real search (a Crafter): the van's size first, then the
 * price, where every euro counts, then gearbox, mileage, age and drive; the drive to the seller
 * counts for little, since it is made once or twice.
 */
object FitScore {

    data class Fit(
        val score: Double,
        /** Hours by road from home, estimated from the straight-line distance. */
        val driveHours: Double?,
        /** The wishes this offer misses, in the user's words, for the notification. */
        val misses: List<String>,
        val airConditioning: Boolean?,
    )

    private val notAClosedVan = Regex(
        """(?i)pritsche|kipper|koffer|ladebordwand|plane|autotransporter|blitzlader|abschlepp|kühl|frigo|sp(?:ü|ue)lwagen|wohnmobil|camper|california|womo|ausbau|rollstuhl|doka|doppelkabine""",
    )
    // "Nur an Gewerbe", "nur Export", "Verkauf ausschließlich für Export oder an Gewerbetreibende";
    // the buyer named, so a van "nur gewerblich genutzt" stays in.
    private val tradeOnly = Regex(
        """(?i)\b(nur|ausschlie(ß|ss)lich)\W{1,3}((an|für|fuer)\W+)?(den\W+)?""" +
            """(gewerbe(treibende|kunden?)?\b|gewerbliche\W+(käufer|kunden|abnehmer)|export|händler|haendler)|export only""",
    )
    private val allWheel = Regex("""(?i)4\s?motion|4mot|allrad|4x4|awd""")
    private val noAirConditioning = Regex("""(?i)keine klimaanlage|(ohne|keine?)\s+klima""")
    private val airConditioning = Regex("""(?i)klimaanlage|klimaautomatik|climatronic|\bklima\b|airco|air ?con""")

    // A straight line is shorter than the road, by about a quarter across Germany, and a long
    // drive there averages about 100 km/h: Stuttgart to Hamburg, 534 km apart, is under seven hours.
    private const val ROAD_FACTOR = 1.25
    private const val AVERAGE_KMH = 100.0

    fun driveHours(distanceKm: Double?): Double? = distanceKm?.let { it * ROAD_FACTOR / AVERAGE_KMH }

    /** 1 at [good], 0 at [bad], straight in between; null stays null (unknown). */
    private fun linear(x: Double?, good: Double, bad: Double): Double? {
        if (x == null) return null
        val t = (x - bad) / (good - bad)
        return t.coerceIn(0.0, 1.0)
    }

    /** The offer's fit to [wishes], or null where it is ruled out. [priceEur] is its landed price. */
    fun score(listing: Listing, wishes: CarFilters, priceEur: Double, softDriveHours: Double): Fit? {
        val text = "${listing.title}\n${listing.description.orEmpty()}"
        // A mobile.de card's title is only make and model; the seller's headline opens the text.
        val headline = "${listing.title} ${CarFilterEngine.headline(listing).orEmpty()}"
        val wantsAVan = wishes.vanLengths.isNotEmpty() || wishes.vanHeights.isNotEmpty()
        if (wantsAVan && notAClosedVan.containsMatchIn(headline)) return null
        if (tradeOnly.containsMatchIn(text)) return null
        // Another ad's photos in this one: the van shown is not the one sold.
        if (listing.photosAlsoIn.isNotEmpty()) return null
        val fuel = listing.vehicle?.fuel
        if (wishes.fuels.isNotEmpty() && fuel != null && fuel !in wishes.fuels) return null
        val seller = listing.seller
        val stars = seller?.rating
        if (stars != null && stars < 4.0) return null

        val v = listing.vehicle
        val misses = mutableListOf<String>()

        // Size: every size the listing could be, against the sizes wanted.
        fun fits(wanted: Set<Int>, low: Int?, high: Int?, what: String): Double? {
            if (wanted.isEmpty()) return null
            if (low == null) return 0.35.also { misses += "$what not stated" }
            val top = high ?: low
            val possible = (low..top).toSet()
            return when {
                possible.all { it in wanted } -> 1.0
                possible.any { it in wanted } -> 0.6.also { misses += "$what not certain" }
                else -> 0.1.also { misses += "other $what" }
            }
        }
        val length = fits(wishes.vanLengths, v?.vanLength, v?.vanLengthMax, "length")
        val height = fits(wishes.vanHeights, v?.vanHeight, v?.vanHeightMax, "roof")
        val size = listOfNotNull(length, height).takeIf { it.isNotEmpty() }?.average()

        val gear = wishes.transmission?.let { wanted ->
            when (v?.gearbox) {
                wanted -> 1.0
                null -> 0.5
                else -> 0.1.also { misses += if (wanted == Transmission.AUTOMATIC) "manual" else "automatic" }
            }
        }
        val drive = wishes.drivetrain?.let { wanted ->
            val stated = v?.drivetrain ?: if (allWheel.containsMatchIn(text)) Drivetrain.AWD else null
            when (stated) {
                wanted -> 1.0
                null -> 0.8
                else -> 0.3.also { misses += stated.name }
            }
        }
        val min = wishes.minPriceEur?.toDouble()
        val max = wishes.maxPriceEur?.toDouble()
        val price = if (max != null) linear(priceEur, (min ?: 0.0) + ((max - (min ?: 0.0)) * 0.25), max * 1.03) else null
        val km = wishes.maxMileageKm?.let { linear(v?.mileageKm?.toDouble(), it * 0.4, it * 1.33) }
        val thisYear = Clock.System.now().toString().take(4).toInt()
        val year = wishes.firstRegFromYear?.let { linear(v?.firstRegYear?.toDouble(), thisYear - 4.0, it - 2.0) }
        // The ideal power fits fully and 50 kW below it not at all; a bare minimum fits fully 20 kW
        // above it and not at all 35 kW below.
        val power = wishes.idealPowerKw?.let { ideal ->
            linear(v?.powerKw?.toDouble(), ideal.toDouble(), ideal - 50.0)?.also { if (it < 0.9) misses += "${v?.powerKw} kW of $ideal" }
        } ?: wishes.minPowerKw?.let { want ->
            linear(v?.powerKw?.toDouble(), want + 20.0, want - 35.0)?.also { if (it < 0.7) misses += "${v?.powerKw} kW" }
        }
        val dealer = when {
            stars != null -> (if (stars >= 4.5) 1.0 else 0.75) *
                ((seller.reviewCount ?: 0).let { if (it >= 20) 1.0 else if (it >= 5) 0.8 else 0.6 })
            seller?.type == SellerType.PRIVATE -> 0.6
            else -> 0.45
        }
        val days = listing.listingDate?.let { (Clock.System.now() - it).inWholeDays.toDouble() }
        val age = days?.let { if (it <= 30) 1.0 else if (it <= 90) 0.85 else (linear(it, 90.0, 365.0) ?: 0.0) * 0.6 + 0.2 }
        val ac = when {
            noAirConditioning.containsMatchIn(text) -> false
            airConditioning.containsMatchIn(text) -> true
            else -> null
        }
        if (ac == false) misses += "no air conditioning"
        val hours = driveHours(listing.distanceKm)
        // Full marks within half the drive the user would happily make, half marks at it, none at twice.
        val where = hours?.let {
            if (it <= softDriveHours / 2) 1.0 else if (it <= softDriveHours) 1.0 - (it - softDriveHours / 2) / softDriveHours
            else (0.5 - (it - softDriveHours) / (2 * softDriveHours)).coerceAtLeast(0.0)
        }

        val parts = listOf(
            size to .26, price to .22, gear to .12, km to .11, year to .09, drive to .07,
            dealer to .05, where to .03, power to .08, age to .01, (ac?.let { if (it) 1.0 else 0.3 }) to .01,
        )
        // A wish the offer does not answer counts a little under half.
        val score = parts.sumOf { (s, w) -> (s ?: 0.45) * w } / parts.sumOf { it.second }
        return Fit(score = score, driveHours = hours, misses = misses, airConditioning = ac)
    }

    /**
     * An offer among [others] that is at least as good as [listing] on price, power, age and
     * mileage and better on one of them, or null when none is. Each of the four has to be known on
     * both sides; a registration month missing on either side compares the years alone.
     */
    fun beatenBy(listing: Listing, others: List<Listing>): Listing? {
        val v = listing.vehicle ?: return null
        val kw = v.powerKw ?: return null
        val km = v.mileageKm ?: return null
        val year = v.firstRegYear ?: return null
        val price = priceEur(listing)
        return others.firstOrNull { other ->
            val o = other.vehicle ?: return@firstOrNull false
            val oKw = o.powerKw ?: return@firstOrNull false
            val oKm = o.mileageKm ?: return@firstOrNull false
            val oYear = o.firstRegYear ?: return@firstOrNull false
            val oPrice = priceEur(other)
            val months = if (v.firstRegMonth != null && o.firstRegMonth != null) o.firstRegMonth!! - v.firstRegMonth!! else 0
            val newer = (oYear - year) * 12 + months
            other.id != listing.id && oPrice <= price && oKw >= kw && oKm <= km && newer >= 0 &&
                (oPrice < price || oKw > kw || oKm < km || newer > 0)
        }
    }

    /** The landed price in euros, as the rest of the app compares prices. */
    fun priceEur(listing: Listing): Double =
        if (listing.price.currency == Currency.EUR) listing.price.amount / 100.0
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR") / 100.0
}
