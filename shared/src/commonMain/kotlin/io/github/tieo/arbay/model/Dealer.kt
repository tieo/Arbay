package io.github.tieo.arbay.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A dealer as its own pages on a market say it: who runs it, where, when it is open, what buyers
 *  rated it, and what else it has for sale. A field is null or empty where the pages do not say. */
@Serializable
data class Dealer(
    val market: String,
    val id: String? = null,
    val url: String? = null,
    val name: String? = null,
    /** The line after the name, often the owner ("Inh. Fouad Saouli"). */
    @SerialName("name_add_on") val nameAddOn: String? = null,
    val contact: String? = null,
    val address: DealerAddress? = null,
    val phones: List<String> = emptyList(),
    /** The legal notice as written: legal name and form, register, directors, VAT id. */
    val imprint: String? = null,
    val hours: List<DealerHours> = emptyList(),
    /** One per source, never mixed. */
    val ratings: List<DealerRating> = emptyList(),
    val stock: DealerStock? = null,
)

@Serializable
data class DealerAddress(val street: String? = null, val postcode: String? = null, val town: String? = null, val country: String? = null)

/** Opening hours of one department, Monday first; a day without spans is closed. */
@Serializable
data class DealerHours(val department: String? = null, val days: List<List<String>> = emptyList())

@Serializable
data class DealerRating(
    val source: String,
    val stars: Double? = null,
    val count: Int = 0,
    @SerialName("recommend_percent") val recommendPercent: Int? = null,
    /** The newest the page shows, worst first. */
    val reviews: List<DealerReview> = emptyList(),
)

@Serializable
data class DealerReview(val stars: Double? = null, val date: String? = null, val text: String? = null, val disliked: String? = null, val reply: String? = null)

@Serializable
data class DealerMoney(val cents: Long, val currency: String)

/** How many offers the dealer has on the market now; the price span covers the [shown] ones only. */
@Serializable
data class DealerStock(val count: Int? = null, val shown: Int? = null, val cheapest: DealerMoney? = null, val dearest: DealerMoney? = null)

private val SOURCES = mapOf("autoscout24.de" to "AutoScout24", "mobile.de" to "mobile.de", "google.com" to "Google")

private fun source(s: String) = SOURCES[s] ?: s

private fun DealerMoney.text(): String {
    val euros = (cents / 100).toString().reversed().chunked(3).joinToString(".").reversed()
    return if (currency == "EUR") "$euros €" else "$euros $currency"
}

/** "4.4★ from 13 reviews, 84 % recommend" or "no reviews". */
fun DealerRating.summary(): String = if (stars == null || count == 0) "no reviews" else
    "${(kotlin.math.round(stars * 10) / 10)}★ from $count review${if (count == 1) "" else "s"}" + (recommendPercent?.let { ", $it % recommend" } ?: "")

val DealerRating.sourceName: String get() = source(source)

/** "1.5★ 2025-10-24: Unfreundlich total. Disliked: ... Dealer replied: ..." */
fun DealerReview.line(): String = listOfNotNull(
    listOfNotNull(stars?.let { "${it}★" }, date).joinToString(" ").ifEmpty { null }?.let { "$it:" },
    text?.trim()?.takeIf { it.isNotEmpty() },
    disliked?.trim()?.takeIf { it.isNotEmpty() }?.let { "Disliked: $it" },
    reply?.trim()?.takeIf { it.isNotEmpty() }?.let { "Dealer replied: $it" },
).joinToString(" ")

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Mon to Fri 08:30-18:00, Sat 09:00-16:00", days with the same hours run together; closed days left out. */
fun DealerHours.text(): String {
    val runs = mutableListOf<Triple<Int, Int, List<String>>>()
    days.forEachIndexed { i, spans ->
        val last = runs.lastOrNull()
        if (last != null && last.second == i - 1 && last.third == spans) runs[runs.size - 1] = Triple(last.first, i, spans) else runs += Triple(i, i, spans)
    }
    return runs.filter { it.third.isNotEmpty() }.joinToString(", ") { (from, to, spans) ->
        val d = if (from == to) DAYS[from] else "${DAYS[from]} to ${DAYS[to]}"
        "$d ${spans.joinToString(" and ")}"
    }
}

/** The dealer's place and phones in one line. */
fun Dealer.whereLine(): String? = listOfNotNull(
    address?.let { a -> listOfNotNull(a.street, listOfNotNull(a.postcode, a.town).joinToString(" ").ifEmpty { null }).joinToString(", ").ifEmpty { null } },
    phones.joinToString(", ").ifEmpty { null },
).joinToString(" · ").ifEmpty { null }

/** "21 offers on AutoScout24, 3.990 € to 29.990 € among the 20 shown". */
fun Dealer.stockLine(): String? = stock?.count?.let { n ->
    val span = if (stock.cheapest != null && stock.dearest != null) ", ${stock.cheapest.text()} to ${stock.dearest.text()}" +
        (if (stock.shown != null && stock.shown < n) " among the ${stock.shown} shown" else "") else ""
    "$n offer${if (n == 1) "" else "s"} on ${source(market)}$span"
}

/** Every fact of the dealer as lines of text, for a reader with no screen. */
fun Dealer.lines(): List<String> = listOfNotNull(
    "Dealer: " + listOfNotNull(name ?: "unnamed", nameAddOn).joinToString(", ") + (contact?.let { " (contact $it)" } ?: ""),
    whereLine(),
) + ratings.flatMap { r -> listOf("${r.sourceName}: ${r.summary()}") + r.reviews.map { "  " + it.line() } } +
    hours.map { h -> "Open " + (h.department?.let { "($it) " } ?: "") + h.text().ifEmpty { "no hours given" } } +
    listOfNotNull(stockLine(), imprint?.trim()?.takeIf { it.isNotEmpty() }?.let { "Legal notice: " + it.replace("\n", " / ") })

/** The address of the dealer's own page behind [listing], where it is sold by one and the market names it. */
fun dealerPage(listing: Listing): String? = listing.seller?.takeIf { it.type == SellerType.BUSINESS }?.url
