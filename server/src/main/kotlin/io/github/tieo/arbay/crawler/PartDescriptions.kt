package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchReach
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How sellers describe a part, learned from the listings that carry its number.
 *
 * A part number finds only the listings that print it, and many do not: a replica "Abdeckung
 * Radhaus Tankrohr Tank passend für E30" and a used original "BMW E30 NFL Tankentlüftung Radhaus
 * Tank Schutzblech" are the BMW cover 16131179011 without saying so. The listings that do print
 * the number say what the part is and what it fits, in their sellers' own words, so those words
 * are taken from them rather than guessed:
 * - [models], the vehicle or device the part belongs to, a word of letters and digits most of
 *   those listings carry ("e30");
 * - [words], what the part is: each word three of those listings carry ("abdeckung",
 *   "tankdeckel", "breather"; the English words of a German market's answer come from a few
 *   sellers, and are how replicas from abroad are titled), and the roots several of them share
 *   ("tank" of "tankdeckel" and "tankentlüftung"), since a replica names the same thing in a
 *   compound of its own ("tankrohr").
 * One part word with the model says too little: "E30 Abdeckung" is every cover of the car, from
 * the loudspeaker's to the boot's. A listing is described as this part when it carries the model
 * and two different part words.
 */
data class PartDescriptions(
    val models: List<String>,
    val words: List<String>,
    /** Pairs of part words the numbered listings print together, most often first. */
    val pairs: List<Pair<String, String>>,
    /** The part words written as whole words, which a market's search can be asked for; a root
     *  ("tank" of "tankdeckel") only matches what was found. */
    val searchable: Set<String> = words.toSet(),
    /** Specific part words, compounds that name the part on their own, most used first. */
    val specific: List<String> = emptyList(),
) {

    /** Each description as the words a listing has to carry: the model and a pair of part words. */
    val phrasings: List<List<String>> get() = models.flatMap { m -> pairs.map { (a, b) -> listOf(m, a, b) } }

    /** Whether a title, letters and digits run together, is described as this part: it carries
     *  the model and one of the [pairs], the two words at different places in it, so a word
     *  cannot count twice ("tank" and "deck" are both in "tankdeckel", a tank cap). */
    fun describes(compactTitle: String): Boolean =
        models.any { it in compactTitle } && pairs.any { (a, b) -> apart(compactTitle, a, b) }

    /** The descriptions worth a search of their own: the model with each specific word the
     *  numbered listings use for the part (a compound of eight letters or more, "tankentlüftung",
     *  "entlüftungsschlauch"), then with the pairs they print together most. A market's search
     *  matches words literally, so "E30 Abdeckung Tankdeckel" finds no "Tankentlüftung Schutzblech"
     *  on Kleinanzeigen; what a broad search drags in is judged by [describes], which stays strict.
     *  A handful, since every one costs a crawl on every market. */
    fun searchTerms(max: Int = 6): List<String> = models.take(1).flatMap { m ->
        specific.map { "$m $it" } + pairs.filter { (a, b) -> a in searchable && b in searchable }.map { (a, b) -> "$m $a $b" }
    }.distinct().take(max)

    val isEmpty: Boolean get() = models.isEmpty() || pairs.isEmpty()

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+""")
        private val NUMBER_RUN = Regex("""\d[\d ]{5,}\d""")

        /** Words that say how or by whom a part is sold, never what it is. */
        private val SELLING_WORDS = setOf(
            "neu", "new", "neuf", "nuevo", "nuovo", "nieuw", "neuer", "original", "originale", "orig", "oem", "genuine",
            "gebraucht", "used", "teil", "ersatzteil", "part", "parts", "passend", "für", "fur", "for", "pour",
            "para", "per", "voor", "mit", "with", "und", "and", "der", "die", "das", "the", "von", "vom",
            "alle", "all", "serie", "series", "benzin", "petrol", "diesel", "essence", "gasolina",
        )

        /** Whether [title] prints a part number that is not [code] (nor its [shortCode]):
         *  "Schutzblech 16131176746" is another part of the same kind and place, described in the
         *  same words. A number of seven digits at least, spaces between its groups allowed. */
        fun printsAnotherNumber(title: String, code: String, shortCode: String?): Boolean =
            NUMBER_RUN.findAll(title).map { it.value.replace(" ", "") }.any { n ->
                n.length >= 7 && n != code && n != shortCode && !code.endsWith(n) && !n.contains(code)
            }

        /**
         * The descriptions of the part [code] names, from the [listings] whose titles carry it (or
         * BMW's seven digit form of it, [shortCode]). Empty where fewer than two listings carry it:
         * one seller's words are that seller's, not how the part is described.
         */
        fun learn(listings: List<Listing>, code: String, shortCode: String? = null): PartDescriptions {
            val compactCode = code.lowercase()
            val carriers = listings.filter { l ->
                val compact = l.title.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
                compactCode in compact || (shortCode != null && shortCode in compact)
            }.distinctBy { it.title.lowercase() }
            if (carriers.size < 2) return PartDescriptions(emptyList(), emptyList(), emptyList())
            val titles = carriers.map { l -> WORD.findAll(l.title.lowercase()).map { it.value }.toSet() }
            val counts = titles.flatten().groupingBy { it }.eachCount()
            fun share(word: String) = (counts[word] ?: 0).toDouble() / carriers.size
            val models = counts.keys
                .filter { w -> w.any(Char::isDigit) && w.any(Char::isLetter) && w.length in 2..6 && share(w) >= 0.5 }
                .sortedByDescending(::share)
            fun names(w: String) = w.all(Char::isLetter) && w.length >= 4 && w !in SELLING_WORDS &&
                CarQueryResolver.resolve(w) == null
            val frequent = counts.filter { (w, n) -> n >= 3 && names(w) }.keys
                .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it })
            // The roots the part's words share, four letters at least, from every word of those
            // titles rather than the frequent ones alone: "tank" of "tankdeckel", "tankentlüftung"
            // and "tank" is in six of the cover's titles while no one of those words is in three.
            // A root counts where it begins a word in two titles at least and is in three, so a
            // shared ending ("tung" of "entlüftung" and "leitung") is no root.
            val compacts = carriers.map { it.title.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "") }
            val partWords = titles.flatten().filter(::names).distinct()
            val roots = partWords.flatMap { a -> partWords.filter { it > a }.mapNotNull { b -> commonPart(a, b) } }
                .distinct()
                .filter { r ->
                    names(r) && r !in frequent &&
                        titles.count { t -> t.any { it.startsWith(r) } } >= 2 && compacts.count { r in it } >= 3
                }
            // The pairs the numbered titles print together, at different places, twice at least:
            // which words belong together is read off the part's own listings, so "abdeckung" with
            // "tank" is the cover, and "tankdeckel" with "kraftstoff", which none of them prints,
            // is a tank cap.
            val words = frequent + roots
            val pairs = compacts.flatMap { t ->
                val present = words.filter { it in t }
                present.indices.flatMap { i -> (i + 1 until present.size).map { j -> present[i] to present[j] } }
                    .filter { (a, b) -> apart(t, a, b) }
            }.groupingBy { it }.eachCount().entries.filter { it.value >= 2 }
                .sortedWith(compareByDescending<Map.Entry<Pair<String, String>, Int>> { it.value }.thenBy { it.key.first + it.key.second })
                .map { it.key }
            val specific = counts.filter { (w, n) -> n >= 2 && w.length >= 8 && names(w) }.keys
                .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { it })
            return PartDescriptions(models, words, pairs, frequent.toSet(), specific)
        }

        /** Whether [a] and [b] both occur in [text] at places that do not overlap. */
        fun apart(text: String, a: String, b: String): Boolean {
            fun at(w: String) = generateSequence(text.indexOf(w).takeIf { it >= 0 }) { i -> text.indexOf(w, i + 1).takeIf { it >= 0 } }
                .map { it until it + w.length }.toList()
            return at(a).any { ra -> at(b).any { rb -> ra.last < rb.first || rb.last < ra.first } }
        }

        /** The longest run of letters [a] and [b] both contain, when four letters or more. */
        private fun commonPart(a: String, b: String): String? {
            var best = ""
            for (i in a.indices) for (j in i + 4..a.length) {
                val part = a.substring(i, j)
                if (part.length > best.length && part in b) best = part
            }
            return best.takeIf { it.length >= 4 }
        }
    }
}

/**
 * The listings that print a part number, read once per number from eBay.de and kept an hour.
 *
 * Descriptions are learned across markets: a part number is printed on eBay, where parts dealers
 * list by number, and hardly ever on Kleinanzeigen, where a used part is titled by what it is.
 * Learned from a market's own answer alone, the market that most needs the descriptions would
 * never get any.
 */
object PartNumberReference {
    private val lock = Mutex()
    private val answers = java.util.concurrent.ConcurrentHashMap<String, Pair<Instant, List<Listing>>>()

    /** What was last read for [code], without reading anything: for judging an answer, which
     *  happens after the market's search has read it. */
    fun known(code: String): List<Listing> = answers[code]?.takeIf { Clock.System.now() - it.first < 1.hours }?.second.orEmpty()

    suspend fun listings(query: SearchQuery, code: String): List<Listing> = lock.withLock {
        val now = Clock.System.now()
        answers[code]?.takeIf { now - it.first < 1.hours }?.let { return@withLock it.second }
        val found = runCatching { CrawlerRegistry.crawlerFor(PlatformId.EBAY_DE)?.search(query.copy(reach = SearchReach())) }
            .getOrNull().orEmpty()
        if (found.isNotEmpty()) answers[code] = now to found
        found
    }
}
