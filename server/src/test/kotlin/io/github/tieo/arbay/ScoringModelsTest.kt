package io.github.tieo.arbay

import io.github.tieo.arbay.classifier.*
import io.github.tieo.arbay.classifier.models.*
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.*

/**
 * Tests for all scoring model implementations.
 * Uses synthetic embeddings to avoid dependency on ONNX model.
 */
class ScoringModelsTest {

    private val rng = Random(42)

    /** Create a synthetic embedding that's "about" a topic by setting specific dimensions high. */
    private fun syntheticEmbedding(seed: Int, dims: Int = 512): FloatArray {
        val r = Random(seed)
        return FloatArray(dims) { r.nextFloat() * 2f - 1f }.also { normalize(it) }
    }

    private fun normalize(v: FloatArray) {
        val norm = kotlin.math.sqrt(v.map { it.toDouble() * it }.sum()).toFloat()
        if (norm > 0) for (i in v.indices) v[i] /= norm
    }

    /** Create a cluster of similar embeddings (same base + small noise). */
    private fun cluster(baseSeed: Int, count: Int): List<FloatArray> {
        val base = syntheticEmbedding(baseSeed)
        return (0 until count).map { i ->
            val noise = syntheticEmbedding(baseSeed * 1000 + i)
            FloatArray(512) { base[it] * 0.9f + noise[it] * 0.1f }.also { normalize(it) }
        }
    }

    private fun contextWith(
        profileSeed: Int? = null,
        lovedSeeds: List<Int> = emptyList(),
        dislikedSeeds: List<Int> = emptyList(),
    ) = ScoringContext(
        profileEmbedding = profileSeed?.let { syntheticEmbedding(it) },
        profileText = "electronics monitors computers",
        lovedEmbeddings = lovedSeeds.map { syntheticEmbedding(it) },
        dislikedEmbeddings = dislikedSeeds.map { syntheticEmbedding(it) },
    )

    private fun trainingData(
        lovedSeeds: List<Int>,
        dislikedSeeds: List<Int>,
        passedSeeds: List<Int> = emptyList(),
    ): List<TrainingExample> {
        return lovedSeeds.map { TrainingExample(syntheticEmbedding(it), "LOVE", "loved-$it", "id-l-$it") } +
            dislikedSeeds.map { TrainingExample(syntheticEmbedding(it), "DISLIKE", "disliked-$it", "id-d-$it") } +
            passedSeeds.map { TrainingExample(syntheticEmbedding(it), "PASS", "passed-$it", "id-p-$it") }
    }

    // ── Logistic Regression Model ───────────────────────────────────────────

    @Test
    fun `logistic - scores are in 0 to 1 range`() {
        val model = LogisticRegressionModel()
        val ctx = contextWith(profileSeed = 1)
        // Before training, should still return valid scores
        repeat(20) {
            val score = model.score(syntheticEmbedding(it), "test", ctx)
            assertTrue(score in 0.0..1.0, "Score $score out of range")
        }
    }

    @Test
    fun `logistic - untrained model returns neutral scores`() {
        val model = LogisticRegressionModel()
        val ctx = contextWith()
        val score = model.score(syntheticEmbedding(42), "test", ctx)
        // Untrained should be around 0.5
        assertTrue(abs(score - 0.5) < 0.2, "Untrained score should be near 0.5, got $score")
    }

    @Test
    fun `logistic - learns to separate loved from disliked`() {
        val model = LogisticRegressionModel()
        // Loved items cluster around seed 1, disliked around seed 50
        val lovedCluster = cluster(1, 15)
        val dislikedCluster = cluster(50, 15)
        val data = lovedCluster.mapIndexed { i, e -> TrainingExample(e, "LOVE", "loved-$i", "l-$i") } +
            dislikedCluster.mapIndexed { i, e -> TrainingExample(e, "DISLIKE", "disliked-$i", "d-$i") }

        val result = model.train(data)
        assertTrue(result.examplesUsed > 0, "Should use training examples")

        val ctx = contextWith()
        // New items from the loved cluster should score higher
        val lovedTest = cluster(1, 3)
        val dislikedTest = cluster(50, 3)
        val avgLoved = lovedTest.map { model.score(it, "test", ctx) }.average()
        val avgDisliked = dislikedTest.map { model.score(it, "test", ctx) }.average()
        assertTrue(avgLoved > avgDisliked, "Loved cluster ($avgLoved) should outscore disliked ($avgDisliked)")
    }

