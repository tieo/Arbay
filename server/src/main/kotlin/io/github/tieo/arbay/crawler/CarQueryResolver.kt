package io.github.tieo.arbay.crawler

/**
 * Resolves free-text queries like "Volkswagen Crafter 2020" into make and model for
 * car sites whose search works via path segments or make/model parameters instead of
 * a free-text query (AutoScout24 ignores its `query` parameter entirely).
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

    // Canonical slug → aliases as they appear in user queries. Multi-word aliases
    // must be matched before shorter ones ("land rover" before a hypothetical "land").
    private val MAKES: Map<String, List<String>> = mapOf(
        "volkswagen" to listOf("volkswagen", "vw"),
        "mercedes-benz" to listOf("mercedes-benz", "mercedes benz", "mercedes"),
        "bmw" to listOf("bmw"),
        "audi" to listOf("audi"),
        "opel" to listOf("opel"),
        "ford" to listOf("ford"),
        "skoda" to listOf("skoda", "škoda"),
        "seat" to listOf("seat"),
        "cupra" to listOf("cupra"),
        "renault" to listOf("renault"),
        "peugeot" to listOf("peugeot"),
        "citroen" to listOf("citroen", "citroën"),
        "fiat" to listOf("fiat"),
        "toyota" to listOf("toyota"),
        "hyundai" to listOf("hyundai"),
        "kia" to listOf("kia"),
        "mazda" to listOf("mazda"),
        "nissan" to listOf("nissan"),
        "volvo" to listOf("volvo"),
        "porsche" to listOf("porsche"),
        "mini" to listOf("mini"),
        "dacia" to listOf("dacia"),
        "suzuki" to listOf("suzuki"),
        "mitsubishi" to listOf("mitsubishi"),
        "honda" to listOf("honda"),
        "tesla" to listOf("tesla"),
        "iveco" to listOf("iveco"),
        "man" to listOf("man"),
        "smart" to listOf("smart"),
        "jeep" to listOf("jeep"),
        "land-rover" to listOf("land rover", "landrover"),
        "jaguar" to listOf("jaguar"),
        "alfa-romeo" to listOf("alfa romeo", "alfa"),
        "chevrolet" to listOf("chevrolet"),
        "subaru" to listOf("subaru"),
        "lexus" to listOf("lexus"),
        "polestar" to listOf("polestar"),
        "byd" to listOf("byd"),
        "mg" to listOf("mg"),
    )

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

        for ((slug, aliases) in MAKES) {
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

    /** Every spelling of the make that [token] names — canonical slug plus its aliases,
     *  lowercased without spaces/hyphens — or null if [token] is not a known make. Lets a
     *  "Volkswagen" query still match a "VW" title (and vice versa). */
    fun makeSpellings(token: String): List<String>? {
        val t = token.lowercase().replace(" ", "").replace("-", "")
        for ((slug, aliases) in MAKES) {
            val forms = (listOf(slug) + aliases).map { it.lowercase().replace(" ", "").replace("-", "") }.distinct()
            if (t in forms) return forms
        }
        return null
    }
}
