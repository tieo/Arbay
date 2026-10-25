package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.classifier.ClipImageModel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ModelWish
import io.github.tieo.arbay.model.ProductWishes
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.repo.ListingArchive
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Which of a search's models an offer's photos look like, told from the photos of every archived
 * offer of that search whose title names its model, and how often that is right.
 *
 * The references are the archive rather than one hour's answer: a search returns a few dozen offers,
 * the archive holds every one it ever returned, and a model seen in ten photos is told far better than
 * one seen in two. An archived offer counts as one of the search's own when its text carries a word of
 * the search that is not a model word (a "Cobra" golf club says no "Parkett"), and it is a reference
 * when its title names exactly one model, or a reviewer said which model it is ([ModelGuesses.check]).
 *
 * [measure] holds each reference out in turn and tells it from the rest, with every copy of the same
 * offer and every reference sharing a near-identical photo left out too: a seller's repost shows the
 * same photos, and telling it from itself says nothing about telling a machine never seen before.
 * Three ways of telling are measured side by side, each at a run of bars, so the bar used is the one
 * the measurement shows is right often enough.
 */
object ModelLikeness {
    private val log = LoggerFactory.getLogger(ModelLikeness::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Photos embedded per offer: the first few show the machine; the rest are details and papers. */
    private const val PHOTOS = 3

    /** Two photos this alike are the same photo, re-encoded or cropped. */
    internal const val SAME_PHOTO = 0.97

    /** Tenths the probe is measured in. */
    private const val FOLDS = 10

    /** A lead from which a wrong answer is worth reading. */
    private const val SURE_WRONG = 0.02

    /** References a held-out offer's nearest neighbours are counted among in the vote. */
    private const val NEIGHBOURS = 7

    class Reference(val listing: Listing, val model: ModelWish, val photos: List<FloatArray>)

    @Serializable
    data class Bar(val bar: Double, val told: Int, val right: Int, val kindRight: Int)

    @Serializable
    data class Method(val name: String, val how: String, val bars: List<Bar>)

    @Serializable
    data class Measured(
        val references: Int,
        val perModel: Map<String, Int>,
        /** How often naming the commonest model for every offer would be right: the floor to beat. */
        val commonestModelRight: Double,
        val methods: List<Method>,
        /** Per model, at each of the nearest-photo method's bars: "told/right". */
        val nearestPerModel: Map<String, List<String>>,
        /** The same per model told rather than held out: how often "told X" was X. */
        val nearestPerModelTold: Map<String, List<String>>,
        /** Per model held out, for the trained probe. */
        val probePerModel: Map<String, List<String>>,
        /** The models the nearest-photo lead may tell, and from which lead; see [gates]. */
        val gates: Map<String, String>,
        /** Held-out offers the nearest-photo method told wrongly with a lead of [SURE_WRONG] or more, for reading. */
        val wrong: List<String>,
    )

    // ---- photos, embedded once and kept --------------------------------------------------------

    private val file = DataDir.file("photo_embeddings.json")
    private val embedded: MutableMap<String, List<String>> = file.readStore(log) {
        json.decodeFromString<Map<String, List<String>>>(it)
    }?.toMutableMap() ?: mutableMapOf()
    @Volatile private var unsaved = 0

    /** The CLIP embeddings of an archived offer's first photos, embedded once and kept on disk. */
    fun photos(l: Listing): List<FloatArray> {
        synchronized(embedded) { embedded[l.id] }?.let { return it.map(::decode) }
        val vectors = ListingArchive.photoFiles(l).take(PHOTOS).mapNotNull { f ->
            runCatching { ClipImageModel.embed(f.readBytes()) }.getOrNull()
        }
        synchronized(embedded) {
            embedded[l.id] = vectors.map(::encode)
            if (++unsaved >= 200) persist()
        }
        return vectors
    }

    fun persist() {
        val all = synchronized(embedded) { unsaved = 0; embedded.toMap() }
        runCatching { file.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("photo embeddings not kept: {}", it.message) }
    }

    private fun encode(v: FloatArray): String {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return Base64.getEncoder().encodeToString(b.array())
    }

    private fun decode(s: String): FloatArray {
        val b = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / 4) { b.getFloat() }
    }

