package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.VehicleInfo

/**
 * Ranks car listings by how well each matches a free-form "ideal car" description, using the local
 * distiluse text embeddings (no API cost, runs on CPU). The description and each listing's text
 * (title + description + a spec summary) are embedded and compared by cosine; every listing gets a
 * [Listing.matchScore] in [0,1] and the list is returned best-first. Identity (unranked, scores
 * left null) when the embedder is unavailable or the description is blank.
 */
object CarCriteriaScorer {

    fun rank(listings: List<Listing>, idealDescription: String): List<Listing> {
        if (listings.isEmpty() || idealDescription.isBlank()) return listings
        val target = EmbeddingModel.embed(idealDescription) ?: return listings
        return listings
            .map { listing ->
                val text = listingText(listing)
                val emb = if (text.isBlank()) null else EmbeddingModel.embed(text)
                listing.copy(matchScore = emb?.let { ((cosine(target, it) + 1.0) / 2.0).coerceIn(0.0, 1.0) })
            }
            .sortedByDescending { it.matchScore ?: -1.0 }
    }

    private fun listingText(l: Listing): String = buildString {
        append(l.title)
        l.description?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
        l.vehicle?.let { specSummary(it).takeIf(String::isNotBlank)?.let { s -> append(". ").append(s) } }
    }.take(2000)

    private fun specSummary(v: VehicleInfo): String = listOfNotNull(
        v.firstRegYear?.let { "year $it" },
        v.mileageKm?.let { "$it km" },
        v.powerKw?.let { "$it kW" },
        v.fuel?.name?.lowercase(),
        v.gearbox?.name?.lowercase(),
        v.bodyType?.name?.lowercase(),
        v.color?.lowercase(),
        v.upholstery,
    ).joinToString(", ")

    /** Cosine of two L2-normalized vectors is their dot product. */
    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
