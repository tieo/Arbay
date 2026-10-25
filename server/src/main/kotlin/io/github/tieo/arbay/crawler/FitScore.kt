package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.ProductWishes
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Drivetrain
import io.github.tieo.arbay.model.FitPart
import io.github.tieo.arbay.model.FitPart.Companion.NOT_STATED
import io.github.tieo.arbay.model.FitWish
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SellerType
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * How well one offer fits a search as a whole, from 0 to 1.
 *
 * A search's vehicle criteria are read as wishes, each scored from 0 (missed by a lot) to 1 (met)
 * and weighed against the others, because a buyer trades them off: a van over budget can still be
 * the best one if everything else is right, and one that scrapes past every filter with several
 * small misses is not. Only a few things rule an offer out entirely: it is not the kind of vehicle
 * wanted at all, it runs on another fuel or states another drive, it is offered to traders or for
 * export only, its dealer has under four stars on the market's own reviews, or it shows another
 * ad's photos.
 *
 * The weights were set with the user on a real search (a Crafter): the van's size first, then the
 * price, where every euro counts, then gearbox, mileage, age and drive; the drive to the seller
 * counts for little, since it is made once or twice, and so does the seller: a dealer under four
 * stars is ruled out already, and what is left between a good dealer and a private seller is
 * mostly the dealer's warranty. A wish the search does not make counts for nothing, so the fit of
 * a search with few wishes is not pulled down by the ones it leaves open.
 */
object FitScore {

    data class Fit(
        val score: Double,
        /** Hours by road from home, estimated from the straight-line distance. */
        val driveHours: Double?,
        val airConditioning: Boolean?,
        /** What each wish adds, in points that sum to [score] in percent. */
        val parts: List<FitPart> = emptyList(),
    )

    /** How well the offer meets one wish, 0 to 1 or null where the ad does not say, and what it has. */
    private data class Wish(val score: Double?, val note: String?)

    /** What a wish the ad does not answer scores. */
    private const val UNKNOWN = 0.45

    private val notAClosedVan = Regex(
        """(?i)pritsche|kipper|koffer|ladebordwand|plane|autotransporter|blitzlader|abschlepp|kühl|frigo|sp(?:ü|ue)lwagen|wohnmobil|camper|california|womo|ausbau|rollstuhl|doka|doppelkabine|rettungswagen|krankenwagen|\b[rk]tw\b""",
    )
    // A workshop fit-out is shelving inside a closed van, "Würth Ausbau", "Sortimo-Ausbau",
    // "Werkstattausbau"; a bare "Ausbau" in a van's headline is a camper conversion.
    private val workshopFitOut = Regex(
        """(?i)(werkstatt|regal|sortimo|w(?:ü|ue)rth|bott|orsy|aluca)[\s-]*ausbau""",
    )
    // "Nur an Gewerbe", "nur Export", "Verkauf ausschließlich für Export oder an Gewerbetreibende";
    // the buyer named, so a van "nur gewerblich genutzt" stays in.
    private val tradeOnly = Regex(
        """(?i)\b(nur|ausschlie(ß|ss)lich)\W{1,3}((an|für|fuer)\W+)?(den\W+)?""" +
            """(gewerbe(treibende|kunden?)?\b|gewerbliche\W+(käufer|kunden|abnehmer)|export|händler|haendler)|export only""",
    )
    private val allWheel = Regex("""(?i)4\s?motion|4mot|allrad|4x4|awd""")
    private val rearWheel = Regex("""(?i)heckantrieb|hinterradantrieb|\brwd\b""")
    private val noAirConditioning = Regex("""(?i)keine klimaanlage|(ohne|keine?)\s+klima""")
    private val airConditioning = Regex("""(?i)klimaanlage|klimaautomatik|climatronic|\bklima\b|airco|air ?con""")

    // A straight line is shorter than the road, by about a quarter across Germany, and a long
    // drive there averages about 100 km/h: Stuttgart to Hamburg, 534 km apart, is under seven hours.
    private const val ROAD_FACTOR = 1.25
    private const val AVERAGE_KMH = 100.0

    fun driveHours(distanceKm: Double?): Double? = distanceKm?.let { it * ROAD_FACTOR / AVERAGE_KMH }

