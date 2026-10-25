package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.classifier.ClipImageModel
import io.github.tieo.arbay.model.Listing
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
 * A fact about an offer told from how its photos look like those of offers whose fact is known:
 * which of a search's models a machine is ([ModelLikeness]), or anything else sellers state in words
 * for some offers and show in the photos of all.
 *
 * The examples are archived offers whose fact is known from their own words or from a reviewer
 * ([PhotoFactChecks]), each with the CLIP embeddings of its first photos. [measure] holds each
 * example out in turn and tells it from the rest, with every copy of the same offer, every offer of
 * the same seller and every example sharing a near-identical photo left out too: a seller's repost
 * or a dealer's yard says nothing about telling an offer never seen before. Several ways of telling
 * are measured side by side; [Teller] uses the nearest photo, and tells a value only from the lead at
 * which that value was right at least [RIGHT_ENOUGH] of the times it was told and far more often than
 * the examples' own mix would make it ([gates]), and only for photos
 * as alike as those of the offers it was told rightly for.
 */
object PhotoLikeness {
    private val log = LoggerFactory.getLogger(PhotoLikeness::class.java)

    /** Photos embedded per offer: the first few show the thing itself; the rest are details and papers. */
    internal const val PHOTOS = 3

    /** Two photos this alike are the same photo, re-encoded or cropped. */
    internal const val SAME_PHOTO = 0.97

    /** How often a value told from photos has to be right before it is told at all. */
    internal const val RIGHT_ENOUGH = 0.9

    /** Offers a value has to have been told at a bar before its record there counts. */
    internal const val TOLD_ENOUGH = 5

    /** The share of rightly told offers allowed to be less alike than the floor. */
    private const val FLOOR_SHARE = 0.1

    /** Tenths the probe is measured in. */
    private const val FOLDS = 10

    /** A lead from which a wrong answer is worth reading. */
    private const val SURE_WRONG = 0.02

    /** References a held-out offer's nearest neighbours are counted among in the vote. */
    private const val NEIGHBOURS = 7

    /** An offer whose fact is known, as [label], with its photos' embeddings. */
    class Example(val listing: Listing, val label: String, val photos: List<FloatArray>)

    /** A value told from photos, how far ahead of the next it is, and how alike its most alike example is. */
    class Told(val label: String, val score: Double, val like: Double = 0.0)

    @Serializable
    data class Bar(val bar: Double, val told: Int, val right: Int, val kindRight: Int)

    @Serializable
    data class Method(val name: String, val how: String, val bars: List<Bar>)

    @Serializable
    data class Measured(
        val references: Int,
        val perModel: Map<String, Int>,
        /** How often telling the commonest value for every offer would be right: the floor to beat. */
        val commonestModelRight: Double,
        val methods: List<Method>,
        /** Per value, at each of the nearest-photo method's bars: "told/right". */
        val nearestPerModel: Map<String, List<String>>,
        /** The same per value told rather than held out: how often "told X" was X. */
        val nearestPerModelTold: Map<String, List<String>>,
        /** Per value held out, for the trained probe. */
        val probePerModel: Map<String, List<String>>,
        /** The values the nearest-photo lead may tell, and from which lead; see [gates]. */
        val gates: Map<String, String>,
        /** Held-out offers the nearest-photo method told wrongly with a lead of [SURE_WRONG] or more, for reading. */
        val wrong: List<String>,
    )

    // ---- photos, embedded once and kept --------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }
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

    /** The embeddings of any offer: its archived photos where it is archived, else its card's. */
    fun photosOf(l: Listing): List<FloatArray> =
        (if (ListingArchive.isArchived(l.id)) ListingArchive.get(l.id)?.let(::photos) else null)
            ?.takeIf { it.isNotEmpty() }
            ?: l.imageUrls.take(PHOTOS).mapNotNull { ClipImageModel.embedUrl(it) }

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

    /** [named] with their photos, one per offer, those without photos left out. */
    fun examples(named: Sequence<Pair<Listing, String>>): List<Example> =
        named.distinctBy { (l, _) -> offerKey(l.id) }.toList()
            .mapNotNull { (l, label) -> photos(l).takeIf { it.isNotEmpty() }?.let { Example(l, label, it) } }
            .also { persist() }