    // ---- references ----------------------------------------------------------------------------

    /** The words of [query] that say what kind of thing it is, rather than which model. */
    internal fun kindWords(query: SearchQuery, wishes: ProductWishes): Set<String> {
        val modelWords = wishes.models.flatMap { m -> m.words.flatMap { FitScore.folded(it).trim().split(' ') } }.toSet()
        return (listOf(query.text) + query.aliases)
            .flatMap { FitScore.folded(it).trim().split(' ') }
            .filter { it.length >= 4 && it !in modelWords }
            .toSet()
    }

    /** The one model [l]'s title names, where it names exactly one and its text says what kind of thing it is. */
    internal fun namedIn(l: Listing, wishes: ProductWishes, kind: Set<String>): ModelWish? {
        val title = FitScore.folded(l.title)
        val model = wishes.models.filter { m -> m.words.any { FitScore.says(title, it) } }.singleOrNull() ?: return null
        val text = FitScore.folded("${l.title}\n${l.description.orEmpty()}")
        // A word the search rules out ("Superhummel") names another machine under the model's word.
        if (wishes.ruledOutWords.any { FitScore.says(text, it) }) return null
        return model.takeIf { kind.any { w -> text.contains(" $w ") } }
    }

    /** Every archived offer of [query] whose title names one of [wishes]' models, one per offer, with its photos;
     *  a rented one counts, since the machine in its photos is the model all the same. */
    fun references(query: SearchQuery, wishes: ProductWishes): List<Reference> {
        val kind = kindWords(query, wishes)
        // A reviewer's verdict outranks the title: that is what makes every look a better reference.
        val checks = ModelGuesses.checks()
        val named = ListingArchive.all()
            .mapNotNull { l ->
                val model = when (val c = checks[offerKey(l.id)]) {
                    null -> namedIn(l, wishes, kind)
                    else -> wishes.models.firstOrNull { it.name == c }
                }
                model?.let { l to it }
            }
            .distinctBy { (l, _) -> offerKey(l.id) }
            .toList()
        return named.mapNotNull { (l, m) -> photos(l).takeIf { it.isNotEmpty() }?.let { Reference(l, m, it) } }
            .also { persist() }
    }

    // ---- telling -------------------------------------------------------------------------------

    /** A model told from photos and how far ahead of the next it is. */
    class Told(val model: ModelWish, val score: Double)

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** How alike the most alike pair of photos of two offers is. */
    private fun likeness(a: List<FloatArray>, b: List<FloatArray>) = a.maxOf { x -> b.maxOf { y -> dot(x, y) } }

    /** Nearest photo: each model scored by its most alike reference; the score is the lead over the next model. */
    fun nearest(photos: List<FloatArray>, refs: List<Reference>): Told? {
        val byModel = refs.groupBy { it.model }.mapValues { (_, rs) -> rs.maxOf { likeness(photos, it.photos) } }
            .entries.sortedByDescending { it.value }
        val first = byModel.firstOrNull() ?: return null
        return Told(first.key, first.value - (byModel.getOrNull(1)?.value ?: 0.0))
    }

    /** Model centre: each model scored by its photos' mean direction; the score is the lead over the next model. */
    fun centre(photos: List<FloatArray>, refs: List<Reference>): Told? {
        val byModel = refs.groupBy { it.model }.mapValues { (_, rs) ->
            val sum = FloatArray(photos.first().size)
            rs.forEach { r -> r.photos.forEach { p -> p.indices.forEach { sum[it] += p[it] } } }
            val norm = Math.sqrt(sum.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0f } ?: 1f
            val c = FloatArray(sum.size) { sum[it] / norm }
            photos.maxOf { dot(it, c) }
        }.entries.sortedByDescending { it.value }
        val first = byModel.firstOrNull() ?: return null
        return Told(first.key, first.value - (byModel.getOrNull(1)?.value ?: 0.0))
    }

