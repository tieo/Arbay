package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SearchQuery

object RelevanceFilter {

    // Unit suffixes that can be glued to a number: "256gb", "512tb", "16mp"
    private val unitSuffixes = setOf("gb", "tb", "mb", "mp", "mhz", "ghz", "mah", "wh", "mm", "cm", "kg", "zoll", "inch")

    data class ParsedQuery(
        val positiveTokens: List<String>,
        val negativeTokens: List<String>,
        val orGroups: List<List<String>>,
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
        return ParsedQuery(
            positiveTokens = tokenize(query.text),
            negativeTokens = query.excludeKeywords.map { normalizeToken(it) },
            orGroups = orGroups,
        )
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
        // Strip context numbers that must NOT match numeric model tokens — BUT only strip storage
        // values if the query isn't itself asking for a size. Asking for one means mentioning the
        // unit at all: as its own word ("32 GB"), glued to the number ("32GB"), or glued to a
        // kit's quantity×size ("1x32GB"). A fixed list of "plausible" sizes was tried here before
        // and got it wrong both ways — it missed "1x32" (not a bare number) and would reject a
        // real, non-power-of-two drive size ("500GB", "480GB") that a query is free to ask for.
        val queryHasStorageToken = (parsed.positiveTokens + parsed.orGroups.flatten()).any { t ->
            t in unitSuffixes || unitSuffixes.any { u ->
                t.endsWith(u) && t.dropLast(u.length).let { pre -> pre.isNotEmpty() && pre.all { c -> c.isDigit() || c == 'x' } }
            }
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

        // Reject bulk lots: "15x", "x15", "15 Stück". A count of one is not a lot, and a number
        // after the x is a size where a unit follows it — "Crucial CT32G4SFD832A, 32 GB, 1 x 32 GB"
        // is one module, and reading it as a lot of 32 threw the exact product off the search.
        // A count sits before the x ("20x", "4 x") or stands as its own word after it ("x 20").
        // Glued to what follows, the x belongs to a model name — Biwin X570, Emtec X200 — and
        // reading those as lots of 570 and 200 threw two real drives off a search for one.
        if (Regex("""(?:^|\s)(?!1\s*x)\d{2,}\s*x(?:\s|$)""").containsMatchIn(titleNorm) ||
            Regex("""(?:^|\s)x\s+\d{2,}(?:\s|$)(?!\s*(gb|tb|mb|mhz))""").containsMatchIn(titleNorm)
        ) return -1.0

        // Word-start positions in titleCompact (for guarding compact matches)
        val wordStartsInCompact: Set<Int> = buildSet {
            var pos = 0
            for (word in titleNormForMatching.split(" ")) {
                if (word.isNotEmpty()) add(pos)
                pos += word.length
            }
        }

        fun tokenMatches(token: String): Boolean {
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

    /** Sizes as a listing writes them: a number glued or spaced to a storage unit. */
    /** A speed, not a size: "7.300 MB/s Lesen" is how fast the drive is, and counting it as a
     *  second size made every drive that advertises one look like a row of variants. */
    private val sizeInTitle =
        Regex("""\b(\d{1,4}(?:[.,]\d)?)\s?(gb|tb|mb)\b(?!\s*/\s*s)""", RegexOption.IGNORE_CASE)

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
        val asked = parsed.positiveTokens.mapNotNull { sizeInGigabytes(it) }.ifEmpty { return false }
        val offered = sizeInTitle.findAll(listing.title)
            .mapNotNull { sizeInGigabytes(it.groupValues[1] + it.groupValues[2]) }
            .toSet()
        // A drive that states its size twice ("2TB (2000GB)") states one size. Only a listing that
        // also offers something smaller than what was asked for is priced at a size nobody asked
        // for, which is what makes its place among the cheapest wrong.
        if (offered.size < 2 || offered.none { it < asked.min() }) return false
        // Unless the smaller sizes are what the asked-for size is made of: "M.2 SSD 2TB (2x 1TB)"
        // is two terabytes, sold as two sticks, at a price for the pair.
        return !addsUpToTheAskedSize(listing.title, asked.min())
    }

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
        val m = Regex("""^(\d{1,4}(?:[.,]\d)?)\s?(gb|tb|mb)$""", RegexOption.IGNORE_CASE)
            .find(token.trim()) ?: return null
        val value = m.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "tb" -> value * 1000
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
            """nuc|mini[\s-]?pc|all[\s-]?in[\s-]?one|playstation|ps5|xbox|konsole|console|pc|""" +
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
        if (hostDevice.containsMatchIn(queryText)) return false
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
        val naming = hostDevice.find(head) ?: return false
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

    // What a thing is sold with, named as the thing on offer. A search for headphones comes back
    // led by a storage case at 15 euro, a replacement headband at 18 and an aftermarket battery at
    // 20, all of them carrying the model number because that is what they fit.
    private val accessoryNoun = Regex(
        // German builds these as compounds — Aufbewahrungshülle, Hochleistungsakku,
        // Ersatzohrpolster — so the head noun is matched wherever the word ends, not only where it
        // stands alone. The other languages a cross-border search reaches name them plainly.
        """(?U)\w*(h(ü|ue)lle|etui|tasche|akkus?|batterien?|ladeger(ä|ae)t|kabel|netzteil|""" +
            """polster|kopfband|halterung|st(ä|ae)nder|schutzfolie|displayschutz|reparaturset|""" +
            """ersatzteile?|platine|mainboard|geh(ä|ae)use|abdeckung)\b|""" +
            """\b(ear\s?pads?|headband|pcb|repair\s?kit|housse|custodia|funda|hoes|""" +
            """cover|case|charger)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** "mit Tasche", "inkl. Ladekabel", "+ Etui": what comes with the thing, rather than instead
     *  of it. A word after one of these names an extra, and the ad is still about the product. */
    private val comesWith = Regex("""(mit|inkl\.?|inklusive|incl\.?|including|with|\+|&)\s*$""", RegexOption.IGNORE_CASE)

    /**
     * Whether the title's own subject is something sold alongside the thing searched for.
     *
     * Only where the query does not ask for it, and only where nothing marks it as an extra: a
     * listing reading "WH-1000XM5 mit Tasche" is the headphones, and "WH-1000XM5
     * Aufbewahrungshülle" is the bag.
     */
    private fun isAnAccessoryNamedOutright(listing: Listing, queryText: String): Boolean {
        if (accessoryNoun.containsMatchIn(queryText)) return false
        val match = accessoryNoun.find(listing.title) ?: return false
        return !comesWith.containsMatchIn(listing.title.take(match.range.first))
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

    private fun isConsumableFor(listing: Listing, queryText: String): Boolean {
        // The query is really after the consumable itself ("schleifpapier ...") — keep those.
        if (consumableNoun.containsMatchIn(queryText) || abrasiveGrit.containsMatchIn(queryText)) return false
        return consumableNoun.containsMatchIn(listing.title) || abrasiveGrit.containsMatchIn(listing.title)
    }

    // An ad seeking the thing, or seeking a person to do it, rather than offering one for sale. The
    // agent noun a compound search also looks under ("Parkettschleifer") is both a machine and the
    // tradesman who works it, so a job posting reads as a match on words alone.
    private val wantedOrJobAd = Regex(
        """(^|\s)(suche|suchen|gesucht|gesuchte?r?)\b|\bwerde\s+teil\b|""" +
            """\(?\s*[mwd]\s*[/|]\s*[mwd]\s*[/|]\s*[mwd]\s*\)?|""" +
            """\b(stellenangebot|stellenanzeige|minijob|aushilfe|festanstellung|""" +
            """wanted|looking\s+for|gezocht|cercasi|se\s+busca|recherche\s+un)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun isWantedOrJobAd(listing: Listing, queryText: String): Boolean {
        if (wantedOrJobAd.containsMatchIn(queryText)) return false
        return wantedOrJobAd.containsMatchIn(listing.title)
    }

    fun filter(listings: List<Listing>, query: SearchQuery): List<Listing> =
        partition(listings, query).kept

    /** Whether the search names a vehicle, model-without-make included ("sprinter 314"). Read
     *  from the live make/model taxonomy rather than from a word list, and asking whether any make
     *  builds the model rather than which one: Sprinter is a Mercedes-Benz van and a Toyota
     *  saloon, and either way a box of brake pads is not what was searched for. */
    private fun namesAVehicle(queryText: String): Boolean =
        CarQueryResolver.resolve(queryText) != null || CarQueryResolver.namesAKnownModel(queryText)

    /** What a market sent, split into what the search keeps and what it drops, each drop carrying
     *  the reason it was dropped. [filter] is the kept half; the dropped half is what the app shows
     *  when someone asks what the search removed. */
    fun partition(listings: List<Listing>, query: SearchQuery): Partitioned {
        val parsed = parseQuery(query)
        val dropped = mutableListOf<DroppedListing>()
        val listings = listings.filter { listing ->
            val reason = when {
                !hasSanePrice(listing) -> DropReason.IMPLAUSIBLE_PRICE
                isWantedOrJobAd(listing, query.text) -> DropReason.WANTED_AD
                isRentalOffer(listing, query.text) -> DropReason.RENTAL
                isAccessoryFor(listing, parsed, query.text) -> DropReason.ACCESSORY
                isAnAccessoryNamedOutright(listing, query.text) -> DropReason.ACCESSORY
                isBuiltIntoADevice(listing, parsed, query.text) -> DropReason.BUILT_INTO_A_DEVICE
                // A part off the vehicle, named without a "für": a trim strip, a sill plate, a
                // wheel bolt, an OEM number. Only for a search that names a vehicle — a model
                // without its make is not enough to send a search to the car sites, but it is
                // enough to know that a box of brake pads is not what was asked for. Elsewhere a
                // long number in a title is an ordinary part number and means nothing.
                namesAVehicle(query.text) && !CarFilterEngine.isPartQuery(query.text) &&
                    CarFilterEngine.namesAVehiclePart(listing) -> DropReason.ACCESSORY
                isOneOfSeveralSizes(listing, parsed) -> DropReason.ONE_OF_SEVERAL_SIZES
                isConsumableFor(listing, query.text) -> DropReason.CONSUMABLE
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
                isASize(token) || token.all { it.isDigit() } ||
                    shareCarrying(token) >= WORDS_ARE_WRITTEN
            }
            parsed.copy(positiveTokens = required)
        }
        val sellersWriteTheseWords = asked.positiveTokens.isNotEmpty() || asked.orGroups.isNotEmpty()

        val kept = listings.mapNotNull { listing ->
            val s = score(listing, asked)
            if (s < 0) {
                dropped += DroppedListing(listing, DropReason.NOT_A_SINGLE_OFFER)
                return@mapNotNull null
            }
            // A compound names what it is about in its leading part, and that part is the test: a
            // search for a Parkettschleifmaschine reaches "Parkett-, Bodenschleifmaschine" and not
            // the sanding belts and belt sanders a market answers with, which share the tail and
            // nothing else.
            val stems = compoundStems(parsed)
            if (stems.isNotEmpty()) {
                if (!carriesAStem(listing, stems)) {
                    dropped += DroppedListing(listing, DropReason.OFF_TARGET)
                    return@mapNotNull null
                }
            } else if (sellersWriteTheseWords && s < ENOUGH_OF_THE_SEARCH) {
                // Otherwise a listing has to carry the search well enough, and only where the
                // market's own answer shows these are words its sellers write. Where they are not,
                // the market's search is the only judge there is, and it already ran.
                dropped += DroppedListing(listing, DropReason.OFF_TARGET)
                return@mapNotNull null
            }
            listing to s
        }.sortedByDescending { it.second }.map { it.first }
        return Partitioned(kept, dropped)
    }

    /**
     * Whether the listing carries what a compound the search names is made of.
     *
     * German writes one thing as one word and then splits it back apart across a list:
     * "Parkett-, Bodenschleifmaschine von Scheer" is a Parkettschleifmaschine, and matching the
     * glued spelling finds neither half of it. The leading part is what separates it from the
     * novels a market returns that merely end in "-maschine", so that is what is compared. A word
     * too short to be built of parts has none to compare, and is matched whole like any other.
     */
    private fun compoundStems(parsed: ParsedQuery): List<String> =
        (parsed.positiveTokens + parsed.orGroups.flatten())
            .map { it.lowercase().replace(NON_ALNUM, "") }
            // A compound is built of words. "CT32G4SFD832A" is a part number, and its first seven
            // characters name nothing, so a market that lists the same module without the number
            // was being dropped for not repeating a prefix of it.
            .filter { it.length >= COMPOUND_LENGTH && it.all { c -> c.isLetter() } }
            .map { it.take(STEM_LENGTH) }
            .distinct()

    private fun carriesAStem(listing: Listing, stems: List<String>): Boolean {
        val text = "${listing.title} ${listing.description ?: ""}".lowercase().replace(NON_ALNUM, "")
        return stems.any { text.contains(it) }
    }

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

    /** The share of a market's answer that has to carry a word of the search before a listing
     *  without one is treated as the exception rather than the rule. Half: measured against the two
     *  answers this decides between — Vinted for "grigri" carries the word in 46 of 48, eBay for a
     *  category word carries it in about half, and a market that ran the search and returned
     *  mostly other things still sits well above nothing. */
    private const val WORDS_ARE_WRITTEN = 0.5

    /** How much of a multi-word search a listing has to carry: three of four words, four of five. */
    private const val ENOUGH_OF_THE_SEARCH = 0.76

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

