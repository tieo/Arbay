package io.github.tieo.arbay.classifier.models

import io.github.tieo.arbay.classifier.*

/**
 * k-Nearest Neighbors in embedding space.
 * Scores by looking at the k most similar training examples and
 * computing a weighted vote (closer neighbors count more).
 *
 * Simple, non-parametric, no gradient descent needed.
 * Naturally adapts as more feedback accumulates.
 */
class KnnModel(private val k: Int = 7) : ScoringModel {
    override val id = "knn"
    override val name = "k-NN (k=$k)"
    override val trainable = true

    @Volatile private var examples: List<TrainingExample> = emptyList()

    override fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext): Double {
        if (examples.isEmpty()) return 0.5

        // Find k nearest neighbors by cosine similarity
        val neighbors = examples
            .map { ex -> ex to cosineSimilarity(listingEmbedding, ex.embedding) }
            .sortedByDescending { it.second }
            .take(k)

        if (neighbors.isEmpty()) return 0.5

        // Weighted vote: similarity-weighted proportion of LOVE among neighbors
        var loveWeight = 0.0
        var totalWeight = 0.0
        for ((ex, sim) in neighbors) {
            // Map similarity from [-1,1] to [0,1] for weighting, minimum weight 0.01
            val weight = ((sim + 1.0) / 2.0).coerceAtLeast(0.01)
            when (ex.action) {
                "LOVE", "LIKE" -> loveWeight += weight
                "DISLIKE" -> { /* contributes to totalWeight only */ }
                "PASS" -> loveWeight += weight * 0.3 // PASS is mildly positive
            }
            totalWeight += weight
        }

        return if (totalWeight > 0) (loveWeight / totalWeight).coerceIn(0.0, 1.0) else 0.5
    }

    override fun train(data: List<TrainingExample>): TrainResult {
        // k-NN is lazy — just store the data
        this.examples = data.filter { it.embedding.isNotEmpty() }

        // Leave-one-out cross-validation for accuracy
        if (examples.size < 4) return TrainResult(examples.size, 0.0)

        var correct = 0
        val evalExamples = examples.filter { it.action == "LOVE" || it.action == "LIKE" || it.action == "DISLIKE" }
        for (i in evalExamples.indices) {
            val held = evalExamples[i]
            val rest = examples.filterIndexed { j, _ -> j != i || examples[j].listingId != held.listingId }
            val neighbors = rest
                .map { it to cosineSimilarity(held.embedding, it.embedding) }
                .sortedByDescending { it.second }
                .take(k)

            var loveW = 0.0
            var totalW = 0.0
            for ((ex, sim) in neighbors) {
                val w = ((sim + 1.0) / 2.0).coerceAtLeast(0.01)
                if (ex.action == "LOVE" || ex.action == "LIKE") loveW += w
                totalW += w
            }
            val predLove = totalW > 0 && loveW / totalW >= 0.5
            val actualPositive = held.action == "LOVE" || held.action == "LIKE"
            if ((predLove && actualPositive) || (!predLove && held.action == "DISLIKE")) correct++
        }

        return TrainResult(
            examplesUsed = examples.size,
            accuracy = if (evalExamples.isNotEmpty()) correct.toDouble() / evalExamples.size else 0.0,
            details = mapOf("k" to "$k", "totalExamples" to "${examples.size}"),
        )
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom > 0) (dot / denom).coerceIn(-1.0, 1.0) else 0.0
    }
}