    /** Neighbour vote: the [NEIGHBOURS] most alike references vote, weighed by likeness; the score is the winner's share. */
    fun vote(photos: List<FloatArray>, refs: List<Reference>): Told? {
        val near = refs.map { it to likeness(photos, it.photos) }.sortedByDescending { it.second }.take(NEIGHBOURS)
        if (near.isEmpty()) return null
        val votes = near.groupBy({ it.first.model }, { it.second }).mapValues { it.value.sum() }
        val winner = votes.maxBy { it.value }
        return Told(winner.key, winner.value / votes.values.sum())
    }

    /** Two offers of one seller's account: a dealer photographs every machine in the same yard. */
    private fun sameSeller(a: Listing, b: Listing): Boolean {
        val x = a.seller?.id ?: a.seller?.name ?: return false
        return a.platformId == b.platformId && x == (b.seller?.id ?: b.seller?.name)
    }

    /**
     * A linear read of the photos trained on the references: one weight per model and embedding
     * dimension, fitted by softmax regression with each model weighed by how rare it is, so the
     * dozen PROFITs count as much as the hundred HUMMELs. Every photo is a training example; an
     * offer is told by the sum of its photos' log-probabilities. The score is the lead of the most
     * likely model's probability over the next.
     */
    class Probe(private val models: List<ModelWish>, private val w: Array<FloatArray>, private val b: FloatArray, private val mean: FloatArray) {
        fun tell(photos: List<FloatArray>): Told? {
            if (photos.isEmpty() || models.isEmpty()) return null
            val sum = DoubleArray(models.size)
            photos.forEach { p -> logSoftmax(centred(p, mean)).forEachIndexed { i, v -> sum[i] += v } }
            val probs = softmax(sum.map { it / photos.size }.toDoubleArray())
            val order = probs.indices.sortedByDescending { probs[it] }
            return Told(models[order[0]], probs[order[0]] - (order.getOrNull(1)?.let { probs[it] } ?: 0.0))
        }

        private fun logits(x: FloatArray) = DoubleArray(models.size) { k -> b[k] + dotD(w[k], x) }
        private fun logSoftmax(x: FloatArray): DoubleArray {
            val z = logits(x); val m = z.max(); val lse = m + Math.log(z.sumOf { Math.exp(it - m) })
            return DoubleArray(z.size) { z[it] - lse }
        }

        companion object {
            /** Every photo of these machines points much the same way; what tells them apart is what is left
             *  once that shared direction is taken off, scaled back to unit length. */
            fun centred(x: FloatArray, mean: FloatArray): FloatArray {
                val c = FloatArray(x.size) { x[it] - mean[it] }
                val n = Math.sqrt(c.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0f } ?: 1f
                return FloatArray(c.size) { c[it] / n }
            }

            private fun dotD(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
            private fun softmax(z: DoubleArray): DoubleArray {
                val m = z.max(); val e = z.map { Math.exp(it - m) }; val t = e.sum()
                return e.map { it / t }.toDoubleArray()
            }

            /** Full-batch gradient descent; the embeddings are unit length, so a fixed step converges. */
            fun train(refs: List<Reference>, epochs: Int = 400, step: Double = 4.0, l2: Double = 1e-3): Probe {
                val models = refs.map { it.model }.distinct()
                val index = models.withIndex().associate { it.value to it.index }
                val count = refs.groupingBy { it.model }.eachCount()
                val all = refs.flatMap { it.photos }
                val mean = FloatArray(all.first().size) { i -> (all.sumOf { it[i].toDouble() } / all.size).toFloat() }
                val samples = refs.flatMap { r -> r.photos.map { p -> Triple(centred(p, mean), index.getValue(r.model), 1.0 / (count.getValue(r.model) * r.photos.size)) } }
                val dim = samples.first().first.size
                val w = Array(models.size) { FloatArray(dim) }
                val b = FloatArray(models.size)
                val total = samples.sumOf { it.third }
                repeat(epochs) {
                    val gw = Array(models.size) { DoubleArray(dim) }
                    val gb = DoubleArray(models.size)
                    samples.forEach { (x, y, weight) ->
                        val z = DoubleArray(models.size) { k -> b[k] + dotD(w[k], x) }
                        val p = softmax(z)
                        for (k in models.indices) {
                            val g = (p[k] - if (k == y) 1.0 else 0.0) * weight
                            gb[k] += g
                            val row = gw[k]
                            for (i in 0 until dim) row[i] += g * x[i]
                        }
                    }
                    for (k in models.indices) {
                        b[k] -= (step * gb[k] / total).toFloat()
                        for (i in 0 until dim) w[k][i] -= (step * (gw[k][i] / total + l2 * w[k][i])).toFloat()
                    }
                }
                return Probe(models, w, b, mean)
            }
        }
    }

