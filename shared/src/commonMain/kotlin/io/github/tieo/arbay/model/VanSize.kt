package io.github.tieo.arbay.model

/**
 * The app's own scale for a panel van's size, whatever codes its maker uses: a roof is normal,
 * high or super-high, a length short, medium, long, or long with an extended overhang. VW calls a
 * Crafter's roofs H2, H3 and H4 where most listings count from H1, so the codes a listing writes
 * are translated onto this scale per make before a filter looks at them.
 */
object VanSize {
    const val NORMAL_ROOF = 1
    const val HIGH_ROOF = 2
    const val SUPER_HIGH_ROOF = 3

    const val SHORT = 1
    const val MEDIUM = 2
    const val LONG = 3
    const val EXTRA_LONG = 4

    val roofs = listOf(NORMAL_ROOF, HIGH_ROOF, SUPER_HIGH_ROOF)
    val lengths = listOf(SHORT, MEDIUM, LONG, EXTRA_LONG)

    fun roofLabel(roof: Int): String = when (roof) {
        NORMAL_ROOF -> "Normal roof"
        HIGH_ROOF -> "High roof"
        SUPER_HIGH_ROOF -> "Super-high roof"
        else -> "Roof $roof"
    }

    fun lengthLabel(length: Int): String = when (length) {
        SHORT -> "Short"
        MEDIUM -> "Medium"
        LONG -> "Long"
        EXTRA_LONG -> "Extra long"
        else -> "Length $length"
    }
}
