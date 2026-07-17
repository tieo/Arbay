package io.github.tieo.arbay.crawler

import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn

/**
 * Parses the posting date that German classifieds print on a listing card into an [Instant], so
 * every crawler can fill `Listing.listingDate` the same way. Handles the common forms:
 * "Heute, 14:32", "Gestern, 20:14", "05.07.2026" (and a few relative phrases). Returns null when
 * no date is recognizable — the field stays absent rather than wrong.
 */
object ListingDateParser {

    private val ddmmyyyy = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b""")
    private val vorTagen = Regex("""vor\s+(\d+)\s+Tag""", RegexOption.IGNORE_CASE)
    private val vorWochen = Regex("""vor\s+(\d+)\s+Woche""", RegexOption.IGNORE_CASE)

    fun parse(text: String?, tz: TimeZone = TimeZone.currentSystemDefault()): Instant? {
        if (text.isNullOrBlank()) return null
        val s = text.trim()
        val today = Clock.System.todayIn(tz)
        return when {
            s.contains("heute", ignoreCase = true) -> today.atStartOfDayIn(tz)
            s.contains("gestern", ignoreCase = true) -> today.minus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
            else -> {
                vorTagen.find(s)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    return today.minus(it, DateTimeUnit.DAY).atStartOfDayIn(tz)
                }
                vorWochen.find(s)?.groupValues?.get(1)?.toIntOrNull()?.let {
                    return today.minus(it * 7, DateTimeUnit.DAY).atStartOfDayIn(tz)
                }
                ddmmyyyy.find(s)?.let { m ->
                    val (d, mo, y) = m.destructured
                    runCatching {
                        kotlinx.datetime.LocalDate(y.toInt(), mo.toInt(), d.toInt()).atStartOfDayIn(tz)
                    }.getOrNull()
                }
            }
        }
    }
}
