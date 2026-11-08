package io.github.tieo.arbay.results

import io.github.tieo.arbay.grouped
import io.github.tieo.arbay.model.DriveFacts
import io.github.tieo.arbay.model.DriveHoursFrom
import io.github.tieo.arbay.model.LiveAuction
import io.github.tieo.arbay.model.PriceSpread
import io.github.tieo.arbay.model.SoldPrices

/** The two figures drives are compared by, set under an offer's price. */
fun DriveFacts.columns(): List<String> = listOfNotNull(
    centsPerGb?.let { "${tenths(it)} ct/GB" },
    lifeHours?.let { "${grouped(it, '.')} h left" },
)

/** What else the offer states of the drive, in one line. */
fun DriveFacts.factsLine(): String = listOfNotNull(
    capacityGb?.let { if (it >= 1000) "${tenths(it / 1000.0).removeSuffix(".0")} TB" else "$it GB" },
    driveInterface?.label,
    recording?.name,
    "external".takeIf { external == true },
    model,
    powerOnHours?.takeIf { hoursFrom != DriveHoursFrom.NEW }?.let { "${grouped(it, '.')} h run" + if (hoursFrom == DriveHoursFrom.AD_TEXT) " by the ad" else "" },
    "new".takeIf { hoursFrom == DriveHoursFrom.NEW },
    health?.label,
    unitPrice?.let { "${it.amount / 100} € each" },
    stock?.let { "$it in stock" },
    "price for how many unclear".takeIf { lotPriceUnclear },
    "reading the ad".takeIf { !pageRead },
    "reading $photosPending photos".takeIf { pageRead && photosPending > 0 },
).joinToString(" · ")

private fun tenths(v: Double): String {
    val t = kotlin.math.round(v * 10).toLong()
    return "${t / 10}.${t % 10}"
}

private fun PriceSpread.words(): String =
    if (count == 0) "no sales" else "${medianEur?.toInt()} € · ${p25Eur?.toInt()} to ${p75Eur?.toInt()} € · $count sold"

/** The rows of a sold-price table: what sold, and its median, quartiles and number of sales. */
fun SoldPrices.rows(): List<Pair<String, String>> = listOf(
    "All" to all.words(),
    "Auctions" to auctions.words(),
    "Buy now" to buyNow.words(),
    "New" to new.words(),
    "Refurbished" to refurbished.words(),
    "Used" to used.words(),
) + months.takeLast(6).reversed().map { it.month to it.spread.words() }

/** What a running auction can be expected to end at, in the state that is known. */
fun LiveAuction.expectedWords(): String = when {
    expectedEndEur != null -> "Expected ${expectedEndEur!!.toInt()} € in all, from $expectedSales ${expectedFrom}" +
        (if (expectedThin) ", few sales" else "") + (limitEur?.let { " · limit $it €" } ?: "")
    expectedPending -> "Reading what these sold for"
    else -> "Too few sales to expect a price"
}
