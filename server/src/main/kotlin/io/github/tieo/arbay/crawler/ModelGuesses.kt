package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.classifier.ClipImageModel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ModelWish
import io.github.tieo.arbay.model.ProductWishes
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** The model an offer was told to be though its card names none, and how it was told. [sure] is
 *  a model named in so many words (the ad's full text, or printed on the machine in a photo);
 *  a likeness of photos is not, and never rules an offer out on its own. */
@Serializable
data class ModelGuess(val model: String, val how: String, val confidence: Double, val sure: Boolean)

/**
 * The model of an offer whose title and card text name none, told from what else the ad shows.
 *
 * A search weighed by [ProductWishes] scores an unnamed model as not stated, and an ad titled
 * "Parkettschleifmaschine" at a good price is as likely a HUMMEL as anything. In turn, each of these:
 * the ad's full text, read from its page, since a card carries only the start of it; the words on
 * the machine in its photos (the maker prints its name and the model's on the housing, and the
 * type plate names both); and last, how much its photos look like those of the offers in the same
 * answer that do name their model, the models that would not do among them, so an edge sander is
 * told apart rather than taken for the nearest wanted machine.
 *
 * Kept per offer, with the wishes it was told against, so an offer is looked at once.
 */
object ModelGuesses {
    private val log = LoggerFactory.getLogger(ModelGuesses::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val file = DataDir.file("model_guesses.json")

    @Serializable
    private data class Kept(val guess: ModelGuess? = null, val against: String)

    private val kept: MutableMap<String, Kept> = file.readStore(log) {
        json.decodeFromString<Map<String, Kept>>(it)
    }?.toMutableMap() ?: mutableMapOf()

    /** Photos compared per offer: the first few show the machine; the rest are details and papers. */
    private const val PHOTOS_COMPARED = 3

    /** Photos read for printed words per offer. */
    private const val PHOTOS_READ = 4

    /** Offers looked at per run, cheapest first: the cheap ones are the ones a good fit hides in. */
    private const val MOST_PER_RUN = 25

    /** How alike two photos of the same model are at least, and how far ahead of the next model the
     *  best has to be (CLIP image embeddings, cosine): set from [checkLikeness] on real answers. */
    internal var LIKE = 0.90
    internal var AHEAD = 0.03

    /** The model [listing] was told to be, where one was. */
    fun of(listing: Listing): ModelGuess? = synchronized(kept) { kept[listing.id]?.guess }

    private fun key(wishes: ProductWishes) = wishes.models.joinToString("|") { it.name + ":" + it.words.joinToString(",") }

    /** Tell the model of every offer among [listings] that names none, as far as each can be told. */
    suspend fun identify(listings: Collection<Listing>, wishes: ProductWishes) {
        if (wishes.models.isEmpty()) return
        val against = key(wishes)
        fun named(l: Listing) = FitScore.namedModel(FitScore.folded("${l.title}\n${l.description.orEmpty()}"), wishes)
        val unnamed = listings.filter { named(it) == null }
            .filter { l -> synchronized(kept) { kept[l.id]?.against != against } }
            .sortedBy { FitScore.priceEur(it) }
            .take(MOST_PER_RUN)
        if (unnamed.isEmpty()) return
        // The offers that name one model in their title are what the others' photos are held against.
        val references = listings.mapNotNull { l ->
            val models = wishes.models.filter { m -> m.words.any { FitScore.says(FitScore.folded(l.title), it) } }
            models.singleOrNull()?.let { it to l }
        }
        unnamed.forEach { l ->
            val guess = try {
                fromText(l, wishes) ?: fromPrint(l, wishes) ?: fromLikeness(l, references)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("model of {} not told: {}", l.id, e.message)
                return@forEach
            }
            synchronized(kept) { kept[l.id] = Kept(guess, against) }
            if (guess != null) log.info("model of {} ({}): {} from {}", l.id, l.title.take(50), guess.model, guess.how)
        }
        checkLikeness(references)
        persist()
    }

    /** The model the ad's full text names, read from its page. */
    private suspend fun fromText(l: Listing, wishes: ProductWishes): ModelGuess? {
        val text = detail(l)?.description ?: return null
        return FitScore.namedModel(FitScore.folded(text), wishes)?.let { ModelGuess(it.name, "the ad's text", 0.9, sure = true) }
    }

    /** The model printed on the machine or its type plate in the ad's photos. */
    private suspend fun fromPrint(l: Listing, wishes: ProductWishes): ModelGuess? {
        if (!PhotoText.present) return null
        photos(l).take(PHOTOS_READ).forEach { url ->
            val bytes = PhotoPrints.downloadAt(SmartScreens.fullSize(url)) ?: return@forEach
            val text = PhotoText.read(bytes, 1.0, PhotoText.Layout.SCATTERED) ?: return@forEach
            FitScore.namedModel(FitScore.folded(text), wishes)?.let { return ModelGuess(it.name, "printed on the machine", 0.9, sure = true) }
        }
        return null
    }

    /** The model whose offers' photos [l]'s look most like, where it is clearly the most alike. */
    private fun fromLikeness(l: Listing, references: List<Pair<ModelWish, Listing>>): ModelGuess? {
        val best = likeness(l, references.filter { it.second.id != l.id }) ?: return null
        return ModelGuess(best.first.name, "looks like the ${best.first.name} ads", 0.75, sure = false)
    }

    /** The most alike model and how alike, if it clears [LIKE] and leads the next model by [AHEAD]. */
    private fun likeness(l: Listing, references: List<Pair<ModelWish, Listing>>): Pair<ModelWish, Double>? {
        val mine = embeddings(l).takeIf { it.isNotEmpty() } ?: return null
        val byModel = references.groupBy({ it.first }, { it.second }).mapValues { (_, ls) ->
            ls.maxOfOrNull { other -> embeddings(other).maxOfOrNull { theirs -> mine.maxOf { dot(it, theirs) } } ?: 0.0 } ?: 0.0
        }.entries.sortedByDescending { it.value }
        val first = byModel.firstOrNull() ?: return null
        val second = byModel.getOrNull(1)?.value ?: 0.0
        return if (first.value >= LIKE && first.value - second >= AHEAD) first.key to first.value else null
    }

    /**
     * How often the photos alone tell the right model, measured on the offers whose title names it:
     * each held out in turn and told from the rest. Logged per run, so the bars above are set from
     * what the markets show rather than guessed.
     */
    private fun checkLikeness(references: List<Pair<ModelWish, Listing>>) {
        if (references.size < 6) return
        var told = 0; var right = 0
        references.forEach { (model, l) ->
            val guess = likeness(l, references.filter { it.second.id != l.id }) ?: return@forEach
            told++
            if (guess.first.name == model.name) right++
        }
        log.info("photo likeness on {} offers naming their model: told {}, right {} (bar {} ahead {})", references.size, told, right, LIKE, AHEAD)
    }

    private val embedded = object : LinkedHashMap<String, List<FloatArray>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, List<FloatArray>>?) = size > 2_000
    }

    private fun embeddings(l: Listing): List<FloatArray> {
        synchronized(embedded) { embedded[l.id]?.let { return it } }
        val all = l.imageUrls.take(PHOTOS_COMPARED).mapNotNull { ClipImageModel.embedUrl(it) }
        if (all.isNotEmpty()) synchronized(embedded) { embedded[l.id] = all }
        return all
    }

    /** Cosine of two L2-normalised embeddings. */
    private fun dot(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private suspend fun detail(l: Listing) = DetailCache.get(l.id)
        ?: CrawlerRegistry.crawlerFor(l.platformId)?.fetchDetail(l)?.also { DetailCache.put(l.id, it) }

    private suspend fun photos(l: Listing): List<String> = detail(l)?.photos?.takeIf { it.isNotEmpty() } ?: l.imageUrls

    private fun persist() {
        val all = synchronized(kept) { kept.toMap() }
        runCatching { file.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("model guesses not kept: {}", it.message) }
    }
}