    /** "~45 min", "~7 h". */
    fun hoursLabel(h: Double) = if (h < 1) "~${(h * 60 / 15).roundToInt() * 15} min" else "~${h.roundToInt()} h"

    private fun grouped(n: Long) = "%,d".format(java.util.Locale.GERMANY, n)

    private fun euros(eur: Double) = "${grouped(eur.roundToInt().toLong())} €"

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
        val headline = "${listing.title} ${CarFilterEngine.headline(listing).orEmpty()} ${CarFilterEngine.vehicleKind(listing).orEmpty()}"
        val wantsAVan = wishes.vanLengths.isNotEmpty() || wishes.vanHeights.isNotEmpty()
        if (wantsAVan && notAClosedVan.containsMatchIn(workshopFitOut.replace(headline, " "))) return null
        if (tradeOnly.containsMatchIn(text)) return null
        // Another ad's photos in this one: the van shown is not the one sold.
        if (listing.photosAlsoIn.isNotEmpty()) return null
        val fuel = listing.vehicle?.fuel
        if (wishes.fuels.isNotEmpty() && fuel != null && fuel !in wishes.fuels) return null
        val seller = listing.seller
        val stars = seller?.rating
        if (stars != null && stars < 4.0) return null

        val v = listing.vehicle
        // The drive decides the body: a Crafter driven at the rear or on all four wheels sits its
        // load floor 100 mm higher than the front-driven one, so the hold is 100 mm lower and its
        // doors are too (VW's data sheet, L3H2: 1,726 against 1,626 mm), and a conversion planned
        // around the one does not fit the other. A drive the ad states and the search does not want
        // rules the offer out; one it does not state is weighed below.
        val statedDrive = v?.drivetrain ?: when {
            allWheel.containsMatchIn(text) -> Drivetrain.AWD
            rearWheel.containsMatchIn(text) -> Drivetrain.RWD
            else -> null
        }
        if (wishes.drivetrain != null && statedDrive != null && statedDrive != wishes.drivetrain) return null
        // Size: every size the listing could be, against the sizes wanted.
        fun fits(wanted: Set<Int>, low: Int?, high: Int?, what: String): Pair<Double, String>? {
            if (wanted.isEmpty()) return null
            if (low == null) return 0.35 to "$what not stated"
            val top = high ?: low
            val possible = (low..top).toSet()
            return when {
                possible.all { it in wanted } -> 1.0 to "$what as wanted"
                possible.any { it in wanted } -> 0.6 to "$what not certain"
                else -> 0.1 to "other $what"
            }
        }
        val length = fits(wishes.vanLengths, v?.vanLength, v?.vanLengthMax, "length")
        val height = fits(wishes.vanHeights, v?.vanHeight, v?.vanHeightMax, "roof")
        val sizes = listOfNotNull(length, height)
        val size = sizes.takeIf { it.isNotEmpty() }?.let { s ->
            Wish(s.map { it.first }.average(), s.map { it.second }.filter { !it.endsWith("as wanted") }.joinToString(", ").ifEmpty { "as wanted" })
        }

        val gear = wishes.transmission?.let { wanted ->
            when (val g = v?.gearbox) {
                wanted -> Wish(1.0, g.name.lowercase())
                null -> Wish(0.5, NOT_STATED)
                else -> Wish(0.1, g.name.lowercase())
            }
        }
        val drive = wishes.drivetrain?.let { if (statedDrive == null) Wish(0.8, NOT_STATED) else Wish(1.0, statedDrive.name) }
        val min = wishes.minPriceEur?.toDouble()
        val max = wishes.maxPriceEur?.toDouble()
        val price = max?.let { Wish(linear(priceEur, (min ?: 0.0) + ((it - (min ?: 0.0)) * 0.25), it * 1.03), euros(priceEur)) }
        val km = wishes.maxMileageKm?.let { Wish(linear(v?.mileageKm?.toDouble(), it * 0.4, it * 1.33), v?.mileageKm?.let { m -> "${grouped(m.toLong())} km" }) }
        val thisYear = Clock.System.now().toString().take(4).toInt()
        val year = wishes.firstRegFromYear?.let { Wish(linear(v?.firstRegYear?.toDouble(), thisYear - 4.0, it - 2.0), v?.firstRegYear?.toString()) }
        // The ideal power fits fully and 50 kW below it not at all; a bare minimum fits fully 20 kW
        // above it and not at all 35 kW below.
        val power = wishes.idealPowerKw?.let { ideal ->
            Wish(linear(v?.powerKw?.toDouble(), ideal.toDouble(), ideal - 50.0), v?.powerKw?.let { "$it kW of $ideal" })
        } ?: wishes.minPowerKw?.let { want ->
            Wish(linear(v?.powerKw?.toDouble(), want + 20.0, want - 35.0), v?.powerKw?.let { "$it kW" })
        }
        val dealer = sellerWish(listing)
        val age = adAgeWish(listing)
        val ac = when {
            noAirConditioning.containsMatchIn(text) -> false
            airConditioning.containsMatchIn(text) -> true
            else -> null
        }
        val hours = driveHours(listing.distanceKm)
        val where = distanceWish(listing, softDriveHours)

