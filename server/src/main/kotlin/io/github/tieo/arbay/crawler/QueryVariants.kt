package io.github.tieo.arbay.crawler

/**
 * Alternative spellings of a German compound noun, for marketplaces whose search matches the query
 * as a literal word. "Parkettschleifmaschine" and "Parkettschleifer" name the same machine, but a
 * site asked for one returns nothing titled the other, so a search for either misses half the
 * market.
 *
 * German builds an instrument noun from a verb stem plus one of a small set of head nouns
 * (-maschine, -gerät, -automat) or the agent suffix -er. Swapping the head between those forms over
 * the same stem is what the language itself does, so the variants name the same thing rather than
 * guessing at synonyms.
 *
 * Restricted to a single long compound word: a multi-word query already spreads across the terms,
 * and a short word ("bohrer") has too little stem left to swap safely. At most two variants, so a
 * search costs three crawls per platform, not an open-ended fan-out.
 */
object QueryVariants {

    private const val MIN_COMPOUND_LENGTH = 12
    private const val MIN_STEM_LENGTH = 6
    private const val MAX_VARIANTS = 2

    // Head nouns interchangeable over one stem, longest first so "-maschine" wins over "-schine".
    private val HEAD_NOUNS = listOf("maschine", "gerät", "geraet", "automat")

    /** Spellings to search alongside the query itself, or empty when it is not a swappable
     *  compound. Never contains the query. */
    fun of(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.length < MIN_COMPOUND_LENGTH) return emptyList()
        if (!q.all { it.isLetter() }) return emptyList()

        // Only the head-noun direction. Going the other way, from any word ending in -er, would
        // treat every such noun as an agent noun ("Waschtrockner") and spend crawls on words the
        // language does not form.
        val head = HEAD_NOUNS.firstOrNull { q.endsWith(it) } ?: return emptyList()
        val stem = q.dropLast(head.length)
        if (stem.length < MIN_STEM_LENGTH) return emptyList()
        // The agent noun first: it is the everyday word, so it carries the most listings.
        val variants = listOf(stem + "er") + HEAD_NOUNS.filterNot { it == head }.map { stem + it }

        // "geraet" and "gerät" are the same word; keep whichever came first.
        val seen = mutableSetOf(q)
        return variants
            .filter { seen.add(it.replace("ä", "ae")) && it != q }
            .take(MAX_VARIANTS)
    }
}
