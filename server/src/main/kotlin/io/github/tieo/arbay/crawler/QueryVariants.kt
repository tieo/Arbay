package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import kotlin.math.ln

/**
 * Other words a market uses for the thing being searched for, read out of the search's own results.
 *
 * A marketplace matches a query as a literal word, so a search misses everything the sellers named
 * differently: Kleinanzeigen answers "Parkettschleifmaschine" with machines titled exactly that and
 * never the ones titled "Parkettschleifer" or "Bodenschleifer", though they are the same tool. The
 * sellers who write two of the names in one title reveal the others, so the words worth trying are
 * already in the first page of results.
 *
 * Two things it deliberately does not do. It does not derive words from how the language builds
 * them — a rule swapping "-maschine" for "-er" invents words nobody sells under and spends a crawl
 * on each. And it does not judge them by embedding distance: measured against the local model, the
 * true synonyms "Bodenschleifer" (0.53) and "Walzenschleifer" (0.50) sit *below* "Waschmaschine"
 * (0.69), because the vector follows the shared ending rather than the meaning; the literature
 * reports the same conflation of "related" with "the same thing".
 *
 * Where a market prints its own related searches, [rank] picks from those: its vocabulary, at no
 * extra request. Where it prints none, [candidatesFrom] infers them from the result titles, ranked
 * by how much more common a word is here than across everything crawled rather than by raw count,
 * so a word frequent everywhere ("gebraucht") cannot win.
 *
 * A follow-up search is not checked against the first one's results. That was measured and is
 * backwards: searching the true synonym "Parkettschleifer" returned only 3.8% of the listings the
 * original search had found — precisely because it reaches the ones that never said
 * "Parkettschleifmaschine" — while the service search "parkett schleifen" overlapped 48%. Precision
 * is left to the relevance filter, which drops what these wider searches drag in.
 */
object QueryVariants {

    /** A query shorter than this is a plain category word ("laptop"); the words beside it in the
     *  results are other products, not other names for it. */
    private const val MIN_QUERY_LENGTH = 12

    /** Two sellers using the word makes it the market's term, not one seller's typo. */
    private const val MIN_OCCURRENCES = 2

    /** A candidate must be several times more common here than in the corpus at large. */
    private const val MIN_LOG_ODDS = 2.0

    /** At most two follow-up searches per platform, so a search costs three crawls, not a fan-out. */
    private const val MAX_VARIANTS = 2

    /** Long enough to be a product word, in any script. */
    private val WORD = Regex("""[\p{L}]{6,}""")

    /** How common a word is across everything crawled so far, as a share of listings in [0,1].
     *  The background a feedback term is judged against. */
    fun interface TermBackground {
        fun shareOfCorpus(term: String): Double
    }

    /**
     * The related searches a marketplace printed on its own results page, narrowed to the ones
     * worth spending a request on. A suggestion list mixes other names for the thing
     * ("Parkettschleifer", "Bodenschleifmaschine") with narrower and adjacent searches ("lägler",
     * "parkett", "parkettschleifmaschine mieten", "parkett schleifen"). The measured tells for each
     * are in the filters below.
     */
    fun rank(suggestions: List<String>, queryText: String, results: List<Listing>): List<String> {
        val query = queryText.trim().lowercase()
        if (query.length < MIN_QUERY_LENGTH) return emptyList()
        val titles = results.map { it.title.lowercase() }
        return suggestions
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != query }
            // One word only. A phrase is a different intent, not another name: measured against
            // Kleinanzeigen's own suggestions for "Parkettschleifmaschine", every phrase was either
            // a rental ("… mieten") or a service ("parkett schleifen", which alone brought in 38
            // tradesmen offering to sand a floor), while every other name for the tool was a word.
            .filterNot { it.contains(' ') }
            // A suggestion the query already spells is the same search ("schleifmaschine").
            .filterNot { it.contains(query) || query.contains(it) }
            .distinct()
            // How often the suggestion shares a title with the query decides the order, and only
            // sharing at all admits it. A word no result uses names something else ("pallmann",
            // "einscheibenmaschine": 0 titles). A word nearly every result uses is an attribute of
            // this market rather than a name for the thing ("lägler": 9 of 26, the make half these
            // machines carry). The other names sit in between, used by the few sellers who wrote
            // both ("parkettschleifer" and "bodenschleifmaschine": 1 each).
            .map { it to titles.count { title -> title.contains(it) } }
            .filter { it.second >= 1 }
            .sortedBy { it.second }
            .take(MAX_VARIANTS)
            .map { it.first }
    }

    /**
     * Terms worth searching alongside [queryText], read out of [results] and ranked by how much
     * more common they are here than in [background]. The fallback for a marketplace that prints
     * no related searches of its own. Empty for a short category word, or when no word is both used
     * by several sellers and distinctive.
     */
    fun candidatesFrom(
        results: List<Listing>,
        queryText: String,
        background: TermBackground,
    ): List<String> {
        val query = queryText.trim().lowercase()
        if (query.length < MIN_QUERY_LENGTH || !query.all { it.isLetter() }) return emptyList()
        if (results.isEmpty()) return emptyList()

        val counts = mutableMapOf<String, Int>()
        for (listing in results) {
            // Once per listing: a title repeating a word does not make it any more the market's.
            for (word in WORD.findAll(listing.title.lowercase()).map { it.value }.toSet()) {
                if (word == query || query in word || word in query) continue
                counts[word] = (counts[word] ?: 0) + 1
            }
        }

        return counts.entries
            .filter { it.value >= MIN_OCCURRENCES }
            .map { (word, count) ->
                val here = count.toDouble() / results.size
                // Smoothed so a word never seen before is distinctive rather than infinitely so.
                val everywhere = background.shareOfCorpus(word).coerceAtLeast(1.0 / 10_000)
                word to ln(here / everywhere)
            }
            .filter { it.second >= MIN_LOG_ODDS }
            .sortedByDescending { it.second }
            .take(MAX_VARIANTS)
            .map { it.first }
    }
}
