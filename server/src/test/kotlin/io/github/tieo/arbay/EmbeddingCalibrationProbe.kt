package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.EmbeddingModel
import kotlin.test.Test

/** Prints how the local embedding places real candidate terms against a real query, so a synonym
 *  threshold is calibrated on data instead of guessed. Not an assertion; read the output. */
class EmbeddingCalibrationProbe {

    private val query = "parkettschleifmaschine"

    // Words taken from the live Kleinanzeigen results for that query, labelled by hand:
    // SAME = another name for the machine, RELATED = accessory/adjacent tool, OTHER = unrelated.
    private val candidates = listOf(
        "parkettschleifer" to "SAME",
        "bodenschleifmaschine" to "SAME",
        "bodenschleifer" to "SAME",
        "bandschleifmaschine" to "SAME",
        "walzenschleifer" to "SAME",
        "schleifmaschine" to "SAME",
        "randschleifer" to "RELATED",
        "bandschleifer" to "RELATED",
        "einscheibenmaschine" to "RELATED",
        "schleifpapier" to "RELATED",
        "industriesauger" to "RELATED",
        "staubbeutel" to "RELATED",
        "parkettleger" to "RELATED",
        "bohrmaschine" to "OTHER",
        "waschmaschine" to "OTHER",
        "hubwagen" to "OTHER",
        "gebraucht" to "OTHER",
        "abholung" to "OTHER",
    )

    @Test
    fun `print similarity of each candidate to the query`() {
        val q = EmbeddingModel.embed(query)
        if (q == null) {
            println("EMBEDDING MODEL UNAVAILABLE — cannot calibrate")
            return
        }
        val scored = candidates.mapNotNull { (word, label) ->
            EmbeddingModel.embed(word)?.let { v ->
                var dot = 0.0
                for (i in q.indices) dot += q[i] * v[i]
                Triple(word, label, dot)
            }
        }.sortedByDescending { it.third }
        println("=== cosine to '$query' ===")
        scored.forEach { (word, label, sim) ->
            println("  %.4f  %-8s %s".format(sim, label, word))
        }
        val same = scored.filter { it.second == "SAME" }.minOfOrNull { it.third }
        val notSame = scored.filter { it.second != "SAME" }.maxOfOrNull { it.third }
        println("lowest SAME = $same ; highest non-SAME = $notSame ; separable = ${same != null && notSame != null && same > notSame}")
    }
}
