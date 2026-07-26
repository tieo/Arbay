package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import kotlin.math.ln

/**
 * Other words a market uses for the thing being searched for.
 *
 * A marketplace matches a query as a literal word, so a search misses everything its sellers named
 * differently: Kleinanzeigen answers "Parkettschleifmaschine" only with titles containing that word,
 * never the same machines titled "Parkettschleifer". Where a market prints its related searches it
 * hands over its own vocabulary for free, on a page already fetched; [candidates] decides which of
 * those are worth a search. [candidatesFrom] infers them from result titles for markets that print
 * none.
 *
 * The hard part is that a related-search list mixes other names for the thing with brands (lägler,
 * kärcher), adjacent products (rasenmäher under heckenschere), accessories (drechseleisen),
 * materials (drechselholz), services (kernbohrung) and noise (zisterne). Measured over 32 niche
 * product queries, four ways of telling them apart failed and are not worth retrying: deriving words
 * from German morphology invents ones nobody sells under (kaffeemaschine → kaffeeer); embedding
 * distance ranks the chisel "drechseleisen" (0.60) above the machine "drechselmaschine" (0.59); a
 * follow-up search's overlap with the first is backwards, the true synonym overlapping 3.8% against
 * a service search's 48%; and price ratio puts the true synonym "parkettschleifer" (0.16 of the
 * query's median) below an actual chisel (0.24). The probes are kept as tests.
 *
 * What does hold on that data is below: what a word is built from, and whether the market names the
 * query back.
 */
object QueryVariants {

    /** A query shorter than this is a plain category word ("laptop", "monitor"); the words beside
     *  it are other products, not other names for it. Ten keeps "Kettensäge" in. */
    private const val MIN_QUERY_LENGTH = 10

    /** What a compound is left with once the word for "machine" is taken off it: the job it does.
     *  Two words naming the same job are two names for the same machine — Parkettschleif|maschine
     *  and Parkettschleif|er both reduce to "parkettschleif". */
    private val DEVICE_HEADS = listOf(
        "maschine", "maschiene", "maschinen", "gerät", "geraet", "anlage", "automat", "bank",
        "presse", "werk", "er", "or",
    )

    /** Nouns saying which tool it is. Two compounds sharing a modifier but differing here are
     *  different machines: a Furnierpresse presses veneer, a Furniersäge cuts it. */
    private val TOOL_NOUNS = listOf(
        "säge", "saege", "fräse", "fraese", "presse", "pumpe", "bohrer", "mühle", "muehle",
        "hammer", "schere", "messer", "hobel", "drehbank", "brenner", "sauger", "bläser",
        "blaeser", "kabine", "ofen",
    )

    /** A job too short to identify anything. */
    private const val MIN_JOB_LENGTH = 6

    /** At most two follow-up searches per platform, so a search costs three crawls, not a fan-out. */
    private const val MAX_VARIANTS = 2

    /** Two sellers using the word makes it the market's term, not one seller's typo. */
    private const val MIN_OCCURRENCES = 2

    /** A candidate must be several times more common here than in the corpus at large. */
    private const val MIN_LOG_ODDS = 2.0

    /** Long enough to be a product word, in any script. */
    private val WORD = Regex("""[\p{L}]{6,}""")

    /** Heads that name something other than the machine asked for: what it works on or with (a
     *  Drechseleisen is the chisel a Drechselbank turns against, Drechselholz the wood it turns),
     *  what it stands on (Siebdrucktisch, Magnetbohrständer), or a different machine of the same
     *  family (Espressomühle beside an Espressomaschine).
     *
     *  Rejected only when the query does not carry the same head: someone searching for a
     *  Kaffeemühle should still be offered another mill. */
    private val OTHER_KIND_HEADS = listOf(
        "holz", "eisen", "krone", "kronen", "papier", "blatt", "blätter", "band", "bänder",
        "scheibe", "scheiben", "werkzeug", "futter", "messer", "kette", "ketten", "zubehör",
        "zubehoer", "ersatzteil", "ersatzteile", "sack", "säcke", "beutel", "aufsatz", "halter",
        "tisch", "ständer", "schrank", "karussell", "mühle",
    )

    /** Nominalised actions ("Kernbohrung" is the hole, not the machine) and infinitives
     *  ("drechseln", "vertikutieren") — the job someone offers, not a thing to buy. */
    private fun namesAnAction(word: String): Boolean {
        if (word.endsWith("ung") || word.endsWith("ungen")) return true
        // -en/-ln/-rn is the German infinitive, but also many plurals; a device's plural keeps its
        // own head ("…maschinen"), so those endings are spared.
        val infinitive = word.endsWith("en") || word.endsWith("ln") || word.endsWith("rn")
        val plural = word.endsWith("maschinen") || word.endsWith("schienen") ||
            word.endsWith("gen") || word.endsWith("ien") || word.endsWith("nen")
        return infinitive && !plural
    }

