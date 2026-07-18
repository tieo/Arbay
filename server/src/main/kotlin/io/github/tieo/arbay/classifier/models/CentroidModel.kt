package io.github.tieo.arbay.classifier.models

import io.github.tieo.arbay.classifier.*

/**
 * Centroid-based model with learned linear projection.
 *
 * 1. Computes centroids for LOVE and DISLIKE clusters
 * 2. Learns a diagonal projection (per-dimension weights) that maximizes
 *    the separation between centroids via Fisher's criterion
 * 3. Scores by comparing projected distance to love centroid vs dislike centroid
 *
 * More principled than raw cosine similarity — learns which embedding dimensions
 * matter most for this user's preferences.
 */
class CentroidModel : ScoringModel {
    override val id = "centroid"
    override val name = "Centroid Projection"
    override val trainable = true

    @Volatile private var loveCentroid: FloatArray? = null
    @Volatile private var dislikeCentroid: FloatArray? = null
    @Volatile private var projection: FloatArray? = null // per-dimension weights

    override fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext): Double {
        // Untrained: fall back to the profile-driven cold-start ranking rather than a flat 0.5.
        fun coldStart() = FreeItemScorer.scoreEmbedding(
            listingEmbedding, listingText,
            context.profileEmbedding, context.profileText,
            context.lovedEmbeddings, context.dislikedEmbeddings,
        )
        val lc = loveCentroid ?: return coldStart()
        val dc = dislikeCentroid ?: return coldStart()
        val proj = projection ?: return coldStart()

        // Project embedding and centroids
        val projected = applyProjection(listingEmbedding, proj)
        val projLove = applyProjection(lc, proj)
        val projDislike = applyProjection(dc, proj)

        val distToLove = euclideanDist(projected, projLove)
        val distToDislike = euclideanDist(projected, projDislike)

        // Convert distances to a score: closer to love = higher score
        // Using softmax-style: score = exp(-distLove) / (exp(-distLove) + exp(-distDislike))
        val total = distToLove + distToDislike
        return if (total > 0) (distToDislike / total).coerceIn(0.0, 1.0) else 0.5
    }

    override fun train(data: List<TrainingExample>): TrainResult {
        val loved = data.filter { (it.action == "LOVE" || it.action == "LIKE") && it.embedding.isNotEmpty() }
        val disliked = data.filter { it.action == "DISLIKE" && it.embedding.isNotEmpty() }

        if (loved.size < 2 || disliked.size < 2) {
            return TrainResult(data.size, 0.0, mapOf("error" to "Need >= 2 loved and 2 disliked"))
        }

        val dims = loved.first().embedding.size
        val lc = centroid(loved.map { it.embedding }, dims)
        val dc = centroid(disliked.map { it.embedding }, dims)

        // Fisher's Linear Discriminant: weight dimensions by (mean_diff)^2 / (variance_sum)
        // Dimensions where the centroids differ a lot and variance is low get high weight
        val proj = FloatArray(dims)
        for (d in 0 until dims) {
            val meanDiff = lc[d] - dc[d]
            val varLove = loved.map { (it.embedding[d] - lc[d]).let { v -> v * v } }.average().toFloat()
            val varDislike = disliked.map { (it.embedding[d] - dc[d]).let { v -> v * v } }.average().toFloat()
            val varSum = varLove + varDislike + 1e-8f
            proj[d] = (meanDiff * meanDiff) / varSum
        }

        // Normalize projection weights
        val maxProj = proj.max()
        if (maxProj > 0) for (i in proj.indices) proj[i] /= maxProj

        this.loveCentroid = lc
        this.dislikeCentroid = dc
        this.projection = proj

        // Evaluate accuracy
        val evalData = data.filter { it.action == "LOVE" || it.action == "LIKE" || it.action == "DISLIKE" }
        var correct = 0
        for (ex in evalData) {
            val s = score(ex.embedding, "", ScoringContext(null, null, emptyList(), emptyList()))
            val predLove = s >= 0.5
            val actualPositive = ex.action == "LOVE" || ex.action == "LIKE"
            if ((predLove && actualPositive) || (!predLove && ex.action == "DISLIKE")) correct++
        }

        return TrainResult(
            examplesUsed = data.size,
            accuracy = correct.toDouble() / evalData.size,
            details = mapOf(
                "lovedCount" to "${loved.size}",
                "dislikedCount" to "${disliked.size}",
                "topDims" to proj.indices.sortedByDescending { proj[it] }.take(5).joinToString(","),
            ),
        )
    }

    private fun centroid(embeddings: List<FloatArray>, dims: Int): FloatArray {
        val c = FloatArray(dims)
        for (emb in embeddings) for (i in c.indices) c[i] += emb[i]
        val n = embeddings.size.toFloat()
        for (i in c.indices) c[i] /= n
        return c
    }

    private fun applyProjection(v: FloatArray, proj: FloatArray): FloatArray {
        return FloatArray(v.size) { v[it] * proj[it] }
    }

    private fun euclideanDist(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return kotlin.math.sqrt(sum)
    }
}
