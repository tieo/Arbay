package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SearchQuery

object RelevanceFilter {

    data class ParsedQuery(
        val positiveTokens: List<String>,
        val negativeTokens: List<String>,
        val orGroups: List<List<String>>,
    )

    fun parseQuery(raw: String): ParsedQuery {
        val parts = raw.split(" ").filter { it.isNotBlank() }
        val negative = mutableListOf<String>()
        val positiveParts = mutableListOf<String>()

        for (part in parts) {
            if (part.startsWith("-") && part.length > 1) {
                negative.add(normalizeToken(part.removePrefix("-")))
            } else {
                positiveParts.add(part)
            }
        }

        val orGroups = mutableListOf<List<String>>()
        val plainTokens = mutableListOf<String>()
        val joined = positiveParts.joinToString(" ")
        if (joined.contains(" OR ", ignoreCase = true)) {
            val alternatives = joined.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE))
            for (alt in alternatives) {
                orGroups.add(alt.trim().split("\\s+".toRegex()).map { normalizeToken(it) })
            }
        } else {
            plainTokens.addAll(positiveParts.map { normalizeToken(it) })
        }

        return ParsedQuery(
            positiveTokens = plainTokens,
            negativeTokens = negative,
            orGroups = orGroups,
        )
    }

    private fun normalizeToken(token: String): String {
        val n = normalize(token.lowercase()).replace(" ", "")
        // Fold a make alias to its canonical spelling so "vw" and "volkswagen" are one token.
        return CarQueryResolver.makeSpellings(n)?.firstOrNull() ?: n
    }

    /** Canonicalize make aliases word-by-word in a normalized title, so "VW"/"Mercedes" match a
     *  "Volkswagen"/"Mercedes-Benz" query token (and vice versa). */
    private fun canonicalizeMakes(normalizedText: String): String =
        normalizedText.split(" ").joinToString(" ") { w ->
            CarQueryResolver.makeSpellings(w)?.firstOrNull() ?: w
        }

    fun score(listing: Listing, parsed: ParsedQuery): Double {
        val titleNorm = normalize(listing.title.lowercase())
        // Strip comparison phrases before token matching — prevents "wie WH-1000XM5" (German "like XM5")
        // from matching the XM5 query. Amazon uses "gleicher Prozessor wie WH-1000XM5" to cross-sell
        // related products, causing false positives when the model appears only in the comparison clause.
        // Also strip "als X" (German "as X") for comparisons like "besser als WH-1000XM5".
        val titleNormForMatching = canonicalizeMakes(
            titleNorm
                .replace(Regex("\\bwie\\s+\\S+(?:\\s+\\S+)?"), " ")
                .replace(Regex("\\bals\\s+\\S+(?:\\s+\\S+)?"), " ")
                .replace(Regex("\\s+"), " ").trim()
        )
        val titleCompact = titleNormForMatching.replace(" ", "")
        // Strip context numbers that must NOT match numeric model tokens — BUT only
        // strip storage values if the query doesn't contain storage-like tokens (e.g. "256")
        val queryHasStorageToken = (parsed.positiveTokens + parsed.orGroups.flatten()).any { t ->
            t.all { c -> c.isDigit() } && t.length >= 2 && t.toIntOrNull()?.let { it in listOf(8,16,32,64,128,256,512,1024,2048) } == true
        }
        val titleNormStripped = titleNormForMatching
            .let { if (queryHasStorageToken) it else it.replace(Regex("\\d+\\s*(?:gb|tb|mb)\\b"), " ") }
            // Greedy: strip ALL digit groups after the OS name (handles "ios 12 7 5" from "iOS 12.7.5")
            .replace(Regex("\\bandroid\\s+(\\d+\\s*)+"), "android ")
            .replace(Regex("\\bios\\s+(\\d+\\s*)+"), "ios ")
            .replace(Regex("\\bipados\\s+(\\d+\\s*)+"), "ipados ")
            .replace(Regex("\\b\\d+\\s+\\d\\s*(?:zoll|inch)\\b", RegexOption.IGNORE_CASE), " ")
            // Strip count phrases: "2 Spiele", "1 Spiel", "3 Games" — a count is not a model number
            .replace(Regex("\\b\\d+\\s+(?:spielen?|games?|titeln?)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()
        val titleWords = titleNormStripped.split(" ").filter { it.isNotBlank() }

        // Reject placeholder titles
        if (isPlaceholderTitle(titleNorm, titleWords)) return -1.0

        // Reject bulk/lot listings: "15x", "x15", "15 Stück", "lot of 3" etc.
        if (Regex("""(?:^|\s)\d{2,}\s*x\s|\s+x\s*\d{2,}(?:\s|$)""").containsMatchIn(titleNorm)) return -1.0

        // Word-start positions in titleCompact (for guarding compact matches)
        val wordStartsInCompact: Set<Int> = buildSet {
            var pos = 0
            for (word in titleNormForMatching.split(" ")) {
                if (word.isNotEmpty()) add(pos)
                pos += word.length
            }
        }

        // Unit suffixes that can be glued to a number: "256gb", "512tb", "16mp"
        val unitSuffixes = setOf("gb", "tb", "mb", "mp", "mhz", "ghz", "mah", "wh", "mm", "cm", "kg", "zoll", "inch")

        fun tokenMatches(token: String): Boolean {
            // ≤2 chars: whole-word only, or number+unit (e.g. "6" matches "6" but not "16")
            if (token.length <= 2) return titleWords.any { it == token }
            // 3-5 chars: whole-word, or numeric token matching word that starts with it + unit suffix
            // (e.g. "256" matches "256gb", "512" matches "512tb")
            if (token.length <= 5) {
                if (titleWords.any { it == token }) return true
                if (token.all { it.isDigit() }) {
                    if (titleWords.any { word -> word.startsWith(token) && unitSuffixes.any { word == token + it } }) return true
                }
                // Compact matching with word-boundary guard (catches hyphen-split tokens).
                // e.g. "xt5" (from query "X-T5") matches "fujifilm x t5" via compact "fujifilmxt5".
                // e.g. "usbc" (from "USB-C") matches "usb c" via compact "usbc".
                // The word-boundary guard prevents "pro" matching inside "propellerschutz".
                var searchFrom = 0
                while (true) {
                    val idx = titleCompact.indexOf(token, searchFrom)
                    if (idx == -1) break
                    val endIdx = idx + token.length
                    if (idx in wordStartsInCompact &&
                        (endIdx == titleCompact.length || endIdx in wordStartsInCompact)
                    ) {
                        // Extra guard: reject matches of the form "1-letter-word + all-digit-word"
                        // (e.g. "s" + "10" from "Tab S 10.5" → "s10") to prevent old model numbers
                        // from matching newer compact codes. Allow "x" + "t5" (letter+digit word) since
                        // the second part contains a letter, making it a model-code suffix not a decimal.
                        val splitInside = wordStartsInCompact.firstOrNull { it > idx && it < endIdx }
                        val isLetterPlusDigits = splitInside != null &&
                            splitInside - idx == 1 &&
                            titleCompact[idx].isLetter() &&
                            titleCompact.substring(splitInside, endIdx).all { c -> c.isDigit() }
                        if (!isLetterPlusDigits) return true
                    }
                    searchFrom = idx + 1
                }
                return false
            }
            // ≥6 chars: raw substring or compact (long tokens are distinctive enough for substring)
            if (titleNormForMatching.contains(token)) return true
            // titleCompact catches tokens split by hyphens/spaces (e.g. "wh1000xm6" = "WH-1000XM6").
            // Guard: match must start AND end at word boundaries (prevents "g16" matching "8G 16GB").
            var searchFrom = 0
            while (true) {
                val idx = titleCompact.indexOf(token, searchFrom)
                if (idx == -1) break
                val endIdx = idx + token.length
                if (idx in wordStartsInCompact &&
                    (endIdx == titleCompact.length || endIdx in wordStartsInCompact)
                ) return true
                searchFrom = idx + 1
            }
            return false
        }

        fun negativeMatches(token: String): Boolean {
            // Short tokens (≤2 chars like "s", "x") must match as whole words only.
            // Avoids "series".endsWith("s") killing all Xbox Series results for query "-s".
            if (token.length <= 2) return titleWords.any { it == token }
            return titleWords.any { word ->
                word == token || word.endsWith(token) ||
                // German plurals: -huelle → -huellen
                word.endsWith(token + "n") || word.endsWith(token + "en") ||
                // Compound words for longer tokens (4+ chars to avoid "pro" in "product")
                (token.length >= 4 && word.contains(token))
            }
        }

        for (neg in parsed.negativeTokens) {
            if (negativeMatches(neg)) return -1.0
        }

        // Short model-code tokens MUST match as exact whole words:
        // - Pure numeric (1-2 digit): "6", "12", "13" — without this, "Galaxy Z Fold 6" would match
        //   "Fold3/Fold5" at 80% because "fold" matches "fold3" and all other tokens also match.
        // - Mixed alphanumeric (length ≤ 3 with at least one digit): "r5", "a7", "m4", "s25"
        //   These are specific model codes — "R5" must not match against "5D" variants. Without
        //   this check, "Canon EOS R5 Mark II" scores 4/5=80% against "Canon EOS 5D Mark II".
        if (parsed.orGroups.isEmpty()) {
            val modelCodesRequired = parsed.positiveTokens.filter { t ->
                t.length <= 3 && t.any { c -> c.isDigit() }
            }
            // Use tokenMatches (not just titleWords) to support compact-form codes like "xt5"
            // matching "x t5" (from "X-T5" hyphen-split in title).
            if (modelCodesRequired.any { token -> !tokenMatches(token) }) return 0.0

            // Bigram check: for each consecutive pair [word, short-numeric OR model qualifier] in
            // the query, require the pair to appear consecutively in the title.
            // Numeric example: "switch 2", "mini 7", "series 10" — prevents "Splatoon 2 Nintendo Switch"
            // from matching "Nintendo Switch 2" because "2" is from the game title.
            // Qualifier example: "s25 ultra", "pro max" — prevents "Galaxy S25 FE ultra sauber"
            // (German "extremely clean") from matching "Galaxy S25 Ultra" query.
            val sp = " $titleNormStripped "
            val tokens = parsed.positiveTokens
            for (i in 0 until tokens.size - 1) {
                val a = tokens[i]
                val b = tokens[i + 1]
                // Only apply when 'a' is a proper word (length > 2) — skip short codes like "m4"
                // that can appear in any order relative to their following size specifier.
                val isNumericBigram = b.length <= 2 && b.all { c -> c.isDigit() } && a.length > 2 && a.any { c -> c.isLetter() }
                // Model variant qualifiers must also be adjacent to their preceding token.
                // Also applies when 'a' is a 2-char alphanumeric model code (e.g. "z6 iii", "r5 ii") —
                // these are specific enough that the qualifier must be adjacent.
                val isAlphanumericCode = a.length == 2 && a.any { c -> c.isLetter() } && a.any { c -> c.isDigit() }
                val isQualifierBigram = b in MODEL_QUALIFIER_SUFFIXES && (a.length > 2 || isAlphanumericCode)
                if (isNumericBigram || isQualifierBigram) {
                    if (!sp.contains(" $a $b ") && !sp.endsWith(" $a $b")) {
                        return 0.0
                    }
                }
            }
        }

        val tokenCount: Int
        val matchedCount: Int

        if (parsed.orGroups.isNotEmpty()) {
            val bestGroup = parsed.orGroups.maxByOrNull { group ->
                if (group.isEmpty()) 0.0
                else group.count { token -> tokenMatches(token) }.toDouble() / group.size
            } ?: return 0.0
            tokenCount = bestGroup.size
            matchedCount = bestGroup.count { token -> tokenMatches(token) }
        } else {
            tokenCount = parsed.positiveTokens.size
            matchedCount = parsed.positiveTokens.count { token -> tokenMatches(token) }
        }

        if (tokenCount == 0) return 0.5
        return matchedCount.toDouble() / tokenCount
    }

    // A parsed price above this (in the listing's own currency's minor units) is a scrape
    // artifact — digits from several DOM nodes concatenated into one number — not a real
    // listing. 2e11 minor units clears the priciest genuine listing in every currency we
    // crawl (weak-currency car prices top out ~1e9) while catching the ~1e15 garbage that
    // was blowing up the price summary.
    private const val MAX_PLAUSIBLE_MINOR_UNITS = 200_000_000_000L

    private fun hasSanePrice(listing: Listing): Boolean =
        listing.effectivePrice.amount in 0..MAX_PLAUSIBLE_MINOR_UNITS

    // An offer to hire the item out, not to sell it. Its daily rate ("Parkettschleifmaschine
    // Mieten, 1 EUR") is not a purchase price, so it wrecks the cheapest/median figures. Dropped
    // unless the query itself asks to rent, in which case the user wants exactly these.
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]""")

    private val rentalOffer = Regex(
        """\b(mieten|vermieten|zu\s+vermieten|vermietung|miete|mietpreis|leihen|verleih|""" +
            """ausleihen|leihgeb(ü|ue)hr|te\s+huur|noleggio|a\s+noleggio|alquiler|""" +
            """for\s+(hire|rent)|rental)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun isRentalOffer(listing: Listing, queryText: String): Boolean {
        if (rentalOffer.containsMatchIn(queryText)) return false
        return rentalOffer.containsMatchIn(listing.title)
    }

    // "<something> für <the thing searched for>" names an accessory made FOR the product, not the
    // product: a dust bag for a floor sander, a case for a phone. The giveaway is positional — the
    // searched-for words sit only AFTER the preposition, while the head noun before it is something
    // else entirely. A genuine listing puts the product itself in the head ("Lägler Hummel
    // Parkettschleifmaschine für Profis"), so it keeps its query tokens before the preposition.
    private val accessoryPreposition = Regex(
        """\b(passend\s+für|geeignet\s+für|kompatibel\s+(mit|für)|für|fuer|compatible\s+with|""" +
            """suitable\s+for|for|voor|per|pour|para)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun isAccessoryFor(listing: Listing, parsed: ParsedQuery, queryText: String): Boolean {
        val tokens = parsed.positiveTokens
        if (tokens.isEmpty()) return false
        val match = accessoryPreposition.find(listing.title) ?: return false
        val head = listing.title.substring(0, match.range.first).lowercase()
        val tail = listing.title.substring(match.range.last + 1).lowercase()
        // Compared with separators stripped, since query tokens are normalised the same way — a
        // title's "MFC-L2750DW" must still match the token "mfcl2750dw".
        fun holds(text: String, token: String) =
            text.contains(token) || text.replace(NON_ALNUM, "").contains(token.replace(NON_ALNUM, ""))
        // Only fires when the head names none of the query and the tail names it — otherwise the
        // product itself leads the title and the phrase is a normal qualifier.
        val headHasQuery = tokens.any { holds(head, it.lowercase()) }
        val tailHasQuery = tokens.any { holds(tail, it.lowercase()) }
        if (headHasQuery || !tailHasQuery) return false
        // The head is the accessory's own noun. If the query already asks for that noun, keep it.
        val q = queryText.lowercase()
        val headWords = head.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
        return headWords.none { q.contains(it) }
    }

    // Consumables and spares sold FOR a machine, named without a "für" — a sanding search returns
    // sandpaper, sanding belts, dust bags and filters far cheaper than any machine, which then poses
    // as the "best price". Dropped only when the query itself does not ask for the consumable.
    private val consumableNoun = Regex(
        """\b(schleifpapier|schleifb[aä]nder?|schleifscheiben?|schleifrollen?|schleifgitter|""" +
            """schleifmittel|staubbeutel|staubfangsack|staubsack|filterbeutel|filtersack|""" +
            """ersatzbeutel|papiers[aä]cke?|zubeh(ö|oe)r|ersatzteile?|verschlei(ß|ss)teile?|""" +
            // Cross-border sanding consumables: ES lija / banda de revestimiento, IT carta·nastro
            // abrasiv*, FR bande abrasive / papier de verre, NL schuurpapier / schuurband.
            """papel\s+de\s+lija|banda\s+de\s+revestimiento|bandas?\s+abrasivas?|""" +
            """carta\s+abrasiva|nastr[oi]\s+abrasiv[oi]|disc[oh]i?\s+abrasiv[oi]|""" +
            """bande\s+abrasive|papier\s+de\s+verre|schuurpapier|schuurband)\b""",
        RegexOption.IGNORE_CASE,
    )

    // An abrasive grit code ("P240", "P100 grain") is a consumable's spec, never a machine's — a
    // language-agnostic tell that catches a sanding belt/sheet whatever tongue names it.
    private val abrasiveGrit = Regex("""\bP(?:40|60|80|100|120|150|180|220|240|320|400)\b""")

    private fun isConsumableFor(listing: Listing, queryText: String): Boolean {
        // The query is really after the consumable itself ("schleifpapier ...") — keep those.
        if (consumableNoun.containsMatchIn(queryText) || abrasiveGrit.containsMatchIn(queryText)) return false
        return consumableNoun.containsMatchIn(listing.title) || abrasiveGrit.containsMatchIn(listing.title)
    }

    fun filter(listings: List<Listing>, query: SearchQuery): List<Listing> {
        val parsed = parseQuery(query.text)
        val listings = listings
            .filter(::hasSanePrice)
            .filterNot { isRentalOffer(it, query.text) }
            .filterNot { isAccessoryFor(it, parsed, query.text) }
            .filterNot { isConsumableFor(it, query.text) }
        val tokenCount = parsed.positiveTokens.size + parsed.orGroups.size
        // Single-token queries ("laptop", "monitor"): the platform's own search already filtered
        // results. A product called "Lenovo ThinkPad X1" IS a laptop even without the word —
        // applying lexical filtering would drop 95%+ of results. Skip filtering entirely.
        if (tokenCount <= 1) {
            // Trusting the platform's own search breaks down for a long compound term: reBuy answers
            // "parkettschleifmaschine" with novels that merely end in "-maschine". A specific compound
            // must therefore still share its leading stem ("parkett") with the listing. Short generic
            // category words ("laptop", "monitor") stay exempt — a ThinkPad X1 IS a laptop without
            // saying so, and stem-matching them would drop nearly everything.
            val token = parsed.positiveTokens.firstOrNull()?.lowercase()
            val stem = token?.replace(NON_ALNUM, "")?.takeIf { it.length >= 10 }?.take(7)
            return listings.mapNotNull { listing ->
                val s = score(listing, parsed)
                if (s < 0) return@mapNotNull null
                // Compared with separators stripped from both sides, so a normalised token
                // ("mfcl2750dw") still matches the title's punctuated form ("MFC-L2750DW").
                if (stem != null &&
                    !"${listing.title} ${listing.description ?: ""}".lowercase()
                        .replace(NON_ALNUM, "").contains(stem)
                ) return@mapNotNull null
                listing to s
            }.sortedByDescending { it.second }.map { it.first }
        }
        return listings.mapNotNull { listing ->
            val s = score(listing, parsed)
            if (s < 0) return@mapNotNull null
            if (s < 0.76) return@mapNotNull null
            listing to s
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Detects a result set the platform returned without applying the query: many listings,
     * almost none containing any query token. Happens when a site silently ignores an
     * unsupported search parameter and serves its default feed (e.g. AutoScout24 `?query=`).
     * Returns a human-readable report, or null when the results look genuine.
     * Single-token queries are exempt: platform-side search may match beyond the title
     * (a ThinkPad X1 is a "laptop" without the word in its title).
     */
    fun irrelevanceReport(listings: List<Listing>, query: SearchQuery): String? {
        val parsed = parseQuery(query.text)
        val tokenCount = parsed.positiveTokens.size + parsed.orGroups.size
        if (tokenCount < 2 || listings.size < 5) return null
        val matching = listings.count { score(it, parsed) > 0.0 }
        if (matching.toDouble() / listings.size >= 0.2) return null
        val sample = listings.take(5).joinToString("; ") { it.title.take(60) }
        return "${listings.size} results, only $matching contain query tokens — search likely ignored (sample: $sample)"
    }

    // Model variant qualifier tokens that must appear adjacent to their preceding query token.
    // "Samsung Galaxy S25 FE ultra sauber" must NOT match "Samsung Galaxy S25 Ultra" query.
    // "ii"/"iii" = Roman numeral version markers (e.g. "Z6 III", "Mark II") — prevents "Nikon Z6 II"
    // from matching "Nikon Z6 III" query because "III" appears in the Tamron 28-75 f/2.8 III lens name.
    private val MODEL_QUALIFIER_SUFFIXES = setOf("ultra", "max", "plus", "mini", "pro", "lite", "ii", "iii")

    // All pattern lists use pre-normalized strings (lowercase, umlauts replaced, hyphens as spaces)
    private fun normalize(text: String): String {
        // Convert decomposed Unicode (NFD, e.g. "a" + combining diaeresis) to composed NFC
        // so that umlaut replacements like "ä"→"ae" work on titles from all crawlers.
        val nfc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        return nfc
            .replace(".", " ")  // Strip period (e.g. "Hüllen." → "Huellen", "z.B." → "z B")
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            // French/Spanish accents (Vinted DE, Marktplaats, etc.)
            .replace("é", "e").replace("è", "e").replace("ê", "e").replace("ë", "e")
            .replace("à", "a").replace("â", "a").replace("á", "a")
            .replace("ç", "c")
            .replace("ô", "o").replace("ó", "o")
            .replace("ù", "u").replace("û", "u").replace("ú", "u")
            .replace("î", "i").replace("ï", "i").replace("í", "i")
            // French storage notation: "256 Go" → "256 gb" (so French listings match "256gb" query token)
            .replace(Regex("(\\d+)\\s*go\\b", RegexOption.IGNORE_CASE), "$1 gb")
            // "+" after a digit = "plus" model suffix (e.g. S24+, Galaxy+, 6+)
            // Only after digits to avoid "Wie Neu+" → "neuplus"
            .replace(Regex("(\\d)\\+"), "$1 plus")
            // Strip remaining "+" as space (e.g. "Pro+" → "Pro", "+sub+era" → "sub era")
            .replace("+", " ")
            // Model variant suffixes directly attached to numbers (e.g. "S24FE" → "S24 FE", "6pro" → "6 pro")
            // Separates so tokens can word-match them
            .replace(Regex("(\\d)(fe)\\b", RegexOption.IGNORE_CASE), "$1 $2")
            .replace(Regex("(\\d)(ti)\\b", RegexOption.IGNORE_CASE), "$1 $2")
            .replace(Regex("(\\d)(pro)\\b", RegexOption.IGNORE_CASE), "$1 $2")
            .replace(Regex("(\\d)(a)\\b", RegexOption.IGNORE_CASE), "$1 $2")
            // Strip "12/512GB" and "12 / 512 GB" style RAM/storage combos before slash expansion
            .replace(Regex("\\d+\\s*/\\s*\\d+\\s*(?:gb|tb|mb)", RegexOption.IGNORE_CASE), "")
            .replace("-", " ").replace("_", " ").replace("/", " ").replace("|", " ").replace("*", " ")
            .replace("!", "").replace("?", "").replace("[", "").replace("]", "")
            .replace("(", "").replace(")", "")
            // Convert inch symbols after digits to "zoll" so decimal screen sizes (7,9" / 10,9'')
            // are handled by the titleNormStripped screen-size strip (e.g. "7 9 zoll" → stripped).
            .replace(Regex("(\\d)\""), "$1 zoll")   // 7,9" → 7,9 zoll
            .replace(Regex("(\\d)''"), "$1 zoll")  // 7,9'' → 7,9 zoll
            .replace("\"", "").replace(",", " ").replace("'", " ").replace("`", "")
            // Strip any remaining non-letter/non-digit characters (emojis, decorative symbols like ❗✅⚡)
            // These can prevent KILL_IF_STARTS from matching (e.g. "❗SUCHE❗" escapes "suche " pattern)
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private val PLACEHOLDER_TITLES = setOf(
        "neues angebot", "new listing", "nieuw", "nouveau",
    )

    private fun isPlaceholderTitle(titleNorm: String, words: List<String>): Boolean {
        if (PLACEHOLDER_TITLES.any { titleNorm == normalize(it) }) return true
        if (words.count { it.length > 1 } < 2) return true
        return false
    }
}

