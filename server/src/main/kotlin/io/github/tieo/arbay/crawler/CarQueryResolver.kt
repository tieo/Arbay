package io.github.tieo.arbay.crawler

/**
 * Resolves free-text queries like "Volkswagen Crafter 2020" into make and model for
 * car sites whose search works via path segments or make/model parameters instead of
 * a free-text query (AutoScout24 ignores its `query` parameter entirely).
 *
 * The make list itself comes from [CarTaxonomyProvider.current] — the same live, ~290-make
 * AutoScout24 catalog the picker uses — not a second hand-typed list. A make this resolver
 * cannot name is a make no site can be asked for anyway, so keeping one authoritative list
 * means a make added to the picker is recognised here too, with no second place to update.
 * [EXTRA_ALIASES] is deliberately the only hand-typed thing left: colloquial names and
 * accented spellings ("VW", "Škoda") that AutoScout24's own label never contains, so they
 * could never be derived from its catalog no matter how it's synced.
 */
object CarQueryResolver {

    /**
     * @param makeSlug canonical slug in AutoScout24 style ("volkswagen", "mercedes-benz")
     * @param modelSlug first token after the make, lowercased ("crafter"), null if the
     *   query names only a make
     * @param remainder tokens after the model, left for relevance filtering
     */
    data class CarQuery(
        val makeSlug: String,
        val modelSlug: String?,
        val remainder: String,
    )

    // Canonical slug → colloquial/accented spellings that never appear in AutoScout24's own
    // label for the make, so no amount of syncing its catalog would ever produce them.
    private val EXTRA_ALIASES: Map<String, List<String>> = mapOf(
        "volkswagen" to listOf("vw"),
        "mercedes-benz" to listOf("mercedes"),
        "land-rover" to listOf("landrover"),
        "alfa-romeo" to listOf("alfa"),
        "citroen" to listOf("citroën"),
        "skoda" to listOf("škoda"),
    )

    /** Canonical slug → every spelling a user might type it as, built fresh from whatever
     *  [CarTaxonomyProvider.current] holds so a make added to the live catalog is recognised
     *  here without a second list to remember to update. */
    private fun makeAliases(): Map<String, List<String>> =
        CarTaxonomyProvider.current.makes.associate { make ->
            val label = make.name.lowercase()
            // AutoScout24's own label sometimes hyphenates a multi-word make ("Mercedes-Benz");
            // a query typed as two words ("mercedes benz") must match it too.
            val spaced = label.replace("-", " ")
            val forms = (listOf(label, spaced) + (EXTRA_ALIASES[make.id] ?: emptyList())).distinct()
            make.id to forms
        }

    /**
     * Matches a make only at the start of the query, so common words that are also
     * brand names ("man", "smart", "mini") cannot trigger mid-sentence.
     * Returns null when the query does not start with a known make.
     */
    fun resolve(text: String): CarQuery? {
        val tokens = text.split(" ")
            .filter { it.isNotBlank() && !it.startsWith("-") }
            .map { it.lowercase() }
        if (tokens.isEmpty()) return null

        for ((slug, aliases) in makeAliases()) {
            for (alias in aliases.sortedByDescending { it.count { c -> c == ' ' } }) {
                val aliasTokens = alias.split(" ")
                if (tokens.size >= aliasTokens.size &&
                    tokens.subList(0, aliasTokens.size) == aliasTokens
                ) {
                    val model = tokens.getOrNull(aliasTokens.size)
                    val remainder = tokens.drop(aliasTokens.size + (if (model != null) 1 else 0))
                        .joinToString(" ")
                    return CarQuery(makeSlug = slug, modelSlug = model, remainder = remainder)
                }
            }
        }
        return null
    }

    /**
     * The same resolution, plus a model named without its make ("crafter", "sprinter 314").
     *
     * Only for a site that is already being crawled as a car site and needs a make in its URL.
     * [resolve] stays strict because it is also what decides whether a query is about cars at all,
     * and a model list is full of ordinary words — "focus", "polo", "captur" — that would drag
     * plain product searches onto car platforms.
     *
     * A model name shared by two makes stays unresolved: "sprinter" is both a Mercedes-Benz and a
     * Toyota, and choosing between them is guessing. Unresolved is the honest answer, and the
     * caller can then decline to crawl rather than fetch a page of the site's whole catalogue.
     */
    fun resolveForCarSite(text: String): CarQuery? {
        resolve(text)?.let { return it }
        val tokens = text.split(" ")
            .filter { it.isNotBlank() && !it.startsWith("-") }
            .map { it.lowercase() }
        val first = tokens.firstOrNull()?.takeIf { it.length >= 3 } ?: return null
        fun named(model: io.github.tieo.arbay.model.CarModelNode) =
            model.id.lowercase() == first || model.name.lowercase() == first
        val owner = CarTaxonomyProvider.current.makes
            .filter { make -> make.models.any { named(it) } }
            .singleOrNull() ?: return null
        val model = owner.models.first { named(it) }
        return CarQuery(
            makeSlug = owner.id,
            modelSlug = model.id,
            remainder = tokens.drop(1).joinToString(" "),
        )
    }

    /** Every spelling of the make that [token] names — canonical slug plus its aliases,
     *  lowercased without spaces/hyphens — or null if [token] is not a known make. Lets a
     *  "Volkswagen" query still match a "VW" title (and vice versa). */
    fun makeSpellings(token: String): List<String>? {
        val t = token.lowercase().replace(" ", "").replace("-", "")
        for ((slug, aliases) in makeAliases()) {
            val forms = (listOf(slug) + aliases).map { it.lowercase().replace(" ", "").replace("-", "") }.distinct()
            if (t in forms) return forms
        }
        return null
    }
}
