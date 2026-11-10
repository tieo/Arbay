package io.github.tieo.arbay.judging

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
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.toCarFilters
import kotlin.math.roundToInt
import kotlin.time.Clock
import io.github.tieo.arbay.relevance.ModelGuess
import io.github.tieo.arbay.relevance.ModelGuesses
import io.github.tieo.arbay.relevance.CarFilterEngine

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
 * price, where every euro counts, then power, then gearbox, mileage, age and drive. Power weighs
 * near the price because a van far under the power wanted is one the user would not buy at the
 * price, while still trading off against the rest rather than ruling the van out. The drive to the seller
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
        """(?i)pritsche|kipper|koffer|ladebordwand|plane|autotransporter|blitzlader|abschlepp|kühl|frigo|sp(?:ü|ue)lwagen|wohnmobil|camper|california|womo|ausbau|rollstuhl|doka|doppelkabine|mixto|rettungswagen|krankenwagen|\b[rk]tw\b""",
    )
    // A workshop fit-out is shelving inside a closed van, "Würth Ausbau", "Sortimo-Ausbau",
    // "Werkstattausbau"; a bare "Ausbau" in a van's headline is a camper conversion.
    private val workshopFitOut = Regex(
        """(?i)(werkstatt|regal|sortimo|w(?:ü|ue)rth|bott|orsy|aluca)[\s-]*ausbau""",
    )
    // A camper named as one use a closed van can be put to, "VW Crafter Handwerker / Camper Auto"
    // (a plain Kasten with a wood-lined hold), "ideal als Camper", "Basis für Camperausbau",
    // "Ausbau möglich", the Dutch dealers' "ideaal voor ombouw naar buscamper": the van is still
    // closed. A camper named alone is a conversion.
    private val camperAsAUse = Regex(
        """(?i)(handwerker|werkstatt|gewerbe|transporter|kasten(wagen)?|lieferwagen|umzug)\w*\s*(/|oder|&|\+|und)\s*(camper|wohnmobil|womo)\w*""" +
            """|(camper|wohnmobil|womo)\w*\s*(/|oder|&|\+|und)\s*(handwerker|werkstatt|gewerbe|transporter|kasten(wagen)?|lieferwagen|umzug)""" +
            """|(ideal|ideaal|perfekt|perfect|geeignet|geschikt|bestens)\s+(als|f(ü|ue)r|zum|voor)\s+((einen|den|ein|een|de)\s+)?""" +
            """((umbau|ombouw)\s+(zum?|zur|naar|tot)\s+((einem|einen|een)\s+)?)?(bus)?(camper|wohnmobil|womo|ausbau)\w*""" +
            """|basis\s+(f(ü|ue)r\s+)?(((einen|den|ein)\s+)?(camper|wohnmobil|womo|ausbau)\w*)""" +
            """|(camper|wohnmobil)?[\s-]*ausbau\s+m(ö|oe)glich""",
    )

    // "Nur an Gewerbe", "nur Export", "Verkauf ausschließlich für Export oder an Gewerbetreibende";
    // the buyer named, so a van "nur gewerblich genutzt" stays in.
    private val tradeOnly = Regex(
        """(?i)\b(nur|ausschlie(ß|ss)lich)\W{1,3}((an|für|fuer)\W+)?(den\W+)?""" +
            """(gewerbe(treibende|kunden?)?\b|gewerbliche\W+(käufer|kunden|abnehmer)|export|händler|haendler)|export only""" +
            // A price a business alone gets: "AKTION GEWERBE", "Gewerbeaktion", "Gewerbekundenpreis".
            """|\baktion\W{1,3}gewerbe|\bgewerbe(kunden)?\W?(aktion|angebot|preis)|\bpreis\W+(nur\W+)?f(ü|ue)r\W+gewerbe""",
    )
    private val allWheel = Regex("""(?i)4\s?motion|4mot|allrad|4x4|awd""")
    private val rearWheel = Regex("""(?i)heckantrieb|hinterradantrieb|\brwd\b""")
    private val noAirConditioning = Regex("""(?i)keine klimaanlage|(ohne|keine?)\s+klima""")
    // "Manuelle Klimatisierung" and VW's "Climatic" name an air conditioning; a bare "Klimatisierung"
    // does not, since dealer feeds print the heading empty ("Klimatisierung: --").
    private val airConditioning = Regex(
        """(?i)klimaanlage|klimaautomatik|climatronic|\bclimatic\b|(manuelle|automatische)\s+klimatisierung|\bklima\b|airco|air ?con""",
    )

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
        if (wantsAVan && notAClosedVan.containsMatchIn(camperAsAUse.replace(workshopFitOut.replace(headline, " "), " "))) return null
        if (tradeOnly.containsMatchIn(text)) return null
        // Another ad's photos in this one: the van shown is not the one sold.
        if (listing.photosAlsoIn.isNotEmpty()) return null
        val fuel = listing.vehicle?.fuel
        if (wishes.fuels.isNotEmpty() && fuel != null && fuel !in wishes.fuels) return null
        val stars = marketStars(listing)?.first
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
        // An electric car's size: its model's length from the catalogue, against the least and
        // the ideal model the search names. Under the least it is ruled out where the filter
        // has not already left it out; between the two it rises to full at the ideal.
        val ev = if (wishes.atLeastSizeOf != null || wishes.idealSizeOf != null || wishes.idealRangeKm != null ||
            wishes.idealDcKw != null || wishes.wantsHeatPump) EvModels.versionOf(listing) else null
        val carLength = if (wishes.atLeastSizeOf != null || wishes.idealSizeOf != null)
            EvModels.lengthOf(EvModels.versionsOf(listing.title)) else null
        val least = EvModels.minLengthMm(wishes)
        val ideal = EvModels.idealLengthMm(wishes)
        if (least != null && carLength != null && carLength < least) return null
        val evSize = if (wishes.atLeastSizeOf == null && wishes.idealSizeOf == null) null else when {
            carLength == null -> Wish(null, null)
            ideal == null || carLength >= ideal -> Wish(1.0, "${grouped(carLength.toLong())} mm long")
            else -> Wish(0.4 + 0.6 * (linear(carLength.toDouble(), ideal.toDouble(), (least ?: ideal - 400).toDouble()) ?: 0.0),
                "${grouped(carLength.toLong())} mm long, ideal ${grouped(ideal.toLong())}")
        }
        val range = wishes.idealRangeKm?.let { want ->
            val km = v?.rangeKm ?: ev?.wltpRangeKm
            Wish(linear(km?.toDouble(), want.toDouble(), want * 0.6), km?.let { "$it km WLTP" + if (v?.rangeKm == null) " (model)" else "" })
        }
        val charging = wishes.idealDcKw?.let { want ->
            val kw = v?.fastChargeKw ?: ev?.dcMaxKw
            Wish(linear(kw, want.toDouble(), want * 0.5), kw?.let { "${it.roundToInt()} kW DC" + if (v?.fastChargeKw == null) " (model)" else "" })
        }
        val heatPump = if (!wishes.wantsHeatPump) null else when {
            v?.heatPump == true -> Wish(1.0, "in the equipment")
            ev?.heatPumpStandard == true -> Wish(1.0, "standard on the model")
            ev?.heatPump == false -> Wish(0.0, "not available on the model")
            else -> Wish(null, null)
        }
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
        // The ideal power fits fully and 35 kW below it not at all; a bare minimum fits fully 20 kW
        // above it and not at all 35 kW below. A 140 PS Crafter against an ideal of 177 PS keeps
        // under a quarter of the power points: the gap is what the user wants the engine for.
        val power = wishes.idealPowerKw?.let { ideal ->
            Wish(linear(v?.powerKw?.toDouble(), ideal.toDouble(), ideal - 35.0), v?.powerKw?.let { "$it kW of $ideal" })
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
            (size ?: evSize)?.let { Triple(FitWish.SIZE, it, .26) }, price?.let { Triple(FitWish.PRICE, it, .22) },
            range?.let { Triple(FitWish.RANGE, it, .12) }, charging?.let { Triple(FitWish.CHARGING, it, .06) },
            heatPump?.let { Triple(FitWish.HEAT_PUMP, it, .05) },
            gear?.let { Triple(FitWish.GEARBOX, it, .12) }, km?.let { Triple(FitWish.MILEAGE, it, .11) },
            power?.let { Triple(FitWish.POWER, it, .20) }, year?.let { Triple(FitWish.YEAR, it, .09) },
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

    /** The stars a dealer has on the offer's own market and how many gave them: the offer's card's, else
     *  the dealer's pages' where they have been read. A dealer page without reviews gives none. */
    internal fun marketStars(listing: Listing, dealer: (String?) -> io.github.tieo.arbay.model.Dealer? = Dealers::cached): Pair<Double, Int?>? {
        listing.seller?.let { s -> s.rating?.let { return it to s.reviewCount } }
        val d = dealer(Dealers.pageOf(listing)) ?: return null
        val r = d.ratings.firstOrNull { it.source == d.market && it.stars != null && it.count > 0 } ?: return null
        return r.stars!! to r.count
    }

    /** The dealer's stars on Google, where its listing there was found; they weigh where the market
     *  gives none and never rule an offer out. */
    internal fun googleStars(listing: Listing, dealer: (String?) -> io.github.tieo.arbay.model.Dealer? = Dealers::cached): Pair<Double, Int>? =
        dealer(Dealers.pageOf(listing))?.google?.ratings?.firstOrNull { it.stars != null && it.count > 0 }?.let { it.stars!! to it.count }

    /** A dealer by the market's own stars and how many gave them, else Google's; a private seller a little over unknown. */
    private fun sellerWish(listing: Listing): Wish {
        val seller = listing.seller
        val rated = marketStars(listing)
        val google = if (rated == null) googleStars(listing) else null
        fun weight(stars: Double, count: Int?) = (if (stars >= 4.5) 1.0 else 0.75) * ((count ?: 0).let { if (it >= 20) 1.0 else if (it >= 5) 0.8 else 0.6 })
        return when {
            rated != null -> Wish(weight(rated.first, rated.second), "${"%.1f".format(rated.first)}★" + (rated.second?.let { " ($it)" } ?: ""))
            google != null -> Wish(weight(google.first, google.second) * if (google.first < 4.0) 0.5 else 1.0, "${"%.1f".format(google.first)}★ on Google (${google.second})")
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
    internal fun folded(text: String): String =
        " " + text.lowercase().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
            .replace("ae", "a").replace("oe", "o").replace("ue", "u")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim() + " "

    /** Whether [text], already [folded], says [phrase] in so many words and not right after a "kein". */
    internal fun says(text: String, phrase: String): Boolean {
        val p = folded(phrase).trim().takeIf { it.isNotEmpty() } ?: return false
        var from = 0
        while (true) {
            val at = text.indexOf(" $p ", from).takeIf { it >= 0 } ?: return false
            val before = text.substring(0, at).trimEnd().substringAfterLast(' ')
            if (before !in NEGATIONS) return true
            from = at + 1
        }
    }

    /** The model of [wishes] that [text], [folded] already, names, the one worth most where several are. */
    /**
     * The [text] without the run of search words a seller closes an ad with, each on a line of its
     * own: "Parkett schleifen / Dielen schleifen / Schleifmaschine / Lägler / Hummel" under a Zentra
     * names what the ad wants to be found by, not the machine. Three such lines or more of at most
     * three words and no sentence mark make the run; a text that is all such lines is the text.
     * Of 379 cached sander ads, 19 closed with a run and one named a wanted model only there.
     */
    private val SPACES = Regex("""\s+""")
    private val SENTENCE_MARK = Regex("[.,:;!?]")

    internal fun withoutTags(text: String): String {
        val lines = text.trimEnd().lines()
        fun tag(line: String) = line.isBlank() || line.trim().let { it.length <= 40 && it.split(SPACES).size <= 3 && !SENTENCE_MARK.containsMatchIn(it) }
        var start = lines.size
        while (start > 0 && tag(lines[start - 1])) start--
        val run = lines.drop(start).count { it.isNotBlank() }
        val before = lines.take(start)
        return if (run >= 3 && before.any { it.isNotBlank() }) before.joinToString("\n") else text
    }

    internal fun namedModel(text: String, wishes: ProductWishes): io.github.tieo.arbay.model.ModelWish? =
        modelsNamed(text, wishes.models).maxByOrNull { it.worth }

    /**
     * The [models] that [text], [folded] already, names. A model whose words run into words naming
     * another that goes on further is not named: "cr scan otter lite" says "cr scan otter" and is
     * the Lite alone, while "otter und otter lite" names both.
     */
    internal fun modelsNamed(text: String, models: List<io.github.tieo.arbay.model.ModelWish>): List<io.github.tieo.arbay.model.ModelWish> {
        val hits = models.associateWith { m -> m.words.flatMap { spans(text, it) } }.filterValues { it.isNotEmpty() }
        return hits.filter { (m, own) ->
            val others = hits.filterKeys { it !== m }.values.flatten()
            own.any { s -> others.none { o -> o.first <= s.last && s.first <= o.last && (o.last > s.last || (o.last == s.last && o.first < s.first)) } }
        }.keys.toList()
    }

    /** Where [text], [folded] already, says [phrase], not right after a "kein". */
    internal fun spans(text: String, phrase: String): List<IntRange> {
        val p = folded(phrase).trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val found = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(" $p ", from).takeIf { it >= 0 } ?: return found
            val before = text.substring(0, at).trimEnd().substringAfterLast(' ')
            if (before !in NEGATIONS) found += (at + 1)..(at + p.length)
            from = at + 1
        }
    }

    /** Where a model's price fits fully when only its limit is named: near the limit, which is a price
     *  the user would pay, unlike a search's band over several models. */
    private const val MODEL_IDEAL_SHARE = 0.8

    /** The price's share of a fit: all but a tenth of it runs out between [ideal] and [zero], and that
     *  tenth is earned below [ideal], so a model at 216 € beats the same one at 399 € under a 400 € ideal. */
    private fun cheaperStillCounts(price: Double, ideal: Double, zero: Double): Double =
        if (price >= ideal) 0.9 * (linear(price, ideal, zero) ?: 0.0) else 0.9 + 0.1 * (ideal - price).coerceAtLeast(0.0) / ideal

    /** How many wanted models a title names before it is a shop's list of variants at one price. */
    private const val VARIANT_LIST = 3

    private val NEGATIONS = setOf("kein", "keine", "keinen", "ohne", "nicht", "no", "not", "without", "senza", "sans")

    /**
     * The offer's fit to the [wishes] of a search for a thing, or null where it is ruled out: sold
     * broken, offered to traders or for export only, showing another ad's photos, sold by a dealer
     * under four stars, or saying a word the search rules out. Weighed: which of the wanted models
     * it is, first; the price, where every euro counts; then what comes with it, the drive to the
     * seller (a machine is fetched, not posted), the seller, and the time the ad has been online.
     */
    fun score(
        listing: Listing, wishes: ProductWishes, priceEur: Double, softDriveHours: Double,
        guessed: ((Listing) -> ModelGuess?)? = ModelGuesses::of,
    ): Fit? {
        val raw = "${listing.title}\n${listing.description.orEmpty()}"
        if (listing.condition == Condition.PARTS_ONLY) return null
        // A rental is shown and never weighed: its price is for a day, not for the thing.
        if (listing.saleType == io.github.tieo.arbay.model.SaleType.RENT) return null
        if (Condition.soldAsDefective(listing.title, listing.description)) return null
        if (tradeOnly.containsMatchIn(raw)) return null
        if (listing.photosAlsoIn.isNotEmpty()) return null
        val stars = marketStars(listing)?.first
        if (stars != null && stars < 4.0) return null
        val title = folded(listing.title)
        val text = folded(raw)
        if (wishes.ruledOutWords.any { says(text, it) }) return null
        val stated = folded("${listing.title}\n${withoutTags(listing.description.orEmpty())}")

        // The model a title names counts in full; one only the text names may be a comparison or
        // a mention ("Schleifpapier passt auch für Hummel"), and counts a little less. A model worth
        // nothing is one that would not do: an edge sander named alone is not the machine wanted,
        // while "Hummel + Elan" is the Hummel with an edge sander thrown in.
        // The price of a title listing a shop's variants is none of theirs in particular.
        var priced: io.github.tieo.arbay.model.ModelWish? = null
        val model = wishes.models.takeIf { it.isNotEmpty() }?.let { models ->
            val namedInTitle = modelsNamed(title, models)
            if (namedInTitle.count { it.worth > 0 } >= VARIANT_LIST) return null
            val inTitle = namedInTitle.maxByOrNull { it.worth }
            if (inTitle != null && inTitle.worth <= 0.0) return null
            val told = guessed?.invoke(listing)
            val inText = modelsNamed(stated, models).filter { it.worth > 0 }.maxByOrNull { it.worth }
                .takeUnless { told?.how == ModelGuess.ONLY_MENTIONED }
            // Someone looked and said which it is, or that it is none of them: that outranks its words.
            if (told?.how == ModelGuess.CHECKED && told.model == ModelGuess.NONE_OF_THEM) return null
            val seen = told?.let { g -> models.firstOrNull { it.name == g.model }?.let { it to g } }
            priced = seen?.takeIf { it.second.how == ModelGuess.CHECKED }?.first ?: inTitle ?: inText ?: seen?.first
            when {
                seen != null && seen.second.how == ModelGuess.CHECKED -> {
                    if (seen.first.worth <= 0.0) return null
                    Wish(seen.first.worth, "${seen.first.name} (${ModelGuess.CHECKED})")
                }
                inTitle != null -> Wish(inTitle.worth, inTitle.name)
                inText != null -> Wish(inText.worth * 0.8, "${inText.name} (in the text)")
                // A model told from the ad's photos or its full text, read since the card came in.
                seen != null -> {
                    if (seen.first.worth <= 0.0 && seen.second.sure) return null
                    Wish(seen.first.worth * seen.second.confidence, "${seen.first.name} (${seen.second.how})")
                }
                else -> Wish(null, null)
            }
        }
        // A model's own limit stands alone: the search's ideal may lie above a cheaper model's limit.
        val own = priced?.maxPriceEur?.toDouble()
        val max = own ?: wishes.maxPriceEur?.toDouble()
        val ideal = priced?.idealPriceEur?.toDouble() ?: if (own != null) own * MODEL_IDEAL_SHARE else wishes.idealPriceEur?.toDouble()
        val price = when {
            max != null -> Wish(cheaperStillCounts(priceEur, ideal ?: (max * 0.25), max * 1.03), euros(priceEur))
            ideal != null -> Wish(cheaperStillCounts(priceEur, ideal, ideal * 3), euros(priceEur))
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

    /**
     * The fit of [listing] to the search [query] as the watch weighs it again: by the thing's wishes
     * where the search names them, by the vehicle's criteria where it is a vehicle search, and not
     * at all otherwise. A 2 TB SSD search carrying only a price band was weighed as a vehicle, air
     * conditioning among its wishes, and two drives sold as defective headed its new offers at 89 %.
     */
    fun forSearch(listing: Listing, query: SearchQuery, softDriveHours: Double): Fit? {
        val price = priceEur(listing)
        query.productWishes?.takeUnless { it.isEmpty }?.let { return score(listing, it, landedEur(listing), softDriveHours) }
        if (query.category != MarketGroup.VEHICLES) return null
        return score(listing, query.toCarFilters() ?: return null, price, softDriveHours)
    }

    /** What the offer costs with its shipping, in euros. */
    fun landedEur(listing: Listing): Double = listing.effectivePrice.let { p ->
        if (p.currency == Currency.EUR) p.amount / 100.0 else ExchangeRates.convert(p.amount, p.currency.name, "EUR") / 100.0
    }

    /** The landed price in euros, as the rest of the app compares prices. */
    fun priceEur(listing: Listing): Double =
        if (listing.price.currency == Currency.EUR) listing.price.amount / 100.0
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR") / 100.0
}
