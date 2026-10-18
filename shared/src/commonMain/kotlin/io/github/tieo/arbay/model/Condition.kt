package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class Condition {
    NEW,
    LIKE_NEW,
    VERY_GOOD,
    GOOD,
    ACCEPTABLE,
    USED,
    REFURBISHED,
    PARTS_ONLY;

    companion object {
        fun parse(text: String): Condition? {
            val lower = text.lowercase().trim()
            return when {
                // eBay's own label for a broken item, in each language its sites write: "Per ricambi
                // o non funzionante", "Pour pièces détachées", "Para piezas o no funciona". Read
                // first, since these labels also carry words of the other conditions.
                partsLabel.containsMatchIn(lower) -> PARTS_ONLY
                lower in listOf("neu", "new", "brand new", "nieuw", "neuf", "nuovo", "nuevo") -> NEW
                lower.contains("wie neu") || lower.contains("like new") || lower.contains("als nieuw") ||
                    lower.contains("come nuovo") || lower.contains("comme neuf") || lower.contains("como nuevo") -> LIKE_NEW
                // "Nuovo (altro)", "Neuf (autre)", "Nuevo: otro": new, out of its packaging.
                Regex("""^(nuovo|neuf|nuevo)\b""").containsMatchIn(lower) -> NEW
                lower.contains("ricondizionat") || lower.contains("reconditionn") || lower.contains("reacondicionad") -> REFURBISHED
                lower.contains("usato") || lower.contains("occasion") || lower.contains("usado") ||
                    lower.contains("segunda mano") || lower.contains("pre-owned") -> USED
                lower.contains("sehr gut") || lower.contains("very good") || lower.contains("exzellent") -> VERY_GOOD
                lower.contains("gut") || lower.contains("good") || lower.contains("nette staat") -> GOOD
                lower.contains("akzeptabel") || lower.contains("acceptable") -> ACCEPTABLE
                lower.contains("gebraucht") || lower.contains("used") || lower.contains("gebruikt") -> USED
                lower.contains("refurbished") || lower.contains("generalüberholt") -> REFURBISHED
                lower.contains("ersatzteile") || lower.contains("parts") || lower.contains("defekt") -> PARTS_ONLY
                else -> null
            }
        }

        private val partsLabel = Regex("""ricambi|non\s+funzionante|pi(è|e)ces\s+d(é|e)tach|para\s+piezas|no\s+funciona""")

        // Negated, the word says the opposite: "nicht defekt", "kein Defekt", "keine Defekte".
        private val defectWord = Regex("""(?<!nicht\s)(?<!kein\s)(?<!keine\s)(?<!ohne\s)\b(defekt|defect|kaputt|kapot|broken)\b""")
        private val defectPhrase = Regex(
            """als\s+defekt|defekt\s+verkauft|wird\s+nicht\s+(mehr\s+)?erkannt|nicht\s+(mehr\s+)?funktionsf(ä|ae)hig|""" +
                """funktioniert\s+nicht\s+mehr|f(ü|ue)r\s+bastler|an\s+bastler|bastlerware|for\s+(parts|spares)|""" +
                """not\s+working|spares\s+or\s+repair|pour\s+pi(è|e)ces|hors\s+service|ne\s+fonctionne\s+(pas|plus)|""" +
                """non\s+funzionante|per\s+ricambi|voor\s+onderdelen|werkt\s+niet""",
        )

        /**
         * Whether a listing says it is sold broken. Its title is the seller's headline, where a
         * plain "defekt" is about the thing itself. Its text is held to phrases that say so
         * outright ("wird als defekt verkauft", "wird im PC nicht erkannt"): a drive "aus einem
         * defekten Laptop ausgebaut" works, and the word alone there would call it broken.
         */
        fun soldAsDefective(title: String, text: String?): Boolean {
            val t = title.lowercase()
            if (defectWord.containsMatchIn(t) || defectPhrase.containsMatchIn(t)) return true
            return text != null && defectPhrase.containsMatchIn(text.lowercase())
        }
    }
}
