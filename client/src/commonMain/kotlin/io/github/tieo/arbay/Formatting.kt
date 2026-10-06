package io.github.tieo.arbay

import io.github.tieo.arbay.model.Money

/** A price as it is shown: in the display currency where its rate is known, else in its own. */
fun Money.format(): String {
    // Show the native currency when we can't convert (unknown rate) rather than mislabelling the
    // raw amount as the display currency \u2014 a 169 900 PLN van must not read as "\u20AC169,900".
    val displayCur = if (DisplayCurrency.canConvert(currency.name)) DisplayCurrency.current else currency.name
    val convertedAmount = if (displayCur == currency.name) amount
        else DisplayCurrency.convert(amount, currency.name)
    val symbol = when (displayCur) {
        "EUR" -> "\u20AC"
        "USD" -> "$"
        "CHF" -> "CHF "
        "GBP" -> "\u00A3"
        else -> "$displayCur "
    }
    val whole = convertedAmount / 100
    val cents = convertedAmount % 100
    return if (cents == 0L) "$symbol${grouped(whole)}"
    else "$symbol${grouped(whole)}.${cents.toString().padStart(2, '0')}"
}

/** A whole number with its thousands grouped by [separator] (88700 reads 88,700), because a van at
 *  10000 and one at 100000 are one glance apart otherwise. */
fun grouped(value: Long, separator: Char = ','): String {
    val digits = value.toString()
    val sign = if (digits.startsWith("-")) "-" else ""
    val body = digits.removePrefix("-")
    return sign + body.reversed().chunked(3).joinToString(separator.toString()).reversed()
}

/** A month and year as 03/2019. */
fun monthYear(month: Int, year: Int): String = "${month.toString().padStart(2, '0')}/$year"

/** A number with exactly [places] decimals, rounded half up: 4.25 to one place reads 4.3. */
fun decimals(value: Double, places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val scaled = kotlin.math.floor(kotlin.math.abs(value) * scale + 0.5).toLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (places == 0) return "$sign$scaled"
    return "$sign${scaled / scale}.${(scaled % scale).toString().padStart(places, '0')}"
}