    // ---- telling -------------------------------------------------------------------------------

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** How alike the most alike pair of photos of two offers is. */
    private fun likeness(a: List<FloatArray>, b: List<FloatArray>) = a.maxOf { x -> b.maxOf { y -> dot(x, y) } }

    /** Nearest photo: each value scored by its most alike example; the score is the lead over the next value. */
    fun nearest(photos: List<FloatArray>, refs: List<Example>): Told? {
        val byLabel = refs.groupBy { it.label }.mapValues { (_, rs) -> rs.maxOf { likeness(photos, it.photos) } }
            .entries.sortedByDescending { it.value }
        val first = byLabel.firstOrNull() ?: return null
        return Told(first.key, first.value - (byLabel.getOrNull(1)?.value ?: 0.0), first.value)
    }

    /** Centre: each value scored by its photos' mean direction; the score is the lead over the next value. */
    fun centre(photos: List<FloatArray>, refs: List<Example>): Told? {
        val byLabel = refs.groupBy { it.label }.mapValues { (_, rs) ->
            val sum = FloatArray(photos.first().size)
            rs.forEach { r -> r.photos.forEach { p -> p.indices.forEach { sum[it] += p[it] } } }
            val norm = Math.sqrt(sum.sumOf { it.toDouble() * it }).toFloat().takeIf { it > 0f } ?: 1f
            val c = FloatArray(sum.size) { sum[it] / norm }
            photos.maxOf { dot(it, c) }
        }.entries.sortedByDescending { it.value }
        val first = byLabel.firstOrNull() ?: return null
        return Told(first.key, first.value - (byLabel.getOrNull(1)?.value ?: 0.0))
    }

    /** Neighbour vote: the [NEIGHBOURS] most alike examples vote, weighed by likeness; the score is the winner's share. */
    fun vote(photos: List<FloatArray>, refs: List<Example>): Told? {
        val near = refs.map { it to likeness(photos, it.photos) }.sortedByDescending { it.second }.take(NEIGHBOURS)
        if (near.isEmpty()) return null
        val votes = near.groupBy({ it.first.label }, { it.second }).mapValues { it.value.sum() }
        val winner = votes.maxBy { it.value }
        return Told(winner.key, winner.value / votes.values.sum())
    }

    /** Two offers of one seller's account: a dealer photographs everything in the same yard. */
    private fun sameSeller(a: Listing, b: Listing): Boolean {
        val x = a.seller?.id ?: a.seller?.name ?: return false
        return a.platformId == b.platformId && x == (b.seller?.id ?: b.seller?.name)
    }

    /**
     * A linear read of the photos trained on the examples: one weight per value and embedding
     * dimension, fitted by softmax regression with each value weighed by how rare it is, so a dozen
     * examples of one count as much as a hundred of another. Every photo is a training example; an
     * offer is told by the sum of its photos' log-probabilities. The score is the lead of the most
     * likely value's probability over the next.
     */
    class Probe(private val labels: List<String>, private val w: Array<FloatArray>, private val b: FloatArray, private val mean: FloatArray) {
        fun tell(photos: List<FloatArray>): Told? {
            if (photos.isEmpty() || labels.isEmpty()) return null
            val sum = DoubleArray(labels.size)
            photos.forEach { p -> logSoftmax(centred(p, mean)).forEachIndexed { i, v -> sum[i] += v } }
            val probs = softmax(sum.map { it / photos.size }.toDoubleArray())
            val order = probs.indices.sortedByDescending { probs[it] }
            return Told(labels[order[0]], probs[order[0]] - (order.getOrNull(1)?.let { probs[it] } ?: 0.0))
        }

        private fun logits(x: FloatArray) = DoubleArray(labels.size) { k -> b[k] + dotD(w[k], x) }
        private fun logSoftmax(x: FloatArray): DoubleArray {
            val z = logits(x); val m = z.max(); val lse = m + Math.log(z.sumOf { Math.exp(it - m) })
            return DoubleArray(z.size) { z[it] - lse }
        }

