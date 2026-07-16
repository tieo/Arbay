package io.github.tieo.arbay.crawler

/**
 * Panel-van length (L1-L4) and roof height (H1-H3) size classes, parsed from a listing's text.
 *
 * Two confidence tiers, kept apart on purpose:
 *  - [excludable]: the explicit manufacturer size codes ("L3H2", "L2 H2", standalone "L3"/"H2").
 *    These are unambiguous across makes and appear verbatim, so a filter may exclude on a
 *    mismatch — the same footing as a find-in-description term.
 *  - [inferred]: German roof/wheelbase words ("Hochdach", "langer Radstand", "Maxi"). These map
 *    to a class only per model — a Crafter "Hochdach" and a Ducato "Hochdach" are different
 *    heights — so they are display hints only and never drop a listing that might fit.
 */
object VanDimensions {

    data class Dims(val length: Int?, val height: Int?)

    private val combined = Regex("""\bl([1-4])\s?h([1-3])\b""", RegexOption.IGNORE_CASE)
    private val lengthCode = Regex("""\bl([1-4])\b""", RegexOption.IGNORE_CASE)
    private val heightCode = Regex("""\bh([1-3])\b""", RegexOption.IGNORE_CASE)

    /** Only the explicit numeric size codes — safe to exclude on. */
    fun excludable(text: String?): Dims {
        if (text.isNullOrBlank()) return Dims(null, null)
        combined.find(text)?.let {
            return Dims(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        val len = lengthCode.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val hgt = heightCode.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return Dims(len, hgt)
    }

    // Roof words → height. Ambiguous middle ("Mittelhochdach" spans H2/H3 by model) is left out
    // so it never contradicts an explicit code.
    private val roofWords = listOf(
        Regex("""\bsuperhochdach|extrahochdach|jumbo\b""", RegexOption.IGNORE_CASE) to 3,
        Regex("""\bhochdach\b""", RegexOption.IGNORE_CASE) to 2,
        Regex("""\bflachdach|normaldach|niederdach|tiefdach\b""", RegexOption.IGNORE_CASE) to 1,
    )
    private val lengthWords = listOf(
        Regex("""\bkompakt|kurzer radstand\b""", RegexOption.IGNORE_CASE) to 1,
        Regex("""\bmaxi|extralang|extralanger radstand\b""", RegexOption.IGNORE_CASE) to 4,
        Regex("""\blanger radstand\b""", RegexOption.IGNORE_CASE) to 3,
    )

    /** Explicit codes first, then a soft word inference to fill gaps — for display only. */
    fun inferred(text: String?): Dims {
        if (text.isNullOrBlank()) return Dims(null, null)
        val codes = excludable(text)
        val hgt = codes.height ?: roofWords.firstOrNull { it.first.containsMatchIn(text) }?.second
        val len = codes.length ?: lengthWords.firstOrNull { it.first.containsMatchIn(text) }?.second
        return Dims(len, hgt)
    }
}