    @Test
    fun `logistic - is trainable`() {
        assertTrue(LogisticRegressionModel().trainable)
    }

    @Test
    fun `logistic - handles empty training data`() {
        val model = LogisticRegressionModel()
        val result = model.train(emptyList())
        assertEquals(0, result.examplesUsed)
    }

    @Test
    fun `logistic - handles all same label`() {
        val model = LogisticRegressionModel()
        val data = (1..10).map { TrainingExample(syntheticEmbedding(it), "LOVE", "t-$it", "id-$it") }
        val result = model.train(data)
        // Should not crash, may not learn much
        assertTrue(result.examplesUsed >= 0)
    }

    // ── k-NN Model ──────────────────────────────────────────────────────────

    @Test
    fun `knn - scores are in 0 to 1 range`() {
        val model = KnnModel()
        val ctx = contextWith()
        repeat(20) {
            val score = model.score(syntheticEmbedding(it), "test", ctx)
            assertTrue(score in 0.0..1.0, "Score $score out of range")
        }
    }

    @Test
    fun `knn - untrained returns neutral`() {
        val model = KnnModel()
        val ctx = contextWith()
        val score = model.score(syntheticEmbedding(1), "test", ctx)
        assertTrue(abs(score - 0.5) < 0.2, "Untrained should be ~0.5, got $score")
    }

    @Test
    fun `knn - learns from feedback`() {
        val model = KnnModel()
        val lovedCluster = cluster(1, 10)
        val dislikedCluster = cluster(50, 10)
        val data = lovedCluster.mapIndexed { i, e -> TrainingExample(e, "LOVE", "l-$i", "l-$i") } +
            dislikedCluster.mapIndexed { i, e -> TrainingExample(e, "DISLIKE", "d-$i", "d-$i") }
        model.train(data)

        val ctx = contextWith()
        val lovedTest = cluster(1, 3)
        val dislikedTest = cluster(50, 3)
        val avgLoved = lovedTest.map { model.score(it, "test", ctx) }.average()
        val avgDisliked = dislikedTest.map { model.score(it, "test", ctx) }.average()
        assertTrue(avgLoved > avgDisliked, "Loved cluster ($avgLoved) should outscore disliked ($avgDisliked)")
    }

    @Test
    fun `knn - is trainable`() {
        assertTrue(KnnModel().trainable)
    }

    // ── Centroid Projection Model ───────────────────────────────────────────

    @Test
    fun `centroid - scores are in 0 to 1 range`() {
        val model = CentroidModel()
        val ctx = contextWith()
        repeat(20) {
            val score = model.score(syntheticEmbedding(it), "test", ctx)
            assertTrue(score in 0.0..1.0, "Score $score out of range")
        }
    }

    @Test
    fun `centroid - untrained returns neutral`() {
        val model = CentroidModel()
        val ctx = contextWith()
        val score = model.score(syntheticEmbedding(1), "test", ctx)
        assertTrue(abs(score - 0.5) < 0.2, "Untrained should be ~0.5, got $score")
    }

    @Test
    fun `centroid - learns to separate clusters`() {
        val model = CentroidModel()
        val lovedCluster = cluster(1, 10)
        val dislikedCluster = cluster(50, 10)
        val data = lovedCluster.mapIndexed { i, e -> TrainingExample(e, "LOVE", "l-$i", "l-$i") } +
            dislikedCluster.mapIndexed { i, e -> TrainingExample(e, "DISLIKE", "d-$i", "d-$i") }
        model.train(data)

        val ctx = contextWith()
        val lovedTest = cluster(1, 3)
        val dislikedTest = cluster(50, 3)
        val avgLoved = lovedTest.map { model.score(it, "test", ctx) }.average()
        val avgDisliked = dislikedTest.map { model.score(it, "test", ctx) }.average()
        assertTrue(avgLoved > avgDisliked, "Loved cluster ($avgLoved) should outscore disliked ($avgDisliked)")
    }

