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
 *  - A Crafter is read in VW's terms where the listing leaves no doubt. A code is sure only where
 *    the two scales cannot both fit it: an H4 or L5 is VW's, an H1, L1 or L2 the common scale's;
 *    anything else is left unchecked. Of the roof words only the two ends are sure: sellers write
 *    "Hochdach" for any tall roof, Superhochdach included ("Lang Hochdach … L3H2" on the same
 *    listing), so it says high or higher and never excludes, while "Superhochdach" and
 *    "Normaldach" are not said of anything else. For the length, the medium wheelbase
 *    (3640 mm) and the overhang are sure; "lang" and 4490 mm fit both long vans and are not.
 *  - Any other van is read on the common scale. Its codes are sure, and its words are only a
 *    hint, since every maker names its own variants and the same word means a different size.
 */
object VanDimensions {

    data class Reading(
        val length: Int?,
        val height: Int?,
        val lengthSure: Boolean,
        val heightSure: Boolean,
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

    private val superHighRoof = Regex("""\bsuperhochdach\b""", RegexOption.IGNORE_CASE)
    private val highRoof = Regex("""\bhochdach\b""", RegexOption.IGNORE_CASE)
    private val normalRoof = Regex("""\bnormaldach\b""", RegexOption.IGNORE_CASE)
    // "lang" and the 4490 mm wheelbase fit both of VW's long vans, L4 and L5 (the same wheelbase
    // with an extended overhang), so they say long or longer and are not sure. The overhang, the
    // medium wheelbase and its 3640 mm are.
    private val overhang = Regex("""\b(verlängerte[mnr]? überhang|überhang)\b""", RegexOption.IGNORE_CASE)
    private val longWords = Regex("""\b(langer radstand|lang)\b""", RegexOption.IGNORE_CASE)
    private val mediumWords = Regex("""\bmittlere[rn]? radstand\b""", RegexOption.IGNORE_CASE)

    private fun readCrafter(text: String, wheelbaseMm: Int?): Reading {
        val c = codes(text)
        val vwOnly = c.height == 4 || c.length == 5
        val commonOnly = c.height == 1 || c.length == 1 || c.length == 2
        val codeLength = when {
            vwOnly && !commonOnly -> when (c.length) { 3 -> VanSize.MEDIUM; 4 -> VanSize.LONG; 5 -> VanSize.EXTRA_LONG; else -> null }
            commonOnly && !vwOnly -> c.length?.takeIf { it in 1..4 }
            else -> null
        }
        val codeHeight = when {
            vwOnly && !commonOnly -> when (c.height) { 2 -> VanSize.NORMAL_ROOF; 3 -> VanSize.HIGH_ROOF; 4 -> VanSize.SUPER_HIGH_ROOF; else -> null }
            commonOnly && !vwOnly -> c.height?.takeIf { it in 1..3 }
            else -> null
        }

        // Length, surest source first.
        val (length, lengthSure) = when {
            overhang.containsMatchIn(text) -> VanSize.EXTRA_LONG to true
            codeLength != null -> codeLength to true
            wheelbaseMm in 3500..3800 || mediumWords.containsMatchIn(text) -> VanSize.MEDIUM to true
            wheelbaseMm in 4300..4600 || longWords.containsMatchIn(text) -> VanSize.LONG to false
            else -> null to false
        }
        // Roof, surest source first; a loose "Hochdach" only fills in what nothing sure said.
        val (height, heightSure) = when {
            superHighRoof.containsMatchIn(text) -> VanSize.SUPER_HIGH_ROOF to true
            codeHeight != null -> codeHeight to true
            normalRoof.containsMatchIn(text) -> VanSize.NORMAL_ROOF to true
            highRoof.containsMatchIn(text) -> VanSize.HIGH_ROOF to false
            else -> null to false
        }
        return Reading(length = length, height = height, lengthSure = lengthSure, heightSure = heightSure)
    }
}
