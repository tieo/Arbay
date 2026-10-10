package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.BlockedDealer
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.blocking
import io.github.tieo.arbay.repo.UserStateStore

object RelevanceFilter {

    // Unit suffixes that can be glued to a number: "256gb", "512tb", "16mp"
    private val unitSuffixes = setOf("gb", "tb", "mb", "mp", "mhz", "ghz", "mah", "wh", "mm", "cm", "kg", "zoll", "inch")

    data class ParsedQuery(
        val positiveTokens: List<String>,
        val negativeTokens: List<String>,
        val orGroups: List<List<String>>,
        /** Whether the search as typed names a unit, so sizes in a title are part of what it asks
         *  for. Read once off every word typed: the words a listing is held to are narrowed to
         *  those its market writes, and a unit typed apart ("256 GB") is rarely written apart. */
        val asksForASize: Boolean = false,
    )

    /** Built from the query's own structured fields — [SearchQuery.excludeKeywords] for what must
     *  not appear, [SearchQuery.aliases] for alternate phrasings that count as the same search.
     *  Neither is read out of the query text: a person's search box, and a catalog entry's own
     *  canonical phrase, both stay exactly the one thing they name. */
    fun parseQuery(query: SearchQuery): ParsedQuery {
        val orGroups = if (query.aliases.isNotEmpty()) {
            (listOf(query.text) + query.aliases).map { tokenize(it) }
        } else {
            emptyList()
        }
        val positiveTokens = tokenize(query.text)
        return ParsedQuery(
            positiveTokens = positiveTokens,
            negativeTokens = query.excludeKeywords.map { normalizeToken(it) },
            orGroups = orGroups,
            asksForASize = (positiveTokens + orGroups.flatten()).any { namesAUnit(it) },
        )
    }

    /** A unit on its own ("GB"), or glued to a number or a kit's quantity×size ("32GB", "1x32GB"). */
    private fun namesAUnit(token: String): Boolean =
        token in unitSuffixes || unitSuffixes.any { u ->
            token.endsWith(u) && token.dropLast(u.length).let { pre -> pre.isNotEmpty() && pre.all { c -> c.isDigit() || c == 'x' } }
        }

    private fun tokenize(phrase: String): List<String> =
        phrase.split(" ").filter { it.isNotBlank() }.map { normalizeToken(it) }

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

    /**
     * The word the reader blocked that this listing carries, if any.
     *
     * Separate from the score so the app can say which word took a listing away. Folded into the
     * score's one "reject" value, a blocked word was reported as "not one offer", which reads as
     * the market having sent a placeholder or a bulk lot — twelve genuine vans sat under that
     * heading because the reader had blocked "Pritsche".
     */
    fun blockedWord(listing: Listing, parsed: ParsedQuery): String? {
        if (parsed.negativeTokens.isEmpty()) return null
        val words = canonicalizeMakes(normalize(gluedThousands(listing.title.lowercase())))
            .split(" ").filter { it.isNotBlank() }
        return parsed.negativeTokens.firstOrNull { token -> matchesNegative(words, token) }
    }

    /** A thousands group written German-style is one number: "30.000 km" is thirty thousand, not
     *  a 30 next to a 000. Normalizing punctuation away split it in two, and a reader who blocked
     *  the Crafter 30 lost every van whose title stated a mileage or a price beginning with 30. */
    internal fun gluedThousands(text: String): String {
        var out = text
        // A dot or a typographic space, never a plain one: "30.000" and "30 000" (narrow no-break)
        // are one number, while "Fold 6 512GB" is a model and a size standing next to each other.
        repeat(3) { out = Regex("""(\d)[.\u00a0\u202f](\d{3})(?!\d)""").replace(out, "$1$2") }
        return out
    }

    /** Short tokens ("s", "x") match as whole words only, so a blocked "-s" does not take every
     *  "Series". Longer ones also match German plurals and compounds. */
    private fun matchesNegative(words: List<String>, token: String): Boolean {
        // A number is blocked as the whole number it is: "50" is the Crafter 50, and a title
        // reading "50 mm" or "1950" is not that van.
        if (token.all { it.isDigit() }) return words.any { it == token }
        if (token.length <= 2) return words.any { it == token }
        return words.any { word ->
            word == token || word.endsWith(token) ||
                word.endsWith(token + "n") || word.endsWith(token + "en") ||
                (token.length >= 4 && word.contains(token))
        }
    }

