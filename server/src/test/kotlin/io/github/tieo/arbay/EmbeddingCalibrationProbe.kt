package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.EmbeddingModel
import kotlin.test.Test

/**
 * Prints how the local embedding places real candidate terms against real queries, so a synonym
 * threshold is calibrated on data instead of guessed. Not an assertion; read the output.
 *
 * Two questions, both answered against live Kleinanzeigen data:
 *  1. bare words — does the model tell another name for the thing from an unrelated machine?
 *  2. the survivors of the shared-stem filter — does it tell the machine from its accessories?
 */
class EmbeddingCalibrationProbe {

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    private fun report(query: String, candidates: List<Pair<String, String>>) {
        val q = EmbeddingModel.embed(query) ?: run { println("MODEL UNAVAILABLE"); return }
        val scored = candidates.mapNotNull { (word, label) ->
            EmbeddingModel.embed(word)?.let { Triple(word, label, cosine(q, it)) }
        }.sortedByDescending { it.third }
        println("=== $query ===")
        scored.forEach { (w, l, s) -> println("  %.4f  %-8s %s".format(s, l, w)) }
        val same = scored.filter { it.second == "SAME" }.minOfOrNull { it.third }
        val other = scored.filter { it.second != "SAME" }.maxOfOrNull { it.third }
        println("  lowest SAME=$same highest other=$other separable=${same != null && other != null && same > other}")
    }

    @Test
    fun `bare words - another name for the thing versus an unrelated machine`() {
        report("parkettschleifmaschine", listOf(
            "parkettschleifer" to "SAME",
            "bodenschleifmaschine" to "SAME",
            "bodenschleifer" to "SAME",
            "walzenschleifer" to "SAME",
            "schleifmaschine" to "SAME",
            "randschleifer" to "OTHER",
            "einscheibenmaschine" to "OTHER",
            "schleifpapier" to "OTHER",
            "bohrmaschine" to "OTHER",
            "waschmaschine" to "OTHER",
        ))
    }

    /**
     * The terms that survive the shared-stem filter over Kleinanzeigen's own suggestions. What is
     * left to tell apart is the machine from the things it uses: a chisel, a blank of wood, a
     * drill bit, the verb for the job.
     */
    @Test
    fun `stem survivors - the machine versus its accessories`() {
        report("drechselbank", listOf(
            "drechselmaschine" to "SAME",
            "holzdrehbank" to "SAME",
            "drechseleisen" to "OTHER",
            "drechselholz" to "OTHER",
            "drechselwerkzeug" to "OTHER",
            "drechselfutter" to "OTHER",
            "drechseln" to "OTHER",
        ))
        report("kernbohrmaschine", listOf(
            "kernbohrgeraet" to "SAME",
            "kernbohrer" to "SAME",
            "magnetbohrmaschine" to "SAME",
            "kernbohrkrone" to "OTHER",
            "kernbohrung" to "OTHER",
        ))
        report("teppichreinigungsmaschine", listOf(
            "teppichreiniger" to "SAME",
            "teppichreinigungsgeraet" to "SAME",
            "waschsauger" to "SAME",
            "teppichreinigung" to "OTHER",
            "polsterreiniger" to "OTHER",
        ))
    }
}
