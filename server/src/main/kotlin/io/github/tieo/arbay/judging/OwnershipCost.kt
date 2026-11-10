package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.OWN_COST_KM
import io.github.tieo.arbay.model.OwnCost
import io.github.tieo.arbay.model.OwnCostPart
import io.github.tieo.arbay.model.VehicleCondition
import io.github.tieo.arbay.model.landedPrice
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * What an electric car costs to own over ten years, from ADAC's tables and the ad.
 *
 * ADAC prices a new model over five years and 75,000 km. Ten years go past that, so each part is
 * carried on in a way the result names:
 * - Purchase: the landed price with the dealer's extras the ad names, else ADAC's flat 500 € for
 *   transfer and registration on a new car.
 * - Value at the end: the model's price new times the share EVs keep at ten years, from ADAC's used
 *   prices by build year (one to seven years old), the share falling at the same yearly rate past
 *   the oldest one, scaled by how this model holds its value against all EVs of the same age, less
 *   the 8 % ADAC states a private sale fetches under a dealer's price.
 * - Fixed costs (insurance, HU, parking flat rate): ADAC's monthly figure, for every month. EVs pay
 *   no car tax until 2035; what they pay after is not counted.
 * - Workshop and tyres: ADAC's monthly figure (with its repair flat rate after year three), half of
 *   it scaling with the distance driven, and half again more for years six to ten.
 * - Energy: the ad's WLTP consumption, else the model's from ADAC's running costs, times the
 *   distance, at the user's charging price mix.
 * - Care: ADAC's 250 € a year for washing and care.
 */
object OwnershipCost {
    const val YEARS = 10
    private const val ADAC_TRANSFER_EUR = 500
    private const val CARE_EUR_PER_YEAR = 250
    private const val LATER_WORKSHOP_FACTOR = 1.5
    private const val PRIVATE_SALE_SHARE = 0.92

    /** The cost of [listing] over ten years at [kmPerYear], or null where ADAC has no row for its model. */
    fun of(listing: Listing, kmPerYear: Int, settings: ImportSettings): OwnCost? {
        val v = listing.vehicle
        // An ad asking nothing names no price to count from.
        val landed = listing.landedPrice(settings).takeIf { it.currency == Currency.EUR && listing.price.amount > 0 }?.amount?.div(100.0) ?: return null
        val assumptions = mutableListOf<String>()
        val own = Adac.costsOf(listing).firstOrNull()
        val row = own ?: Adac.nearestElectricOfMake(listing.title, landed)?.also {
            assumptions += "fixed, workshop and running costs of the ${it.make} ${it.model}, the make's electric car nearest in price (ADAC lists no row of this model)"
        } ?: return null
        val costs = Adac.tables.costs
        val newCar = v?.condition == VehicleCondition.NEW || v?.condition == VehicleCondition.PRE_REGISTRATION
        val transfer = if (listing.extraCosts == null && newCar) ADAC_TRANSFER_EUR.also {
            assumptions += "transfer and registration $it € (ADAC's flat rate; the ad names none)"
        } else 0
        val purchase = landed + transfer

        val newPrice = Adac.usedOf(listing).minByOrNull { u -> v?.powerKw?.let { p -> u.powerKw?.let { kotlin.math.abs(it - p) } } ?: 0 }
            ?.newPrice?.cents?.div(100.0) ?: row.basePrice?.cents?.div(100.0) ?: purchase
        val kept = keptAt(listing, YEARS)
        val value = kept?.let { newPrice * it.first * PRIVATE_SALE_SHARE }
        if (kept != null) assumptions += "worth ${(kept.first * PRIVATE_SALE_SHARE * 100).roundToInt()} % of ${euros(newPrice)} new after $YEARS years (${kept.second})"

        val catalog = Adac.catalogOf(listing).firstOrNull()
        val consumption = v?.consumptionKwhPer100Km ?: catalog?.consumptionKwhPer100Km?.also {
            assumptions += "consumption $it kWh/100 km of the ${catalog.name} (ADAC catalogue)"
        } ?: run {
            // ADAC's running costs are its energy price times consumption, plus its care flat rate.
            val price = costs.energyPrices["Strom"] ?: return@run null
            val kmPerMonth = costs.kmPerYear / 12.0
            ((row.running - CARE_EUR_PER_YEAR / 12.0) / price / kmPerMonth * 100).takeIf { it > 5 }
                ?.also { assumptions += "consumption ${"%.1f".format(it)} kWh/100 km from ADAC's running costs" }
        }
        val centPerKwh = settings.chargingCentPerKwh

        fun total(km: Int): Triple<Int, List<OwnCostPart>, Boolean> {
            val distanceShare = 0.5 + 0.5 * km / costs.kmPerYear
            val firstYears = row.workshop * 12.0 * costs.years * distanceShare
            val laterYears = row.workshop * 12.0 * (YEARS - costs.years) * distanceShare * LATER_WORKSHOP_FACTOR
            val parts = listOfNotNull(
                OwnCostPart("Purchase", purchase.roundToInt(), if (transfer > 0) "with $transfer € transfer and registration" else null),
                value?.let { OwnCostPart("Value after $YEARS years", -it.roundToInt(), "sold privately") },
                OwnCostPart("Insurance and fixed costs", (row.fixed * 12.0 * YEARS).roundToInt(), "ADAC ${row.fixed} € a month"),
                OwnCostPart("Workshop and tyres", (firstYears + laterYears).roundToInt(), "ADAC ${row.workshop} € a month, ×$LATER_WORKSHOP_FACTOR from year ${costs.years + 1}"),
                consumption?.let { OwnCostPart("Charging", (it / 100 * km * YEARS * centPerKwh / 100).roundToInt(), "${"%.1f".format(it)} kWh/100 km at ${"%.1f".format(centPerKwh)} ct") },
                OwnCostPart("Care", CARE_EUR_PER_YEAR * YEARS, "ADAC's flat rate"),
            )
            return Triple(parts.sumOf { it.eur }, parts, value != null && consumption != null)
        }

        val (sum, parts, whole) = total(kmPerYear)
        assumptions += "workshop and tyres for years ${costs.years + 1} to $YEARS at ${LATER_WORKSHOP_FACTOR}× ADAC's five-year rate, half of it scaling with the distance"
        return OwnCost(
            years = YEARS,
            kmPerYear = kmPerYear,
            totalEur = sum,
            totalsByKm = (OWN_COST_KM + kmPerYear).distinct().sorted().associateWith { total(it).first },
            parts = parts,
            assumptions = assumptions,
            notCounted = listOfNotNull(
                "battery repair after the maker's warranty",
                "car tax from 2035",
                "the state's purchase bonus",
                if (value == null) "the value after $YEARS years (ADAC has no used prices of this model)" else null,
                if (consumption == null) "charging (no consumption known)" else null,
            ),
            source = "ADAC ${costs.edition.orEmpty()}, ${row.make} ${row.model}" + if (!whole) " (incomplete)" else "",
        )
    }