        companion object {
            /** Every photo of these things points much the same way; what tells them apart is what is left
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
            fun train(refs: List<Example>, epochs: Int = 400, step: Double = 4.0, l2: Double = 1e-3): Probe {
                val labels = refs.map { it.label }.distinct()
                val index = labels.withIndex().associate { it.value to it.index }
                val count = refs.groupingBy { it.label }.eachCount()
                val all = refs.flatMap { it.photos }
                val mean = FloatArray(all.first().size) { i -> (all.sumOf { it[i].toDouble() } / all.size).toFloat() }
                val samples = refs.flatMap { r -> r.photos.map { p -> Triple(centred(p, mean), index.getValue(r.label), 1.0 / (count.getValue(r.label) * r.photos.size)) } }
                val dim = samples.first().first.size
                val w = Array(labels.size) { FloatArray(dim) }
                val b = FloatArray(labels.size)
                val total = samples.sumOf { it.third }
                repeat(epochs) {
                    val gw = Array(labels.size) { DoubleArray(dim) }
                    val gb = DoubleArray(labels.size)
                    samples.forEach { (x, y, weight) ->
                        val z = DoubleArray(labels.size) { k -> b[k] + dotD(w[k], x) }
                        val p = softmax(z)
                        for (k in labels.indices) {
                            val g = (p[k] - if (k == y) 1.0 else 0.0) * weight
                            gb[k] += g
                            val row = gw[k]
                            for (i in 0 until dim) row[i] += g * x[i]
                        }
                    }
                    for (k in labels.indices) {
                        b[k] -= (step * gb[k] / total).toFloat()
                        for (i in 0 until dim) w[k][i] -= (step * (gw[k][i] / total + l2 * w[k][i])).toFloat()
                    }
                }
                return Probe(labels, w, b, mean)
            }
        }
    }

    // ---- measuring -----------------------------------------------------------------------------

    private class Way(val name: String, val how: String, val bars: List<Double>, val tell: (List<FloatArray>, List<Example>) -> Told?)

    private val NEAREST_BARS = listOf(0.0, 0.01, 0.02, 0.03, 0.05, 0.08)

    private val ways = listOf(
        Way("nearest", "lead of the most alike photo's value over the next value", NEAREST_BARS, ::nearest),
        Way("centre", "lead of the closest value centre over the next", listOf(0.0, 0.01, 0.02, 0.03, 0.05, 0.08), ::centre),
        Way("vote", "share of the $NEIGHBOURS nearest offers' weighed vote", listOf(0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0), ::vote),
    )

    /**
     * Per value told, the lowest bar of the nearest-photo lead at which it was right at least
     * [RIGHT_ENOUGH] of the [TOLD_ENOUGH] or more times it was told, with that share: what a value
     * may be told from photos at, if at all. Judged by the value told rather than the value held out,
     * since an offer told a HUMMEL is believed by how often "HUMMEL" was right. And it has to say
     * more than the examples' own mix does: where nine vans in ten have a high roof, "high roof" is
     * right nine times in ten whatever the photos show, so a value has to make at most half the
     * mistakes that telling it for every offer would.
     */
    internal fun gates(told: List<Pair<String, Told>>): Map<String, Pair<Double, Double>> {
        val base = told.groupingBy { it.first }.eachCount().mapValues { it.value.toDouble() / told.size }
        return told.map { it.second.label }.distinct().mapNotNull { name ->
            val enough = maxOf(RIGHT_ENOUGH, 1.0 - (1.0 - (base[name] ?: 0.0)) / 2)
            NEAREST_BARS.firstNotNullOfOrNull { bar ->
                val over = told.filter { it.second.label == name && it.second.score >= bar }
                val share = over.count { it.first == name }.toDouble() / over.size.coerceAtLeast(1)
                (bar to share).takeIf { over.size >= TOLD_ENOUGH && share >= enough }
            }?.let { name to it }
        }.toMap()
    }

    /** Each example told from the others, leaving out with it what the class doc says. */
    private fun heldOut(refs: List<Example>, tell: (List<FloatArray>, List<Example>) -> Told?): List<Pair<Example, Told>> =
        refs.mapNotNull { held ->
            val rest = refs.filter { r ->
                offerKey(r.listing.id) != offerKey(held.listing.id) && !sameSeller(r.listing, held.listing) &&
                    likeness(held.photos, r.photos) < SAME_PHOTO
            }
            tell(held.photos, rest)?.let { held to it }
        }

    /**
     * Every way measured side by side, for reading; see [Measured]. [kind] groups values that are as
     * good as each other for the reader (a model worth something against one worth nothing), so a
     * wrong value of the right kind is counted apart from a wrong kind.
     */
    fun measure(refs: List<Example>, kind: (String) -> String = { it }): Measured {
        val perLabel = refs.groupingBy { it.label }.eachCount()
        val told = ways.associateWith { heldOut(refs, it.tell) }
        // The probe is trained, so it is held out a tenth at a time rather than one by one: each
        // seller's offers in one tenth, so no seller's yard is both learned and told.
        val probeTold = mutableListOf<Pair<Example, Told>>()
        val folds = refs.groupBy { r -> Math.floorMod((r.listing.seller?.id ?: r.listing.seller?.name ?: offerKey(r.listing.id)).hashCode(), FOLDS) }
        folds.values.forEach { held ->
            val heldIds = held.map { it.listing.id }.toSet()
            val train = refs.filter { r -> r.listing.id !in heldIds && held.none { h -> likeness(h.photos, r.photos) >= SAME_PHOTO } }
            if (train.isEmpty()) return@forEach
            val probe = Probe.train(train)
            held.forEach { h -> probe.tell(h.photos)?.let { probeTold += h to it } }
        }
        fun bars(name: String, how: String, bars: List<Double>, told: List<Pair<Example, Told>>) = Method(name, how, bars.map { bar ->
            val over = told.filter { it.second.score >= bar }
            Bar(bar, over.size, over.count { it.first.label == it.second.label },
                over.count { kind(it.first.label) == kind(it.second.label) })
        })
        fun perLabel(told: List<Pair<Example, Told>>, bars: List<Double>, by: (Pair<Example, Told>) -> String) =
            told.map(by).distinct().sorted().associateWith { name ->
                bars.map { bar ->
                    val over = told.filter { by(it) == name && it.second.score >= bar }
                    "${over.size}/${over.count { it.first.label == it.second.label }}"
                }
            }
        val probeBars = listOf(0.0, 0.2, 0.4, 0.6, 0.8, 0.9)
        val methods = ways.map { bars(it.name, it.how, it.bars, told.getValue(it)) } +
            bars("probe", "lead of the trained linear read's most likely value over the next", probeBars, probeTold)
        val nearestTold = told.getValue(ways.first())
        val wrong = nearestTold.filter { it.first.label != it.second.label && it.second.score >= SURE_WRONG }.map { (r, t) ->
            "${r.listing.id} \"${r.listing.title.take(60)}\" is ${r.label}, told ${t.label} (lead %.3f)".format(t.score)
        }
        return Measured(
            references = refs.size,
            perModel = perLabel.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            commonestModelRight = if (refs.isEmpty()) 0.0 else (perLabel.values.maxOrNull() ?: 0).toDouble() / refs.size,
            methods = methods,
            nearestPerModel = perLabel(nearestTold, NEAREST_BARS) { it.first.label },
            nearestPerModelTold = perLabel(nearestTold, NEAREST_BARS) { it.second.label },
            probePerModel = perLabel(probeTold, probeBars) { it.first.label },
            gates = gates(nearestTold.map { it.first.label to it.second }).mapValues { (_, g) -> "from a lead of ${g.first}, right ${"%.0f".format(g.second * 100)} %" },
            wrong = wrong,
        ).also { log.info("photo likeness measured on {} examples: {}", refs.size, methods) }
    }

    // ---- telling unknown offers ----------------------------------------------------------------

    /**
     * One fact's examples with the bars each value is told at, measured as it is built. Kept for a
     * day by whoever holds it ([Tellers]): the measurement runs over every example, the telling is cheap.
     */
    class Teller private constructor(
        val refs: List<Example>,
        val gates: Map<String, Pair<Double, Double>>,
        /** Per value, how alike the photos of an offer told it rightly were at the least, nearly: a
         *  wall planer is nearer a drum sander than anything else here and still nothing like one. */
        val floors: Map<String, Double>,
        val at: Long = System.currentTimeMillis(),
    ) {
        /**
         * The value [l]'s photos look like among the examples, where that value's measured record at
         * the lead it has clears [RIGHT_ENOUGH] and its photos are as alike as the right answers' were;
         * with that record as its confidence. [allowed] narrows the values to those the offer's own
         * words leave open.
         */
        fun tell(l: Listing, allowed: (String) -> Boolean = { true }): Pair<String, Double>? {
            if (gates.isEmpty()) return null
            val mine = photosOf(l).takeIf { it.isNotEmpty() } ?: return null
            val told = nearest(mine, refs.filter { offerKey(it.listing.id) != offerKey(l.id) && allowed(it.label) }) ?: return null
            val (bar, share) = gates[told.label] ?: return null
            val floor = floors[told.label] ?: return null
            return if (told.score >= bar && told.like >= floor) told.label to share else null
        }

        companion object {
            fun of(refs: List<Example>, what: String): Teller {
                val held = heldOut(refs, ::nearest)
                val gates = gates(held.map { it.first.label to it.second })
                val floors = held.filter { it.first.label == it.second.label }.groupBy({ it.second.label }, { it.second.like })
                    .mapValues { (_, likes) -> likes.sorted()[(likes.size * FLOOR_SHARE).toInt().coerceAtMost(likes.size - 1)] }
                log.info("photo likeness for {}, {} examples: told {}, at least as alike as {}", what, refs.size, gates, floors)
                return Teller(refs, gates, floors)
            }
        }
    }

    /** Tellers by key, each rebuilt by [build] once it is a day old. */
    class Tellers {
        private val held = mutableMapOf<String, Teller>()

        fun get(key: String, build: () -> Teller): Teller {
            synchronized(held) { held[key]?.takeIf { System.currentTimeMillis() - it.at < MEASURED_FOR_MS }?.let { return it } }
            return build().also { synchronized(held) { held[key] = it } }
        }

        private companion object {
            const val MEASURED_FOR_MS = 24 * 3600_000L
        }
    }
}

/**
 * A reviewer's verdict on a photo fact of an offer, per fact and offer (every market copy alike):
 * what an offer is, settled by someone who looked, which outranks its words and makes it an example
 * the next offers' photos are told by.
 */
object PhotoFactChecks {
    private val log = LoggerFactory.getLogger(PhotoFactChecks::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = DataDir.file("photo_fact_checks.json")

    private val checks: MutableMap<String, MutableMap<String, String>> = (
        file.readStore(log) { json.decodeFromString<Map<String, Map<String, String>>>(it) }
            ?: DataDir.file("model_checks.json").readStore(log) {
                // Where the model's verdicts were kept before every photo fact had them.
                mapOf(ModelGuesses.FACT to json.decodeFromString<Map<String, String>>(it))
            }
            ?: emptyMap()
        ).mapValues { it.value.toMutableMap() }.toMutableMap()

    /** The verdict on [fact] of [listingId], or null when nobody checked it. */
    fun of(fact: String, listingId: String): String? = synchronized(checks) { checks[fact]?.get(offerKey(listingId)) }

    /** Every verdict on [fact], by offer. */
    fun all(fact: String): Map<String, String> = synchronized(checks) { checks[fact]?.toMap().orEmpty() }

    fun set(fact: String, listingId: String, value: String) {
        val all = synchronized(checks) {
            checks.getOrPut(fact) { mutableMapOf() }[offerKey(listingId)] = value
            checks.mapValues { it.value.toMap() }
        }
        runCatching { file.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("photo fact checks not kept: {}", it.message) }
        log.info("{} of {} checked: {}", fact, listingId, value.ifEmpty { "none of them" })
    }
}
