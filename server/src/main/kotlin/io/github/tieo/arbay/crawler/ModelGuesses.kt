package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ProductWishes
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** The model an offer was told to be though its card names none, and how it was told. [sure] is
 *  a model named in so many words (the ad's full text, printed on the machine in a photo) or checked
 *  by a reviewer; a likeness of photos is not, and never rules an offer out on its own. */
@Serializable
data class ModelGuess(val model: String, val how: String, val confidence: Double, val sure: Boolean) {
    /** Told from the photos' likeness alone and not yet looked at by anyone. */
    val unchecked: Boolean get() = how == FROM_PHOTOS

    companion object {
        const val FROM_PHOTOS = "its photos look like one"
        const val CHECKED = "checked"

        /** The model of a [CHECKED] offer that is none of the search's: not a machine wanted at all. */
        const val NONE_OF_THEM = ""
    }
}

/**
 * The model of an offer whose title and card text name none, told from what else the ad shows.
 *
 * A search weighed by [ProductWishes] scores an unnamed model as not stated, and an ad titled
 * "Parkettschleifmaschine" at a good price is as likely a HUMMEL as anything. In turn, each of these:
 * a reviewer's verdict ([check]); the ad's full text, read from its page, since a card carries only
 * the start of it; the words on the machine in its photos (the maker prints its name and the model's
 * on the housing, and the type plate names both); how much its photos look like those of the
 * archived offers naming their model ([ModelLikeness]), only for a model whose photos were measured
 * to be told right often enough, and with that record as the confidence.
 *
 * A photo likeness is a guess until someone looks: an alert it carries says so, and the verdict given
 * back ([check]) both settles the offer and makes it one of the references the next offers are told
 * from, so every look improves what the photos tell.
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

    /** The fact [PhotoFactChecks] keeps the model's verdicts under. */
    const val FACT = "model"

    /** Photos read for printed words per offer. */
    private const val PHOTOS_READ = 4

    /** Offers looked at per run, cheapest first: the cheap ones are the ones a good fit hides in. */
    private const val MOST_PER_RUN = 25

    /** The model [listing] was told to be, where one was. */
    fun of(listing: Listing): ModelGuess? {
        checkOf(listing.id)?.let { name -> return ModelGuess(name, ModelGuess.CHECKED, 1.0, sure = true) }
        return synchronized(kept) { kept[listing.id]?.guess }
    }

    /** The reviewer's verdict on [listingId]: a model's name, "" for none of the search's models, null when never checked. */
    fun checkOf(listingId: String): String? = PhotoFactChecks.of(FACT, listingId)

    /** A reviewer's verdict on what [listingId] is: one of the search's models by name, or null for none of them. */
    fun check(listingId: String, model: String?) = PhotoFactChecks.set(FACT, listingId, model.orEmpty())

    internal fun key(wishes: ProductWishes) = wishes.models.joinToString("|") { it.name + ":" + it.words.joinToString(",") }

    /** Tell the model of every offer among [listings] that names none, as far as each can be told. */
    suspend fun identify(listings: Collection<Listing>, query: SearchQuery, wishes: ProductWishes) {
        if (wishes.models.isEmpty()) return
        val against = key(wishes)
        fun named(l: Listing) = FitScore.namedModel(FitScore.folded("${l.title}\n${l.description.orEmpty()}"), wishes)
        val unnamed = listings.filter { named(it) == null && checkOf(it.id) == null }
            .filter { l -> synchronized(kept) { kept[l.id]?.against != against } }
            .sortedBy { FitScore.priceEur(it) }
            .take(MOST_PER_RUN)
        if (unnamed.isEmpty()) return
        unnamed.forEach { l ->
            val guess = try {
                fromText(l, wishes) ?: fromPrint(l, wishes) ?: fromLikeness(l, query, wishes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("model of {} not told: {}", l.id, e.message)
                return@forEach
            }
            synchronized(kept) { kept[l.id] = Kept(guess, against) }
            if (guess != null) log.info("model of {} ({}): {} from {}", l.id, l.title.take(50), guess.model, guess.how)
        }
        persist()
    }

    /** The model the offer's photos look like, where that model's photos were measured to be told right. */
    private fun fromLikeness(l: Listing, query: SearchQuery, wishes: ProductWishes): ModelGuess? =
        ModelLikeness.tell(l, query, wishes)?.let { (model, right) -> ModelGuess(model.name, ModelGuess.FROM_PHOTOS, right, sure = false) }

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

    private suspend fun detail(l: Listing) = DetailCache.get(l.id)
        ?: CrawlerRegistry.crawlerFor(l.platformId)?.fetchDetail(l)?.also { DetailCache.put(l.id, it) }

    private suspend fun photos(l: Listing): List<String> = detail(l)?.photos?.takeIf { it.isNotEmpty() } ?: l.imageUrls

    private fun persist() {
        val all = synchronized(kept) { kept.toMap() }
        runCatching { file.writeTextAtomically(json.encodeToString(all)) }
            .onFailure { log.warn("model guesses not kept: {}", it.message) }
    }
}