    /** The share of its price new an EV keeps at [age] years by ADAC's used prices, all EVs' rate of
     *  loss carried past the oldest year given, scaled by how this model holds its value; with a
     *  note of what it rests on. Null where ADAC gives no used EV prices at all. */
    internal fun keptAt(listing: Listing, age: Int): Pair<Double, String>? {
        val year = Adac.usedYear ?: return null
        fun shares(rows: List<Adac.UsedRow>) = rows.flatMap { r ->
            val new = r.newPrice?.cents?.toDouble()?.takeIf { it > 0 } ?: return@flatMap emptyList()
            r.prices.map { (year - it.year) to it.price.cents / new }.filter { it.first >= 1 }
        }
        val all = shares(Adac.tables.used.rows.filter { it.electric })
        // The median share at each age, and the straight line through their logarithms: a car loses
        // about the same part of its value each year.
        val medians = all.groupBy({ it.first }, { it.second }).mapValues { (_, s) -> s.sorted()[s.size / 2] }
        if (medians.size < 2) return null
        val xs = medians.keys.map { it.toDouble() }
        val ys = medians.values.map { ln(it) }
        val mx = xs.average()
        val my = ys.average()
        val slope = xs.zip(ys).sumOf { (x, y) -> (x - mx) * (y - my) } / xs.sumOf { (it - mx) * (it - mx) }
        fun pooled(a: Int) = exp(my + slope * (a - mx))
        val own = shares(Adac.usedOf(listing))
        val factor = own.map { (a, s) -> s / pooled(a) }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }?.coerceIn(0.7, 1.3)
        val kept = pooled(age) * (factor ?: 1.0)
        val oldest = medians.keys.max()
        return kept to (if (factor != null) "this model's used prices against all EVs'" else "all EVs' used prices; none of this model") +
            (if (age > oldest) ", carried on from $oldest years" else "")
    }

    private fun euros(eur: Double) = "%,d €".format(java.util.Locale.GERMANY, eur.roundToInt())
}