    /** [implied] are words of the search the market's own answer shows this listing to be, though
     *  its title leaves them out (see [impliedWords]). */
    fun score(listing: Listing, parsed: ParsedQuery, implied: Set<String> = emptySet()): Double {
        // Each alias is the whole search phrased another way, and is held to everything the search
        // typed alone is held to: its size, its model numbers, its "Pro XL" written together.
        // Counted only as a share of words, "Pixel 9 Pro XL 256GB" let a 128GB phone, a 9 Pro and
        // a 9 Pro Fold through on four words of five.
        val phrasings = parsed.orGroups.filter { it.isNotEmpty() }
        if (phrasings.isNotEmpty()) {
            return phrasings.maxOf { score(listing, parsed.copy(positiveTokens = it, orGroups = emptyList()), implied) }
        }
        // Thousands groups are glued back together before anything else reads the title, so a
        // mileage or a price is one number here as it is on the page. Split into "30" and "000",
        // it both matched a blocked "30" and offered "000" as a model number to match against.
        val titleNorm = normalize(gluedThousands(listing.title.lowercase()))
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
        // Strip context numbers that must NOT match numeric model tokens — BUT only strip storage
        // values if the query isn't itself asking for a size. Asking for one means mentioning the
        // unit at all: as its own word ("32 GB"), glued to the number ("32GB"), or glued to a
        // kit's quantity×size ("1x32GB"). A fixed list of "plausible" sizes was tried here before
        // and got it wrong both ways — it missed "1x32" (not a bare number) and would reject a
        // real, non-power-of-two drive size ("500GB", "480GB") that a query is free to ask for.
        // Read off the search as typed, not off the words this listing is held to: those are narrowed
        // to what the market writes, and narrowed past "GB" the "256GB" a phone was sold as got
        // stripped before "256" could match it, which threw every such phone off a 256 GB search.
        val queryHasStorageToken = parsed.asksForASize || (parsed.positiveTokens + parsed.orGroups.flatten()).any { namesAUnit(it) }
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

        // Reject bulk lots: "15x", "x15", "15 Stück". A count of one is not a lot, and a number
        // after the x is a size where a unit follows it — "Crucial CT32G4SFD832A, 32 GB, 1 x 32 GB"
        // is one module, and reading it as a lot of 32 threw the exact product off the search.
        // A count sits before the x ("20x", "4 x") or stands as its own word after it ("x 20").
        // Glued to what follows, the x belongs to a model name — Biwin X570, Emtec X200 — and
        // reading those as lots of 570 and 200 threw two real drives off a search for one.
        // Glued, only two digits are a count: "20x" is twenty of them, while "SN850X" and a Ryzen
        // "5800X" are the names of one, and reading them as lots threw a single 4TB drive away.
        // A count followed by a length is a measurement: "M.2 22 x 80 mm" is the card's size.
        // A spaced count is at most three digits: "Ryzen 9 7950 X" is a processor. And what the search
        // itself names is never a count: "Surface Pro X 13" and a "ThinkBook 14x" are the machines.
        val searched = (parsed.positiveTokens + parsed.orGroups.flatten()).toSet()
        val countBefore = Regex("""(?:^|\s)(?!1\s*x)(\d{2}x|\d{2,3}\s+x)(?:\s|$)(?!\s*\d+\s*(mm|cm)\b)""")
            .findAll(titleNorm).any { it.groupValues[1].replace(" ", "") !in searched }
        val countAfter = "x" !in searched &&
            Regex("""(?:^|\s)x\s+\d{2,}(?:\s|$)(?!\s*(gb|tb|mb|mhz|mm|cm))""").containsMatchIn(titleNorm)
        if (countBefore || countAfter) return -1.0

        // Word-start positions in titleCompact (for guarding compact matches)
        val wordStartsInCompact: Set<Int> = buildSet {
            var pos = 0
            for (word in titleNormForMatching.split(" ")) {
                if (word.isNotEmpty()) add(pos)
                pos += word.length
            }
        }

        fun tokenMatches(token: String): Boolean {
            if (token in implied) return true
            // A size is a number of bytes, written however the seller writes it: a 2TB drive is
            // sold as "2048GB" and "2000GB" as often as "2TB", and only the spelling differs.
            if (isASize(token)) sizeInGigabytes(token)?.let { asked ->
                if (sizesIn(listing.title).any { sameSize(it, asked) }) return true
            }
            // A word the title says in other words: every PCIe M.2 drive is an NVMe drive, and
            // geizhals, eBay and Ricardo name the bus instead ("M.2 2280 / M-Key / PCIe 4.0 x4").
            WRITTEN_AS[token]?.let { names ->
                if (names.any { name -> " $titleNormForMatching ".contains(" $name ") }) return true
            }
            // A make glued to the model is one word on some markets: TruckScout24 writes
            // "VWCrafter", and every real Crafter there read as carrying neither word of
            // "Volkswagen Crafter" — the model is not at a word start, and the make is not the
            // whole word. A word that is a make followed by this token is both of them.
            if (token.length >= 4 && titleWords.any { word ->
                    word.length > token.length && word.endsWith(token) &&
                        CarQueryResolver.makeSpellings(word.dropLast(token.length)) != null
                }
            ) return true
            // The make itself, glued to the front of the same word, in any of its spellings —
            // "VWCrafter" carries "Volkswagen" as surely as "VW Crafter" does.
            CarQueryResolver.makeSpellings(token)?.let { spellings ->
                if (titleWords.any { word ->
                        spellings.any { spelling ->
                            word.length > spelling.length + 2 && word.startsWith(spelling) &&
                                word.drop(spelling.length).all { c -> c.isLetter() || c.isDigit() }
                        }
                    }
                ) return true
            }
            // A bare unit word ("GB", "MHz", "Zoll"...) is never its own word in a real listing
            // title — every seller glues it to the number ("32GB"). A query typed with a space
            // before the unit ("32 GB", "1x32 GB") must still match those titles.
            if (token in unitSuffixes && titleWords.any { Regex("""^\d+${Regex.escape(token)}$""").matches(it) }) {
                return true
            }
            // ≤2 chars: whole-word only, or number+unit (e.g. "6" matches "6" but not "16")
            if (token.length <= 2) return titleWords.any { it == token }
            // 3-5 chars: whole-word, or numeric token matching word that starts with it + unit suffix
            // (e.g. "256" matches "256gb", "512" matches "512tb")
            if (token.length <= 5) {
                if (titleWords.any { it == token }) return true
                // A plain size ("256") or a kit's quantity×size ("1x32", "2x16") both glue directly
                // to a unit suffix in real listing titles ("256gb", "1x32gb"), never with the space a
                // query typed between them ("1x32 GB" searching for a title that never wrote "1x32 "
                // as its own word never matched anything, on any market, and looked identical to
                // nothing existing).
                if (token.all { it.isDigit() } || Regex("""^\d+x\d+$""").matches(token)) {
                    if (titleWords.any { word -> word.startsWith(token) && unitSuffixes.any { word == token + it } }) return true
                    // A van is sold as "Sprinter 314CDI" and a search asks for the 314: the trim
                    // code glues onto the model number, and only letters may follow it, so 314 does
                    // not reach 3140.
                    if (titleWords.any { word ->
                            word.length > token.length && word.startsWith(token) &&
                                word.drop(token.length).all { c -> c.isLetter() }
                        }
                    ) return true
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
            // A German compound is written apart across a list — "Parkett-, Bodenschleifmaschine"
            // is a Parkettschleifmaschine — and what it is about is its leading part. That part
            // stands for the whole word, and only for that word: the rest of the search still has
            // to be found, so a Tiguan does not pass a search for a Crafter on "Volkswagen" alone.
            if (token.length >= COMPOUND_LENGTH && token.all { it.isLetter() } &&
                titleCompact.contains(token.take(STEM_LENGTH))
            ) return true
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

        for (neg in parsed.negativeTokens) {
            if (matchesNegative(titleWords, neg)) return -1.0
        }

        // Short model-code tokens MUST match as exact whole words:
        // - Pure numeric (1-2 digit): "6", "12", "13" — without this, "Galaxy Z Fold 6" would match
        //   "Fold3/Fold5" at 80% because "fold" matches "fold3" and all other tokens also match.
        // - Mixed alphanumeric (length ≤ 3 with at least one digit): "r5", "a7", "m4", "s25"
        //   These are specific model codes — "R5" must not match against "5D" variants. Without
        //   this check, "Canon EOS R5 Mark II" scores 4/5=80% against "Canon EOS 5D Mark II".
        // A size is required the same way: a 128GB phone is not the 256GB one on the strength of
        // every other word.
        val modelCodesRequired = parsed.positiveTokens.filter { t ->
            (t.length <= 3 && t.any { c -> c.isDigit() }) || isASize(t)
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
            val isQualifierBigram = b in MODEL_QUALIFIER_SUFFIXES && (a.length > 2 || isAlphanumericCode) ||
                // "XL" after a qualifier is part of the model's name: a Pixel 9 Pro and a 9 Pro Fold
                // are other phones than the 9 Pro XL. After anything else it is a clothing size,
                // written wherever the seller puts it ("Jacke Gr. XL").
                b == "xl" && a in MODEL_QUALIFIER_SUFFIXES
            if (isNumericBigram || isQualifierBigram) {
                if (!sp.contains(" $a $b ") && !sp.endsWith(" $a $b")) {
                    return 0.0
                }
            }
        }

        val tokenCount = parsed.positiveTokens.size
        val matchedCount = parsed.positiveTokens.count { token -> tokenMatches(token) }

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

    /** How far past "für" the thing itself has to appear for the phrase to be about it. */
    private const val WORDS_AFTER_FOR = 4

    /** A product sold as a stand-in for another, which is the same kind of thing as what was
     *  searched for rather than something made for it. */
    private val substituteFor = Regex(
        """\b(replacement|ersatz(modul|speicher)?|equivalent[ea]?|equivalente|sostituzione|""" +
            """remplacement|reemplazo|sustituto|ricambio|vervanging|compatible\s+replacement|""" +
            """alternativ(e|es)?)\b""",
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
        fun holds(text: String, token: String): Boolean {
            if (text.contains(token)) return true
            val compact = text.replace(NON_ALNUM, "")
            val tokenCompact = token.replace(NON_ALNUM, "")
            if (compact.contains(tokenCompact)) return true
            // A German compound names the same machine several ways: an ad for a capacitor says
            // "für Parkettschleifer" where the search says "parkettschleifmaschine", and requiring
            // the whole word meant the capacitor read as a machine. The shared stem is what the two
            // have in common, and it is what the phrase after "für" is pointing at.
            return tokenCompact.length >= 10 && compact.contains(tokenCompact.take(8))
        }
        // Only fires when the head names none of the query and the tail names it — otherwise the
        // product itself leads the title and the phrase is a normal qualifier.
        val headHasQuery = tokens.any { holds(head, it.lowercase()) }
        // What the phrase points at decides what it says. "Arbeitsspeicher für Laptop
        // CT32G4SFD832A" names the machines the memory fits and is the memory; "Toner für Brother
        // MFC-L2750DW" names what the toner is for and is not a printer.
        val pointsAtAMachine = tail.trimStart().split(Regex("\\s+")).firstOrNull()
            ?.let { hostDevice.matches(it.trim(',', '.', ':', ';')) } ?: false
        if (pointsAtAMachine) return false
        val pointedAt = tail.trim().split(Regex("\\s+")).take(WORDS_AFTER_FOR).joinToString(" ")
        val tailHasQuery = tokens.any { holds(pointedAt, it.lowercase()) }
        if (headHasQuery || !tailHasQuery) return false
        // A substitute names the thing it replaces, and is the same kind of thing: "32GB DDR4
        // SODIMM (Replacement for Crucial CT32G4SFD832A)" is a module, not a module's accessory.
        if (substituteFor.containsMatchIn(listing.title)) return false
        // The head is the accessory's own noun. If the query already asks for that noun, keep it.
        val q = queryText.lowercase()
        val headWords = head.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }
        return headWords.none { q.contains(it) }
    }

    /** Sizes as a listing writes them: a number glued or spaced to a storage unit, French "Go"
     *  and "To" included. A speed is not a size: "7.300 MB/s Lesen" is how fast the drive is, and
     *  counting it as a second size made every drive that advertises one look like a row of variants. */
    private val sizeInTitle =
        Regex("""\b(\d{1,4}(?:[.,]\d)?)\s?(gigabytes?|terabytes?|gb|tb|mb|go|to)\b(?!\s*/\s*s)""", RegexOption.IGNORE_CASE)

    /** Every size the title states, in gigabytes. A thousands group is one number first: "2.000 GB"
     *  is two terabytes, and read as "2." and "000 GB" it offered a drive of nothing beside it. */
    private fun sizesIn(title: String): Set<Double> {
        val text = gluedThousands(title)
        val stated = sizeInTitle.findAll(text).mapNotNull { sizeInGigabytes(it.groupValues[1] + it.groupValues[2]) }
        // A range names its lower end without the unit: "1 - 4 TB" sells 1TB to 4TB, priced at 1TB.
        val rangeStarts = sizeRange.findAll(text).mapNotNull { sizeInGigabytes(it.groupValues[1] + it.groupValues[2]) }
        // "128/256GB" offers two tiers of storage. Below 32 the first number is working memory
        // written beside the storage ("12/256GB"), and the listing is one device.
        val tierStarts = storageTiers.findAll(text).mapNotNull { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it >= 32 }?.let { sizeInGigabytes("${it}gb") }
        } + spacedTiers.findAll(text).mapNotNull { m ->
            // "256 512GB" with only a space between: a number that is itself a storage tier, and
            // smaller than the one after it. A model number ("RTX 3070 512GB") is neither.
            val first = m.groupValues[1].toInt()
            val second = m.groupValues[2].toInt()
            first.takeIf { it in STORAGE_TIERS && second in STORAGE_TIERS && it < second }?.let { sizeInGigabytes("${it}gb") }
        }
        return (stated + rangeStarts + tierStarts).toSet()
    }

    private val spacedTiers = Regex("""\b(\d{2,4})\s+(\d{2,4})\s?gb\b""", RegexOption.IGNORE_CASE)

    private val STORAGE_TIERS = setOf(32, 64, 128, 256, 512, 1024)

    private val storageTiers = Regex("""\b(\d{2,4})\s*/\s*\d{2,4}\s?gb\b""", RegexOption.IGNORE_CASE)

    private val sizeRange = Regex("""\b(\d{1,4})\s*[-–]\s*\d{1,4}\s?(gb|tb)\b""", RegexOption.IGNORE_CASE)

    /** Whether two sizes are the one size, decimal or binary: 2TB is sold as 2000GB and as 2048GB. */
    private fun sameSize(a: Double, b: Double): Boolean =
        a == b || a == b / 1000 * 1024 || b == a / 1000 * 1024

    /**
     * Whether the listing offers a row of sizes and is priced at the smallest of them.
     *
     * One eBay listing sells the same drive in 120GB, 240GB, 500GB, 1TB and 2TB, and the price on
     * the card is the 120GB one. Measured over a live search for a 2TB drive: 31 of 188 results
     * were shaped like this, at a median of 78 euro against 220 for the rest, so they take every
     * cheapest place in the list and none of them is an offer of what was asked for.
     *
     * Only when the search names a size itself — a search for a drive by model has no size to be
     * misled about.
     */
    private fun isOneOfSeveralSizes(listing: Listing, parsed: ParsedQuery): Boolean {
        val asked = askedSizes(parsed).ifEmpty { return false }
        // A phone's working memory is not a size it is sold in: "256 GB – 16 GB RAM" is one phone.
        val offered = sizesIn(listing.title.replace(memorySize, " "))
        // A drive that states its size twice ("2TB (2000GB)", "2TB (2048GB)") states one size. Only
        // a listing that also offers something smaller than what was asked for is priced at a size
        // nobody asked for, which is what makes its place among the cheapest wrong.
        // A size fifty times smaller is not one on sale beside it: "7300MB" is a speed with its
        // "/s" left off, and "Nur 5GB Geschrieben" how much the drive has written.
        val smaller = offered.filter { it < asked.min() && !sameSize(it, asked.min()) && it >= asked.min() / 50 }
        if (offered.size < 2 || smaller.isEmpty()) return false
        // Unless the smaller sizes are what the asked-for size is made of: "M.2 SSD 2TB (2x 1TB)"
        // is two terabytes, sold as two sticks, at a price for the pair.
        return !addsUpToTheAskedSize(listing.title, asked.min())
    }

    /** A size stated as working memory: "16 GB RAM", "32GB DDR5", or joined to the storage with a plus, "16GB+256GB"
     *  and "256GB + 16GB". What the device runs on, beside what it stores. A phone's memory tier
     *  written straight beside its storage is the same: "256GB-16GB", "16GB 256GB", "512GB 16GB". */
    private val memorySize = Regex(
        """\b\d{1,3}\s?gb\s*(ram|arbeitsspeicher|(lp)?ddr\d\w*)\b|\b\d{1,2}\s?gb\s*\+(?=\s*\d)|(?<=gb)\s*\+\s*\d{1,2}\s?gb\b|""" +
            """(?<=\b(64|128|256|512)\s?gb|\b1\s?tb)\s*[-|,]?\s*\b(6|8|12|16|24)\s?gb\b|""" +
            """\b(6|8|12|16|24)\s?gb\s*[-|,]?\s*(?=(64|128|256|512)\s?gb\b|1\s?tb\b)""",
        RegexOption.IGNORE_CASE,
    )

    /** The sizes the search names, read off each phrasing as typed: "256 GB" with a space is as
     *  much a size as "256GB", and read word by word it was neither. */
    private fun askedSizes(parsed: ParsedQuery): List<Double> =
        (listOf(parsed.positiveTokens) + parsed.orGroups).flatMap { sizesIn(it.joinToString(" ")) }

    /** "2x 1TB", "4 x 512GB": a count and a size whose product is the size asked for, which is
     *  that size sold in pieces rather than a smaller thing at a smaller price. */
    private fun addsUpToTheAskedSize(title: String, askedGb: Double): Boolean =
        Regex("""(\d{1,2})\s*x\s*(\d{1,4}(?:[.,]\d)?\s?(?:gb|tb|mb))""", RegexOption.IGNORE_CASE)
            .findAll(title)
            .any { m ->
                val count = m.groupValues[1].toIntOrNull() ?: return@any false
                val each = sizeInGigabytes(m.groupValues[2].replace(" ", "")) ?: return@any false
                count * each == askedGb
            }

    /** A size in gigabytes, from the way a listing writes one, or null when the word is not a
     *  size at all. Compared as numbers so "2TB" and "2000GB" are the one size they are. */
    private fun sizeInGigabytes(token: String): Double? {
        val m = Regex("""^(\d{1,4}(?:[.,]\d)?)\s?(gigabytes?|terabytes?|gb|tb|mb|go|to)$""", RegexOption.IGNORE_CASE)
            .find(token.trim()) ?: return null
        val value = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "tb", "to", "terabyte", "terabytes" -> value * 1000
            "mb" -> value / 1000
            else -> value
        }
    }

    // A device that a searched-for part is built into, named as the thing on offer. A search for a
    // 2TB M.2 SSD comes back with gaming PCs and MacBooks that have one inside, which are the same
    // words and a different product — and, at ten to a hundred times the price, the ones that wreck
    // what a search says the thing costs.
    private val hostDevice = Regex(
        """\b(gaming[\s-]?pc|gamer[\s-]?pc|komplett[\s-]?pc|desktop|tower|workstation|server|""" +
            """notebook|laptop|macbook|imac|mac\s?mini|thinkpad|elitebook|probook|latitude|""" +
            """zbook|precision|optiplex|thinkcentre|thinkstation|ideapad|vivobook|zenbook|""" +
            """surface\s?(pro|laptop)|chromebook|mac\s?studio|steam\s?deck|rog\s?ally|""" +
            """nuc|mini[\s-]?pc|all[\s-]?in[\s-]?one|playstation|ps5|xbox|konsole|console|""" +
            // "PC SN730" is the name Western Digital sells a drive under, not a computer.
            """pc(?!\s*sn\d)|""" +
            // The same machines as the markets in other languages name them.
            """port(á|a)til(es)?|ordenador(es)?|portatile|computer|ordinateur|draagbare)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Where a title's own name for what it sells ends and its spec list begins. */
    private val specListStart = Regex("""[,|/:;•·]|\s[-–—]\s|\smit\s|\swith\s|\sinkl\b""")

    /** Where in the title the first word of the search appears, comparing with separators stripped
     *  from both sides so a title's "M.2" is found by the token "m2". Null when none appears. */
    private fun firstTokenPosition(title: String, tokens: List<String>): Int? {
        val positions = ArrayList<Int>(title.length)
        val compact = StringBuilder(title.length)
        title.lowercase().forEachIndexed { index, c ->
            if (c.isLetterOrDigit()) { compact.append(c); positions += index }
        }
        val text = compact.toString()
        return tokens.mapNotNull { token ->
            text.indexOf(token.lowercase().replace(NON_ALNUM, "")).takeIf { it >= 0 }
        }.minOrNull()?.let { positions.getOrNull(it) }
    }

    /**
     * Whether the listing is a device the searched-for thing sits inside.
     *
     * The tell is where the words fall: the title names a machine of its own before its spec list
     * starts, and the words searched for appear only inside that list. A listing for the part
     * itself leads with the part ("Samsung 990 Evo Plus, NVMe M.2 2280"), so its own words are in
     * the head. A search that asks for the machine keeps them, since then the machine is the thing.
     */
    private fun isBuiltIntoADevice(listing: Listing, parsed: ParsedQuery, queryText: String): Boolean {
        if (hostDevice.containsMatchIn(queryText) || phoneOrTablet.containsMatchIn(queryText)) return false
        if (isADeviceOfThatSize(listing, parsed)) return true
        // The rule weighs how much of the search falls either side of the machine's name, so it
        // needs more than one word to weigh. Asked for a part number alone, everything sits on one
        // side by definition, and "Crucial 32GB Notebook DDR4-SODIMM CT32G4SFD832A" — a module for
        // notebooks — read as a notebook.
        val tokens = parsed.positiveTokens.takeIf { it.size >= 2 } ?: return false
        // Where the machine's own name ends: its spec list, or — for the titles written as one run
        // of words, which is most of them on a classifieds site — the first word of the search.
        val boundary = specListStart.find(listing.title)?.range?.first
            ?: firstTokenPosition(listing.title, tokens)
            ?: return false
        val head = listing.title.substring(0, boundary)
        // The machine has to be what the ad leads with. "Crucial 32GB DDR4-3200 SO-DIMM Laptop RAM
        // CT32G4SFD832A" says "laptop" about what the part goes into, five words in, and is the
        // part itself; "Gaming PC: 9850X3D, …" and "NEUER GAMER PC ULTRA 7 …" say it at the front,
        // about themselves.
        // Matched on the whole title, so what follows a name is seen even past the cut: "PC SN740"
        // is a drive, and cut before "SN740" the head read as a PC.
        val naming = hostDevice.findAll(listing.title)
            .firstOrNull { it.range.last < boundary && namesTheMachineItself(listing.title, it) } ?: return false
        if (head.take(naming.range.first).split(Regex("\\s+")).count { it.isNotBlank() } > 2) return false
        val headCompact = head.lowercase().replace(NON_ALNUM, "")
        val tailCompact = listing.title.substring(boundary).lowercase().replace(NON_ALNUM, "")
        val inHead = tokens.count { headCompact.contains(it.lowercase().replace(NON_ALNUM, "")) }
        val inTail = tokens.count { tailCompact.contains(it.lowercase().replace(NON_ALNUM, "")) }
        // More of the search in the spec list than in the name, rather than none in the name: a
        // MacBook Pro M2 Max carries "m2" in its own name, where it is the processor and not the
        // slot, and the drive it holds is listed with everything else it holds.
        return inTail > inHead
    }

    /** "PS5 kompatibel", "PS5 ready": the machine a part is said to fit, not the thing sold. */
    private val fitsMachine = Regex("""^\W*(kompatib\w*|compatib\w*|ready|bereit|geeignet|tauglich)\b""", RegexOption.IGNORE_CASE)

    private fun namesTheMachineItself(title: String, naming: MatchResult): Boolean {
        val after = title.substring(naming.range.last + 1)
        if (fitsMachine.containsMatchIn(after)) return false
        // "Ps5 Wd black 4tb", "PlayStation | WE_Black SN850 NVMe", "steam deck 512gb 2230 nvme": the
        // machine named as what the drive is for, with the drive straight after it. A machine sold
        // with one says so first ("PS5 mit …", "Steam Deck mit 1 TB", "PlayStation 5 + …").
        val next = after.split(Regex("[^\\p{L}\\p{N}._+&]+")).filter { it.isNotBlank() }.take(2)
        return next.none { storageWord.matches(it) } || next.any { comesWithWord.matches(it) }
    }

    private val storageWord = Regex(
        // "M.2" with its dot: "MacBook Pro M2 Max" names the processor.
        """(?i)ssd|nvme|m\.2|22[3468]0|festplatte|wd|we_black|wd_black|western|samsung|seagate|crucial|""" +
            """kingston|lexar|sandisk|corsair|kioxia|hynix|sabrent|teamgroup|adata|xpg|wd-black""",
    )

    private val comesWithWord = Regex("""(?i)mit|inkl\.?|inklusive|incl\.?|with|\+|&|und|and|plus""")

    private val phoneOrTablet = Regex("""\b(smartphone|handy|iphone|ipad|galaxy|tablet)\b""", RegexOption.IGNORE_CASE)

    /**
     * Whether the listing is a machine that holds the size searched for, and nothing else of the
     * search. reBuy answers "1TB NVMe" with a Galaxy S25 Ultra 1TB, an iPhone Air 1TB and an Xbox One
     * X 1 TB: none of them carries "NVMe" or anything written in its place, so on that market the
     * word could not be required, and the size alone let them through. The machine is what the title
     * leads with, in its first three words and ahead of the size; a drive that names what it goes
     * into ("Samsung 980 Pro 1TB SSD für PC") names it after.
     */
    private fun isADeviceOfThatSize(listing: Listing, parsed: ParsedQuery): Boolean {
        val words = parsed.positiveTokens.filterNot { isASize(it) }
        if (words.isEmpty() || words.size == parsed.positiveTokens.size) return false
        val size = sizeInTitle.find(listing.title) ?: return false
        val device = listOfNotNull(hostDevice.find(listing.title), phoneOrTablet.find(listing.title))
            .filter { namesTheMachineItself(listing.title, it) }
            .minByOrNull { it.range.first } ?: return false
        if (device.range.first > size.range.first) return false
        if (listing.title.take(device.range.first).split(Regex("\\s+")).count { it.isNotBlank() } > 2) return false
        return score(listing, parsed.copy(positiveTokens = words, orGroups = emptyList())) == 0.0
    }

    // What a thing is sold with, named as the thing on offer. A search for headphones comes back
    // led by a storage case at 15 euro, a replacement headband at 18 and an aftermarket battery at
    // 20, all of them carrying the model number because that is what they fit.
    private val accessoryNoun = Regex(
        // German builds these as compounds — Aufbewahrungshülle, Hochleistungsakku,
        // Ersatzohrpolster — so the head noun is matched wherever the word ends, not only where it
        // stands alone. The other languages a cross-border search reaches name them plainly.
        """(?U)\w*(h(ü|ue)lle|etui|tasche|akkus?|batterien?|ladeger(ä|ae)t|kabel|netzteil|""" +
            """polster|kopfband|halterung|st(ä|ae)nder|schutzfolie|displayschutz|reparaturset|""" +
            """ersatzteile?|platine|mainboard|geh(ä|ae)use|abdeckung|""" +
            // What a drive is put into rather than the drive: a search for a 2 TB M.2 came back led
            // by six enclosures at €9, each of them carrying "M.2", "SSD" and "2TB" because that is
            // what fits inside it.
            """dockingstation|adapterkarten?|einbaurahmen)\b|""" +
            """\b(ear\s?pads?|headband|pcb|repair\s?kit|housse|custodia|funda|hoes|""" +
            """cover|case|charger|enclosure|caddy|docking\s?station|""" +
            """bo(î|i)tier|carcasa|caja\s+externa|behuizing)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "mit Tasche", "inkl. Ladekabel", "+ Etui", "mit viel Zubehör": what comes with the thing,
     *  rather than instead of it. A word after one of these names an extra, and the ad is still
     *  about the product. */
    private val comesWith = Regex("""(\b(mit|inkl\.?|inklusive|incl\.?|including|with|und|sowie|plus|and)|\+|&)\s*([\p{L}\d]+[\s-]+){0,3}$""", RegexOption.IGNORE_CASE)

    /** A device's battery as its seller reports it: "Akku 85%", "100% Batterie", "Akku Kapazität
     *  88%", "Batterie: %83", "Akku 100 Prozent", "Akku Top", "Neu Akku", "Batterie & Display Neu".
     *  A battery sold on its own has no health to report and is not new in something. */
    private val batteryReport = Regex(
        """\d{2,3}\s?(%|prozent)\s*(akku|batterie|battery)|""" +
            """(akku|batterie|battery)\w*(\s+\p{L}+){0,2}\s*:?\s*(\d{2,3}\s?(%|prozent)|%\s?\d{2,3})|""" +
            """(akku|batterie|battery)\w*\s*(&\s*display\s*)?(neu|top|getauscht|gewechselt|erneuert|ersetzt)\b|""" +
            """\b(neue[rnms]?|neu)\s+(akku|batterie|battery)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether the title's own subject is something sold alongside the thing searched for.
     *
     * Only where the query does not ask for it, and only where nothing marks it as an extra: a
     * listing reading "WH-1000XM5 mit Tasche" is the headphones, and "WH-1000XM5
     * Aufbewahrungshülle" is the bag.
     */
    private fun isAnAccessoryNamedOutright(listing: Listing, parsed: ParsedQuery, queryText: String): Boolean {
        if (accessoryNoun.containsMatchIn(queryText)) return false
        val reports = batteryReport.findAll(listing.title).map { it.range }.toList()
        val match = accessoryNoun.findAll(listing.title)
            .firstOrNull { m -> reports.none { m.range.first in it } } ?: return false
        val before = listing.title.take(match.range.first)
        if (comesWith.containsMatchIn(before)) return false
        return !saidAboutTheDevice(before, parsed)
    }

    /**
     * Whether what a title states ahead of an accessory's noun makes the noun part of a device's
     * description. A case or a charger has no storage and no battery health: where the size searched
     * for or a battery's state comes first, "Pixel 9 Pro XL 256 GB Akku 93%" reports the phone's
     * battery, "256 GB Bundle Neues Mainboard" its repair, and "iPhone 11 Pro Max · 82 % Akku ·
     * Zubehör" what comes with it.
     */
    private fun saidAboutTheDevice(before: String, parsed: ParsedQuery): Boolean {
        if (batteryReport.containsMatchIn(before)) return true
        val asked = askedSizes(parsed)
        return asked.isNotEmpty() && sizesIn(before).any { offered -> asked.any { sameSize(it, offered) } }
    }

    // Consumables and spares sold FOR a machine, named without a "für" — a sanding search returns
    // sandpaper, sanding belts, dust bags and filters far cheaper than any machine, which then poses
    // as the "best price". Dropped only when the query itself does not ask for the consumable.
    private val consumableNoun = Regex(
        // German plurals, because the word boundary after the singular is what let an eight euro
        // pack of "Schleifpapiere" through as a floor sander: an ad names what it is selling in
        // whatever number it has of them.
        """\b(schleifpapiere?|schleifb[aä]nder?|schleifscheiben?|schleifrollen?|schleifgitter|""" +
            """schleifmittel|staubbeutel|staubfangs[aä]cke?|staubs[aä]cke?|filterbeutel|filters[aä]cke?|""" +
            """ersatzbeutel|papiers[aä]cke?|zubeh(ö|oe)r|ersatzteile?|verschlei(ß|ss)teile?|""" +
            // The electrical spares a machine is stripped for. Named as the thing being sold, so
            // they carry the machine's own name and its price is a fraction of one, which put a
            // €33 switch at the top of a search for the machine it belongs to.
            """schalter|kohleb(ü|ue)rsten|kondensator|keilriemen|antriebsriemen|""" +
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

    private fun isConsumableFor(listing: Listing, parsed: ParsedQuery, queryText: String): Boolean {
        // The query is really after the consumable itself ("schleifpapier ...") — keep those.
        if (consumableNoun.containsMatchIn(queryText) || abrasiveGrit.containsMatchIn(queryText)) return false
        // A sanding sheet has no capacity: "Patriot P400 4TB" and "Patriot P320 2 TB" are drives.
        if (abrasiveGrit.containsMatchIn(listing.title) && sizesIn(listing.title).isEmpty()) return true
        // "mit Zubehör", "+ Zubehör": what comes with the machine, said about the machine.
        val match = consumableNoun.find(listing.title) ?: return false
        val before = listing.title.take(match.range.first)
        return !comesWith.containsMatchIn(before) && !saidAboutTheDevice(before, parsed)
    }

    // An ad seeking the thing, or seeking a person to do it, rather than offering one for sale. The
    // agent noun a compound search also looks under ("Parkettschleifer") is both a machine and the
    // tradesman who works it, so a job posting reads as a match on words alone.
    private val wantedOrJobAd = Regex(
        """(^|\s)(suche|suchen|gesucht|gesuchte?r?)\b|\bwerde\s+teil\b|""" +
            """\(?\s*[mwd]\s*[/|]\s*[mwd]\s*[/|]\s*[mwd]\s*\)?|""" +
            """\b(stellenangebot|stellenanzeige|minijob|aushilfe|festanstellung|""" +
            """wanted|looking\s+for|gezocht|cercasi|se\s+busca|recherche\s+un)\b|""" +
            // A size a buyer sets as the least they will take: "Pixel 9 oder 10 Pro XL mind. 256GB".
            """\bmind(\.|estens)\s*\d+\s?(gb|tb)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun isWantedOrJobAd(listing: Listing, queryText: String): Boolean {
        if (wantedOrJobAd.containsMatchIn(queryText)) return false
        return wantedOrJobAd.containsMatchIn(listing.title)
    }

    fun filter(
        listings: List<Listing>,
        query: SearchQuery,
        blockedDealers: List<BlockedDealer> = UserStateStore.blockedDealers(),
    ): List<Listing> = partition(listings, query, blockedDealers).kept

    /**
     * Whether the listing's own title carries any word of the search.
     *
     * Keeping a listing and interrupting someone about it are different bars. Where a market's
     * sellers write none of the words searched for — a search for the part number CT32G4SFD832A,
     * which eBay's sellers never print — nothing can be required of a title and the market's own
     * search is the only judge, so every listing it returned is kept and shown. That is fine on a
     * screen someone chose to open, and wrong in a notification: seven alerts went out for 8 GB and
     * 16 GB modules, a SK hynix stick and a Supermicro ECC board, each announced as the Crucial
     * 32 GB module being watched for.
     */
    fun carriesAWordOfTheSearch(listing: Listing, query: SearchQuery): Boolean =
        score(listing, parseQuery(query)) > 0.0

    /** Whether the search names a vehicle, model-without-make included ("sprinter 314"). Read
     *  from the live make/model taxonomy rather than from a word list, and asking whether any make
     *  builds the model rather than which one: Sprinter is a Mercedes-Benz van and a Toyota
     *  saloon, and either way a box of brake pads is not what was searched for. */
    private fun namesAVehicle(queryText: String): Boolean =
        CarQueryResolver.resolve(queryText) != null || CarQueryResolver.namesAKnownModel(queryText)

    /** What a market sent, split into what the search keeps and what it drops, each drop carrying
     *  the reason it was dropped. [filter] is the kept half; the dropped half is what the app shows
     *  when someone asks what the search removed.
     *
     *  A listing from a dealer the user blocked goes first and under its own reason, whatever else
     *  could be said about it: who they buy from is their decision, and the dealer's name is what
     *  they need to see to take it back. */
    fun partition(
        listings: List<Listing>,
        query: SearchQuery,
        blockedDealers: List<BlockedDealer> = UserStateStore.blockedDealers(),
    ): Partitioned {
        val parsed = parseQuery(query)
        val dropped = mutableListOf<DroppedListing>()
        val listings = listings.filter { listing ->
            val dealer = blockedDealers.blocking(listing)
            if (dealer != null) {
                dropped += DroppedListing(listing, DropReason.BLOCKED_DEALER, dealer.name)
                return@filter false
            }
            val reason = when {
                !hasSanePrice(listing) -> DropReason.IMPLAUSIBLE_PRICE
                isWantedOrJobAd(listing, query.text) -> DropReason.WANTED_AD
                isRentalOffer(listing, query.text) -> DropReason.RENTAL
                isAccessoryFor(listing, parsed, query.text) -> DropReason.ACCESSORY
                isAnAccessoryNamedOutright(listing, parsed, query.text) -> DropReason.ACCESSORY
                isBuiltIntoADevice(listing, parsed, query.text) -> DropReason.BUILT_INTO_A_DEVICE
                // A part off the vehicle, named without a "für": a trim strip, a sill plate, a
                // wheel bolt, an OEM number. Only for a search that names a vehicle — a model
                // without its make is not enough to send a search to the car sites, but it is
                // enough to know that a box of brake pads is not what was asked for. Elsewhere a
                // long number in a title is an ordinary part number and means nothing.
                namesAVehicle(query.text) && !CarFilterEngine.isPartQuery(query.text) &&
                    CarFilterEngine.namesAVehiclePart(listing) -> DropReason.ACCESSORY
                isOneOfSeveralSizes(listing, parsed) -> DropReason.ONE_OF_SEVERAL_SIZES
                isConsumableFor(listing, parsed, query.text) -> DropReason.CONSUMABLE
                else -> null
            }
            if (reason != null) dropped += DroppedListing(listing, reason)
            reason == null
        }
        // Which of the words searched for a listing has to carry, decided per word rather than for
        // the search as a whole.
        //
        // A word with a number in it is a size or a model code — 2TB, M.2, S25 — and asking for one
        // is asking for that one, so it is always required. A word of letters alone is a category
        // word, and whether it can be required is read off the market's own answer: Idealo lists
        // "Lexar NM620 2TB M.2" and never writes "SSD", so requiring that word threw away the very
        // drives asked for, while Vinted answers "grigri" with the word in nearly every title, so a
        // listing without it is the odd one out. Where nothing can be required, the market's own
        // search is the only judge there is, and it already ran.
        fun shareCarrying(token: String): Double {
            if (listings.isEmpty()) return 0.0
            val t = token.lowercase().replace(NON_ALNUM, "")
            if (t.isEmpty()) return 1.0
            return listings.count { listing ->
                val text = "${listing.title} ${listing.description ?: ""}"
                // A short word is looked for as a word of its own, the way it is matched: "m2"
                // inside "nm790" is the model number of a different drive, not the slot.
                if (t.length <= 2) {
                    normalize(text.lowercase()).split(" ").any { it.replace(NON_ALNUM, "") == t }
                } else {
                    text.lowercase().replace(NON_ALNUM, "").contains(t)
                }
            }.toDouble() / listings.size
        }
        // Aliases are alternate phrasings of the whole search, scored as competing wholes, so only a
        // plain token list is narrowed this way.
        val asked = if (parsed.orGroups.isNotEmpty()) parsed else {
            val required = parsed.positiveTokens.filter { token ->
                // A size and a bare number are what a search cannot be talked out of: 2TB is not
                // 1TB, and a Sprinter 314 is not a Sprinter 316 and certainly not a book with
                // "Sprinter" in its title. A word carrying letters as well as digits is a form
                // factor or a trim as often as a model — "M.2" is on half the drives that have one
                // — so those follow the market's own answer like any other word.
                isASize(token) || token.all { it.isDigit() } || isACompound(token) ||
                    shareCarrying(token) >= WORDS_ARE_WRITTEN
            }
            // A requirement made only of words the whole answer carries decides nothing. Asked for
            // a Volkswagen Crafter, mobile.de answered with thirty-four Volkswagen Tiguans:
            // "crafter" is written by none of them, so it was dropped from the requirement, and
            // every Tiguan then matched the one word left perfectly. Where that is the shape of an
            // answer, the word the answer does not write is exactly the word that has to be
            // required — it is the only one that can tell the thing from what came instead.
            // A market that simply omits a category word is a different shape: there the other
            // words of the search are not universal either, since they name the thing.
            val decidesNothing = required.isNotEmpty() &&
                required.all { shareCarrying(it) >= EVERY_LISTING_SAYS_IT }
            val telling = if (!decidesNothing) emptyList() else
                parsed.positiveTokens.filter { it !in required && shareCarrying(it) < EVERY_LISTING_SAYS_IT }
            parsed.copy(positiveTokens = required + telling)
        }
        val sellersWriteTheseWords = asked.positiveTokens.isNotEmpty() || asked.orGroups.isNotEmpty()
        // A size is not a search on its own. Where only the size could be required, a listing still
        // has to carry some other word of the search, as long as this market's sellers write those
        // words at all: Vinted answers "4TB NVMe" with hard disks and memory cards that share
        // nothing with it but "4TB". A market that never writes them (Idealo does not write "SSD"
        // under a drive) leaves its listings to its own search, as every other rule here does.
        val beyondTheSize = parsed.positiveTokens.filterNot { isASize(it) }
        val onlyTheSizeRequired = parsed.orGroups.isEmpty() && beyondTheSize.isNotEmpty() &&
            beyondTheSize.size < parsed.positiveTokens.size &&
            asked.positiveTokens.all { isASize(it) } &&
            beyondTheSize.any { shareCarrying(it) > 0.0 }


        val implied = impliedWords(listings, parsed)
        val kept = listings.mapNotNull { listing ->
            // A word the reader blocked is their own decision, and is reported as that rather than
            // as something the market got wrong.
            if (blockedWord(listing, asked) != null) {
                dropped += DroppedListing(listing, DropReason.BLOCKED_WORD)
                return@mapNotNull null
            }
            val s = score(listing, asked, implied[listing.id].orEmpty())
            if (s < 0) {
                dropped += DroppedListing(listing, DropReason.NOT_A_SINGLE_OFFER)
                return@mapNotNull null
            }
            // A listing has to carry the search well enough, and only where the market's own answer
            // shows these are words its sellers write. Where they are not, the market's search is
            // the only judge there is, and it already ran.
            if (sellersWriteTheseWords && s < ENOUGH_OF_THE_SEARCH) {
                dropped += DroppedListing(listing, DropReason.OFF_TARGET)
                return@mapNotNull null
            }
            if (onlyTheSizeRequired && score(listing, parsed.copy(positiveTokens = beyondTheSize), implied[listing.id].orEmpty()) == 0.0) {
                dropped += DroppedListing(listing, DropReason.OFF_TARGET)
                return@mapNotNull null
            }
            listing to s
        }.sortedByDescending { it.second }.map { it.first }
        return Partitioned(kept, dropped)
    }

    /**
     * The words of the search each listing is shown to be by the rest of the market's answer.
     *
     * A seller names the product and leaves out what everyone knows it is: "Samsung 990 PRO SSD
     * 1TB" is an NVMe drive and an M.2 one, and a search for "1TB NVMe" or "4TB M.2" threw it out
     * for not saying so. Other sellers in the same answer do say so: where most of the listings
     * carrying the same model word ("990", "sn850x", "p310") also carry the word searched for, a
     * listing with that model word carries it too. A spec word ("2280", "gen4") is on SATA drives
     * as much as on NVMe ones and names no product, so it shows nothing.
     */
    private fun impliedWords(listings: List<Listing>, parsed: ParsedQuery): Map<String, Set<String>> {
        val words = (parsed.positiveTokens + parsed.orGroups.flatten()).distinct()
            .filter { w -> !isASize(w) && !w.all { it.isDigit() } && w.any { it.isLetter() } }
        if (words.isEmpty() || listings.isEmpty()) return emptyMap()
        val evidence = ModelWordEvidence.installed
        val single = { w: String -> parsed.copy(positiveTokens = listOf(w), orGroups = emptyList()) }
        val carries = words.associateWith { w -> listings.map { score(it, single(w)) == 1.0 } }
        val modelWordsOf = listings.map { modelWords(it.title) }
        val carriers = HashMap<String, MutableList<Int>>()
        modelWordsOf.forEachIndexed { i, ws -> ws.forEach { carriers.getOrPut(it) { mutableListOf() } += i } }
        val result = HashMap<String, Set<String>>()
        listings.forEachIndexed { i, listing ->
            val shown = words.filter { w ->
                !carries.getValue(w)[i] && modelWordsOf[i].any { m ->
                    val others = carriers.getValue(m).filter { it != i }
                    val here = others.size >= MODEL_EVIDENCE_LISTINGS &&
                        others.count { carries.getValue(w)[it] }.toDouble() / others.size >= MODEL_EVIDENCE_SHARE
                    // An answer too small to show it is judged by what the other answers showed.
                    here || evidence?.shows(m, w) == true
                }
            }
            if (shown.isNotEmpty()) result[listing.id] = shown.toSet()
        }
        evidence?.record(
            listingModels = listings.indices.associate { listings[it].id to modelWordsOf[it] },
            carried = listings.indices.associate { i -> listings[i].id to words.filter { carries.getValue(it)[i] }.toSet() },
            words = words,
        )
        return result
    }

    /** The words in a title that can name a model: a number of three or four digits ("990"), or
     *  letters and digits together, three or more of them ("sn850x", "p310", "nm790"). Two are a
     *  form factor or a lane count ("M.2", "x4"), which SATA drives carry as well. Sizes are what
     *  was asked, not which product it is. */
    private fun modelWords(title: String): Set<String> =
        normalize(gluedThousands(title.lowercase())).split(" ").filter { w ->
            w.any { it.isDigit() } && w.all { it.isLetterOrDigit() } &&
                (if (w.all { it.isDigit() }) w.length in 3..4 else w.length >= 3) &&
                !isASize(w) && sizeInGigabytes(w) == null && !specWord.matches(w)
        }.toSet()

    /** Words a title states a spec in rather than names a product with: a card length, a bus
     *  generation, a lane count. A SATA drive is "M.2 2280" as much as an NVMe one is. */
    private val specWord = Regex("""22(30|42|60|80|110)|gen\d|pcie\d|pci\d|ddr\d|usb\d\w*|3d""")

    /** Other listings that have to share a model word before it says anything about this one. */
    private const val MODEL_EVIDENCE_LISTINGS = 2

    /** The share of those that have to carry the word searched for. */
    private const val MODEL_EVIDENCE_SHARE = 0.6

    /**
     * Whether the listing carries what a compound the search names is made of.
     *
     * German writes one thing as one word and then splits it back apart across a list:
     * "Parkett-, Bodenschleifmaschine von Scheer" is a Parkettschleifmaschine, and matching the
     * glued spelling finds neither half of it. The leading part is what separates it from the
     * novels a market returns that merely end in "-maschine", so that is what is compared. A word
     * too short to be built of parts has none to compare, and is matched whole like any other.
     */
    /** From this many characters a word is built of parts rather than being one. */
    private const val COMPOUND_LENGTH = 10

    /** The leading part compared, long enough to name the thing the compound is about. */
    private const val STEM_LENGTH = 7

    /**
     * Whether the word is a size: digits glued to a unit, as a listing writes one.
     *
     * A size is the one part of a search that cannot be traded away — 2TB is not 1TB, and a market
     * whose answer is full of other sizes is answering about other things. Every other word, model
     * codes included, is required only where the market's own answer writes it: half the drives on
     * Vinted and Ricardo never write "M.2" and are M.2 drives, and asking for the words they leave
     * out lost the very listings searched for.
     */
    private fun isASize(token: String): Boolean {
        val t = token.lowercase().replace(NON_ALNUM, "")
        return unitSuffixes.any { unit ->
            t.endsWith(unit) && t.dropLast(unit.length).let { pre ->
                pre.isNotEmpty() && pre.all { it.isDigit() || it == 'x' }
            }
        }
    }

    /** Whether the word is a compound naming one thing: letters enough of them that nobody types
     *  it meaning a category. "Parkettschleifmaschine" is a machine for parquet and nothing else,
     *  so a market answering it with sanding belts has not answered — while "laptop" is a word its
     *  sellers can leave out of a laptop's title, and is judged on the answer instead. Matched
     *  through its leading part, so a compound written apart still counts. */
    private fun isACompound(token: String): Boolean =
        token.length >= COMPOUND_LENGTH && token.all { it.isLetter() }

    /** The share of a market's answer that has to carry a word of the search before a listing
     *  without one is treated as the exception rather than the rule. Half: measured against the two
     *  answers this decides between — Vinted for "grigri" carries the word in 46 of 48, eBay for a
     *  category word carries it in about half, and a market that ran the search and returned
     *  mostly other things still sits well above nothing. */
    private const val WORDS_ARE_WRITTEN = 0.5

    /** How much of a multi-word search a listing has to carry: three of four words, four of five. */
    private const val ENOUGH_OF_THE_SEARCH = 0.76

    /** From this share of a market's answer upwards, a word says nothing about which listing in it
     *  is the thing searched for. */
    private const val EVERY_LISTING_SAYS_IT = 0.95

    /**
     * Whether a market answered a different question than the one asked: it returned a page of
     * listings and not one of them carries a word from the search.
     *
     * Measured on the two cases this exists for. reBuy answers "grigri" with "Grün ist die Heide"
     * and "Mosaik (Grundkurs)" — six listings, none containing the word, because the site dropped
     * the search and served its own shelf. eBay Italy answers "2tb m.2 ssd" with a hundred drives
     * and five exact matches: it ran the search, and those five are real.
     *
     * So the line is at nothing, not at a share. A market that ran the search and mostly missed is
     * handled listing by listing like every other market; one that never ran it has nothing to
     * filter, since what it sent is about something else entirely.
     */
    fun answeredSomethingElse(
        listings: List<Listing>,
        query: SearchQuery,
        askedInItsOwnLanguage: Boolean = true,
    ): String? {
        // A market asked in a language it does not search cannot carry the words back, and its
        // answer is judged listing by listing like any other rather than thrown away whole.
        if (!askedInItsOwnLanguage) return null
        val parsed = parseQuery(query)
        val tokens = (parsed.positiveTokens + parsed.orGroups.flatten())
            .map { it.lowercase().replace(NON_ALNUM, "") }
            .filter { it.length >= 3 }
        if (tokens.isEmpty() || listings.size < MIN_ANSWER_TO_JUDGE) return null
        val matching = listings.count { listing ->
            val text = "${listing.title} ${listing.description ?: ""}".lowercase().replace(NON_ALNUM, "")
            tokens.any { text.contains(it) }
        }
        if (matching > 0) return null
        val sample = listings.take(5).joinToString("; ") { it.title.take(60) }
        return "${listings.size} results, not one carrying a word of the search (sample: $sample)"
    }

    /** Below this, an answer is too small to tell a market that ignored the search from one that
     *  genuinely had nothing close. */
    private const val MIN_ANSWER_TO_JUDGE = 5

    /** The two halves of [partition]: what the search shows, and what it removed. */
    data class Partitioned(val kept: List<Listing>, val dropped: List<DroppedListing>)

    /**
     * Detects a result set the platform returned without applying the query: many listings,
     * almost none containing any query token. Happens when a site silently ignores an
     * unsupported search parameter and serves its default feed (e.g. AutoScout24 `?query=`).
     * Returns a human-readable report, or null when the results look genuine.
     *
     * A one-word query is judged too, on whether the word itself appears anywhere in the listing
     * rather than on [score]: a market that returns nothing containing the word did not search for
     * it. eBay answered "grigri" with grey folders and belt buckles, having matched the French
     * "gris"; reBuy answered it with "Grieche sucht Griechin". The one-in-five floor is what keeps
     * a genuine answer safe — a market where a ThinkPad X1 comes back for "laptop" still has plenty
     * of listings that do say laptop, and stays.
     */
    fun irrelevanceReport(listings: List<Listing>, query: SearchQuery): String? {
        val parsed = parseQuery(query)
        val tokenCount = parsed.positiveTokens.size + parsed.orGroups.size
        if (tokenCount < 1 || listings.size < 5) return null
        val matching = if (tokenCount == 1) {
            val token = (parsed.positiveTokens.firstOrNull() ?: parsed.orGroups.firstOrNull()?.firstOrNull())
                ?.lowercase()?.replace(NON_ALNUM, "") ?: return null
            if (token.length < 3) return null
            listings.count { listing ->
                "${listing.title} ${listing.description ?: ""}".lowercase()
                    .replace(NON_ALNUM, "").contains(token)
            }
        } else {
            listings.count { score(it, parsed) > 0.0 }
        }
        if (matching.toDouble() / listings.size >= 0.2) return null
        val sample = listings.take(5).joinToString("; ") { it.title.take(60) }
        return "${listings.size} results, only $matching contain query tokens — search likely ignored (sample: $sample)"
    }

    /** Words of a search a title can carry under another name, as normalized words. Only names that
     *  always mean the word: PCIe on an M.2 drive is the NVMe protocol, while "M.2" alone is not,
     *  since M.2 SATA drives exist. */
    private val WRITTEN_AS = mapOf(
        "nvme" to listOf("pcie", "pci e", "pci express", "pcie3", "pcie4", "pcie5"),
        // Geizhals names a drive by its slot and bus and never calls it an SSD: "Lexar NM790 1TB,
        // M.2 2280 / M-Key / PCIe 4.0 x4". Only a drive is keyed for M.2 storage or speaks NVMe.
        "ssd" to listOf("nvme", "m key", "solid state"),
        // A make written out by some sellers and by its initials by others.
        "wd" to listOf("western digital", "wd black", "wd blue"),
    )

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
            // A dotted capital I ("İPhone", typed on a Turkish keyboard) lowercases to an i with a
            // combining dot, which the symbol strip below would turn into "i phone".
            .replace("̇", "").replace("İ", "i")
            // Sellers write the make's word apart and the model's qualifiers together or swapped:
            // "I Phone", "I-Phone", "Iphon", "Ipfon 11 ProMax", "iPhone 11 max pro" are all the 11 Pro Max.
            .replace(Regex("""\bi[\s-]*(phone|phon|pfon|fon)\b""", RegexOption.IGNORE_CASE), "iphone")
            .replace(Regex("""\bi[\s-]+pad\b""", RegexOption.IGNORE_CASE), "ipad")
            .replace(Regex("""(pro)(max)\b""", RegexOption.IGNORE_CASE), "$1 $2")
            // A unit written out: "512 Gigabyte" is 512 GB.
            .replace(Regex("""(\d)\s*gigabytes?\b""", RegexOption.IGNORE_CASE), "$1 gb")
            .replace(Regex("""(\d)\s*terabytes?\b""", RegexOption.IGNORE_CASE), "$1 tb")
            .replace(Regex("""(\d)\s+max\s+pro\b""", RegexOption.IGNORE_CASE), "$1 pro max")
            // A letter, a period, a digit is one word in the thing's own name: "M.2", "V.2".
            // Joined before periods become spaces, so a title's "M.2" and a query's "m.2" end up
            // in the same shape ("m2") — split, the title says "m 2" and the query token "m2"
            // never matches it, which dropped every M.2 drive on every market.
            .replace(Regex("""\b([a-z])\.(\d)""", RegexOption.IGNORE_CASE), "$1$2")
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

    /** Words that name the act of selling rather than the thing sold. A title made only of these
     *  says nothing about what is on offer. */
    private val SALE_WORDS = setOf(
        "verkaufe", "verkauf", "biete", "angebot", "neu", "neues", "gebraucht", "zu", "verkaufen",
        "privatverkauf", "sale", "offer", "new", "used", "listing", "artikel", "top", "gut",
    )

    /**
     * A title that names nothing being sold.
     *
     * Counting words decided this before, and a one-word title was taken for a placeholder: four
     * Kleinanzeigen ads titled exactly "Parkettschleifmaschine" were dropped from a search for a
     * Parkettschleifmaschine. What makes a title empty is that every word in it is about selling,
     * not how many words there are.
     */
    private fun isPlaceholderTitle(titleNorm: String, words: List<String>): Boolean {
        if (PLACEHOLDER_TITLES.any { titleNorm == normalize(it) }) return true
        val naming = words.filter { it.length > 1 && it !in SALE_WORDS && it.any { c -> c.isLetter() } }
        return naming.isEmpty()
    }
}

