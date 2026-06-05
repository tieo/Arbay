package io.github.tieo.arbay.classifier

/**
 * Interface for all free-item scoring models.
 * Each model scores a listing given its embedding and context,
 * producing a value in [0.0, 1.0].
 */
interface ScoringModel {
    /** Unique identifier for this model (e.g., "logistic", "knn", "centroid"). */
    val id: String

    /** Human-readable name. */
    val name: String

    /** Whether this model can be retrained from feedback data. */
    val trainable: Boolean

    /**
     * Score a listing.
     * @param listingEmbedding 512-dim embedding of the listing text
     * @param listingText raw listing text (title + description) for keyword-based signals
     * @param context shared context (profile embedding, feedback data, etc.)
     * @return score in [0.0, 1.0], higher = more relevant
     */
    fun score(listingEmbedding: FloatArray, listingText: String, context: ScoringContext): Double

    /**
     * Train or retrain the model from feedback data.
     * Only meaningful for [trainable] models.
     * @return training metrics (e.g., accuracy on held-out data)
     */
    fun train(data: List<TrainingExample>): TrainResult = TrainResult(0, 0.0)
}

/**
 * Shared context passed to all models during scoring.
 * Avoids each model independently fetching feedback data.
 */
data class ScoringContext(
    val profileEmbedding: FloatArray?,
    val profileText: String?,
    val lovedEmbeddings: List<FloatArray>,
    val dislikedEmbeddings: List<FloatArray>,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

data class TrainingExample(
    val embedding: FloatArray,
    val action: String, // LOVE, DISLIKE, PASS
    val title: String,
    val listingId: String,
) {
    override fun equals(other: Any?): Boolean = listingId == (other as? TrainingExample)?.listingId
    override fun hashCode(): Int = listingId.hashCode()
}

data class TrainResult(
    val examplesUsed: Int,
    val accuracy: Double,
    val details: Map<String, String> = emptyMap(),
)
