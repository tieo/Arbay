package io.github.tieo.arbay.classifier.models

import io.github.tieo.arbay.classifier.*
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Logistic regression trained on embedding vectors.
 * Learns a weight vector w and bias b such that P(LOVE) = sigmoid(w . x + b).
 *
 * Uses mini-batch gradient descent with L2 regularization.
 * Retrains from scratch on each call to [train] — fast enough for <10k examples.
 */
class LogisticRegressionModel : ScoringModel {
    override val id = "logistic"
    override val name = "Logistic Regression"
    override val trainable = true

    @Volatile private var weights: FloatArray? = null
    @Volatile private var bias: Float = 0f
    @Volatile private var trained = false

    override fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext): Double {
        val w = weights ?: return 0.5
        return sigmoid(dot(w, listingEmbedding) + bias)
    }

    override fun train(data: List<TrainingExample>): TrainResult {
        // Need at least some data with both classes
        val examples = data.filter { it.action == "LOVE" || it.action == "LIKE" || it.action == "DISLIKE" }
        if (examples.size < 4) return TrainResult(0, 0.0)

        val hasLove = examples.any { it.action == "LOVE" || it.action == "LIKE" }
        val hasDislike = examples.any { it.action == "DISLIKE" }
        if (!hasLove || !hasDislike) return TrainResult(examples.size, 0.5)

        val dims = examples.first().embedding.size
        val w = FloatArray(dims) { 0f }
        var b = 0f

        val lr = 0.1f
        val lambda = 0.001f // L2 regularization
        val epochs = 200

        for (epoch in 0 until epochs) {
            var totalLoss = 0.0
            for (ex in examples.shuffled()) {
                val y = if (ex.action == "LOVE" || ex.action == "LIKE") 1f else 0f
                val pred = sigmoid(dot(w, ex.embedding) + b).toFloat()
                val error = pred - y
                totalLoss += -y * kotlin.math.ln((pred + 1e-7f).toDouble()) -
                    (1 - y) * kotlin.math.ln((1 - pred + 1e-7f).toDouble())

                // Gradient update
                for (i in w.indices) {
                    w[i] -= lr * (error * ex.embedding[i] + lambda * w[i])
                }
                b -= lr * error
            }
        }

        this.weights = w
        this.bias = b
        this.trained = true

        // Evaluate on training data
        var correct = 0
        for (ex in examples) {
            val pred = sigmoid(dot(w, ex.embedding) + b)
            val predPositive = pred >= 0.5
            val actualPositive = ex.action == "LOVE" || ex.action == "LIKE"
            if (predPositive == actualPositive) correct++
        }
        val accuracy = correct.toDouble() / examples.size

        return TrainResult(
            examplesUsed = examples.size,
            accuracy = accuracy,
            details = mapOf(
                "epochs" to "$epochs",
                "dims" to "$dims",
                "bias" to "%.4f".format(b),
            ),
        )
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
    private fun sigmoid(x: Float): Double = sigmoid(x.toDouble())

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
