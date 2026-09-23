package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.VanSize

/**
 * A panel van's length and roof, read from what its listing says, on the app's own scale
 * ([VanSize]: roof 1 normal, 2 high, 3 super-high; length 1 short, 2 medium, 3 long, 4 long with
 * an extended overhang), with how far each value can be trusted.
 *
 * The codes a listing writes are not one scale. The common one counts a van's own sizes up from
 * 1 (L1H1 the shortest and lowest). VW numbers the Crafter's differently: Normaldach is H2,
 * Hochdach H3, Superhochdach H4, and the lengths are L3 (3640 mm wheelbase), L4 (4490 mm) and L5
 * (4490 mm with the extended overhang), verified against VW's own technical data. So "L3H2" on a
 * Crafter is a medium-wheelbase Normaldach to VW and a long Hochdach to anyone using the common
 * scale, and a listing that writes it cannot be judged on it. Excluding on that code removed
 * Hochdach Crafters from a search for a tall one.
 *
 * What a value may exclude on is therefore decided per make:
 *  - A Crafter's reading is a range: every size its words, wheelbase and codes could mean,
 *    narrowed to what they all allow. A code stands for what it names on either scale ("L3H2":
 *    medium or long, normal or high roof), "Hochdach" and "lang" for high or higher and long or
 *    longer, and "Superhochdach", "Normaldach", the overhang and 3640 mm for one size. So a van
 *    is excluded only when none of the sizes it could be is wanted, and unchecked while more
 *    than one could.
 *  - Any other van is read on the common scale. Its codes are sure, and its words are only a
 *    hint, since every maker names its own variants and the same word means a different size.
 */
object VanDimensions {

    /**
     * What a listing says about its size. [length]..[lengthMax] and [height]..[heightMax] are the
     * sizes it can be; a filter excludes it only when none of them is wanted. A null max means the
     * value is a hint and bounds nothing. Sure means the range is a single size.
     */
    data class Reading(
        val length: Int?,
        val height: Int?,
        val lengthSure: Boolean,
        val heightSure: Boolean,
        val lengthMax: Int? = if (lengthSure) length else null,
        val heightMax: Int? = if (heightSure) height else null,
    ) {
        val isEmpty: Boolean get() = length == null && height == null
    }

    private val combined = Regex("""\bl([1-5])\s?h([1-4])\b""", RegexOption.IGNORE_CASE)
    private val lengthCode = Regex("""\bl([1-5])\b""", RegexOption.IGNORE_CASE)
    private val heightCode = Regex("""\bh([1-4])\b""", RegexOption.IGNORE_CASE)

    private data class Codes(val length: Int?, val height: Int?)

