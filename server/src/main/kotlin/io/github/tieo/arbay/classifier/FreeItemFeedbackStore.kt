package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

enum class FeedbackAction { LOVE, LIKE, DISLIKE, PASS }

@Serializable
data class StoredFeedback(
    val listingId: String,
    val title: String,
    val action: String,            // FeedbackAction.name
    val embedding: List<Float>,    // 512-dim text vector (distiluse)
    val imageEmbedding: List<Float>? = null, // 512-dim CLIP image vector; null when no photo / model unavailable
    // Rich metadata — nullable for backward compatibility with old feedback files
    val url: String? = null,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val relevanceScore: Double? = null,
    val timestamp: Instant? = null,
)

/**
 * Persists love/dislike feedback for free items.
 */
object FreeItemFeedbackStore {

    private val log = LoggerFactory.getLogger(FreeItemFeedbackStore::class.java)
    private val feedbackFile = DataDir.file("free_item_feedback.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val feedback = mutableListOf<StoredFeedback>()

    init {
        load()
    }

    fun add(listingId: String, title: String, action: FeedbackAction, listing: Listing? = null) {
        val embedding = EmbeddingModel.embed(title) ?: emptyList<Float>().toFloatArray()
        val imageUrl = listing?.imageUrls?.firstOrNull { it.startsWith("http") }
        // Embed the photo so the model learns from what the item looks like, not just its title.
        val imageEmbedding = ClipImageModel.embedUrl(imageUrl)?.toList()
        val stored = StoredFeedback(
            listingId = listingId,
            title = title,
            action = action.name,
            embedding = embedding.toList(),
            imageEmbedding = imageEmbedding,
            url = listing?.url,
            imageUrl = imageUrl,
            locationText = listing?.location?.let { loc ->
                loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
            },
            description = listing?.description,
            relevanceScore = listing?.relevanceScore,
            timestamp = Clock.System.now(),
        )
        synchronized(feedback) {
            feedback.removeAll { it.listingId == listingId }
            feedback.add(stored)
        }

        save()
    }

    fun lovedEmbeddings(): List<FloatArray> = synchronized(feedback) {
        feedback.filter { it.action == FeedbackAction.LOVE.name || it.action == FeedbackAction.LIKE.name }
            .filter { it.embedding.isNotEmpty() }
            .map { it.embedding.toFloatArray() }
    }

    fun dislikedEmbeddings(): List<FloatArray> = synchronized(feedback) {
        feedback.filter { it.action == FeedbackAction.DISLIKE.name }
            .filter { it.embedding.isNotEmpty() }
            .map { it.embedding.toFloatArray() }
    }

    /** CLIP image embeddings of loved/liked items that have a photo (for image affinity). */
    fun lovedImageEmbeddings(): List<FloatArray> = synchronized(feedback) {
        feedback.filter { it.action == FeedbackAction.LOVE.name || it.action == FeedbackAction.LIKE.name }
            .mapNotNull { it.imageEmbedding?.toFloatArray() }
    }

    fun dislikedImageEmbeddings(): List<FloatArray> = synchronized(feedback) {
        feedback.filter { it.action == FeedbackAction.DISLIKE.name }
            .mapNotNull { it.imageEmbedding?.toFloatArray() }
    }

    /** All feedback entries (for history display). Most recent first. */
    fun allFeedback(): List<StoredFeedback> = synchronized(feedback) {
        feedback.sortedByDescending { it.timestamp ?: Instant.DISTANT_PAST }.toList()
    }

    /** Only loved entries, most recent first. */
    fun lovedItems(): List<StoredFeedback> = synchronized(feedback) {
        feedback.filter { it.action == FeedbackAction.LOVE.name }
            .sortedByDescending { it.timestamp ?: Instant.DISTANT_PAST }
    }

    /** Only disliked entries, most recent first. */
    fun dislikedItems(): List<StoredFeedback> = synchronized(feedback) {
        feedback.filter { it.action == FeedbackAction.DISLIKE.name }
            .sortedByDescending { it.timestamp ?: Instant.DISTANT_PAST }
    }

    /** Aggregate stats. */
    fun stats(): FeedbackStats {
        val all = synchronized(feedback) { feedback.toList() }
        return FeedbackStats(
            totalLoved = all.count { it.action == FeedbackAction.LOVE.name },
            totalLiked = all.count { it.action == FeedbackAction.LIKE.name },
            totalDisliked = all.count { it.action == FeedbackAction.DISLIKE.name },
            totalSeen = all.size,
        )
    }

    /** Remove feedback for a listing (undo love/dislike). */
    fun removeFeedback(listingId: String): Boolean {
        val removed = synchronized(feedback) {
            feedback.removeAll { it.listingId == listingId }
        }
        if (removed) {
            save()
        }
        return removed
    }

    /** All listing IDs that have been loved or disliked. */
    fun seenIds(): List<String> = synchronized(feedback) {
        feedback.map { it.listingId }
    }

    private fun load() {
        val loaded = feedbackFile.readStore(log) { json.decodeFromString<List<StoredFeedback>>(it) } ?: return
        synchronized(feedback) { feedback.addAll(loaded) }
        log.info("Loaded ${loaded.size} free item feedback entries")
    }

    // The copy is taken and written under one lock, so a save that copied earlier can never
    // land on disk after one that copied later and roll the file back.
    private fun save() = synchronized(feedbackFile) {
        try {
            val copy = synchronized(feedback) { feedback.toList() }
            feedbackFile.writeTextAtomically(json.encodeToString(copy))
        } catch (e: Exception) {
            log.error("Could not save free item feedback: {}", e.message)
        }
    }
}

data class FeedbackStats(
    val totalLoved: Int,
    val totalLiked: Int = 0,
    val totalDisliked: Int,
    val totalSeen: Int,
)
