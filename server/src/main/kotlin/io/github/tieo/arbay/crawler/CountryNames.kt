package io.github.tieo.arbay.crawler

import java.text.Normalizer
import java.util.Locale

/**
 * A country as a market writes it ("Regno Unito", "Vereinigtes Königreich", "Cina") to its ISO
 * 3166-1 alpha-2 code. Import VAT is decided on that code, and a name it cannot read leaves a
 * seller in the UK or China priced as if the parcel crossed no border.
 *
 * The names come from the JDK's own country names in every language it carries, so a market in a
 * new language needs nothing added here.
 */
object CountryNames {
    private val byName: Map<String, String> by lazy {
        val languages = Locale.getAvailableLocales().map { it.language }.filter { it.isNotEmpty() }.toSet()
        val map = HashMap<String, String>()
        for (code in Locale.getISOCountries()) {
            val country = Locale.of("", code)
            for (lang in languages) {
                val name = key(country.getDisplayCountry(Locale.of(lang)))
                if (name.isNotEmpty()) map.putIfAbsent(name, code)
            }
        }
        map
    }

    private fun key(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()

    /** The code for a written country name, or the code itself when it is one; null when neither. */
    fun iso(name: String?): String? {
        val text = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length == 2 && text.uppercase() in Locale.getISOCountries()) return text.uppercase()
        return byName[key(text)]
    }

    /** The country an origin line names, "da Germania", "aus China", "from United States": the
     *  line less its leading word, so the word for "from" in any language need not be known. */
    fun fromOriginLine(line: String): String? {
        val rest = line.trim().substringAfter(' ', "").takeIf { it.isNotBlank() } ?: return null
        return iso(rest)
    }
}
