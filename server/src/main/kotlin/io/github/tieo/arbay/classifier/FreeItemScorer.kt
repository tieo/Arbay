package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.model.Listing

/**
 * Scores free listings against user interests using a three-signal approach:
 * 1. Profile similarity: semantic similarity to user's interest description
 * 2. Love boost: similarity to previously loved listings
 * 3. Dislike penalty: similarity to previously disliked listings
 *
 * Final score is in [0.0, 1.0]. Returns null if embedding model is unavailable
 * (falls back to keyword-only scoring in that case).
 */
object FreeItemScorer {

    /**
     * Score a listing. Returns a value in [0.0, 1.0].
     * Higher = more relevant to user interests.
     *
     * @param profileText raw profile text for keyword boosting (optional, supplements embeddings)
     */
    fun score(listing: Listing, profileEmbedding: FloatArray?, profileText: String? = null): Double {
        val raw = buildString {
            append(listing.title)
            listing.description?.let { append(" $it") }
        }
        // Strip "zu verschenken" and similar freebie noise before embedding —
        // these words appear in nearly every listing and dilute semantic similarity
        val text = raw
            .replace(Regex("(?i)zu verschenken|kostenlos|gratis|abzugeben|selbstabholung|abholung"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        val lovedEmbeddings = FreeItemFeedbackStore.lovedEmbeddings()
        val dislikedEmbeddings = FreeItemFeedbackStore.dislikedEmbeddings()

        // If embedding model is unavailable, all items score equally
        val listingEmbedding = EmbeddingModel.embed(text) ?: return 0.5

        // ── Signal 1: Profile similarity [0,1] ──────────────────────────────────
        // Maps cosine similarity from [-1,1] → [0,1]. This is the primary signal.
        val profileSim = if (profileEmbedding != null) {
            (EmbeddingModel.similarity(listingEmbedding, profileEmbedding) + 1.0) / 2.0
        } else {
            0.5 // neutral when no profile set
        }

        // ── Signal 2: Love boost [0,1] ───────────────────────────────────────────
        // If the user loved similar things, boost this listing proportionally.
        val loveSim = if (lovedEmbeddings.isNotEmpty()) {
            val maxLoveSim = lovedEmbeddings.maxOf { EmbeddingModel.similarity(listingEmbedding, it) }
            (maxLoveSim + 1.0) / 2.0
        } else {
            null // no loved items yet — signal inactive
        }

        // ── Signal 3: Dislike penalty [0,1] ─────────────────────────────────────
        // The highest similarity to any disliked item. Only subtract if the listing
        // is *more* similar to a disliked item than to the profile — avoids globally
        // depressing all scores just because the user disliked one thing.
        val dislikeSim = if (dislikedEmbeddings.isNotEmpty()) {
            val maxDislikeSim = dislikedEmbeddings.maxOf { EmbeddingModel.similarity(listingEmbedding, it) }
            (maxDislikeSim + 1.0) / 2.0
        } else {
            null // no dislikes yet — signal inactive
        }

        // ── Combine signals ──────────────────────────────────────────────────────
        // Base score: profile similarity is the primary driver (weight 0.7).
        // Love signal adds up to 0.3 on top, only when we have loved items.
        // Dislike penalty only applies when the listing is more similar to disliked
        // items than to the profile — this prevents a disliked "sofa" from penalizing
        // every "electronics" listing equally.
        var score = profileSim * 0.7

        if (loveSim != null) {
            // Blend in the love signal: adds up to 0.3 when loveSim=1
            score += loveSim * 0.3
        }

        if (dislikeSim != null) {
            // Only subtract the portion of dislike similarity that *exceeds* profile similarity.
            // This means: "penalise this listing only to the degree it resembles dislikes
            // MORE than it resembles the user's stated interests."
            val netPenalty = (dislikeSim - profileSim).coerceAtLeast(0.0)
            score -= netPenalty * 0.5
        }

        // ── Signal 4: Direct keyword boost ──────────────────────────────────────
        // Embedding similarities for German marketplace text cluster in a narrow 3–6% band.
        // A direct lexical match (profile word appears in listing) provides an additional
        // discriminative signal that breaks ties and rewards exact content matches.
        if (profileText != null) {
            val profileWords = profileText.lowercase()
                .replace(Regex("[^a-z0-9äöüß\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 4 }
                .toSet()
            val listingWords = text.lowercase()
            val matchRatio = if (profileWords.isEmpty()) 0.0
                else profileWords.count { pw -> listingWords.contains(pw) }.toDouble() / profileWords.size
            // Max boost: 0.15 (when every profile word appears in listing)
            score += matchRatio * 0.15
        }

        return score.coerceIn(0.0, 1.0)
    }

    /** Embed the user's profile text. Returns null if model unavailable. */
    fun embedProfile(profileText: String): FloatArray? {
        // Embed in multiple languages to improve cross-lingual matching.
        // distiluse multilingual aligns English and German in the same space,
        // but short English phrases can drift — an explicit German version anchors it.
        val germanMirror = profileText
            .replace(Regex("(?i)electronics?"), "Elektronik")
            .replace(Regex("(?i)electric(al)?( stuff)?"), "Elektrogeräte Elektrik")
            .replace(Regex("(?i)computers?"), "Computer")
            .replace(Regex("(?i)phones?|smartphones?"), "Handy Smartphone")
            .replace(Regex("(?i)monitors?|screens?|displays?"), "Monitor Bildschirm")
            .replace(Regex("(?i)cables?|chargers?"), "Kabel Ladegerät")
        val combined = "$profileText $germanMirror"
        return EmbeddingModel.embed(combined)
    }
}