    // ---- measuring -----------------------------------------------------------------------------

    private class Way(val name: String, val how: String, val bars: List<Double>, val tell: (List<FloatArray>, List<Reference>) -> Told?)

    private val NEAREST_BARS = listOf(0.0, 0.01, 0.02, 0.03, 0.05, 0.08)

    private val ways = listOf(
        Way("nearest", "lead of the most alike photo's model over the next model", NEAREST_BARS, ::nearest),
        Way("centre", "lead of the closest model centre over the next", listOf(0.0, 0.01, 0.02, 0.03, 0.05, 0.08), ::centre),
        Way("vote", "share of the $NEIGHBOURS nearest offers' weighed vote", listOf(0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0), ::vote),
    )

    /** How often a model told from photos has to be right before it is told at all. */
    internal const val RIGHT_ENOUGH = 0.9

    /** Offers a model has to have been told at a bar before its record there counts. */
    internal const val TOLD_ENOUGH = 5

    /**
     * Per model told, the lowest bar of the nearest-photo lead at which it was right at least
     * [RIGHT_ENOUGH] of the [TOLD_ENOUGH] or more times it was told, with that share: what a model
     * may be told from photos at, if at all. Judged by the model told rather than the model held out,
     * since an offer told a HUMMEL is believed by how often "HUMMEL" was right.
     */
    internal fun gates(told: List<Pair<ModelWish, Told>>): Map<String, Pair<Double, Double>> =
        told.map { it.second.model.name }.distinct().mapNotNull { name ->
            NEAREST_BARS.firstNotNullOfOrNull { bar ->
                val over = told.filter { it.second.model.name == name && it.second.score >= bar }
                val share = over.count { it.first.name == name }.toDouble() / over.size.coerceAtLeast(1)
                (bar to share).takeIf { over.size >= TOLD_ENOUGH && share >= RIGHT_ENOUGH }
            }?.let { name to it }
        }.toMap()

    /** Each reference told from the others, leaving out with it what the class doc says. */
    private fun heldOut(refs: List<Reference>, tell: (List<FloatArray>, List<Reference>) -> Told?): List<Pair<Reference, Told>> =
        refs.mapNotNull { held ->
            val rest = refs.filter { r ->
                offerKey(r.listing.id) != offerKey(held.listing.id) && !sameSeller(r.listing, held.listing) &&
                    likeness(held.photos, r.photos) < SAME_PHOTO
            }
            tell(held.photos, rest)?.let { held to it }
        }

