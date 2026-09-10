package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.*
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelevanceFilterTest {

    private fun listing(title: String, price: Long = 10000) = Listing(
        id = "test:${title.hashCode()}",
        platformId = PlatformId.EBAY_DE,
        externalId = title.hashCode().toString(),
        url = "https://example.com",
        title = title,
        price = Money(price, Currency.EUR),
        scrapedAt = Clock.System.now(),
    )

    private fun search(
        query: String,
        listings: List<Listing>,
        excludeKeywords: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
    ): List<Listing> =
        RelevanceFilter.filter(listings, SearchQuery(text = query, excludeKeywords = excludeKeywords, aliases = aliases, category = MarketGroup.GENERAL))

    // === Placeholder title filtering ===

    @Test
    fun `filters out placeholder titles`() {
        val results = search("WH-1000XM5", listOf(
            listing("Neues Angebot"),
            listing("Sony WH-1000XM5 Bluetooth Kopfhörer"),
            listing("x"),
        ))
        assertEquals(1, results.size)
        assertTrue(results[0].title.contains("Bluetooth"))
    }

    // === Numeric model token must match as whole word ===

    @Test
    fun `Galaxy Z Fold 6 filters older Fold generations`() {
        val results = search("Samsung Galaxy Z Fold 6", listOf(
            listing("Samsung Galaxy Z Fold 6 SM-F956B 256GB Phantom Black"),
            listing("Samsung Galaxy Z Fold 6 512GB Shadow Cyan"),
            listing("Samsung Galaxy Z Fold3 5G SM-F926B 256GB Schwarz"),
            listing("Samsung Galaxy Z Fold5 SM-F946B 512GB Phantom Black"),
        ))
        assertTrue(results.any { it.title.contains("Fold 6 SM-F956B") }, "Fold 6 should be kept")
        assertTrue(results.any { it.title.contains("Fold 6 512GB") }, "Fold 6 should be kept")
        assertTrue(results.none { it.title.contains("Fold3") }, "Fold3 should be filtered")
        assertTrue(results.none { it.title.contains("Fold5") }, "Fold5 should be filtered")
    }

    // === Negative keywords ===

    @Test
    fun `negative keywords exclude matching listings`() {
        val results = search("MFC-L2750DW", listOf(
            listing("Brother MFC-L2750DW Multifunktionsdrucker"),
            listing("Toner kompatibel für Brother MFC-L2750DW"),
            listing("DR2400 Trommel Drum für MFC-L2750DW"),
            listing("Brother MFC-L2750DW 4-in-1 Laserdrucker"),
        ), excludeKeywords = listOf("toner", "drum"))
        assertEquals(2, results.size)
        assertTrue(results.all { !it.title.lowercase().contains("toner") && !it.title.lowercase().contains("drum") })
    }

    // === Token matching ===

    @Test
    fun `hyphenated model numbers match compact and split forms`() {
        val results = search("WH-1000XM5", listOf(
            listing("Sony WH-1000XM5 Bluetooth Kopfhörer"),
            listing("Sony WH1000XM5 Noise Cancelling"),
            listing("Sony WH 1000 XM5 Headphones"),
        ))
        assertEquals(3, results.size, "All forms should match")
    }

    // === Model qualifier bigram ===

    @Test
    fun `model qualifier must be adjacent`() {
        val results = search("Samsung Galaxy S25 Ultra", listOf(
            listing("Samsung Galaxy S25 Ultra 256GB Titanium Black"),
            listing("Samsung Galaxy S25 FE ultra sauber wie neu"),
        ))
        assertTrue(results.any { it.title.contains("Ultra 256GB") }, "Actual Ultra should be kept")
        assertTrue(results.none { it.title.contains("ultra sauber") }, "German 'ultra clean' should be filtered")
    }

    // === Score threshold ===

    @Test
    fun `low match ratio listings are filtered`() {
        val results = search("Canon EOS R5 Mark II", listOf(
            listing("Canon EOS R5 Mark II Mirrorless Camera Body"),
            listing("Canon EOS 5D Mark IV DSLR Camera"),
        ))
        assertTrue(results.any { it.title.contains("R5 Mark II") })
        assertTrue(results.none { it.title.contains("5D Mark IV") }, "Different model should be filtered by score threshold")
    }

    // === OR queries ===

    @Test
    fun `OR query matches either group`() {
        val results = search("Sony WH-1000XM5", listOf(
            listing("Sony WH-1000XM5 Noise Cancelling Headphones"),
            listing("Sony WH-1000XM4 Wireless Headphones"),
            listing("Sony WF-1000XM5 Earbuds"),
        ), aliases = listOf("Sony WH-1000XM4"))
        assertTrue(results.any { it.title.contains("XM5 Noise") })
        assertTrue(results.any { it.title.contains("XM4 Wireless") })
    }

    // === All results pass through when they match ===

    @Test
    fun `matching listings are not filtered by removed kill lists`() {
        val results = search("MFC-L2750DW", listOf(
            listing("Brother MFC-L2750DW Multifunktionsdrucker"),
            listing("Toner kompatibel für Brother MFC-L2750DW"),
            listing("Schutzhülle Case für MFC-L2750DW"),
            listing("Brother MFC-L2750DW Mainboard Formatter"),
        ))
        // No hardcoded kill lists: the printer itself and a spare part named without an
        // "accessory for" phrasing both pass. What does not pass is an accessory whose title
        // says it is made FOR the searched product — a searcher after the printer does not
        // want its toner or a case (see the accessory-for tests below).
        assertTrue(results.any { it.title.contains("Multifunktionsdrucker") })
        assertTrue(results.any { it.title.contains("Mainboard") })
        assertTrue(results.none { it.title.contains("Toner") }, "toner is an accessory for the printer")
        assertTrue(results.none { it.title.contains("Schutzhülle") }, "case is an accessory for the printer")
    }

    // === Irrelevance report (platform ignored the query) ===

    private val garbageListings = listOf(
        listing("BMW 320d Touring Sportpaket"),
        listing("Audi A4 Avant 2.0 TDI"),
        listing("Opel Corsa 1.2 Edition"),
        listing("Ford Focus Turnier Titanium"),
        listing("Renault Clio TCe 90"),
        listing("Skoda Octavia Combi Style"),
        listing("Toyota Yaris Hybrid Comfort"),
        listing("Fiat 500 Lounge Cabrio"),
        listing("Peugeot 208 Allure Pack"),
        listing("Hyundai i30 Kombi Trend"),
    )

    @Test
    fun `irrelevanceReport flags result set without any query matches`() {
        val report = RelevanceFilter.irrelevanceReport(garbageListings, SearchQuery(text = "Volkswagen Crafter", category = MarketGroup.VEHICLES))
        assertNotNull(report, "10 listings with zero query matches should be flagged")
    }

    @Test
    fun `irrelevanceReport passes genuine result set`() {
        val genuine = listOf(
            listing("Volkswagen Crafter 35 Kasten Hochdach"),
            listing("VW Crafter 2.0 TDI L3H3"),
            listing("Volkswagen Crafter Pritsche Doka"),
            listing("Volkswagen Crafter Kombi 9-Sitzer"),
            listing("VW Crafter Grand California 600"),
        )
        val report = RelevanceFilter.irrelevanceReport(genuine, SearchQuery(text = "Volkswagen Crafter", category = MarketGroup.VEHICLES))
        assertNull(report, "Matching results should not be flagged")
    }

    @Test
    fun `a one-word query is judged too, when the word is nowhere in the answer`() {
        // Ten cars back from a search for "Laptop" is a market that searched for something else.
        val report = RelevanceFilter.irrelevanceReport(garbageListings, SearchQuery(text = "Laptop", category = MarketGroup.GENERAL))
        assertNotNull(report, "A market answering 'Laptop' with cars has not answered")
    }

    @Test
    fun `a one-word query survives an answer that mostly names the thing`() {
        // The case the blanket exemption was protecting: a laptop listing need not say "laptop",
        // and as long as enough of them do, the market plainly searched for it.
        val laptops = listOf(
            listing("Lenovo ThinkPad X1 Carbon i7"),
            listing("Dell XPS 13 9310"),
            listing("Laptop HP EliteBook 840 G8"),
            listing("Laptop Acer Aspire 5"),
            listing("MacBook Air M2"),
            listing("Gaming Laptop Lenovo Legion 5"),
        )
        val report = RelevanceFilter.irrelevanceReport(laptops, SearchQuery(text = "Laptop", category = MarketGroup.GENERAL))
        assertNull(report, "Half of them say laptop; that market answered")
    }

    @Test
    fun `an M dot 2 drive matches a query that spells the slot the same way`() {
        // Every market writes the slot "M.2". The query does too, and the two have to end up in
        // the same shape: split into "m" and "2", the title matched nothing and the search came
        // back empty on every market at once.
        val results = search("2tb m.2 ssd", listOf(
            listing("Lexar NM990 2TB M.2 SSD"),
            listing("SSD Samsung 990 EVO Plus M.2 2TB"),
            listing("Kioxia Exceria G3 1TB M.2 SSD"),
            listing("Sandisk Extreme Portable SSD 2TB USB-C"),
        ))
        assertTrue(results.any { it.title.contains("NM990") }, "an M.2 2TB drive is what was asked for")
        assertTrue(results.any { it.title.contains("990 EVO") }, "size written after the slot still matches")
        assertTrue(results.none { it.title.contains("1TB") }, "a 1TB drive is a different size")
        assertTrue(results.none { it.title.contains("Portable") }, "an external USB drive has no M.2 slot")
    }

    @Test
    fun `a market answering part of a multi-token query keeps that part`() {
        // Most of what a market returns for a specific query is near-misses, so the share of
        // listings containing a token is routinely low. The report says so and nothing acts on it:
        // every listing is judged on its own, so the ones that matched survive the ones that did not.
        val mixed = listOf(
            listing("Lexar NM790 2TB M.2 SSD"),
            listing("Gaming PC Ryzen 7 7800X3D RTX 5080 32GB RAM"),
            listing("Netac NV3000 NVMe M.2 SSD 500GB"),
            listing("PlayStation 5 Pro 2TB Bundle"),
            listing("Hikvision Kamera Set 16x Dome +2TB HDD"),
            listing("Lenovo ThinkCentre M710q Tiny 250GB"),
        )
        val query = SearchQuery(text = "2tb m.2 ssd", category = MarketGroup.GENERAL)
        assertNotNull(
            RelevanceFilter.irrelevanceReport(mixed, query),
            "one in six containing the tokens is what the report is built to flag",
        )
        val kept = RelevanceFilter.filter(mixed, query)
        assertEquals(listOf("Lexar NM790 2TB M.2 SSD"), kept.map { it.title })
    }

    @Test
    fun `a market that sent back none of the search's words answered something else`() {
        // reBuy, live, for "grigri": six listings, not one of them carrying the word, because the
        // site dropped the search and served its own shelf.
        val shelf = listOf(
            listing("Graubünden: Grischun - Grigioni - Dino Sassi"),
            listing("Grün ist die Heide - Löns,Hermann"),
            listing("Grün Blau Grau"),
            listing("Mosaik (Grundkurs)"),
            listing("Power Semiconductor Drives"),
            listing("Solid-State-Drives (SSDs) Modeling"),
        )
        assertNotNull(
            RelevanceFilter.answeredSomethingElse(shelf, SearchQuery(text = "grigri", category = MarketGroup.GENERAL)),
            "not one listing carries the word, so nothing here was an answer to it",
        )
    }

    @Test
    fun `a market asked in the wrong language is not accused of ignoring the search`() {
        // eBay Italy, live, asked in German because nobody turned translation on: it answers in
        // Italian, so not one listing carries the word. It answered as well as it could.
        val italian = listOf(
            listing("5/10 nastri abrasivi nastro abrasivo per levigatrice, 750 x 200"),
            listing("Nastro abrasivo 200 x 750 mm per levigatrice"),
            listing("50 dischi abrasivi in ceramica a strappo per parquet"),
            listing("Cinghia trapezoidale 13x787 Li"),
            listing("2 Pezzi Cinghia per levigatrice"),
        )
        val query = SearchQuery(text = "parkettschleifmaschine", category = MarketGroup.GENERAL)
        assertNull(
            RelevanceFilter.answeredSomethingElse(italian, query, askedInItsOwnLanguage = false),
            "asked in a language it does not search, it could not have sent the word back",
        )
        assertNotNull(
            RelevanceFilter.answeredSomethingElse(italian, query, askedInItsOwnLanguage = true),
            "asked in its own language, an answer without one word of it is a different question",
        )
    }

    @Test
    fun `a market that mostly missed still keeps what it matched`() {
        // eBay Italy, live, for "2tb m.2 ssd": a hundred drives of every size, a few of them the
        // one asked for. It ran the search, so its answer is filtered listing by listing.
        val mostlyOtherSizes = listOf(
            listing("Netac SSD NVME M2 1TB SSD 250GB 500GB M2 Solid State"),
            listing("Fanxiang M.2 SSD PCIe 4.0 1TB dissipatore disco"),
            listing("Intel SSD 660p Series 512GB M.2 NVME PCIe"),
            listing("Crucial P3 Plus 1TB M.2 NVMe"),
            listing("Kingston NV3 500GB M.2 2280"),
            listing("Lexar NM790 2TB M.2 SSD"),
        )
        val query = SearchQuery(text = "2tb m.2 ssd", category = MarketGroup.GENERAL)
        assertNull(
            RelevanceFilter.answeredSomethingElse(mostlyOtherSizes, query),
            "the words are all over this answer; the market plainly ran the search",
        )
        assertEquals(listOf("Lexar NM790 2TB M.2 SSD"), RelevanceFilter.filter(mostlyOtherSizes, query).map { it.title })
    }

    @Test
    fun `a switch off the machine is not the machine`() {
        // Off the phone: a 33 euro Geizhals listing led a search for the machine it belongs to,
        // because it carries the machine's own name and costs a fraction of one.
        val kept = search("parkettschleifmaschine", listOf(
            listing("Lägler Randschleifer Elan, Flip, Unico, Schalter Parkettschleifmaschine", price = 3299),
            listing("Lägler Parkettschleifmaschine Hummel", price = 30000),
        )).map { it.title }
        assertEquals(listOf("Lägler Parkettschleifmaschine Hummel"), kept)
    }

    @Test
    fun `what a compound is about has to be in the listing`() {
        // Straight off the live answer for "parkettschleifmaschine": Geizhals and Amazon send
        // sanding belts and belt sanders, which share the tail of the word and nothing else. What
        // the search is about is its leading part, and none of them is about parquet.
        val answer = listOf(
            listing("Holzmann SBPSM Schleifband K80, 200x650mm, 1 Stück"),
            listing("Einhell Bandschleifer TC-BS 8038 (800 W)"),
            listing("Lägler Hummel Parkettschleifmaschine Bandschleifer"),
            listing("Parkett-, Bodenschleifmaschine von Scheer"),
            listing("Makita Exzenterschleifer BO5041J"),
            listing("Bosch Professional Bandschleifer GBS 75 AE"),
        )
        val kept = search("parkettschleifmaschine", answer).map { it.title }
        assertTrue(kept.any { it.contains("Lägler Hummel") }, "the machine itself")
        assertTrue(kept.any { it.startsWith("Parkett-,") }, "the same machine, written as a list")
        assertTrue(kept.none { it.contains("Schleifband") }, "a belt is not a machine for parquet")
        assertTrue(kept.none { it.contains("Bandschleifer TC-BS") }, "a belt sander is a different machine")
        assertTrue(kept.none { it.contains("Exzenterschleifer") }, "so is an orbital sander")
    }

    @Test
    fun `a listing without the word goes where the market's sellers write it`() {
        // Vinted, live, for "grigri": the word is in nearly every title it sent, so the two that
        // never say it are the exception, not the market's own vocabulary.
        val vinted = listOf(
            listing("Grigri de sac ou de chaussure"),
            listing("Mini grigri amitié"),
            listing("Grigri de téléphone"),
            listing("Porte-clés grigri tulipes rose"),
            listing("Collier avec grigri poisson étoile de mer"),
            listing("Porte clé, Marke: Accessories"),
            listing("Sac élégant, Marke: Pimkie"),
        )
        val kept = search("grigri", vinted).map { it.title }
        assertTrue(kept.none { it.startsWith("Porte clé,") }, "no word of the search anywhere in it")
        assertTrue(kept.none { it.startsWith("Sac élégant") }, "no word of the search anywhere in it")
        assertEquals(5, kept.size)
    }

    @Test
    fun `a category word its sellers never write leaves the market to judge`() {
        // The other side of the same measurement: a laptop listing names the machine, not the
        // category, so demanding the word would throw away the market's whole answer.
        val laptops = listOf(
            listing("Lenovo ThinkPad X1 Carbon i7"),
            listing("Dell XPS 13 9310"),
            listing("HP EliteBook 840 G8"),
            listing("Acer Aspire 5"),
            listing("MacBook Air M2"),
            listing("Laptop Lenovo Legion 5"),
        )
        assertEquals(6, search("laptop", laptops).size, "the market ran the search; it is the judge here")
    }

    @Test
    fun `irrelevanceReport skips small result sets`() {
        val report = RelevanceFilter.irrelevanceReport(garbageListings.take(4), SearchQuery(text = "Volkswagen Crafter", category = MarketGroup.VEHICLES))
        assertNull(report, "Fewer than 5 results is too small a sample to flag")
    }

    @Test
    fun `rental offers are dropped, unless the query asks to rent`() {
        val listings = listOf(
            listing("Parkettschleifmaschine Mieten", price = 100),
            listing("Parkettschleifmaschine Lägler Hummel", price = 90000),
            listing("Parkettschleifmaschine zu vermieten", price = 500),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }
        assertEquals(listOf("Parkettschleifmaschine Lägler Hummel"), kept)
        // Asking to rent keeps them: that is exactly what was searched for.
        val rentKept = search("parkettschleifmaschine mieten", listings).map { it.title }
        assertTrue(rentKept.any { it.contains("Mieten") }, "rental query must keep rental offers")
    }



    @Test
    fun `a long compound single-token query drops results that only share its tail`() {
        val listings = listOf(
            listing("Die Zeitmaschine", price = 389),
            listing("Leo und die Abenteuermaschine", price = 289),
            listing("Parkettschleifmaschine Lägler Hummel", price = 250000),
            listing("Parkett Schleifmaschine Bandschleifer", price = 90000),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }.toSet()
        assertEquals(
            setOf("Parkettschleifmaschine Lägler Hummel", "Parkett Schleifmaschine Bandschleifer"),
            kept,
        )
    }

    @Test
    fun `a short generic single-token query still trusts the platform search`() {
        val listings = listOf(
            listing("Lenovo ThinkPad X1 Carbon", price = 50000),
            listing("Dell XPS 13", price = 60000),
        )
        assertEquals(2, search("laptop", listings).size, "generic category words must not stem-match")
    }

    @Test
    fun `consumables and spares for the machine are dropped, unless the query asks for them`() {
        val listings = listOf(
            listing("Schleifpapier / Schleifband Parkettschleifmaschine (Boels)", price = 600),
            listing("TM Rent 5 Beutel Staubfangsack Parkettschleifmaschine", price = 994),
            listing("Schleifscheiben für Parkettschleifmaschine 150mm", price = 1200),
            listing("Lijadora de banda de revestimiento 75x610 mm P240 SIA WOOD", price = 1325),
            listing("Parkettschleifmaschine Laegler Hummel", price = 250000),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }
        assertEquals(listOf("Parkettschleifmaschine Laegler Hummel"), kept)
        // A search for the consumable keeps it.
        assertEquals(1, search("schleifpapier parkett", listOf(listings[0])).size)
    }

    @Test
    fun `sanding paper is a consumable in the plural too`() {
        // Straight off the phone: a watched search for parkettschleifmaschine notified about an
        // eight euro pack of sandpaper, because the rule said "schleifpapier" with a word boundary
        // and the ad said "Schleifpapiere".
        val listings = listOf(
            listing("Schleifpapiere Parkett für Walzenschleifmaschine NEU", price = 800),
            listing("Schleifpapier für Parkettschleifmaschine", price = 900),
            listing("Parkett Schleifmaschine Walzenschleifmaschine", price = 40000),
            listing("Parkett-, Bodenschleifmaschine von Scheer", price = 9000),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }
        assertEquals(
            listOf("Parkett Schleifmaschine Walzenschleifmaschine", "Parkett-, Bodenschleifmaschine von Scheer"),
            kept,
            "the machines stay and the paper goes",
        )
    }

    @Test
    fun `a part sold for the machine goes, even when the ad calls the machine something else`() {
        // Straight off the phone again: these were stored for a parkettschleifmaschine watch. The
        // ads say "für Parkettschleifer", the search says "parkettschleifmaschine", and requiring
        // the whole word meant a capacitor and a roller read as machines.
        val listings = listOf(
            listing("Kondensator 31,5uf für Parkettschleifer Kunzle & Tasin, Dismac", price = 6499),
            listing("Lägler Hummel Walze für Parkettschleifmaschinen - Neubezug", price = 23900),
            listing("Lägler Parkettschleifmaschine", price = 30000),
            listing("Künzle & Tasin Arlequin Parkettschleifmaschine 230 V", price = 50000),
        )
        val kept = search("parkettschleifmaschine", listings).map { it.title }
        assertEquals(
            listOf("Lägler Parkettschleifmaschine", "Künzle & Tasin Arlequin Parkettschleifmaschine 230 V"),
            kept,
        )
    }
}
