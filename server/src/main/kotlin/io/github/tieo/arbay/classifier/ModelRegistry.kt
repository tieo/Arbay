package io.github.tieo.arbay.classifier

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of all scoring models. Scores items with all models,
 * tracks which model is active (drives feed ordering), and
 * manages training.
 */
object ModelRegistry {

    private val log = LoggerFactory.getLogger(ModelRegistry::class.java)
    private val models = ConcurrentHashMap<String, ScoringModel>()

    /** Cache of listing embeddings for fast rescoring after retrain. */
    private data class CachedEmbedding(val embedding: FloatArray, val text: String)
    private val embeddingCache = ConcurrentHashMap<String, CachedEmbedding>()

    @Volatile
    var activeModelId: String = "logistic"
        private set

    fun register(model: ScoringModel) {
        models[model.id] = model
        log.info("Registered scoring model: {} ({})", model.id, model.name)
    }

    fun get(id: String): ScoringModel? = models[id]

    fun activeModel(): ScoringModel? = models[activeModelId]

    fun allModels(): List<ScoringModel> = models.values.toList()

    fun setActive(modelId: String) {
        require(models.containsKey(modelId)) { "Unknown model: $modelId" }
        activeModelId = modelId
        log.info("Active scoring model changed to: {}", modelId)
    }

    /**
     * Score a listing with ALL registered models.
     * Returns map of modelId → score.
     */
    fun scoreAll(listingEmbedding: FloatArray, listingText: String, context: ScoringContext): Map<String, Double> {
        return models.mapValues { (_, model) ->
            try {
                model.score(listingEmbedding, listingText, context)
            } catch (e: Exception) {
                log.warn("Model {} failed to score: {}", model.id, e.message)
                0.0
            }
        }
    }

    /**
     * Retrain all trainable models from current feedback data.
     * Returns map of modelId → TrainResult.
     */
    fun retrainAll(): Map<String, TrainResult> {
        val data = buildTrainingData()
        if (data.isEmpty()) return emptyMap()

        return models.filter { it.value.trainable }.mapValues { (_, model) ->
            try {
                model.train(data).also {
                    log.info("Retrained {}: {} examples, accuracy={}", model.id, it.examplesUsed, it.accuracy)
                }
            } catch (e: Exception) {
                log.warn("Failed to retrain {}: {}", model.id, e.message)
                TrainResult(0, 0.0, mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    private fun buildTrainingData(): List<TrainingExample> {
        return FreeItemFeedbackStore.allFeedback()
            .filter { it.embedding.isNotEmpty() }
            .map { f ->
                TrainingExample(
                    embedding = f.embedding.toFloatArray(),
                    action = f.action,
                    title = f.title,
                    listingId = f.listingId,
                )
            }
    }

    /** Cache an embedding for later rescoring. */
    fun cacheEmbedding(listingId: String, embedding: FloatArray, text: String) {
        embeddingCache[listingId] = CachedEmbedding(embedding, text)
    }

    /**
     * Rescore cached listings using the active model.
     * Returns map of listingId → new active score, only for IDs found in cache.
     */
    fun rescoreCached(listingIds: List<String>): Map<String, Double> {
        val context = buildContext()
        val active = activeModel() ?: return emptyMap()
        return listingIds.mapNotNull { id ->
            val cached = embeddingCache[id] ?: return@mapNotNull null
            val score = try {
                active.score(cached.embedding, cached.text, context)
            } catch (e: Exception) {
                log.warn("Rescore failed for {}: {}", id, e.message)
                0.0
            }
            id to score
        }.toMap()
    }

    /** Build a ScoringContext from current state. */
    fun buildContext(): ScoringContext {
        return ScoringContext(
            profileEmbedding = FreeItemProfileStore.getEmbedding(),
            profileText = FreeItemProfileStore.get()?.description,
            lovedEmbeddings = FreeItemFeedbackStore.lovedEmbeddings(),
            dislikedEmbeddings = FreeItemFeedbackStore.dislikedEmbeddings(),
        )
    }

    /** For testing — remove all models. */
    internal fun clear() {
        models.clear()
        embeddingCache.clear()
        activeModelId = "logistic"
    }
}
