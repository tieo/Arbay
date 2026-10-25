package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToInt

/** One thing a search weighs when it scores how well an offer fits, in the order of how much it counts. */
@Serializable
enum class FitWish(val label: String) {
    MODEL("Model"),
    SIZE("Size"),
    PRICE("Price"),
    GEARBOX("Gearbox"),
    MILEAGE("Mileage"),
    POWER("Power"),
    YEAR("Year"),
    DRIVE("Drive"),
    DESCRIPTION("Like the description"),
    SELLER("Seller"),
    DISTANCE("Distance"),
    AD_AGE("Time online"),
    AIR_CONDITIONING("Air conditioning"),
    EXTRAS("Extras"),
}

/**
 * What one wish adds to an offer's fit: [points] of the [of] it could add, on a scale where all of
 * an offer's parts together add up to its fit in percent. [note] is what the offer has, or that the
 * ad does not say, so a lost point can be traced to its cause.
 */
@Serializable
data class FitPart(val wish: FitWish, val points: Double, val of: Double, val note: String? = null) {
    val known: Boolean get() = note != NOT_STATED

    companion object {
        /** The [note] of a wish the ad does not answer, which scores a little under half. */
        const val NOT_STATED = "not stated"
    }
}

/**
 * Each part's points and its maximum in whole numbers, the way the fit is shown ("76 %"): the
 * maxima add up to the rounded whole and the points to the rounded fit, so the reader is never a
 * point short of the sum. The points lost are what is rounded, not the points kept, so the wish that
 * lost most still shows the biggest loss.
 */
fun List<FitPart>.wholePoints(): List<Pair<Int, Int>> {
    fun share(values: List<Double>, total: Int): List<Int> {
        val floors = values.map { floor(it).toInt() }
        var left = total - floors.sum()
        val order = values.indices.sortedByDescending { values[it] - floors[it] }
        val out = floors.toMutableList()
        for (i in order) { if (left <= 0) break; out[i]++; left-- }
        return out
    }
    val of = share(map { it.of }, sumOf { it.of }.roundToInt())
    val lost = share(map { (it.of - it.points).coerceAtLeast(0.0) }, of.sum() - sumOf { it.points }.roundToInt())
    return of.zip(lost) { o, l -> (o - l).coerceAtLeast(0) to o }
}

/** The wishes that cost an offer the most, biggest loss first, each with the whole points it lost: "price −10". */
fun List<FitPart>.biggestLosses(count: Int = 4): List<Pair<FitPart, Int>> =
    zip(wholePoints()).map { (part, whole) -> part to (whole.second - whole.first) }
        .filter { it.second >= 1 }
        .sortedByDescending { it.second }
        .take(count)