        // A wish the search does not make counts for nothing; one it makes and the ad does not
        // answer counts a little under half.
        val weighed = listOfNotNull(
            size?.let { Triple(FitWish.SIZE, it, .26) }, price?.let { Triple(FitWish.PRICE, it, .22) },
            gear?.let { Triple(FitWish.GEARBOX, it, .12) }, km?.let { Triple(FitWish.MILEAGE, it, .11) },
            power?.let { Triple(FitWish.POWER, it, .08) }, year?.let { Triple(FitWish.YEAR, it, .09) },
            drive?.let { Triple(FitWish.DRIVE, it, .07) }, Triple(FitWish.SELLER, dealer, .03),
            Triple(FitWish.DISTANCE, where, .03), Triple(FitWish.AD_AGE, age, .01),
            Triple(FitWish.AIR_CONDITIONING, Wish(ac?.let { if (it) 1.0 else 0.3 }, ac?.let { if (it) "yes" else "no" }), .01),
        )
        return weigh(weighed, hours, ac)
    }

    /** The parts and the score of [weighed] wishes, each counting its weight's share of the whole. */
    private fun weigh(weighed: List<Triple<FitWish, Wish, Double>>, hours: Double?, ac: Boolean?): Fit {
        val total = weighed.sumOf { it.third }
        val parts = weighed.map { (wish, w, weight) ->
            val of = weight / total * 100
            FitPart(wish, (w.score ?: UNKNOWN) * of, of, if (w.score == null) NOT_STATED else w.note)
        }
        val score = parts.sumOf { it.points } / 100
        return Fit(score = score, driveHours = hours, airConditioning = ac, parts = parts)
    }

    /** A dealer by the market's own stars and how many gave them; a private seller a little over unknown. */
    private fun sellerWish(listing: Listing): Wish {
        val seller = listing.seller
        val stars = seller?.rating
        return when {
            stars != null -> Wish(
                (if (stars >= 4.5) 1.0 else 0.75) * ((seller.reviewCount ?: 0).let { if (it >= 20) 1.0 else if (it >= 5) 0.8 else 0.6 }),
                "${"%.1f".format(stars)}★" + (seller.reviewCount?.let { " ($it)" } ?: ""),
            )
            seller?.type == SellerType.PRIVATE -> Wish(0.6, "private")
            else -> Wish(0.45, NOT_STATED)
        }
    }

    /** A fresh ad fits fully; one nobody bought in a year is likely priced wrong or has a catch. */
    private fun adAgeWish(listing: Listing): Wish {
        val days = listing.listingDate?.let { (Clock.System.now() - it).inWholeDays.toDouble() }
        return Wish(days?.let { if (it <= 30) 1.0 else if (it <= 90) 0.85 else (linear(it, 90.0, 365.0) ?: 0.0) * 0.6 + 0.2 }, days?.let { "${it.toLong()} days" })
    }

    /** Full marks within half the drive the user would happily make, half marks at it, none at twice. */
    private fun distanceWish(listing: Listing, softDriveHours: Double): Wish {
        val hours = driveHours(listing.distanceKm)
        return Wish(hours?.let {
            if (it <= softDriveHours / 2) 1.0 else if (it <= softDriveHours) 1.0 - (it - softDriveHours / 2) / softDriveHours
            else (0.5 - (it - softDriveHours) / (2 * softDriveHours)).coerceAtLeast(0.0)
        }, hours?.let { h -> listOfNotNull(listing.distanceKm?.let { "${it.roundToInt()} km" }, hoursLabel(h)).joinToString(", ") })
    }

    /** Lower case with umlauts folded and every run of other characters one space, so "Lägler",
     *  "Laegler" and "LAGLER" read alike and a word is found only as a whole. */
    private fun folded(text: String): String =
        " " + text.lowercase().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
            .replace("ae", "a").replace("oe", "o").replace("ue", "u")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim() + " "

    /** Whether [text], already [folded], says [phrase] in so many words and not right after a "kein". */
    private fun says(text: String, phrase: String): Boolean {
        val p = folded(phrase).trim().takeIf { it.isNotEmpty() } ?: return false
        var from = 0
        while (true) {
            val at = text.indexOf(" $p ", from).takeIf { it >= 0 } ?: return false
            val before = text.substring(0, at).trimEnd().substringAfterLast(' ')
            if (before !in NEGATIONS) return true
            from = at + 1
        }
    }

    private val NEGATIONS = setOf("kein", "keine", "keinen", "ohne", "nicht", "no", "not", "without", "senza", "sans")

    /**
     * The offer's fit to the [wishes] of a search for a thing, or null where it is ruled out: sold
     * broken, offered to traders or for export only, showing another ad's photos, sold by a dealer
     * under four stars, or saying a word the search rules out. Weighed: which of the wanted models
     * it is, first; the price, where every euro counts; then what comes with it, the drive to the
     * seller (a machine is fetched, not posted), the seller, and the time the ad has been online.
     */
    fun score(listing: Listing, wishes: ProductWishes, priceEur: Double, softDriveHours: Double): Fit? {
        val raw = "${listing.title}\n${listing.description.orEmpty()}"
        if (listing.condition == Condition.PARTS_ONLY) return null
        if (tradeOnly.containsMatchIn(raw)) return null
        if (listing.photosAlsoIn.isNotEmpty()) return null
        val stars = listing.seller?.rating
        if (stars != null && stars < 4.0) return null
        val title = folded(listing.title)
        val text = folded(raw)
        if (wishes.ruledOutWords.any { says(text, it) }) return null

        // The model a title names counts in full; one only the text names may be a comparison or
        // a mention ("Schleifpapier passt auch für Hummel"), and counts a little less.
        val model = wishes.models.takeIf { it.isNotEmpty() }?.let { models ->
            val inTitle = models.filter { m -> m.words.any { says(title, it) } }.maxByOrNull { it.worth }
            val inText = models.filter { m -> m.words.any { says(text, it) } }.maxByOrNull { it.worth }
            when {
                inTitle != null -> Wish(inTitle.worth, inTitle.name)
                inText != null -> Wish(inText.worth * 0.8, "${inText.name} (in the text)")
                else -> Wish(null, null)
            }
        }
        val max = wishes.maxPriceEur?.toDouble()
        val ideal = wishes.idealPriceEur?.toDouble()
        val price = when {
            max != null -> Wish(linear(priceEur, ideal ?: (max * 0.25), max * 1.03), euros(priceEur))
            ideal != null -> Wish(linear(priceEur, ideal, ideal * 3), euros(priceEur))
            else -> null
        }
        val plus = wishes.plusWords.takeIf { it.isNotEmpty() }?.let { words ->
            val said = words.filter { says(text, it) }
            Wish(if (said.isEmpty()) 0.3 else (0.6 + 0.2 * said.size).coerceAtMost(1.0), said.joinToString(", ").ifEmpty { "none named" })
        }
        val weighed = listOfNotNull(
            model?.let { Triple(FitWish.MODEL, it, .35) }, price?.let { Triple(FitWish.PRICE, it, .30) },
            plus?.let { Triple(FitWish.EXTRAS, it, .10) }, Triple(FitWish.DISTANCE, distanceWish(listing, softDriveHours), .12),
            Triple(FitWish.SELLER, sellerWish(listing), .08), Triple(FitWish.AD_AGE, adAgeWish(listing), .05),
        )
        return weigh(weighed, driveHours(listing.distanceKm), null)
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