    /** Every way measured side by side, for reading; see [Measured]. */
    fun measure(query: SearchQuery, wishes: ProductWishes): Measured {
        val refs = references(query, wishes)
        val perModel = refs.groupingBy { it.model.name }.eachCount()
        val told = ways.associateWith { heldOut(refs, it.tell) }
        // The probe is trained, so it is held out a tenth at a time rather than one by one: each
        // seller's offers in one tenth, so no seller's yard is both learned and told.
        val probeTold = mutableListOf<Pair<Reference, Told>>()
        val folds = refs.groupBy { r -> Math.floorMod((r.listing.seller?.id ?: r.listing.seller?.name ?: offerKey(r.listing.id)).hashCode(), FOLDS) }
        folds.values.forEach { held ->
            val heldIds = held.map { it.listing.id }.toSet()
            val train = refs.filter { r -> r.listing.id !in heldIds && held.none { h -> likeness(h.photos, r.photos) >= SAME_PHOTO } }
            if (train.isEmpty()) return@forEach
            val probe = Probe.train(train)
            held.forEach { h -> probe.tell(h.photos)?.let { probeTold += h to it } }
        }
        fun goodOrNot(m: ModelWish) = m.worth > 0.0
        fun bars(name: String, how: String, bars: List<Double>, told: List<Pair<Reference, Told>>) = Method(name, how, bars.map { bar ->
            val over = told.filter { it.second.score >= bar }
            Bar(bar, over.size, over.count { it.first.model == it.second.model },
                over.count { goodOrNot(it.first.model) == goodOrNot(it.second.model) })
        })
        fun perModel(told: List<Pair<Reference, Told>>, bars: List<Double>, by: (Pair<Reference, Told>) -> String) =
            told.map(by).distinct().sorted().associateWith { name ->
                bars.map { bar ->
                    val over = told.filter { by(it) == name && it.second.score >= bar }
                    "${over.size}/${over.count { it.first.model == it.second.model }}"
                }
            }
        val probeBars = listOf(0.0, 0.2, 0.4, 0.6, 0.8, 0.9)
        val methods = ways.map { bars(it.name, it.how, it.bars, told.getValue(it)) } +
            bars("probe", "lead of the trained linear read's most likely model over the next", probeBars, probeTold)
        val nearestTold = told.getValue(ways.first())
        val wrong = nearestTold.filter { it.first.model != it.second.model && it.second.score >= SURE_WRONG }.map { (r, t) ->
            "${r.listing.id} \"${r.listing.title.take(60)}\" is ${r.model.name}, told ${t.model.name} (lead %.3f)".format(t.score)
        }
        return Measured(
            references = refs.size,
            perModel = perModel.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            commonestModelRight = if (refs.isEmpty()) 0.0 else (perModel.values.maxOrNull() ?: 0).toDouble() / refs.size,
            methods = methods,
            nearestPerModel = perModel(nearestTold, NEAREST_BARS) { it.first.model.name },
            nearestPerModelTold = perModel(nearestTold, NEAREST_BARS) { it.second.model.name },
            probePerModel = perModel(probeTold, probeBars) { it.first.model.name },
            gates = gates(nearestTold.map { it.first.model to it.second }).mapValues { (_, g) -> "from a lead of ${g.first}, right ${"%.0f".format(g.second * 100)} %" },
            wrong = wrong,
        ).also { log.info("model likeness measured on {} references: {}", refs.size, methods) }
    }

    // ---- telling unnamed offers ----------------------------------------------------------------

    /** The references of one search with the bars each model is told at, measured once a day. */
    private class Teller(val against: String, val at: Long, val refs: List<Reference>, val gates: Map<String, Pair<Double, Double>>)

    private val tellers = mutableMapOf<String, Teller>()

    private const val MEASURED_FOR_MS = 24 * 3600_000L

    private fun teller(query: SearchQuery, wishes: ProductWishes, against: String): Teller {
        synchronized(tellers) { tellers[against]?.takeIf { System.currentTimeMillis() - it.at < MEASURED_FOR_MS }?.let { return it } }
        val refs = references(query, wishes)
        val gates = gates(heldOut(refs, ::nearest).map { it.first.model to it.second })
        log.info("photo likeness for {} references: told {}", refs.size, gates)
        return Teller(against, System.currentTimeMillis(), refs, gates).also { synchronized(tellers) { tellers[against] = it } }
    }

    /**
     * The model [l]'s photos look like among the search's references, where that model's measured
     * record at the lead it has clears [RIGHT_ENOUGH]; with that record as its confidence.
     */
    fun tell(l: Listing, query: SearchQuery, wishes: ProductWishes, against: String): Pair<ModelWish, Double>? {
        val t = teller(query, wishes, against)
        if (t.gates.isEmpty()) return null
        val mine = (if (ListingArchive.isArchived(l.id)) ListingArchive.get(l.id)?.let(::photos) else null)
            ?.takeIf { it.isNotEmpty() }
            ?: l.imageUrls.take(PHOTOS).mapNotNull { ClipImageModel.embedUrl(it) }
        if (mine.isEmpty()) return null
        val told = nearest(mine, t.refs.filter { offerKey(it.listing.id) != offerKey(l.id) }) ?: return null
        val (bar, share) = t.gates[told.model.name] ?: return null
        return if (told.score >= bar) told.model to share else null
    }
}
