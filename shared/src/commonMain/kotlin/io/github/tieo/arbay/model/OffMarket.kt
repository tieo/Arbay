package io.github.tieo.arbay.model

/**
 * An ad that sends its buyer off the market: talk only on WhatsApp or Telegram, messages here go
 * unread, no payment through the market's own checkout. The market's buyer protection covers only
 * what is paid through it, and an ad steering around it is how goods that never ship are sold:
 * "Non leggiamo i messaggi qui. NON effettuiamo vendite tramite wallet Vinted. ... esclusivamente
 * su WhatsApp" under a Pixel 9 Pro XL at 346 €.
 *
 * A seller offering WhatsApp beside the market's messages is common and honest, so the word alone
 * says nothing; only the market's own channel being refused does.
 */
object OffMarket {
    private val messenger = """(whats\s?app|telegram|signal|wa\.me|t\.me)"""

    private val signs = listOf(
        // Talk elsewhere and only there. Only the words that lead to a channel may stand between:
        // "nur 2 Monate alt, WhatsApp eingerichtet" is a phone's description.
        Regex("""\b(nur|ausschlie(ß|ss)lich|only|exclusively|esclusivamente|solo|soltanto|uniquement|seulement|exclusivamente|alleen)\b\W+((über|per|via|auf|on|su|tramite|par|sur|by|bei|unter|in|me|us|mich|uns|ci|contact\w*|kontakt\w*|contattar\w*|schreib\w*|write|message)\W+){0,3}$messenger""", RegexOption.IGNORE_CASE),
        Regex("""$messenger\W+(\w+\W+)?(only|esclusivamente|solamente|uniquement)\b""", RegexOption.IGNORE_CASE),
        // Messages on the market go unread.
        Regex("""\b(non\s+leggiamo|non\s+leggo|wir\s+lesen\s+(hier\s+)?keine|ich\s+lese\s+(hier\s+)?keine|(we|i)\s+(don'?t|do\s+not)\s+read|nous\s+ne\s+lisons\s+pas|je\s+ne\s+lis\s+pas|no\s+leemos)\b""", RegexOption.IGNORE_CASE),
        // The market's own checkout refused, where it is the way that market is paid.
        Regex("""\b(non|no|not|nicht|kein\w*|pas)\b\W+(\w+\W+){0,4}(wallet|tramite\s+vinted|via\s+vinted|über\s+vinted|par\s+vinted|vinted[-\s]?(zahlung|bezahlung|kauf|checkout))""", RegexOption.IGNORE_CASE),
    )

    /** The words of [text] that send a buyer off the market, or null where it says none. */
    fun sign(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return signs.firstNotNullOfOrNull { it.find(text)?.value?.trim() }
    }
}