    /** Whether the word names the same kind of thing as the query, as against a part of it, the
     *  stuff it works on, what it stands on, or the job it does. */
    private fun namesSameKind(word: String, query: String): Boolean {
        if (namesAnAction(word)) return false
        // Folded, so a transliterated query ("kaffeemuehle") carries the same head as the word
        // ("espressomühle") and is not treated as a different kind of thing.
        val w = normalise(word)
        val q = normalise(query)
        return OTHER_KIND_HEADS.none { head ->
            val h = normalise(head)
            w.endsWith(h) && !q.endsWith(h)
        }
    }

    /** How many different searches may offer a term before it is taken for a make or a category
     *  rather than a name for this thing. Measured over 32 niche products: every term seen under
     *  three or more searches was one of those, while the real synonyms sat at one or two. */
    private const val MAX_SEARCHES_OFFERING_IT = 2

    /** A term worth a search, and whether it is close enough to the query's own spelling to be
     *  trusted on that alone. */
    data class Candidate(val term: String, val sharesStem: Boolean)

    /**
     * The related searches a marketplace printed, narrowed to those worth spending a request on.
     * Phrases go — every phrase measured was a rental ("… mieten") or a service ("parkett
     * schleifen", which alone brought back 38 tradesmen offering to sand a floor). So do words the
     * query already spells, which would only re-run the same search, and words naming a part or a
     * job rather than a device.
     *
     * Those sharing the query's stem come first and need no further evidence: over 32 products that
     * kept 17 terms, every one another name for the thing. The rest are worth trying only if the
     * market names the query back from their own page — see [namesBack].
     *
     * [offeredUnder] tells how many different searches have been offered a term, which is what
     * separates a make (einhell, stihl) or a category that sits beside everything (rasenmäher) from
     * a name for this particular thing.
     */
    fun candidates(
        suggestions: List<String>,
        queryText: String,
        offeredUnder: (String) -> Int = { 0 },
    ): List<Candidate> {
        val query = queryText.trim().lowercase()
        if (query.length < MIN_QUERY_LENGTH) return emptyList()
        val kept = suggestions
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != query }
            .filterNot { it.contains(' ') }
            // Compared with umlauts folded, so a truncated "oberfräs" is recognised as part of
            // "oberfraese" rather than looking like a word of its own.
            .filterNot { normalise(it).contains(normalise(query)) || normalise(query).contains(normalise(it)) }
            .filter { namesSameKind(it, query) }
            // A term the market offers under many unrelated searches is a make or a category that
            // sits beside everything, not a name for this thing.
            .filterNot { offeredUnder(it) > MAX_SEARCHES_OFFERING_IT }
            .distinct()
        // A plural of a term already kept is the same search.
        val singulars = kept.filterNot { w -> kept.any { it != w && (w == it + "n" || w == it + "en") } }
        return singulars
            .map { Candidate(it, namesSameThing(query, it)) }
            .sortedByDescending { it.sharesStem }
            .take(MAX_VARIANTS)
    }

    /**
     * Whether the market, asked about a candidate, names [queryText] back. A word sharing none of
     * the query's spelling can still be the same thing — "Motorsäge" for "Kettensäge" — and the
     * market saying so in both directions is the evidence for it. Alone this shows only relatedness
     * (a chisel names its machine back too), so it is used on top of the device test, never instead.
     */
    fun namesBack(queryText: String, termSuggestions: List<String>): Boolean {
        val query = normalise(queryText)
        return termSuggestions.any { normalise(it).contains(query) }
    }

    private fun normalise(text: String): String = text.trim().lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(" ", "").replace("-", "")

    /** The compound with its "machine" word taken off, leaving the job it does. */
    private fun job(word: String): String {
        val w = normalise(word)
        val head = DEVICE_HEADS.map { normalise(it) }
            .filter { w.endsWith(it) && w.length - it.length >= 5 }
            .maxByOrNull { it.length }
        return if (head != null) w.dropLast(head.length) else w
    }

    /**
     * Whether two words name the same job, and so the same machine. Equal jobs settle it; one job
     * extending the other is still the same machine when the extra part only says more about it
     * ("einscheiben" against "einscheibenschleif"), but not when it names a different tool
     * ("furnier" against "furniersäge") — which is what separates a planter from a lifter:
     * "kartoffelrod" and "kartoffellege" share only the crop.
     */
    private fun namesSameThing(query: String, candidate: String): Boolean {
        val a = job(query)
        val b = job(candidate)
        if (a.length < MIN_JOB_LENGTH || b.length < MIN_JOB_LENGTH) return false
        if (a == b) return true
        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a
        if (!longer.startsWith(shorter)) return false
        val extra = longer.drop(shorter.length)
        // German joins compounds with a linking s or e, which is not part of the next word.
        val forms = setOf(extra, extra.drop(1).takeIf { extra.firstOrNull() in setOf('s', 'e') } ?: extra)
        return TOOL_NOUNS.map { normalise(it) }.none { tool -> forms.any { it.startsWith(tool) } }
    }

    /**
     * Terms read out of the result titles instead, ranked by how much more common they are here than
     * in [background]. The fallback for a marketplace that prints no related searches of its own.
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
                if (!namesSameKind(word, query)) continue
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

    /** How common a word is across everything crawled so far, as a share of listings in [0,1].
     *  The background a feedback term is judged against. */
    fun interface TermBackground {
        fun shareOfCorpus(term: String): Double
    }
}
