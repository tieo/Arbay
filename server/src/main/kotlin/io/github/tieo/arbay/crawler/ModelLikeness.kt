package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ModelWish
import io.github.tieo.arbay.model.ProductWishes
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.repo.ListingArchive

/**
 * Which of a search's models an offer's photos look like ([PhotoLikeness]), told from the photos of
 * every archived offer of that search whose title names its model.
 *
 * The examples are the archive rather than one hour's answer: a search returns a few dozen offers,
 * the archive holds every one it ever returned, and a model seen in ten photos is told far better than
 * one seen in two. An archived offer counts as one of the search's own when its text carries a word of
 * the search that is not a model word (a "Cobra" golf club says no "Parkett"), and it is an example
 * when its title names exactly one model, or a reviewer said which model it is ([ModelGuesses.check]).
 */
object ModelLikeness {
    private val tellers = PhotoLikeness.Tellers()

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

    /** Every archived offer of [query] whose model is known, one per offer, with its photos, labelled by
     *  the model's name; a rented one counts, since the machine in its photos is the model all the same. */
    fun examples(query: SearchQuery, wishes: ProductWishes): List<PhotoLikeness.Example> {
        val kind = kindWords(query, wishes)
        // A reviewer's verdict outranks the title: that is what makes every look a better example.
        val checks = PhotoFactChecks.all(ModelGuesses.FACT)
        return PhotoLikeness.examples(ListingArchive.all().mapNotNull { l ->
            val model = when (val c = checks[offerKey(l.id)]) {
                null -> namedIn(l, wishes, kind)
                else -> wishes.models.firstOrNull { it.name == c }
            }
            model?.let { l to it.name }
        })
    }

    /** Every way of telling measured on the search's examples, a model worth something and one worth
     *  nothing counted as different kinds. */
    fun measure(query: SearchQuery, wishes: ProductWishes): PhotoLikeness.Measured {
        val worth = wishes.models.associate { it.name to it.worth }
        return PhotoLikeness.measure(examples(query, wishes)) { if ((worth[it] ?: 0.0) > 0.0) "good" else "worthless" }
    }

    /** The model [l]'s photos look like, where that model is told right often enough, with its record as confidence. */
    fun tell(l: Listing, query: SearchQuery, wishes: ProductWishes, against: String): Pair<ModelWish, Double>? {
        val teller = tellers.get(against) { PhotoLikeness.Teller.of(examples(query, wishes), "the models of \"${query.text}\"") }
        val (name, share) = teller.tell(l) ?: return null
        return wishes.models.firstOrNull { it.name == name }?.let { it to share }
    }
}
