package io.github.tieo.arbay.classifier

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Tracks per-model prediction accuracy from user swipes.
 *
 * When items are scored, all model scores are stored as predictions.
 * When the user swipes (LOVE/DISLIKE/PASS), each model's prediction
 * is evaluated: was it correct?
 *
 * A prediction is "correct" if:
 * - Model score >= 0.5 and user LOVEd it
 * - Model score < 0.5 and user DISLIKEd it
 * - PASS is ignored for accuracy (ambiguous signal)
 */
object ModelArena {

    private val log = LoggerFactory.getLogger(ModelArena::class.java)
    private val file = File(System.getProperty("user.home"), ".arbay/model_arena.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    // listingId → map of modelId → predicted score
    private val predictions = mutableMapOf<String, MutableMap<String, Double>>()

    // modelId → running stats
    private val stats = mutableMapOf<String, ModelStats>()

    init {
        load()
    }

    /**
     * Record predictions from all models for a listing.
     * Called when items are scored in the stream.
     */
    fun recordPredictions(listingId: String, scores: Map<String, Double>) {
        synchronized(predictions) {
            predictions[listingId] = scores.toMutableMap()
        }
    }

    /**
     * Record the user's actual action (swipe result).
     * Evaluates each model's prediction against ground truth.
     */
    fun recordOutcome(listingId: String, action: String) {
        if (action == "PASS") return // ambiguous — don't count
        // LIKE is treated as positive signal (same as LOVE for model evaluation)

        val preds = synchronized(predictions) {
            predictions.remove(listingId) ?: return
        }

        for ((modelId, score) in preds) {
            val modelStats = stats.getOrPut(modelId) { ModelStats(modelId) }
            val predictedLove = score >= 0.5
            val actualLove = action == "LOVE" || action == "LIKE"
            val correct = predictedLove == actualLove

            modelStats.totalPredictions++
            if (correct) modelStats.correctPredictions++
            if (actualLove && predictedLove) modelStats.truePositives++
            if (!actualLove && !predictedLove) modelStats.trueNegatives++
            if (actualLove && !predictedLove) modelStats.falseNegatives++
            if (!actualLove && predictedLove) modelStats.falsePositives++

            // Track score distributions for loved vs disliked
            if (actualLove) modelStats.lovedScoreSum += score
            else modelStats.dislikedScoreSum += score
            if (actualLove) modelStats.lovedCount++ else modelStats.dislikedCount++

            // Track worst predictions (most wrong)
            val confidence = if (predictedLove) score else 1.0 - score
            if (!correct && confidence > 0.3) {
                modelStats.worstPredictions.add(
                    WrongPrediction(listingId, score, action, confidence)
                )
                // Keep only the 20 worst
                if (modelStats.worstPredictions.size > 20) {
                    modelStats.worstPredictions.sortByDescending { it.confidence }
                    while (modelStats.worstPredictions.size > 20) modelStats.worstPredictions.removeLast()
                }
            }
        }

        save()
    }

    fun getStats(): List<ModelStats> = stats.values.toList()

    fun getStats(modelId: String): ModelStats? = stats[modelId]

    fun leaderboard(): List<ModelLeaderboardEntry> {
        return stats.values
            .filter { it.totalPredictions >= 5 }
            .map { s ->
                ModelLeaderboardEntry(
                    modelId = s.modelId,
                    accuracy = s.accuracy,
                    precision = s.precision,
                    recall = s.recall,
                    separation = s.separation,
                    totalPredictions = s.totalPredictions,
                    falseNegatives = s.falseNegatives,
                )
            }
            .sortedByDescending { it.accuracy }
    }

    /** Clear all tracking data. */
    fun clear() {
        synchronized(predictions) { predictions.clear() }
        stats.clear()
        file.delete()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            val loaded = json.decodeFromString<List<ModelStats>>(file.readText())
            for (s in loaded) stats[s.modelId] = s
            log.info("Loaded arena stats for {} models", loaded.size)
        } catch (e: Exception) {
            log.warn("Could not load arena stats: {}", e.message)
        }
    }

    private fun save() {
        try {
            file.parentFile.mkdirs()
            file.writeText(json.encodeToString(stats.values.toList()))
        } catch (e: Exception) {
            log.warn("Could not save arena stats: {}", e.message)
        }
    }
}

@Serializable
data class ModelStats(
    val modelId: String,
    var totalPredictions: Int = 0,
    var correctPredictions: Int = 0,
    var truePositives: Int = 0,
    var trueNegatives: Int = 0,
    var falsePositives: Int = 0,
    var falseNegatives: Int = 0,
    var lovedScoreSum: Double = 0.0,
    var dislikedScoreSum: Double = 0.0,
    var lovedCount: Int = 0,
    var dislikedCount: Int = 0,
    val worstPredictions: MutableList<WrongPrediction> = mutableListOf(),
) {
    val accuracy: Double get() = if (totalPredictions > 0) correctPredictions.toDouble() / totalPredictions else 0.0
    val precision: Double get() {
        val predicted = truePositives + falsePositives
        return if (predicted > 0) truePositives.toDouble() / predicted else 0.0
    }
    val recall: Double get() {
        val actual = truePositives + falseNegatives
        return if (actual > 0) truePositives.toDouble() / actual else 0.0
    }
    val separation: Double get() {
        val avgLoved = if (lovedCount > 0) lovedScoreSum / lovedCount else 0.0
        val avgDisliked = if (dislikedCount > 0) dislikedScoreSum / dislikedCount else 0.0
        return avgLoved - avgDisliked
    }
}

@Serializable
data class WrongPrediction(
    val listingId: String,
    val predictedScore: Double,
    val actualAction: String,
    val confidence: Double,
)

@Serializable
data class ModelLeaderboardEntry(
    val modelId: String,
    val accuracy: Double,
    val precision: Double,
    val recall: Double,
    val separation: Double,
    val totalPredictions: Int,
    val falseNegatives: Int,
)