    private fun codes(text: String): Codes {
        combined.find(text)?.let { return Codes(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        return Codes(
            lengthCode.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            heightCode.find(text)?.groupValues?.get(1)?.toIntOrNull(),
        )
    }

    /**
     * Read [text] as the listing of a van. [modelHint] is the model the search is for, used when
     * the listing itself does not say which van it is.
     */
    fun read(text: String?, modelHint: String? = null, wheelbaseMm: Int? = null): Reading {
        if (text.isNullOrBlank()) return Reading(null, null, false, false)
        return if (isCrafter(text, modelHint)) readCrafter(text, wheelbaseMm) else readCommon(text)
    }

    private val crafterWord = Regex("""\bcrafter\b""", RegexOption.IGNORE_CASE)
    private val otherVanModel = Regex(
        """\b(sprinter|transit|ducato|boxer|jumper|master|movano|daily|tge|vito|vivaro|trafic|proace|transporter)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun isCrafter(text: String, modelHint: String?): Boolean =
        crafterWord.containsMatchIn(text) ||
            (modelHint?.contains("crafter", ignoreCase = true) == true && !otherVanModel.containsMatchIn(text))

    // ── the common scale ──────────────────────────────────────────────────────────────────────

    private val commonRoofWords = listOf(
        Regex("""\b(superhochdach|extrahochdach|jumbo)\b""", RegexOption.IGNORE_CASE) to VanSize.SUPER_HIGH_ROOF,
        Regex("""\bhochdach\b""", RegexOption.IGNORE_CASE) to VanSize.HIGH_ROOF,
        Regex("""\b(flachdach|normaldach|niederdach|tiefdach)\b""", RegexOption.IGNORE_CASE) to VanSize.NORMAL_ROOF,
    )
    // Checked in order, so the longest names win over the words inside them.
    private val commonLengthWords = listOf(
        Regex("""\b(maxi|extralang|extralanger radstand)\b""", RegexOption.IGNORE_CASE) to VanSize.EXTRA_LONG,
        Regex("""\b(lang|langer radstand|langradstand)\b""", RegexOption.IGNORE_CASE) to VanSize.LONG,
        Regex("""\b(mittel|mittellang|mittlerer radstand|mittelradstand)\b""", RegexOption.IGNORE_CASE) to VanSize.MEDIUM,
        Regex("""\b(kompakt|kurz|kurzer radstand)\b""", RegexOption.IGNORE_CASE) to VanSize.SHORT,
    )

    private fun readCommon(text: String): Reading {
        val c = codes(text)
        // The common scale has no H4 or L5; a code outside it is some maker's own and says nothing here.
        val length = c.length?.takeIf { it in 1..4 }
        val height = c.height?.takeIf { it in 1..3 }
        return Reading(
            length = length ?: commonLengthWords.firstOrNull { it.first.containsMatchIn(text) }?.second,
            height = height ?: commonRoofWords.firstOrNull { it.first.containsMatchIn(text) }?.second,
            lengthSure = length != null,
            heightSure = height != null,
        )
    }

    // ── the Crafter, in VW's terms ────────────────────────────────────────────────────────────

    // A code on a Crafter may be written on either scale, so it stands for every size it names on
    // either: L3H2 is VW's medium Normaldach or the common scale's long Hochdach, so medium or
    // long, normal or high roof. VW's lengths: L3 3640 mm, L4 4490 mm, L5 4490 mm with the
    // extended overhang; its roofs: H2 Normaldach, H3 Hochdach, H4 Superhochdach.
    private val vwLength = mapOf(3 to VanSize.MEDIUM, 4 to VanSize.LONG, 5 to VanSize.EXTRA_LONG)
    private val vwRoof = mapOf(2 to VanSize.NORMAL_ROOF, 3 to VanSize.HIGH_ROOF, 4 to VanSize.SUPER_HIGH_ROOF)

    private val superHighRoof = Regex("""\bsuperhochdach\b""", RegexOption.IGNORE_CASE)
    // Sellers write "Hochdach" of any tall roof, Superhochdach included ("Lang Hochdach … L3H2" on
    // one listing), so it says high or higher.
    private val highRoof = Regex("""\bhochdach\b""", RegexOption.IGNORE_CASE)
    private val normalRoof = Regex("""\bnormaldach\b""", RegexOption.IGNORE_CASE)
    // "lang" and the 4490 mm wheelbase fit both of VW's long vans, L4 and L5.
    private val overhang = Regex("""\b(verlängerte[mnr]? überhang|überhang)\b""", RegexOption.IGNORE_CASE)
    private val longWords = Regex("""\b(langer radstand|lang)\b""", RegexOption.IGNORE_CASE)
    private val mediumWords = Regex("""\b(mittlere[rn]? radstand|mittellang|mittel lang)\b""", RegexOption.IGNORE_CASE)

    /** The sizes every source allows, taken in the order given; a source that would leave none is
     *  passed over, so a seller's slip does not erase what a surer source said. */
    private fun narrowed(sources: List<Set<Int>>): Set<Int>? =
        sources.fold(null as Set<Int>?) { acc, next ->
            when {
                acc == null -> next
                (acc intersect next).isNotEmpty() -> acc intersect next
                else -> acc
            }
        }

    private fun readCrafter(text: String, wheelbaseMm: Int?): Reading {
        val c = codes(text)
        // A code is written on one scale. A part only one scale has (VW's H4 and L5, the common
        // H1, L1 and L2) says which; so can the length, once something surer has settled it: an
        // L3 on a van with the 3640 mm wheelbase is VW's L3, and then its H2 is VW's Normaldach.
        var vw = !(c.height == 1 || c.length == 1 || c.length == 2) || c.height == 4 || c.length == 5
        var common = !(c.height == 4 || c.length == 5) || c.height == 1 || c.length == 1 || c.length == 2
        fun lengthsOf(code: Int): Set<Int> =
            setOfNotNull(vwLength[code].takeIf { vw }, code.takeIf { it in 1..4 && common })
        fun roofsOf(code: Int): Set<Int> =
            setOfNotNull(vwRoof[code].takeIf { vw }, code.takeIf { it in 1..3 && common })

        val surerLengths = narrowed(listOfNotNull(
            setOf(VanSize.EXTRA_LONG).takeIf { overhang.containsMatchIn(text) },
            setOf(VanSize.MEDIUM).takeIf { wheelbaseMm in 3500..3800 },
            setOf(VanSize.LONG, VanSize.EXTRA_LONG).takeIf { wheelbaseMm in 4300..4600 },
            setOf(VanSize.MEDIUM).takeIf { mediumWords.containsMatchIn(text) },
        ))
        if (vw && common && c.length != null && surerLengths != null) {
            val vwFits = vwLength[c.length] in surerLengths
            val commonFits = c.length in surerLengths
            if (vwFits != commonFits) { vw = vwFits; common = commonFits }
        }
        val lengths = narrowed(listOfNotNull(
            surerLengths,
            c.length?.let(::lengthsOf)?.takeIf { it.isNotEmpty() },
            setOf(VanSize.LONG, VanSize.EXTRA_LONG).takeIf { longWords.containsMatchIn(text) },
        ))
        val roofs = narrowed(listOfNotNull(
            setOf(VanSize.SUPER_HIGH_ROOF).takeIf { superHighRoof.containsMatchIn(text) },
            setOf(VanSize.NORMAL_ROOF).takeIf { normalRoof.containsMatchIn(text) },
            c.height?.let(::roofsOf)?.takeIf { it.isNotEmpty() },
            setOf(VanSize.HIGH_ROOF, VanSize.SUPER_HIGH_ROOF).takeIf { highRoof.containsMatchIn(text) },
        ))
        return Reading(
            length = lengths?.min(), height = roofs?.min(),
            lengthSure = lengths?.size == 1, heightSure = roofs?.size == 1,
            lengthMax = lengths?.max(), heightMax = roofs?.max(),
        )
    }
}