    @Test
    fun `centroid - is trainable`() {
        assertTrue(CentroidModel().trainable)
    }

    // ── Cold-start (untrained, profile-driven) ──────────────────────────────

    @Test
    fun `cold-start ranks profile-similar items above dissimilar ones on every untrained model`() {
        // With a profile set but no feedback yet, the learned models are untrained. They must
        // still rank by profile similarity instead of emitting a flat 0.5 for everything — the
        // "always 50%" bug. Profile sits on cluster 1; near item resembles it, far item does not.
        val ctx = contextWith(profileSeed = 1)
        val near = cluster(1, 1).first()
        val far = cluster(50, 1).first()

        for (model in listOf(LogisticRegressionModel(), KnnModel(), CentroidModel())) {
            val nearScore = model.score(near, "test", ctx)
            val farScore = model.score(far, "test", ctx)
            assertTrue(nearScore > farScore,
                "${model.id}: profile-near ($nearScore) should outscore far ($farScore) at cold-start")
        }
    }

    @Test
    fun `likes alone reshape ranking on untrained logistic without any dislikes`() {
        // The reported failure: the user only clicks "like" (no dislikes). Logistic never trains
        // (it needs both classes), so it must lean on the love signal from the cold-start path.
        // An item resembling a liked one has to outrank one that does not.
        val model = LogisticRegressionModel()
        val before = contextWith() // nothing liked yet
        val ctxLiked = contextWith(lovedSeeds = listOf(50)) // now something on cluster 50 is liked

        val nearLiked = cluster(50, 1).first()
        val farFromLiked = cluster(1, 1).first()

        assertTrue(model.train(emptyList()).examplesUsed == 0) // still untrained
        val nearBefore = model.score(nearLiked, "test", before)
        val nearAfter = model.score(nearLiked, "test", ctxLiked)
        val farAfter = model.score(farFromLiked, "test", ctxLiked)

        assertTrue(nearAfter > nearBefore, "Liking a similar item must raise its score ($nearBefore → $nearAfter)")
        assertTrue(nearAfter > farAfter, "Item near a liked one ($nearAfter) must outrank a far one ($farAfter)")
    }

    @Test
    fun `cold-start does not collapse every item to a single constant`() {
        // The regression guard for the reported bug: a spread of listings under a set profile
        // must not all receive the identical score.
        val ctx = contextWith(profileSeed = 1)
        val scores = (1..30).map { LogisticRegressionModel().score(syntheticEmbedding(it * 7), "test", ctx) }
        assertTrue(scores.toSet().size > 1, "Cold-start scores must vary, got all=${scores.first()}")
    }

    // ── Cross-model consistency ─────────────────────────────────────────────

    @Test
    fun `all models agree on obvious cases after training`() {
        val models = listOf(LogisticRegressionModel(), KnnModel(), CentroidModel())
        val lovedCluster = cluster(1, 15)
        val dislikedCluster = cluster(50, 15)
        val data = lovedCluster.mapIndexed { i, e -> TrainingExample(e, "LOVE", "l-$i", "l-$i") } +
            dislikedCluster.mapIndexed { i, e -> TrainingExample(e, "DISLIKE", "d-$i", "d-$i") }
        models.forEach { it.train(data) }

        val ctx = contextWith()
        val obviouslyGood = cluster(1, 1).first()
        val obviouslyBad = cluster(50, 1).first()

        for (model in models) {
            val goodScore = model.score(obviouslyGood, "test", ctx)
            val badScore = model.score(obviouslyBad, "test", ctx)
            assertTrue(goodScore > badScore,
                "${model.id}: good ($goodScore) should outscore bad ($badScore)")
        }
    }
}
