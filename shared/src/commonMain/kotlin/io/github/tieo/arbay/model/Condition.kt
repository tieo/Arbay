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
                lower in listOf("neu", "new", "nieuw", "neuf") -> NEW
                lower.contains("wie neu") || lower.contains("like new") || lower.contains("als nieuw") -> LIKE_NEW
                lower.contains("sehr gut") || lower.contains("very good") || lower.contains("exzellent") -> VERY_GOOD
                lower.contains("gut") || lower.contains("good") || lower.contains("nette staat") -> GOOD
                lower.contains("akzeptabel") || lower.contains("acceptable") -> ACCEPTABLE
                lower.contains("gebraucht") || lower.contains("used") || lower.contains("gebruikt") -> USED
                lower.contains("refurbished") || lower.contains("generalüberholt") -> REFURBISHED
                lower.contains("ersatzteile") || lower.contains("parts") || lower.contains("defekt") -> PARTS_ONLY
                else -> null
            }
        }
    }
}
