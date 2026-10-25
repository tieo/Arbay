package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.*
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // No hardcoded kill lists for a product: the printer itself passes on its own words. What
        // does not pass is what the printer is sold beside — its toner, a case made for it, and
        // the board out of one. A search for a printer led by a 20 euro mainboard is the shape
        // this removes, and each of them says which rule took it.
        assertTrue(results.any { it.title.contains("Multifunktionsdrucker") })
        assertTrue(results.none { it.title.contains("Mainboard") }, "a board out of one is not one")
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
    fun `a model next to the one searched for is not it`() {
        // Back Market, live, answered "IB-3740-C31" with one listing: an I-Tec dock whose code
        // begins with the "C31". No listing of that answer printed the code, so nothing was asked of
        // it, and the dock stood as the cheapest of the enclosure at 82 €.
        val dock = listing("I-Tec C31HDMIDPDOCKPD Docking-Station")
        assertEquals(emptyList(), RelevanceFilter.filter(listOf(dock), SearchQuery(text = "IB-3740-C31", category = MarketGroup.GENERAL)))
        // The box itself, written without its 31, is still it.
        val shortName = listing("ICY BOX Externes 4-Fach System 3,5\" SATA USB 3.1 Type-C IB-3740-C")
        assertEquals(1, RelevanceFilter.filter(listOf(shortName), SearchQuery(text = "IB-3740-C31", category = MarketGroup.GENERAL)).size)
        // A module one size down shares everything but the size with the one searched for.
        val crucial = SearchQuery(text = "CT32G4SFD832A", category = MarketGroup.GENERAL)
        assertEquals(emptyList(), RelevanceFilter.filter(listOf(listing("Crucial CT16G4SFD832A 16GB DDR4 3200 SODIMM")), crucial))
        // A seller who never prints the number is left to the market's own search, as before.
        assertEquals(1, RelevanceFilter.filter(listOf(listing("Crucial 32GB DDR4 3200 SO-DIMM Arbeitsspeicher")), crucial).size)
        assertEquals(1, RelevanceFilter.filter(listOf(listing("Crucial DDR4-3200 32GB SODIMM PC4-25600")), crucial).size)
        // eBay, live, for "IB-3805-C31": the maker's other cases, each printing its own code.
        val icyBox = SearchQuery(text = "IB-3805-C31", category = MarketGroup.GENERAL)
        assertEquals(emptyList(), RelevanceFilter.filter(listOf(
            listing("ICY BOX IB-287-C31 2,5\" HDD SSD Gehäuse USB 3.1 Typ-C IP66 Wasserdicht"),
            listing("ICY BOX IB-1807MT-C31 Externes Gehäuse zu PCIe NVMe M.2 60959 B-Ware"),
            listing("Seagate FireCuda Gaming HDD 2TB, Tragbare Externe Festplatte (STKL2000400)"),
        ), icyBox))
        // Amazon, live, for "IB-3640SU3": a dock and a PC, while the box itself is sold under a title
        // printing no code at all.
        val quad = SearchQuery(text = "IB-3640SU3", category = MarketGroup.GENERAL)
        assertEquals(listOf("ICY BOX 4-fach Externes Festplattengehäuse für 3,5 Zoll SATA, USB 3.0 & eSATA"), RelevanceFilter.filter(listOf(
            listing("ORICO Dual Festplatten Dockingstation, USB 3.2 Gen 2 (6626C3-C)"),
            listing("HP EliteDesk 800 Mini Desktop PC Core i5-7400T 8GB 240GB"),
            listing("ICY BOX 4-fach Externes Festplattengehäuse für 3,5 Zoll SATA, USB 3.0 & eSATA"),
        ), quad).map { it.title })
    }

    @Test
    fun `a word with an umlaut is the word its sellers write without one`() {
        // Live answers for "lägler hummel": Kleinanzeigen, 2dehands and eBay sellers write the maker
        // as "Lagler", and every one of those machines was dropped as off target.
        val machines = listOf(
            listing("parkettschleifmaschine lagler Hummel jahr 2025", 450000),
            listing("Parket schuurmachine lagler Hummel numatic", 260000),
            listing("Lägler Hummel Bandschleifmaschine 230V", 210000),
        )
        val found = RelevanceFilter.partition(machines, SearchQuery(text = "lägler hummel", category = MarketGroup.GENERAL))
        assertEquals(machines.map { it.title }, found.kept.map { it.title }.sortedBy { t -> machines.indexOfFirst { it.title == t } }, "${found.dropped}")
        // A sander sold for parquet is the sander: Subito's "levigatrice per parquet" was taken for
        // something sold for a parquet floor.
        val subito = listing("Levigatrice per parquet HUMMEL USATA Lagler", 500000)
        assertEquals(1, RelevanceFilter.filter(listOf(subito) + machines, SearchQuery(text = "lägler hummel", category = MarketGroup.GENERAL)).count { it == subito })
    }

    @Test
    fun `a spare part of the machine searched for is not the machine`() {
        // eBay FR, live, for "lägler hummel": Lägler's own spare parts at 370 to 685 €, each naming
        // the maker and the machine, and under a 700 € alert each read as a cheap Hummel.
        val parts = listOf(
            listing("Lagler Hummel Belt Tensioner - Part# P064 - OEM", 37047),
            listing("Hummel Switch Part # P202 - NEW - OEM - Original - Made by Lagler - Hummel", 41561),
            listing("P169 - Lagler - Hummel Fan Insert - Complete Assembly", 57579),
            listing("Hummel Drum #P161 - OEM - BRAND NEW - Lagler Hummel Drum", 68470),
            listing("Lagler P278 Universal Contactor For Hummel Sanding Machines", 48569),
            listing("Lägler Hummel Ersatzteil Antriebsriemen", 4500),
            // Kleinanzeigen and Marktplaats, live: the tensioning unit and a sanding drum.
            listing("Lägler Hummel Spann Aggregat Drive unit", 39000),
            listing("Nieuwe schuurwals Parketschuurmachine Hummel", 42500),
        )
        val machines = listOf(
            listing("parkettschleifmaschine lagler Hummel jahr 2025", 450000),
            listing("Lägler Hummel Bandschleifmaschine 230V", 210000),
        )
        val kept = RelevanceFilter.filter(parts + machines, SearchQuery(text = "lägler hummel", category = MarketGroup.GENERAL))
        assertEquals(machines.map { it.title }.toSet(), kept.map { it.title }.toSet())
    }

    @Test
    fun `a machine named by its maker or by what it is is the machine, and a namesake is not`() {
        // Live answers for "lägler hummel": Vinted and eBay list Goebel's Hummel figurines and Hummel
        // football shirts, while Kleinanzeigen and 2dehands sell the sander without naming Lägler.
        val hummel = SearchQuery(
            text = "lägler hummel",
            aliases = listOf("hummel parkettschleifmaschine", "hummel bandschleifmaschine", "hummel parketschuurmachine"),
            category = MarketGroup.GENERAL,
        )
        val machines = listOf(
            listing("Parkett - Schleifmaschine Hummel", 145000),
            listing("Hummel Parketschuurmachine ,bandschuurmachine", 225000),
            listing("parkettschleifmaschine lagler Hummel jahr 2025", 450000),
        )
        val namesakes = listOf(
            listing("Original Hummel \"Glitzernde Frühlingsboten“ (HUM 2284) beim Spielen mit Swarovski-Kristallen", 30520),
            listing("Goebel Hummel Porzellan Figuren Sammlung", 31570),
            listing("Hummel Dänemark Laudrup Euro 1992", 31570),
            listing("Randschleifmaschine Parkettschleifmaschine", 61500),
            // Vinted, live: kept as a Hummel because three Hummels in the answer were built in 2024.
            listing("Match Attax Euro 2024 Booster Konvolut Restposten 500 Packs à 8 Karten", 36820),
        )
        val built2024 = listOf(
            listing("Lägler Hummel BJ 2024 , Parkett Schleifmaschine", 490000),
            listing("Lägler Hummel Baujahr 2024 top Zustand inkl. MwSt.", 549900),
            listing("Lägler Hummel BJ 2024 Top Zustand mit Garantie", 609900),
        )
        assertEquals((machines + built2024).map { it.title }.toSet(), RelevanceFilter.filter(machines + built2024 + namesakes, hummel).map { it.title }.toSet())
    }

    @Test
    fun `a search is offered the other names sellers write its words under`() {
        // pearl counts bays as "4-fach", MediaMarkt as "4 Einschübe": a market answering only titles
        // carrying "bay" never shows them unless it is asked in their words.
        val named = RelevanceFilter.otherNamings("4-bay festplattengehäuse")
        assertTrue("4-fach festplattengehäuse" in named, "$named")
        assertTrue("4-Einschübe festplattengehäuse" in named, "$named")
        assertTrue("4-bay HDD Gehäuse" in named, "$named")
        assertFalse(named.any { it.equals("4-bay festplattengehäuse", ignoreCase = true) }, "$named")
        // Typed in a shop's words, the search is offered the others, the bay among them.
        assertTrue("4 Bay gehäuse" in RelevanceFilter.otherNamings("4 fach gehäuse"))
        assertEquals(emptyList(), RelevanceFilter.otherNamings("terramaster d4-320"))
    }

    @Test
    fun `a word with an umlaut counts as carried back`() {
        // The web market, live, for "hiyori aoi488 festplattengehäuse": 84 enclosures from other
        // makers, every one titled "Festplattengehäuse", thrown away whole as an answer to some
        // other question because the search's word was folded to "festplattengehaeuse" and the
        // titles were not.
        val enclosures = listOf(
            listing("Festplattengehäuse ICY-BOX IB-377-C31"),
            listing("Festplattengehäuse LogiLink UA0439"),
            listing("Laufwerksgehäuse Festplattenadapter Speichergehäuse"),
            listing("Externes Festplattengehäuse 2,5 Zoll"),
            listing("4 Bay Festplattengehäuse USB-C"),
        )
        val query = SearchQuery(text = "hiyori aoi488 festplattengehäuse", category = MarketGroup.GENERAL)
        assertNull(RelevanceFilter.answeredSomethingElse(enclosures, query))
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
    fun `a computer with the drive in it is not the drive`() {
        // Off the live answer for "2tb m.2 ssd": 29 of 174 results were whole computers with one
        // inside, and being ten to a hundred times the price they are what a search says the thing
        // costs. Every title here is real.
        val kept = search("2tb m.2 ssd", listOf(
            listing("Gaming PC: 9850X3D,RTX 5080,32GB DDR5,X870E-E,2TB M.2 SSD", price = 260000),
            listing("Apple MacBook Pro M2 Max | 14\" | 96GB RAM | 2TB SSD", price = 349900),
            listing("PlayStation 5 Pro PS5 Pro mit M.2 SSD 2TB, Disc-Laufwerk", price = 119900),
            listing("Samsung 990 Evo Plus, NVMe M.2 2280, 2TB SSD", price = 11400),
            listing("Lexar NM790 2TB M.2 SSD", price = 9900),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Samsung 990") }, "the drive itself leads its own title")
        assertTrue(kept.any { it.startsWith("Lexar") }, "so does this one")
        assertTrue(kept.none { it.contains("Gaming PC") }, "a PC with one inside is a PC")
        assertTrue(kept.none { it.contains("MacBook") }, "so is a laptop")
        assertTrue(kept.none { it.contains("PlayStation") }, "so is a console")
    }

    @Test
    fun `an X in a model name is not a count`() {
        // Off the phone, both removed as bulk lots: Biwin X570 and Emtec X200 are single drives
        // whose model names happen to start with an x.
        val kept = search("2tb m.2 ssd", listOf(
            listing("Biwin X570 2tb SSD M.2, Zustand: Neu"),
            listing("Emtec x200 2tb ssd m.2, Zustand: Neu, mit Etikett"),
            listing("Konvolut 20 x SSD M.2 2TB gemischt"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Biwin X570") }, "X570 is what it is called")
        assertTrue(kept.any { it.startsWith("Emtec x200") }, "so is x200")
        assertTrue(kept.none { it.startsWith("Konvolut") }, "twenty of them is a lot")
    }

    @Test
    fun `a size sold in two sticks is still that size`() {
        val kept = search("2tb m.2 ssd", listOf(
            listing("Transcend MTE400S M.2 SSD 2TB (2x 1TB)"),
            listing("Fanxiang M.2 SSD 256GB 512GB 1TB 2TB PCIe"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Transcend") }, "two times one terabyte is two terabytes")
        assertTrue(kept.none { it.startsWith("Fanxiang") }, "a row of sizes is not")
    }

    @Test
    fun `one times a size is one of them, not a lot of them`() {
        // Off the phone: "Crucial CT32G4SFD832A, 32 GB, 1 x 32 GB, DDR4" — the exact product —
        // was read as a bulk lot of 32 and thrown away, while a real lot says how many it is.
        val kept = search("CT32G4SFD832A", listOf(
            listing("Crucial CT32G4SFD832A, 32 GB, 1 x 32 GB, DDR4, 3200 MHz, 260-pin SO-DIMM"),
            listing("Konvolut 20x Crucial CT32G4SFD832A Module"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Crucial CT32G4SFD832A,") }, "one module of 32 GB")
        assertTrue(kept.none { it.startsWith("Konvolut") }, "twenty of them is a lot")
    }

    @Test
    fun `a module named for the machines it fits is the module`() {
        // Straight off the phone, all of them removed as accessories: the memory itself, an
        // equivalent module sold as a replacement for it, and the same in three languages.
        val kept = search("CT32G4SFD832A", listOf(
            listing("Crucial DDR4 RAM 32GB 3200MHz SODIMM CL22, Arbeitsspeicher für Laptop CT32G4SFD832A"),
            listing("32GB DDR4 PC4-25600 SODIMM (Replacement for Crucial CT32G4SFD832A)"),
            listing("32 GB DDR4 PC4-25600 SODIMM (sostituzione per Crucial CT32G4SFD832A)"),
            listing("OWC 32GB Replacement for Crucial CT32G4SFD832A"),
            listing("RAM para portátil Crucial CT32G4SFD832A 32 GB DDR4 3200 MHz"),
            listing("32 GB DDR4 PC4-25600 SODIMM (Reemplazo para Crucial CT32G4SFD832A)"),
            listing("Schutzhülle für Crucial CT32G4SFD832A"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Crucial DDR4 RAM") }, "which laptops it fits is not what it is")
        assertEquals(6, kept.size, "a replacement for the module is a module, in any language")
        assertTrue(kept.none { it.startsWith("Schutzhülle") }, "a case for it is still a case")
    }

    @Test
    fun `a case for the headphones is not the headphones`() {
        // Live, for "WH-1000XM5": a storage case at 15 euro, a replacement headband at 18 and an
        // aftermarket battery at 20 led a list whose headphones sit around 160, all of them
        // carrying the model number because that is what they fit.
        val kept = search("WH-1000XM5", listOf(
            listing("Sony WH-1000XM5 Aufbewahrungshülle grau", price = 1500),
            listing("Sony WH-1000XM5 Kopfband 16-Pin Kunststoff", price = 1800),
            listing("ERYNK WH-1000XM5/723741 Ersatz Akku kompatibel mit Sony WH-1000XM5", price = 2000),
            listing("Sony WH-1000XM5 Bluetooth Kopfhörer schwarz", price = 21900),
            listing("Sony WH-1000XM5 Kopfhörer mit Tragetasche", price = 23000),
        )).map { it.title }
        assertTrue(kept.any { it.endsWith("Kopfhörer schwarz") }, "the headphones themselves")
        assertTrue(kept.any { it.endsWith("mit Tragetasche") }, "a bag that comes with them is not the subject")
        assertTrue(kept.none { it.contains("Aufbewahrungshülle") }, "a case is a case")
        assertTrue(kept.none { it.contains("Kopfband") }, "so is a spare headband")
        assertTrue(kept.none { it.contains("Ersatz Akku") }, "so is an aftermarket battery")
    }

    @Test
    fun `a case, box or screen glass for the phone is not the phone`() {
        // Live, for "Pixel 10 Pro XL": cases written in French, Italian or as a German plural, an
        // empty box and screen glass led the list at under 15 euro, kept as phones.
        val kept = search("Pixel 10 Pro XL", listOf(
            listing("Coque Google pixel 10 pro XL", price = 175),
            listing("Coques pixel 10 pro xl lot de 2", price = 400),
            listing("Custodie Pixel 10 Pro XL", price = 500),
            listing("Handyhüllen Pixel 10 Pro XL", price = 700),
            listing("Boite Google Pixel 10 pro XL noire", price = 910),
            listing("LK 2 Stück für Google Pixel 10 Pro XL für Panzerglas", price = 1188),
            listing("Google Pixel 10 Pro XL Schutzglas Kameraschutz", price = 999),
            listing("Spigen Nano Pop Google Pixel 10 Pro XL", price = 1540),
            listing("Google Pixel 10 pro XL hoesje", price = 1645),
            listing("Telefoonhoesjes Google pixel 10 pro XL", price = 1540),
            listing("Google Pixel 10 Pro XL Display Reparatur EXPRESS Heidelberg", price = 39900),
            listing("Google pixel 10 pro XL Emty Box!!", price = 1645),
            listing("Lot étui de téléphone Google pixel 10 pro xl", price = 1645),
            listing("Das Praxisbuch Google Pixel 10 & Pixel 10 Pro/XL - Anleitung für Einsteiger", price = 1950),
            listing("Spigen GLAS.tR EZ Fit Privacy Screen Protector für Google Pixel 10 Pro XL, 2er-Pack", price = 2090),
            listing("Benks Pixel 10 Pro XL", price = 2170),
            listing("Otterbox Defender Pro XT mit Pixelsnap für Google Pixel 10 Pro XL Dark Side", price = 3011),
            listing("Google Pixel 10 Pro XL Backcover Obsidian", price = 3499),
            listing("Google Pixel 10 Pro XL 256 GB Obsidian", price = 62000),
            listing("Google Pixel 10 Pro XL 512 GB mit Panzerglas und Hüllen", price = 70000),
            listing("Google Pixel 10 Pro XL 256 GB inkl. Spigen Case und OVP", price = 64000),
        )).map { it.title }
        assertEquals(listOf(
            "Google Pixel 10 Pro XL 256 GB Obsidian",
            "Google Pixel 10 Pro XL 512 GB mit Panzerglas und Hüllen",
            "Google Pixel 10 Pro XL 256 GB inkl. Spigen Case und OVP",
        ), kept)
    }

    @Test
    fun `a search for a part number keeps the part whatever kind of part it is`() {
        // Live, for "16131179011": eBay returned twenty listings of that BMW E30 fuel cap vent
        // cover, and all but one were dropped as accessories for calling it an "Abdeckung".
        val titles = listOf(
            "ORIGINAL BMW Abdeckung Tankdeckel Entlüftungsschlauch 3er E30 - 16131179011",
            "Original BMW 16131179011 - Abdeckung 3er",
            "Neu Original BMW Cover 16131179011",
            "BMW E30 Abdeckung Tankentlüftung 16 13 1 179 011",
            "BMW E30 3er Tankdeckel Schlauch Atemleitung Abdeckung Neu 1179011",
        )
        for (q in listOf("16131179011", "16 13 1 179 011")) {
            val kept = search(q, titles.map { listing(it, price = 11998) }).map { it.title }
            assertEquals(titles, kept, "searched as $q")
        }
        // A size is no part number: a case for a 256 GB phone is still a case.
        assertTrue(search("Pixel 9 Pro XL 256 GB", listOf(listing("Pixel 9 Pro XL Hülle", price = 900))).isEmpty())
    }

    @Test
    fun `a part number search keeps the part described the way its numbered listings describe it`() {
        // eBay's whole answer to "16131179011" on 2026-10-11. Replicas and used originals rarely
        // print the number; the listings that do say the part is an E30 "Abdeckung" or "Cover",
        // so a listing described that way is the part. Another number is another part, and a
        // diesel cap for an F20 is no E30 cover.
        val answer = listOf(
            "ORIGINAL BMW Abdeckung Tankdeckel Entlüftungsschlauch 3er E30 - 16131179011",
            "ORIGINAL BMW Abdeckung für Tankdeckel Entlüftungsschlauch 3er E30 16131179011",
            "BMW E30 Abdeckung Tankentlüftung 16131179011 – 3er Serie Ersatzteil",
            "Original BMW Abdeckung 16131179011",
            "Neu Original BMW Cover 16131179011",
            "BMW E30 3er Tankdeckel Schlauch Atemleitung Abdeckung Neu 1179011",
            "BMW 3 E30 Kraftstoffentlüftungsleitungsabdeckung 316 1.8 Benzin 16131179011",
            "Neu BMW 3 E30 Fuel Breather Line Decken 316 1.8 Benzin-16131179011 OEM Original",
            "BMW 3 E30 Fuel Breather Line Cover 316 1.8 Petrol 16131179011",
            "BMW 3 E30 Kraftstoffentlüftungsleitung Abdeckung 316 1.8 Benzin 16131179011",
            "BMW E30 3 Abdeckung 16131179011 Original",
            "BMW GENUINE 3 SERIES E30 FUEL TANK FILLER HOSE BREATHER LINE COVER 1179011",
            "BMW E30 Original Kohlefaser Tankentlüftung Abdeckung 16131179011",
            "BMW NEUER ORIGINAL F20 F30 F10 G20 G31 G11 DIESEL TANK DECKEL ABDECKUNG",
            "Abdeckung Radhaus Tankrohr Tank passend für E30",
            "Schutzabdeckung für BMW 3er E30 Kraftstoffrohr Cover Kunststoff",
            "OEM BMW E30 Abdeckung Tankdeckel Entlüftungsschlauch Schutzblech 16131176746",
            "BMW NEW GENUINE F20 F30 F10 G20 G31 G11 DIESEL FUEL TANK FILLER PIPE CAP COVER",
            "CAtuned Fuel Breather Line Cover PLASTIC Compatible with E30",
            // What a search for "E30 Abdeckung" brought back: covers of the same car that are not
            // this cover, and covers for a Motorola Moto E30.
            "Bmw e30 Lautsprecher Abdeckung schwarz",
            "BMW E30 TOURING ABDECKUNG KOFFERRAUM ( 17€ INKL DHL VERSICHERT )",
            "Abdeckung schwarz für Fensterkurbel vorne/hinten BMW E30 / E34",
            "Betterfon Motorola Moto E20 E30 E40 Carbon Hülle Outdoor Case Back Cover Handy Tasche",
            // And what "E30 Abdeckung Tankdeckel" brought back: tank caps.
            "Tankdeckel Verschluss Kraftstoffbehälter für BMW 3er E21 E30 E36 E46",
            "Gummidichtung Tankdeckel Verschluss Kraftstoffbehälter für Audi VW BMW E21 E30",
        )
        val kept = search("16131179011", answer.map { listing(it, price = 5200) }).map { it.title }.toSet()
        assertTrue(kept.none { "Lautsprecher" in it || "KOFFERRAUM" in it || "Fensterkurbel" in it || "Motorola" in it || "Verschluss" in it }, "other covers: $kept")
        assertTrue("Abdeckung Radhaus Tankrohr Tank passend für E30" in kept, "an ABS replica without the number")
        assertTrue("CAtuned Fuel Breather Line Cover PLASTIC Compatible with E30" in kept)
        assertTrue(answer.filter { "1179011" in it }.all { it in kept }, "every numbered listing")
        assertTrue(kept.none { "F20" in it }, "a diesel cap for other models")
        assertTrue(kept.none { "16131176746" in it }, "another part number is another part")
    }

    @Test
    fun `a Pixel written XL Pro is the Pro XL`() {
        // Live, for the saved "Pixel 9 Pro XL 256 GB": a Hazel phone at 500 euro was dropped as
        // off target for its seller writing "9 XL Pro".
        val kept = search("Pixel 9 Pro XL 256 GB", listOf(listing("Google Pixel 9 XL Pro Hazel 256 GB", price = 50000)))
        assertEquals(1, kept.size)
    }

    @Test
    fun `a part that says what it goes into is still the part`() {
        // Live, for the part number "CT32G4SFD832A": seven exact matches were dropped as whole
        // machines because their titles say "Laptop RAM", and Geizhals lists the same module
        // without the number at all.
        // Each market is judged on its own answer, which is how the filter is called, so eBay —
        // where every listing writes the number — and Geizhals — where none does — are two sets.
        // A part number on its own says nothing about where the words sit, so a title naming a
        // machine cannot be told from one naming what the module goes into. Both are kept: losing
        // "Crucial 32GB Notebook DDR4-SODIMM CT32G4SFD832A" — the module itself — is the worse of
        // the two mistakes, and it is what asking with one word used to do.
        val ebay = search("CT32G4SFD832A", listOf(
            listing("Crucial 32GB DDR4-3200 SO-DIMM Laptop RAM CT32G4SFD832A"),
            listing("Crucial 32GB Notebook DDR4-SODIMM CT32G4SFD832A"),
            listing("Nueva Laptop Crucial 32GB DDR4 3200Mhz SODIMM CL22 260Pin CT32G4SFD832A"),
        )).map { it.title }
        assertEquals(3, ebay.size, "every one of them is the module that was searched for")

        // A number is what the search is: the modules a market lists without it may well be the
        // same part, but nothing in their titles says so, and a 16GB module plainly is not.
        // A market that lists the same module without ever printing the number searched its own
        // catalogue, where the number is a field rather than a word, and is left to it.
        val geizhals = search("CT32G4SFD832A", listOf(
            listing("Crucial SO-DIMM 32GB, DDR4-3200, CL22-22-22, 2RX8"),
            listing("Crucial SO-DIMM 16GB, DDR4-3200, CL22"),
            listing("Kingston SO-DIMM 32GB, DDR4-3200, CL22"),
        )).map { it.title }
        assertEquals(3, geizhals.size, "nothing in these titles contradicts the search")
    }

    @Test
    fun `a search for the machine keeps the machine`() {
        // The same listings, asked for as what they are.
        val kept = search("gaming pc rtx 5080", listOf(
            listing("Gaming PC: 9850X3D,RTX 5080,32GB DDR5,X870E-E,2TB M.2 SSD", price = 260000),
        )).map { it.title }
        assertEquals(1, kept.size, "asked for the PC, the PC is the answer")
    }

    @Test
    fun `a market that never writes the category word still answers for it`() {
        // Idealo, live, for "2tb m.2 ssd": it lists the drive by make and size and never writes
        // "SSD", so requiring that word threw away the very drives asked for. The size and the
        // slot are asked for by number, and those it does write.
        val idealo = listOf(
            listing("Intenso M.2 PCIe Premium 2TB"),
            listing("Lexar NM620 2TB M.2"),
            listing("Samsung 980 Pro 2TB M.2"),
            listing("Silicon Power UD90 2TB M.2"),
            listing("Crucial P3 Plus 1TB M.2"),
            listing("Kingston NV3 500GB M.2"),
        )
        val kept = search("2tb m.2 ssd", idealo).map { it.title }
        assertTrue(kept.contains("Lexar NM620 2TB M.2"), "the drive asked for, named the way Idealo names it")
        assertEquals(4, kept.size, "every 2TB M.2 it listed")
        assertTrue(kept.none { it.contains("1TB") || it.contains("500GB") }, "a different size is a different drive")
    }

    @Test
    fun `a van sold as 314CDI is the 314 that was searched for`() {
        // Live, for "sprinter 314": every real van on Kleinanzeigen writes the trim code onto the
        // model number, and the search dropped all of them while keeping books off reBuy whose
        // titles merely start with "Sprinter".
        val kept = search("sprinter 314", listOf(
            listing("Mercedes-Benz Sprinter III Pritsche 314CDI RW Heck"),
            listing("Mercedes-Benz Sprinter 314CDI Kasten 3,5t FWD L1H1 Kamera"),
            listing("Mercedes-Benz Sprinter 316 CDI Kasten"),
            listing("Sprinterjahre. Glanz und Schatten einer Radsportkarriere"),
            listing("Mercedes Sprinter 3140 Sonderaufbau"),
        )).map { it.title }
        assertTrue(kept.any { it.contains("Pritsche 314CDI") }, "the trim code glues onto the model number")
        assertTrue(kept.any { it.contains("314CDI Kasten") }, "so does this one")
        assertTrue(kept.none { it.contains("316") }, "a 316 is a different van")
        assertTrue(kept.none { it.startsWith("Sprinterjahre") }, "a book about cycling is not a van")
        assertTrue(kept.none { it.contains("3140") }, "and 314 does not reach 3140")
    }

    @Test
    fun `a listing selling five sizes is not an offer of the one asked for`() {
        // Off the live answer for "2tb m.2 ssd": these took every cheapest place in the list at a
        // median of 78 euro against 220 for the rest, because the price on the card belongs to the
        // smallest size in the title.
        val kept = search("2tb m.2 ssd", listOf(
            listing("Fanxiang M.2 2280 NVMe Interne SSD 256GB 512GB 1TB 2TB 4TB PCIE", price = 5000),
            listing("verschiedene SSD Festplatten 2,5\" M2 SATA NVME 120 240 250 500GB 2TB", price = 1400),
            listing("Netac 2TB 1T 500GB 250GB Interne Festplatte SSD M.2 2280 NVMe", price = 4600),
            listing("Samsung SSD 990 PRO 2TB, M.2 2280 / M-Key / PCIe 4.0 x4", price = 31800),
            listing("Lexar NM790 2TB M.2 SSD", price = 9900),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Samsung SSD 990 PRO 2TB") }, "one size, one price")
        assertTrue(kept.any { it.startsWith("Lexar") }, "same")
        assertEquals(2, kept.size, "the three that sell a row of sizes are not offers of a 2TB drive")
    }

    @Test
    fun `a drive that states its size twice states one size`() {
        // Also off the live answer: "Air Disk 2TB (2000GB)" is one drive saying the same number
        // two ways, and comparing the sizes as words rather than as numbers threw it out.
        val kept = search("2tb m.2 ssd", listOf(
            listing("Air Disk 2TB (2000GB) m.2 NVME SSD Fast Neu 100%", price = 8900),
            listing("Fanxiang M.2 SSD 256GB 512GB 1TB 2TB PCIe", price = 2800),
            listing("Lexar NM790 2TB M.2 SSD", price = 9900),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Air Disk") }, "2TB and 2000GB are the same size")
        assertTrue(kept.none { it.startsWith("Fanxiang") }, "256GB is not")
    }

    @Test
    fun `a drive that never writes its form factor is still that drive`() {
        // Vinted, Ricardo and Amazon, live: half the M.2 drives on them never write "M.2", and
        // asking for the words they leave out lost the very listings searched for. The size is a
        // different matter, and every one of these carries it.
        val kept = search("2tb m.2 ssd", listOf(
            listing("Lexar Nm790 Ssd 2Tb, Marke: Lexar, Zustand: Neu"),
            listing("WD Blue SN5000 powered by SANDISK 2TB NVMe SSD"),
            listing("Samsung 980 Pro 2TB SSD"),
            listing("WD_BLACK SN850X NVMe SSD 2 TB, bis zu 7.300 MB/s Lesen"),
            listing("SK Hynix PC801 1TB NVMe PCIe 4.0 M.2 SSD"),
            listing("Kioxia 512GB Festplatte M.2 SSD 2280 NVMe PCIe"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Lexar Nm790") }, "a 2TB drive written without the slot")
        assertTrue(kept.any { it.startsWith("Samsung 980 Pro") }, "so is this one")
        assertEquals(4, kept.size, "every 2TB one of them")
        assertTrue(kept.none { it.contains("1TB") || it.contains("512GB") }, "a different size is a different drive")
    }

    @Test
    fun `a size is the same size however it is written`() {
        // Off the live answers for "2TB NVMe": each of these is a 2TB NVMe drive, and each was
        // thrown out as off target for writing the size in gigabytes or the bus instead of "NVMe".
        val kept = search("2TB NVMe", listOf(
            listing("Crucial P310 NVMe 2000GB"),
            listing("Kingston KC3000 PCIe 4.0 NVMe SSD 2048GB, M.2 2280 / M-Key / PCIe 4.0 x4"),
            listing("Samsung SSD 990 PRO 2TB, M.2 2280 / M-Key / PCIe 4.0 x4"),
            listing("SSD Samsung 990 NVMe 2To - M.2 PCle 4.0"),
            listing("Western Digital WD Green SN350 NVMe SSD 2 TB M.2 Solid State Disk 2.000 GB Intern"),
            listing("Samsung SSD 990 PRO 1TB, M.2 2280 / M-Key / PCIe 4.0 x4"),
            listing("Samsung Portable SSD T9, 2 TB, USB 3.2 Gen.2x2, Extern"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Crucial P310") }, "2000GB is 2TB")
        assertTrue(kept.any { it.startsWith("Kingston KC3000") }, "and so is 2048GB")
        assertTrue(kept.any { it.startsWith("Samsung SSD 990 PRO 2TB") }, "a PCIe M.2 drive is an NVMe drive")
        assertTrue(kept.any { it.startsWith("SSD Samsung 990 NVMe 2To") }, "French writes To")
        assertTrue(kept.any { it.startsWith("Western Digital") }, "2.000 GB is the same 2TB again, not a second size")
        assertTrue(kept.none { it.contains("1TB") }, "a 1TB drive is not 2TB")
        assertTrue(kept.none { it.contains("Portable") }, "a USB drive is not NVMe")
    }

    @Test
    fun `a size typed apart from its unit finds it glued`() {
        // eBay, live, for "Pixel 9 Pro XL 256 GB": every phone that wrote "256GB" was thrown out
        // as off target, and only those that wrote "256 GB" apart were kept.
        val kept = search("Pixel 9 Pro XL 256 GB", listOf(
            listing("Google Pixel 9 Pro XL 256GB Hazel"),
            listing("Google Pixel 9 Pro XL Dual SIM 256GB obsidian"),
            listing("Google Pixel 9 Pro XL 256GB Smartphone Grau Slate GZC4K FCC ID A4RGZC4K"),
            listing("Google Pixel 9 Pro XL 5G Dual-SIM 256 GB grau Smartphone Handy Mobile Android"),
            listing("Google Pixel 9 Pro XL 256 GB 16 GB RAM Dunkelgrau 5G Smartphone Gebraucht"),
            listing("Google Pixel 9 Pro XL 128GB Hazel"),
            listing("Google Pixel 9 Pro 256GB Schwarz"),
        )).map { it.title }
        assertTrue(kept.any { it == "Google Pixel 9 Pro XL 256GB Hazel" }, "256GB is 256 GB")
        assertTrue(kept.any { it.contains("Dual SIM 256GB obsidian") })
        assertTrue(kept.any { it.contains("Slate") })
        assertTrue(kept.any { it.contains("Dual-SIM 256 GB") })
        assertTrue(kept.none { it.contains("128GB") }, "a 128GB phone is not 256GB")
    }

    @Test
    fun `an alias is held to its size and model like the search itself`() {
        // The saved Pixel search, live: "Pixel 9 Pro XL 256 GB" with the alias "Pixel 9 Pro XL
        // 256GB" kept 128GB phones, a 9 Pro and a 9 Pro Fold on four words of five.
        val kept = search("Pixel 9 Pro XL 256 GB", aliases = listOf("Pixel 9 Pro XL 256GB"), listings = listOf(
            listing("Google Pixel 9 Pro XL 256GB Hazel"),
            listing("Google Pixel 9 Pro XL 5G Dual-SIM 256 GB grau Smartphone Handy Mobile Android"),
            listing("Google Pixel 9 Pro XL 128GB Hazel"),
            listing("Google Pixel 9 Pro XL 128GB Obsidian, !!Display Burn-In!! -used"),
            listing("Google Pixel 9 Pro 256 GB Schwarz ,OVP & Ladekabel, Zubehörpaket"),
            listing("Google Pixel 9 pro fold . 16+256Gb . Wie neu . Top Zustand 100% ！"),
        )).map { it.title }
        assertEquals(
            listOf("Google Pixel 9 Pro XL 256GB Hazel", "Google Pixel 9 Pro XL 5G Dual-SIM 256 GB grau Smartphone Handy Mobile Android"),
            kept.sorted(),
        )
    }

    @Test
    fun `a phone described by its battery or its accessories is the phone`() {
        // Kleinanzeigen, live, for "Pixel 9 Pro XL 256 GB": each of these is the phone, and each
        // was thrown out as an accessory or a consumable for a word said about it.
        val listings = listOf(
            listing("Google Pixel 9 Pro XL 256 GB – 16 GB RAM Akku 93% Gebraucht"),
            listing("Google Pixel 9 pro XL 256 gb 94 Prozent Batterie"),
            listing("Pixel 9 Pro XL 256 GB Bundle Neues Mainboard durch Garantietausch"),
            listing("Google Pixel 9 Pro XL 256 GB mit viel Zubehör"),
            listing("Google Pixel 9 Pro XL 256GB Hazel + Zubehör *OVP wie NEU*"),
            listing("Google Pixel 9 Pro XL Hülle Silikon"),
            listing("Google Pixel 9 Pro XL Akku Ersatz"),
        )
        val result = RelevanceFilter.partition(listings, SearchQuery(text = "Pixel 9 Pro XL 256 GB", category = MarketGroup.GENERAL), emptyList())
        val kept = result.kept.map { it.title }
        assertEquals(5, kept.size, kept.toString())
        assertTrue(kept.none { it.contains("Hülle") || it.contains("Ersatz") }, "a case and a battery carry no storage")
    }

    @Test
    fun `a size typed with a space still marks a row of sizes`() {
        val result = RelevanceFilter.partition(listOf(
            listing("Google Pixel 9 Pro XL 128GB 256GB 512GB 1TB Rose Grau Weiß Schwarz - WIE NEU"),
            listing("Google Pixel 9 Pro XL 5G 128/256GB Smartphone Handy Mobile Android 6.8\" Schwarz"),
            listing("Google Pixel 9 Pro XL 256GB Hazel"),
            // Memory written beside the storage is one phone, not a second size.
            listing("Google Pixel 9 Pro XL 16GB+256GB OBSIDIAN BLACK Unlocked Android Mobile Phone"),
            listing("Google Pixel 9 Pro XL 5G Hazel 256GB + 16GB Dual-SIM Unlocked SIMFree NEU"),
            listing("Google Pixel 9 Pro XL 12/256GB Obsidian"),
            listing("Google Pixel 9 Pro XL 256 GB – 16 GB RAM Akku 93% Gebraucht"),
            // A buyer's ad.
            listing("Google Pixel 9 oder 10 Pro XL mind. 256GB"),
        ), SearchQuery(text = "Pixel 9 Pro XL 256 GB", aliases = listOf("Pixel 9 Pro XL 256GB"), category = MarketGroup.GENERAL), emptyList())
        assertEquals(5, result.kept.size, result.kept.map { it.title }.toString())
        assertEquals(
            listOf(DropReason.ONE_OF_SEVERAL_SIZES, DropReason.ONE_OF_SEVERAL_SIZES, DropReason.WANTED_AD),
            result.dropped.map { it.reason },
            result.dropped.map { it.listing.title }.toString(),
        )
    }

    @Test
    fun `a model the rest of the answer calls NVMe is NVMe though its title does not say so`() {
        // Vinted and Ricardo, live, for "1TB NVMe": the 990 PRO and the 980 PRO were thrown out for
        // leaving out a word every other seller of the same drive writes.
        val kept = search("1TB NVMe", listOf(
            listing("Samsung 990 PRO NVMe M.2 SSD 1TB"),
            listing("Samsung SSD 990 PRO 1TB NVMe PCIe 4.0"),
            listing("Samsung 990 PRO 1TB NVMe Interne SSD"),
            listing("Samsung 980 PRO 1TB NVMe M.2"),
            listing("Samsung 980 PRO NVMe 1TB PCIe 4.0"),
            listing("WD Black SN850X 1TB NVMe"),
            listing("Crucial P3 Plus 1TB NVMe"),
            listing("Kingston NV3 1TB NVMe PCIe 4.0"),
            listing("Lexar NM790 1TB NVMe"),
            listing("Samsung 990 PRO SSD 1TB"),
            listing("SSD Samsung 980 Pro 1TB"),
            // A SATA drive shares only the category words with them, and stays out.
            listing("Crucial MX500 1TB M.2 SATA"),
            listing("Crucial MX500 1TB 2,5 Zoll"),
            listing("Samsung 870 EVO 1TB"),
        )).map { it.title }
        assertTrue("Samsung 990 PRO SSD 1TB" in kept, kept.toString())
        assertTrue("SSD Samsung 980 Pro 1TB" in kept, kept.toString())
        assertTrue(kept.none { it.contains("MX500") || it.contains("870 EVO") }, kept.toString())
    }

    @Test
    fun `a 4-bay enclosure is found however its bays are written`() {
        // Live answers for "4 bay festplattengehäuse" and "yottamaster 4 bay": each of these is
        // a 4-bay enclosure and each was thrown off as off target.
        val kept = search("4 bay festplattengehäuse", listOf(
            listing("Renkforce RF-6647598 3,5\" Festplattengehäuse 4Bay USB-A SATA 6Gb/s 1782976"),
            listing("FANTEC QB-35US3-6G Externes 4-fach Festplattengehäuse (für 4x 3,5 Zoll SATA)"),
            listing("Fantec QB-35U31 – 4-Bay HDD Gehäuse – USB 3.1 Gen2 (10Gbit/s) – mit Zubehör"),
            listing("4 Schacht Festplattengehäuse mit Lüfter für 2.5/3.5“ SATA HDD/SSD"),
            listing("Conrad Festplattengehäuse 4-Bay USB gekühlt HD3402JSC HDD SATA"),
            // Other counts of bays stay out.
            listing("ORICO 5 Bay Festplattengehäuse, USB 3.1 Typ-C"),
            listing("6 Schacht Festplattengehäuse mit Lüfter für 2.5/3.5“ SATA HDD/SSD"),
            listing("MAIWO Dual Bay Raid Gehäuse für 3,5\" SATA HDD Festplattengehäuse mit Lüfter"),
        )).map { it.title }
        assertEquals(5, kept.size, kept.toString())
        assertTrue(kept.none { it.startsWith("ORICO") || it.startsWith("6 Schacht") || it.startsWith("MAIWO") }, kept.toString())
        val yotta = search("yottamaster 4 bay", listOf(
            listing("Yottamaster DF4RU3 4-Bay RAID Gehäuse USB 3.0 3,5/2,5 HDD SSD RGB-Lüfter"),
            listing("4 Fach RAID FestplattenGehäuse, Yottamaster Spielorientiert Aluminium Externes HDD Gehäuse"),
            listing("Yottamaster 4 Bay USB C Festplattengehäuse Ghäuse Für 2,5/3,5\" SATA SSD HDD 88TB"),
            listing("Yottamaster 5 Bay RAID USB C Festplattengehäuse Aluminium"),
        )).map { it.title }
        assertEquals(3, yotta.size, yotta.toString())
        assertTrue(yotta.none { it.contains("5 Bay") }, yotta.toString())
        // A generation after its word is still held to it: Splatoon 2 is not a Switch 2.
        val switch = search("nintendo switch 2", listOf(
            listing("Splatoon 2 Nintendo Switch"),
            listing("Nintendo Switch 2 Konsole"),
        )).map { it.title }
        assertEquals(listOf("Nintendo Switch 2 Konsole"), switch)
    }

    @Test
    fun `a count still counts the word typed after it when the market rarely writes that word`() {
        // The web, live, for "yottamaster 4 bay": a shop's page listed twenty unrelated products,
        // so "bay" was no longer required, and the 4 was then held to "yottamaster 4" written
        // together. Both real enclosures were thrown out.
        val junk = (1..20).map { listing("Faltbarer Kleiderschrank Organizer Modell $it") }
        val kept = search("yottamaster 4 bay", junk + listOf(
            listing("Yottamaster Y-Focus 4 Bay External Hard Drive Enclosure"),
            listing("Yottamaster Y-Pioneer 4 Bay External Hard Drive Enclosure"),
            listing("YOTTAMASTER Superspeed USB 3.1 PCI-Express Card"),
        )).map { it.title }
        assertEquals(listOf("Yottamaster Y-Focus 4 Bay External Hard Drive Enclosure", "Yottamaster Y-Pioneer 4 Bay External Hard Drive Enclosure"), kept.sorted())
    }

    @Test
    fun `a compound written as its last part is found beside the other words`() {
        // Kleinanzeigen, live: "Verkaufe Icy Box 4 Bay Gehäuse" is a 4-bay drive enclosure.
        val kept = search("4 bay festplattengehäuse", listOf(
            listing("Verkaufe Icy Box 4 Bay Gehäuse"),
            listing("Fantec 4-Bay USB-3.0-Festplattengehäuse mit Netzteil"),
            listing("Icy Box 2 Bay Gehäuse"),
        )).map { it.title }
        assertEquals(listOf("Verkaufe Icy Box 4 Bay Gehäuse", "Fantec 4-Bay USB-3.0-Festplattengehäuse mit Netzteil").sorted(), kept.sorted())
        // On its own the last part says nothing: a search of one word stays held to that word.
        val alone = search("parkettschleifmaschine", listOf(
            listing("Parkettschleifmaschine Lägler Hummel"),
            listing("Schleifmaschine Bosch"),
        )).map { it.title }
        assertEquals(listOf("Parkettschleifmaschine Lägler Hummel"), alone)
    }

    @Test
    fun `a compound is searched shorter only by a last part sellers write alone`() {
        val seen = setOf(" gehäuse", " schleifmaschine", " maschine")
        val background = io.github.tieo.arbay.crawler.QueryVariants.TermBackground { t -> if (t.lowercase() in seen) 0.01 else 0.0 }
        assertEquals(listOf("4 bay gehäuse"), io.github.tieo.arbay.crawler.QueryVariants.compoundHeads("4 bay festplattengehäuse", background))
        assertEquals(listOf("schleifmaschine lägler"), io.github.tieo.arbay.crawler.QueryVariants.compoundHeads("parkettschleifmaschine lägler", background))
        assertEquals(emptyList(), io.github.tieo.arbay.crawler.QueryVariants.compoundHeads("festplattengehäuse", background))
        // A model names one product; its long word is a make. Live, "terramaster d4-320" was
        // searched as "master d4-320".
        val master = io.github.tieo.arbay.crawler.QueryVariants.TermBackground { t -> if (t == " master") 0.01 else 0.0 }
        assertEquals(emptyList(), io.github.tieo.arbay.crawler.QueryVariants.compoundHeads("terramaster d4-320", master))
    }

    @Test
    fun `a part number's revision suffix is not a model word`() {
        // eBay.fr, live, for "2TB NVMe": a 2.5" SATA hard disk with part number 1FM101-002 was kept
        // as NVMe because most other listings ending in "-002" were NVMe drives.
        val kept = search("2TB NVMe", listOf(
            listing("Dell 2TB NVMe PCIe SSD 0ABCD1-002"),
            listing("HPE 2TB NVMe SSD P10224-002"),
            listing("Lenovo 2TB NVMe M.2 SSD 5SD0Z-002"),
            listing("Samsung 990 PRO 2TB NVMe"),
            listing("HDD Seagate 2TB 2.5\" 6G SATA 1FM101-002 ST2000NX0253"),
        )).map { it.title }
        assertTrue(kept.none { it.startsWith("HDD Seagate") }, kept.toString())
        assertTrue("Samsung 990 PRO 2TB NVMe" in kept, kept.toString())
    }

    @Test
    fun `a heatsink for a drive is not the drive, a drive with one is`() {
        // eBay, live, for "2TB M.2": the cooler was kept as a 2TB drive.
        val kept = search("2TB M.2", listOf(
            listing("MSI SPATIUM Kühlkörper für M580 PCIe 5.0 NVMe M.2 2TB FROZR"),
            listing("Seagate FireCuda 530 2TB M.2 NVMe PCIe Gen4 SSD mit Kühlkörper"),
            listing("Fanxiang M.2 2280 2TB NVMe SSD Heatsink PCIe 4.0 PS5"),
            listing("Samsung 990 PRO 2TB M.2 NVMe"),
        )).map { it.title }
        assertTrue(kept.none { it.startsWith("MSI SPATIUM") }, kept.toString())
        assertTrue(kept.any { it.startsWith("Seagate") } && kept.any { it.startsWith("Fanxiang") }, kept.toString())
    }

    @Test
    fun `a model name written apart is one drive, not a lot`() {
        // Kleinanzeigen, live, for "2TB NVMe": "sn 850 x" was read as a lot of 850.
        val kept = search("2TB NVMe", listOf(
            listing("WD black sn 850 x 2tb nvme"),
            listing("WD Black SN850X 2TB NVMe"),
            listing("Samsung 990 PRO 2TB NVMe"),
            listing("Lot 20 x Samsung 2TB NVMe"),
        )).map { it.title }
        assertTrue("WD black sn 850 x 2tb nvme" in kept, kept.toString())
        assertTrue(kept.none { it.startsWith("Lot 20") }, kept.toString())
    }

    @Test
    fun `a drive is not a sanding sheet, a speed is not a size, and a card size is not a lot`() {
        val kept = search("4TB NVMe", listOf(
            listing("Patriot P400 V4 4TB interne SSD - NVMe PCIe Gen 4x4 - M.2 2280"),
            listing("4Tb Western Digital WD_BLACK SN850X SSD 4TB 7300MB warranty top NVMe"),
            listing("Samsung 990 EVO Plus 4TB NVMe (M.2 22 x 80 mm)"),
            listing("Western Digital PC SN810 4TB NVMe SSD"),
            listing("Samsung 990 PRO 4TB NVMe"),
            listing("SSD WD_BLACK SN850X Gaming NVMe M.2 | PCIe 4.0 x4 | 1 - 4 TB"),
        )).map { it.title }
        assertEquals(5, kept.size, kept.toString())
        assertTrue(kept.none { it.contains("1 - 4 TB") }, "a range is still priced at its smallest")
    }

    @Test
    fun `a small answer is judged by what the other answers showed`() {
        // Ricardo, live, for "4TB M.2": its 990 PROs did not write "M.2", and an answer of a few
        // listings cannot show that a 990 is one. The bigger answers before it did.
        val evidence = io.github.tieo.arbay.crawler.ModelWordEvidence(null)
        io.github.tieo.arbay.crawler.ModelWordEvidence.installed = evidence
        try {
            val big = (1..12).map { listing("Samsung 990 PRO 4TB M.2 NVMe Nummer $it") } +
                (1..12).map { listing("Kingston NV3 4TB M.2 2280 Angebot $it") } +
                (1..12).map { listing("WD Black SN850X 4TB M.2 Nummer $it") }
            search("4TB M.2", big)
            val kept = search("4TB M.2", listOf(
                listing("SAMSUNG NVMe 990 PRO 4TB"),
                listing("Samsung 990 Pro 4000 GB"),
                listing("Crucial MX500 4TB SATA 2,5 Zoll"),
                listing("Seagate IronWolf 4TB NAS"),
                listing("WD Black SN850X 4TB M.2"),
            )).map { it.title }
            assertTrue("SAMSUNG NVMe 990 PRO 4TB" in kept, kept.toString())
            assertTrue("Samsung 990 Pro 4000 GB" in kept, kept.toString())
            assertTrue(kept.none { it.contains("MX500") || it.contains("IronWolf") }, kept.toString())
        } finally {
            io.github.tieo.arbay.crawler.ModelWordEvidence.installed = null
        }
    }

    @Test
    fun `a drive named by slot, said to fit a console, or beside RAM is still the drive`() {
        val kept = search("1TB SSD", listOf(
            listing("Lexar NM790 1TB, M.2 2280 / M-Key / PCIe 4.0 x4"),
            listing("Samsung SSD 990 PRO 1TB, M.2 2280 / M-Key / PCIe 4.0 x4"),
            listing("Crucial SSD P3 Plus 1TB"),
            listing("Ps5 Kompatible Highend SSD Seagate FireCuda 530 1TB M.2 NVMe"),
            listing("Kingston SSD NV3 1TB"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("Lexar NM790") }, "Geizhals never writes SSD under a drive: $kept")
        assertTrue(kept.any { it.startsWith("Ps5 Kompatible") }, "a drive said to fit a PS5 is the drive: $kept")
        val pcs = RelevanceFilter.partition(listOf(
            listing("Gaming PC | Ryzen 5 7500F | 32GB DDR5 | 1TB NVMe"),
            listing("HP ZBook Fury 15.6 G8 - i9-11950H | 32GB | 1TB NVMe | RTX A2000"),
            listing("Samsung 990 PRO 1TB NVMe"),
        ), SearchQuery(text = "1TB NVMe", category = MarketGroup.GENERAL), emptyList())
        assertEquals(listOf("Samsung 990 PRO 1TB NVMe"), pcs.kept.map { it.title })
        assertTrue(pcs.dropped.all { it.reason == DropReason.BUILT_INTO_A_DEVICE }, pcs.dropped.map { it.listing.title to it.reason }.toString())
    }

    @Test
    fun `what takes a drive of the size asked for is not the drive`() {
        // Live answers for "6TB HDD", "10TB HDD", "14TB HDD", "8TB HDD", "4TB HDD", "3TB HDD", "18TB HDD".
        fun reasons(query: String, vararg titles: String) = RelevanceFilter.partition(
            titles.map { listing(it) } + listing("Seagate Exos Festplatte HDD SATA $query"),
            SearchQuery(text = query, category = MarketGroup.GENERAL), emptyList(),
        ).let { r -> r.dropped.associate { it.listing.title to it.reason } to r.kept.map { it.title } }
        val (six, sixKept) = reasons("6TB HDD",
            "PS4 Data Bank HDD Upgrade bis 6TB",
            "AC/DC Adapter for LaCie 2big USB3 TBT2 6TB USB3.0 Professional Hard Disk Drive HDD HD 12V 5A Power Supply Cord Cable")
        assertEquals(listOf("Seagate Exos Festplatte HDD SATA 6TB HDD"), sixKept, six.toString())
        val (ten, tenKept) = reasons("10TB HDD", "PCI-E x1 Controller-Karte mit 8 SATA-3.0-Anschlüssen für 6 Gbit/s Übertragung und 10 TB HDD-Unterstützung für Windows")
        assertEquals(DropReason.ACCESSORY, ten.values.single(), tenKept.toString())
        val (fourteen, _) = reasons("14TB HDD", "JideTech 4K PoE NVR 8-Port, erweiterbar bis zu 20-K-Netzwerk-Videorekorder, passend für 14TB HDD")
        assertEquals(1, fourteen.size, fourteen.toString())
        val (eight, _) = reasons("8TB HDD", "G-Technology G-Raid mit Thunderbold Gehäuse 8TB ohne HDD!")
        assertEquals(DropReason.ACCESSORY, eight.values.single())
        val (devices, _) = reasons("4TB HDD", "Hiseeu nvr registratore video rete 4tb hdd")
        assertEquals(DropReason.BUILT_INTO_A_DEVICE, devices.values.single())
        val (capsule, _) = reasons("3TB HDD", "Apple Time Capsule mit 3TB HDD WLAN Router & Backup")
        assertEquals(DropReason.BUILT_INTO_A_DEVICE, capsule.values.single())
        val (row, _) = reasons("18TB HDD", "Ultraschnelle 6/14/16/18TB Externe Festplatte USB3.2 Datensicherungsspeicher HDD")
        assertEquals(DropReason.ONE_OF_SEVERAL_SIZES, row.values.single())
        val (lot, _) = reasons("4TB HDD", "4x HDD 2,5 Zoll Festplatte 3x 2TB und 1x 4TB")
        assertEquals(DropReason.ONE_OF_SEVERAL_SIZES, lot.values.single())
        // A drive stating its size plainly beside a limit is still the drive.
        val (_, kept) = reasons("8TB HDD", "WD Red 8TB HDD NAS bis 8 Bays")
        assertTrue("WD Red 8TB HDD NAS bis 8 Bays" in kept, kept.toString())
    }

    @Test
    fun `a model number is required even where no title in the answer writes it`() {
        // Vinted, live, for "Toshiba MG09ACA18TE": remote controls and televisions, none with the number.
        val kept = search("Toshiba MG09ACA18TE", listOf(
            listing("Télécommande Toshiba"),
            listing("Toshiba Smart TV"),
            listing("Toshiba Canvio Partner 4TB External HDD"),
        ))
        assertEquals(emptyList(), kept.map { it.title })
        val drives = search("Toshiba MG09ACA18TE", listOf(
            listing("18TB SATA Festplatte HDD Toshiba MG09ACA18TE"),
            listing("Toshiba MG09 ACA18TE 18 TB"),
            listing("Toshiba MG09 18TB SATA Festplatten HDD"),
        ))
        assertEquals(listOf("18TB SATA Festplatte HDD Toshiba MG09ACA18TE", "Toshiba MG09 ACA18TE 18 TB"), drives.map { it.title })
    }

    @Test
    fun `a size whose digits match once the comma is gone is another size`() {
        val result = RelevanceFilter.partition(listOf(
            listing("Seagate Exos 10E2400 1,2TB SAS 12Gb/s 10K 2,5\" Enterprise HDD ST1200MM0129"),
            listing("Seagate Exos X12 12TB SATA 6Gb/s 256MB 7200 RPM 3.5\""),
            listing("Seagate Exos 12TB (2x 6TB) SATA"),
            listing("Seagate Exos X18 16TB SATA"),
        ), SearchQuery(text = "Exos 12TB", category = MarketGroup.GENERAL), emptyList())
        assertEquals(listOf("Seagate Exos X12 12TB SATA 6Gb/s 256MB 7200 RPM 3.5\"", "Seagate Exos 12TB (2x 6TB) SATA"), result.kept.map { it.title })
        assertEquals(DropReason.ANOTHER_SIZE, result.dropped.first { it.listing.title.contains("1,2TB") }.reason)
        val cisco = RelevanceFilter.partition(listOf(
            listing("NEU Cisco UCS-HD18TB10K4KN 1,8TB 10k 2,5\" SAS 12G Festplatte Disk HDD mit Caddy"),
            listing("Seagate Exos X18 18 TB HDD 3.5 Zoll Hyperscale SATA 6 Gb/s Festplatte"),
        ), SearchQuery(text = "18TB HDD", category = MarketGroup.GENERAL), emptyList())
        assertEquals(listOf("Seagate Exos X18 18 TB HDD 3.5 Zoll Hyperscale SATA 6 Gb/s Festplatte"), cisco.kept.map { it.title })
        val imac = RelevanceFilter.partition(listOf(
            listing("iMac27 3,2 GHz QuadCore Intel i5 NVIDIA GT 755M 1GB 1,12 TB HDD"),
            listing("WD Purple 12TB Festplatte – 3,5 Zoll SATA HDD"),
        ), SearchQuery(text = "12TB HDD", category = MarketGroup.GENERAL), emptyList())
        assertEquals(listOf("WD Purple 12TB Festplatte – 3,5 Zoll SATA HDD"), imac.kept.map { it.title })
    }

    @Test
    fun `two storage tiers written with a space are a row`() {
        val result = RelevanceFilter.partition(listOf(
            listing("Google Pixel 9 Pro XL 5G 256 512GB 6,8 \"OLED 50MP 5060mAh Telefon von FedEx"),
            listing("Nvidia RTX 3070 512GB SSD Gaming PC"),
            listing("Google Pixel 9 Pro XL 512GB Obsidian"),
        ), SearchQuery(text = "Pixel 9 Pro XL 512GB", category = MarketGroup.GENERAL), emptyList())
        assertEquals(listOf("Google Pixel 9 Pro XL 512GB Obsidian"), result.kept.map { it.title })
        assertEquals(DropReason.ONE_OF_SEVERAL_SIZES, result.dropped.first { it.listing.title.contains("FedEx") }.reason)
    }

    @Test
    fun `a model named like a count is the model when the search names it`() {
        val surface = search("Surface Pro X", listOf(
            listing("Microsoft Surface Pro X 13\" SQ2 512GB SSD 16GB RAM [Wi-Fi + 4G] platin"),
            listing("Microsoft Surface Pro X SQ1 8GB 256GB"),
        )).map { it.title }
        assertEquals(2, surface.size, surface.toString())
        val thinkbook = search("ThinkBook 14x", listOf(listing("Lenovo ThinkBook 14x ILL, Luna Grey, Core Ultra 5 226V, 16GB RAM, 512GB SSD")))
        assertEquals(1, thinkbook.size)
        val ryzen = search("Ryzen 9 7950X", listOf(listing("AMD Ryzen 9 7950 X Prozessor boxed")))
        assertEquals(1, ryzen.size)
        // A count is still a count.
        assertTrue(search("512GB NVMe", listOf(listing("11x 512GB M.2 NVMe SSDs – verschiedene Hersteller – getestet"))).isEmpty())
        assertTrue(search("Kabel", listOf(listing("Kabel x 20 Stück"))).isEmpty())
    }

    @Test
    fun `a drive sold for a console or a handheld is the drive`() {
        // Kleinanzeigen and eBay, live: each is a drive, and each was dropped as the machine it fits.
        val kept = search("1TB NVMe", listOf(
            listing("WD PC SN740 1TB m.2 NVME 2230 SSD für Notebook Steam Deck Surface"),
            listing("Western Digital PC SN740 1TB NVMe M.2 SSD 2230"),
            listing("PlayStation | WE_Black SN850 NVMe SSD | 1 TB | Neu & Versiegelt"),
            listing("Valve Steam Deck mit 1 TB NVMe SSD Upgrade Kioxia"),
            listing("Samsung 990 PRO 1TB NVMe"),
        )).map { it.title }
        assertTrue(kept.any { it.startsWith("WD PC SN740") }, kept.toString())
        assertTrue(kept.any { it.startsWith("Western Digital PC SN740") }, kept.toString())
        assertTrue(kept.any { it.startsWith("PlayStation | WE_Black") }, kept.toString())
        assertTrue(kept.none { it.startsWith("Valve Steam Deck mit") }, "the Deck with a drive in it is the Deck: $kept")
        val ps5 = search("4TB SSD", listOf(
            listing("Ps5 Wd black 4tb Festplatte ssd SN850P 850X"),
            listing("PlayStation 5 (FW7.61) + ps5-linux + OVP DualSense + 4TB NVME"),
            listing("Samsung 990 PRO 4TB SSD"),
        )).map { it.title }
        assertTrue(ps5.any { it.startsWith("Ps5 Wd black") }, ps5.toString())
        assertTrue(ps5.none { it.startsWith("PlayStation 5 (FW") }, ps5.toString())
        val deck = search("512GB NVMe", listOf(
            listing("Original steam deck 512gb 2230 nvme ssd"),
            listing("Steam Deck OLED 512GB"),
            listing("Samsung PM9A1 512GB NVMe"),
        )).map { it.title }
        assertTrue(deck.any { it.startsWith("Original steam deck") }, deck.toString())
        assertTrue(deck.none { it == "Steam Deck OLED 512GB" }, deck.toString())
        // Asked for by model, the name ahead of the model is where the title is cut.
        val sn740 = search("SN740 1TB", listOf(
            listing("Western Digital PC SN740 1TB NVMe M.2 SSD 2230"),
            listing("WD PC SN740 1TB M.2 2230 SSD NVMe PCIe4x4 For Steam Deck ASUS ROG Flow X Ally Z1"),
        )).map { it.title }
        assertEquals(2, sn740.size, sn740.toString())
    }

    @Test
    fun `a speed in megabytes is no second size, and WD is Western Digital`() {
        val kept = search("500GB NVMe", listOf(
            listing("Kingston Nv3 SNV3S/500G 500Gb Nvme Ssd M.2 Interface PCIE Gen4 2280 Lesen 6000 Mb"),
        )).map { it.title }
        assertEquals(1, kept.size)
        val wd = search("WD 4TB NVMe", listOf(
            listing("Western Digital Blue SN5000 NVMe 4TB"),
            listing("WD Black SN850X 4TB NVMe"),
        )).map { it.title }
        assertEquals(2, wd.size, wd.toString())
    }

    @Test
    fun `a brand search keeps its model though every title names the model`() {
        // eBay, live, for "Samsung 4TB NVMe": nearly every title is a 990, so the word could not
        // tell one product from the category, and the 990s that left out "NVMe" were lost.
        val kept = search("Samsung 4TB NVMe", (1..10).map { listing("Samsung 990 PRO 4TB NVMe M.2 Angebot $it") } + listOf(
            listing("Samsung 990 PRO 4 TB, SSD"),
            listing("Samsung 990 EVO Plus 4 TB, SSD"),
            listing("Samsung 870 QVO 4TB SATA"),
        )).map { it.title }
        assertTrue("Samsung 990 PRO 4 TB, SSD" in kept, kept.toString())
        assertTrue(kept.none { it.contains("870 QVO") }, kept.toString())
    }

    @Test
    fun `a machine that holds the size asked for is not the part`() {
        // reBuy, live, for "1TB NVMe": not one title on its shelf says NVMe, so the word could not
        // be required there, and the phones and the console came through on their size alone.
        val kept = search("1TB NVMe", listOf(
            listing("Samsung Galaxy S25 Ultra Dual SIM 1TB titanium black"),
            listing("Apple iPhone Air 1TB lichtgold"),
            listing("Microsoft Xbox One X 1 TB [Battlefield V Gold Rush Special Edition]"),
            listing("Samsung 980 Pro 1TB SSD für PC"),
            listing("WD Blue SN5000 1TB"),
            // The rest of the shelf, which does not carry the size, as most of reBuy's answer did not.
            listing("Die Zeitmaschine"),
            listing("Power Semiconductor Drives"),
        )).map { it.title }
        assertEquals(listOf("Samsung 980 Pro 1TB SSD für PC", "WD Blue SN5000 1TB"), kept.sorted())
    }

    @Test
    fun `the size alone is not the search where sellers write the rest`() {
        // Vinted, live, for "4TB NVMe": under half its titles say NVMe, so the word could not be
        // required, and hard disks came through on "4TB" alone.
        val kept = search("4TB NVMe", listOf(
            listing("Seagate Skyhawk 4tb"),
            listing("Disque dur 4To Western Digital Neuf"),
            listing("Festplatte 4Tb"),
            listing("Fikwot FN960 4TB NVMe SSD PCIe 4.0"),
            listing("SSD M2 4TB Nvme PCI-e 4.0 Sabrent Rocket Q4"),
            listing("Ssd 4Tb sn7100 PCIe senza scatola"),
            // The rest of what Vinted sent, which carries neither the size nor the word.
            listing("Pullover"),
            listing("Hard Disk 250GB"),
            listing("Computer disk"),
            listing("Blu-ray-Laufwerk"),
        )).map { it.title }
        assertEquals(
            listOf("Fikwot FN960 4TB NVMe SSD PCIe 4.0", "SSD M2 4TB Nvme PCI-e 4.0 Sabrent Rocket Q4", "Ssd 4Tb sn7100 PCIe senza scatola"),
            kept.sorted(),
        )
    }

    @Test
    fun `a range of sizes is priced at its smallest`() {
        val kept = search("4TB NVMe", listOf(
            listing("SSD Patriot Viper Gaming VP4300 Lite M.2 NVMe 2.0 | PCIe 4.0 x4 | 1 - 4 TB"),
            listing("WD_BLACK SN850X 4TB NVMe"),
        )).map { it.title }
        assertEquals(listOf("WD_BLACK SN850X 4TB NVMe"), kept)
    }

    @Test
    fun `a model number ending in X is one thing, not a lot`() {
        // Live, for "4TB NVMe": the SN850X read as a lot of 850 and was dropped as not one offer.
        val kept = search("4TB NVMe", listOf(
            listing("WDBlack Optimus GX PRO 850X NVMe SSD 4TB"),
            listing("20x NVMe SSD 4TB Restposten"),
        )).map { it.title }
        assertEquals(listOf("WDBlack Optimus GX PRO 850X NVMe SSD 4TB"), kept)
    }

    @Test
    fun `a size asked for by number is not negotiable`() {
        // reBuy, live, for the same search: it carries "SSD" in one listing of its own shelf, which
        // used to be enough to let the whole shelf through unjudged.
        val shelf = listOf(
            listing("Mashed (Playstation 2) [UK Import] PlayStation 2"),
            listing("Power Semiconductor Drives"),
            listing("Solid-State-Drives (SSDs) Modeling"),
            listing("Samsung Galaxy S23 Ultra Dual SIM 1TB cream"),
            listing("DriveClub [Special Edition Steelbook] PlayStation 4"),
            listing("Die Zeitmaschine"),
        )
        assertEquals(emptyList(), search("2tb m.2 ssd", shelf), "none of it is a 2TB M.2 anything")
    }

    @Test
    fun `a title that is only the thing's name is a title`() {
        // Off the live answer: four Kleinanzeigen ads titled exactly "Parkettschleifmaschine" were
        // being thrown away as placeholders, for having one word.
        val kept = search("parkettschleifmaschine", listOf(
            listing("Parkettschleifmaschine", price = 45000),
            listing("Verkaufe", price = 1000),
            listing("Neues Angebot", price = 1000),
            listing("Lägler Hummel Parkettschleifmaschine", price = 60000),
        )).map { it.title }
        assertTrue(kept.contains("Parkettschleifmaschine"), "an ad named after the thing is an ad")
        assertTrue(kept.none { it == "Verkaufe" }, "a title that only says 'selling' names nothing")
        assertTrue(kept.none { it == "Neues Angebot" }, "nor does a market's own placeholder")
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
    fun `a Tiguan does not answer a search for a Crafter`() {
        // Off the phone: six Tiguans among the Crafters on mobile.de. Both words of the search are
        // written all over that market's answer, so both are asked for, and a van that carries one
        // of them carries half the search.
        val answer = List(9) { listing("Volkswagen Crafter 35 Kasten L3H2 Nr $it") } +
            List(3) { listing("Volkswagen Tiguan Allspace") }
        val kept = search("Volkswagen Crafter", answer).map { it.title }
        assertEquals(9, kept.size, "the Crafters")
        assertTrue(kept.none { it.contains("Tiguan") }, "a Tiguan is a different vehicle")
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
    fun `a count in a title names no product that other listings could vouch for`() {
        // A live "parkettschleifmaschine mieten" answer: rental sets titled "3er-Parkett…" and
        // "4er-Parkett…" made the "3er" of a brush set read as a parquet sander's model.
        val listings = listOf(
            listing("3er-Parkettschleifmaschine - 3 Maschinen im Paket mieten", price = 8235),
            listing("3er-Parkettschleifset EASY mieten", price = 8235),
            listing("3er Parkettschleifmaschine Set mieten Berlin", price = 7000),
            listing("PEARL 3er-Set Hartborsten-Fugen- & Eckenbürsten, Spaltenreinigung, 120x40 mm", price = 399),
            listing("Parkettschleifmaschine mieten", price = 5600),
        )
        val kept = search("parkettschleifmaschine mieten", listings).map { it.title }
        assertTrue(kept.none { it.startsWith("PEARL") }, kept.toString())
    }

    @Test
    fun `a bay is what shops count as fach, Einschübe, Schächte or slots`() {
        val listings = listOf(
            listing("Xystec 4-fach-Festplatten-Gehäuse für 3.5\" & 2.5\" (SATA), RAID, USB", price = 14999),
            listing("HIYORI Externes 3,5\"-Festplattengehäuse USB 3.0 4 Einschübe für Festplatten", price = 7599),
            listing("Icy Box Festplattengehäuse mit 4 Schächten USB-C", price = 15900),
            listing("Festplattengehäuse 2,5 Zoll USB 3.0", price = 999),
        )
        val kept = search("4 bay festplattengehäuse", listings).map { it.price.amount }
        assertEquals(setOf(14999L, 7599L, 15900L), kept.toSet())
    }

    @Test
    fun `a model the seller states apart from the title counts as named`() {
        // MediaMarkt names the AOI488 only in the offer's address, read into its MPN.
        val mm = listing("HIYORI Externes 3,5\"-Festplattengehäuse USB 3.0 4 Einschübe für Festplatten, Festplattengehäuse", price = 7599).copy(mpn = "aoi488")
        val other = listing("HIYORI Externes Festplattengehäuse SSD/HDD Type-C 2.5-Zoll, Festplattengehäuse", price = 2999).copy(mpn = "akt314")
        val kept = search("hiyori aoi488 festplattengehäuse", listOf(mm, other)).map { it.id }
        assertEquals(listOf(mm.id), kept)
    }

    @Test
    fun `a rental is one by its sale type, whatever its title says`() {
        // Mietmeile and Loxam title their machines like any shop; the page states they are hired out.
        val rented = listing("Parkett Walzenschleifmaschine groß", price = 5900)
            .copy(saleType = io.github.tieo.arbay.model.SaleType.RENT, rentPer = io.github.tieo.arbay.model.RentPeriod.DAY)
        val sold = listing("Parkettschleifmaschine Lägler Hummel", price = 90000)
        assertEquals(listOf(sold.title), search("parkettschleifmaschine", listOf(rented, sold)).map { it.title })
        val rent = listing("Parkettschleifmaschine", price = 4500)
            .copy(saleType = io.github.tieo.arbay.model.SaleType.RENT, rentPer = io.github.tieo.arbay.model.RentPeriod.DAY)
        assertTrue(search("parkettschleifmaschine mieten", listOf(rent, listing("Parkettschleifmaschine mieten Berlin", price = 4000))).any { it.id == rent.id },
            "a rental carries the search's 'mieten' though its title leaves it out")
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
            // A pack of twelve belts named in the singular, let through by an alert at 25 euro.
            listing("Schleifband Parkettschleifmaschine", price = 2500),
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

    @Test
    fun `a phone whose title reports its battery is the phone`() {
        // A search for an iPhone 11 Pro Max lost every phone that stated its battery health: the
        // battery word read as a battery sold on its own.
        val listings = listOf(
            listing("iPhone 11 Pro Max 64 GB Akku 85%", price = 19900),
            listing("Apple iPhone 11 Pro Max Schwarz 64GB Guter Zustand 100% Batterie Simlockfrei iOS", price = 19000),
            listing("Apple iPhone 11 Pro Max Gold | OVP | 82 % Akku | Sehr guter Zustand", price = 19500),
            listing("iPhone 11 Pro Max 256GB Space Gray — Top-Zustand, Neuer Akku 100%", price = 27500),
            listing("Apple iPhone 11 / 11 Pro / 11 Pro Max Akku Reparatur / Austausch", price = 5900),
            listing("Iphone 11 Pro Max Schwarz mit Grau Magcase Hülle", price = 24200),
            listing("iPhone 11 Pro Max Hülle", price = 200),
        )
        val kept = search("iPhone 11 Pro Max", listings).map { it.title }
        assertEquals(listings.map { it.title }.filterNot { "Reparatur" in it || it.endsWith("Max Hülle") }, kept)
    }

    @Test
    fun `the make and the model qualifiers count however the seller spaces them`() {
        val listings = listOf(
            listing("İPhone 11 pro Max 64 GB", price = 19000),
            listing("iphone 11 ProMax 256GB Nachtgrün", price = 22000),
            listing("I Phone 11 Pro Max", price = 16900),
            listing("iPhone 11 max pro", price = 15800),
            listing("iPhone 11 Pro 256 GB weiß", price = 17000),
        )
        val kept = search("iPhone 11 Pro Max", listings).map { it.title }
        assertEquals(listings.dropLast(1).map { it.title }, kept)
    }

    @Test
    fun `a phone sold with its accessories is the phone`() {
        val listings = listOf(
            listing("iPhone 11 Pro Max · 512 GB · Nachtgrün · 82 % Akku · Zubehör", price = 27000),
            listing("iPhone 11 Pro Max 512 Gigabyte", price = 28000),
            listing("iPhone 11 Pro Max 512 giga nero", price = 28000),
            listing("iPhone 11 Pro Max 64GB Silver", price = 15000),
        )
        assertEquals(listings.take(3).map { it.title }, search("iPhone 11 Pro Max 512GB", listings).map { it.title })
        val sized = listOf(
            listing("iPhone 11 Pro Max - 256 GB - 70% Akku - viel Zubehör", price = 22900),
            listing("Apple iPhone 11 Pro Max 256 GB mit 2 Lederhüllen und Zubehör", price = 15500),
            listing("I-Phone 11 Pro Max 256 GB", price = 21500),
            listing("i Phone 11ProMax 256GB", price = 22000),
            listing("Ipfon 11 pro Max 256 GB", price = 22000),
        )
        assertEquals(sized.map { it.title }, search("iPhone 11 Pro Max 256GB", sized).map { it.title })
    }

    @Test
    fun `a battery stated in any of the ways sellers write it is the phone's`() {
        val listings = listOf(
            listing("IPHONE 15 PRO MAX IN DER FARBE BLACK 256GB SEHR GUTE ZUSTAND FREI FÜR ALLE SIM-KARTEN AKKU KAPAZITÄT 88%", price = 54900),
            listing("iPhone 15 pro Max 250 GB Batterie: %83", price = 47700),
            listing("iPhone 15 Pro Max wie Neu Akku 100 Prozent", price = 59900),
            listing("Apple Iphone 15 Pro Max Akku Top 256 Gb", price = 46000),
            listing("Apple iPhone 15 Pro Max - 256GB - Titan Blau - Batterie & Display Neu - in OVP", price = 54900),
            listing("Apple iPhone 15 Pro Max 512 GB OVP und MagSafe Case", price = 55000),
            listing("iPhone 15 Pro Max Hülle und Schutzfolie", price = 1000),
            listing("Ladekabel und Netzteil iPhone 15 Pro Max", price = 1500),
        )
        val kept = search("iPhone 15 Pro Max", listings).map { it.title }
        assertEquals(listings.dropLast(2).map { it.title }, kept)
    }

    @Test
    fun `a phone's memory written beside its storage is not a second size`() {
        val listings = listOf(
            listing("Google Pixel 10 Pro XL 5G 256GB-16GB Wie Neu Top Zustand 100% .", price = 78900),
            listing("Google Pixel 10 Pro XL 5G 16GB 256GB Obsidian Ohne Simlock NEU", price = 83000),
            listing("Google Pixel 10 Pro XL 256GB 16GB Obsidian + Pixel Buds Pro 2", price = 94900),
            listing("Google Pixel 10 Pro XL 128GB/256GB Obsidian", price = 70000),
        )
        assertEquals(listings.dropLast(1).map { it.title }, search("Pixel 10 Pro XL 256GB", listings).map { it.title })
    }

    @Test
    fun `a size written apart from its unit is no model word to learn the slot from`() {
        // Most of what says 2000 GB here is an M.2 drive, and "2000go" reads as "2000 gb": taken
        // for a model number, the hard disk inherited the slot from them.
        val results = search("2TB M.2", listOf(
            listing("Kingston 2000 GB M.2 NVMe"),
            listing("Lexar 2000 GB M.2 SSD"),
            listing("Crucial 2000 GB M.2 PCIe"),
            listing("WD Elements 2TB externe Festplatte"),
            listing("Seagate Expansion 2TB USB"),
            listing("Toshiba Canvio 2TB"),
            listing("Intenso 2TB Memory Case"),
            listing("Disque dur 3.5\" 2to 2000go seagate"),
        ))
        assertTrue(results.none { it.title.startsWith("Disque dur") }, results.joinToString { it.title })
        assertTrue(results.any { it.title.startsWith("Kingston") })
    }

    @Test
    fun `a size typed apart in an alias is still the size asked for`() {
        // The 2 TB and 1 TB SSD watches learned their lowest price from a 256 GB and a 250 GB drive:
        // the alias "2 TB NVMe" read as the words "2" and "tb", "2" matched the "M 2" of the slot,
        // and "tb" was implied by the rest of the answer.
        val q2 = RelevanceFilter.parseQuery(SearchQuery(text = "2TB NVMe", aliases = listOf("2 TB NVMe", "2000GB NVMe"), category = MarketGroup.GENERAL))
        val q1 = RelevanceFilter.parseQuery(SearchQuery(text = "1TB NVMe", aliases = listOf("1 TB NVMe", "1000GB NVMe"), category = MarketGroup.GENERAL))
        assertEquals(0.0, RelevanceFilter.score(listing("EN680E 256 Go SSD PCle 3,0x4 NVMe M 2 2280 Cache SLC 1400 Mo/s en Lecture"), q2, setOf("tb")))
        assertEquals(0.0, RelevanceFilter.score(listing("Intenso 250GB M.2 SSD PCIe Premium, bis zu 2100 MB/s, (PCI Express Gen.3x4 NVMe 1.3, Solid State Drive)"), q1, setOf("tb")))
        assertEquals(1.0, RelevanceFilter.score(listing("WD Blue SN550 2TB NVMe SSD inkl. USB Case"), q2))
        assertEquals(1.0, RelevanceFilter.score(listing("Crucial P310 SSD 2 To M.2 2280 PCIe Gen4 NVMe"), q2))
    }

    @Test
    fun `a docking station that takes drives up to the size is not a drive`() {
        val dock = listing("SSD und Festplatten Docking Station USB 3.0, HDD Docking Station, Dockingstation für Festplatten für 2,5 und 3,5 Zoll SATA HDD/SSD 32TB mit SD/TF-Kartenleser, Unterstützt Offline-Cloning,UASP", 2899)
        val drive = listing("Seagate Exos 32TB HDD SATA", 60000)
        assertEquals(listOf(drive.title), search("32TB HDD", listOf(dock, drive)).map { it.title })
    }

    @Test
    fun `a NAS named by its line is a NAS, and a drive made for one is a drive`() {
        val nas = listing("NAS Buffalo TeraStation 5140DN mit 32TB", 45000)
        val drive = listing("WD Red 32TB NAS Festplatte HDD", 60000)
        assertEquals(listOf(drive.title), search("32TB HDD", listOf(nas, drive)).map { it.title })
    }

    @Test
    fun `a drive's circuit board named after its size is the board`() {
        val board = listing("1,0TB SAMSUNG Momentus Platine ST1000LM024 2,5\" SATA-II 5400", 980)
        val drive = listing("Samsung Momentus 1TB HDD ST1000LM024", 2500)
        assertEquals(listOf(drive.title), search("1TB HDD", listOf(board, drive)).map { it.title })
    }

    @Test
    fun `bays, media players, a Synology and an enclosure for something are not the drive searched for`() {
        val drive = listing("WD Red Plus 6TB HDD", 9000)
        assertEquals(listOf(drive.title), search("6TB HDD", listOf(
            listing("6TB UASP Acceleration HDD -Gehäuse für OS 9.1/OS 10.2 Benutzer", 1503), drive,
        )).map { it.title })
        assertTrue(search("10TB HDD", listOf(listing("Himedia Q10 inkl. Fernbedienung (10TB interne HDD extra)", 6000))).isEmpty())
        assertTrue(search("30TB HDD", listOf(listing("Asustor Xpanstor 4 AS5004U 4-Bay mit 30TB (3x 10TB)", 149400))).isEmpty())
        assertTrue(search("32TB HDD", listOf(listing("Synology DS918+ mit 16GB RAM und 32TB HDD (Ironwolf)", 95000))).isEmpty())
        assertEquals(1, search("18TB HDD", listOf(listing("Seagate Exos 18TB HDD mit Gehäuse", 26000))).size)
    }

    @Test
    fun `a drive bay named after its maker and model, with drives in it, is the bay`() {
        val bay = listing("Asustor Xpanstor 4 AS5004U 4-Bay mit 30TB (3x 10TB) Bestpreis Festplatte SATA HDD", 149400)
        assertTrue(search("30TB HDD", listOf(bay)).isEmpty())
        // A drive said to suit one stays a drive.
        assertEquals(1, search("8TB HDD", listOf(listing("Seagate IronWolf 8TB HDD für Synology DS918+", 15000))).size)
    }

    @Test
    fun `a size under a terabyte written in terabytes is that size, not a cache`() {
        assertTrue(search("30TB HDD", listOf(listing("HPE 0.30TB HDD 2.5 518006-002", 24005))).isEmpty())
        assertEquals(1, search("18TB HDD", listOf(listing("Seagate Exos 18TB HDD 256MB Cache", 25000))).size)
    }

    @Test
    fun `a QNAP with drives in it is the NAS`() {
        assertTrue(search("32TB HDD", listOf(listing("Qnap TS-432X 4x HDD 32TB NAS 10GbE 2,5G 4GB RAM PCIe Gen 3", 215111))).isEmpty())
    }

    @Test
    fun `a model line asked for is not left out of the title`() {
        val kept = search("toshiba mg09", listOf(
            listing("Telecomando Toshiba CT8508", 385),
            listing("Toshiba V28A00095300 Menü Button Board für Toshiba 37XV633D", 175),
            listing("Alimentazione Pc", 175),
            listing("Toshiba MG09ACA18TE 18TB HDD", 25000),
        )).map { it.title }
        assertEquals(listOf("Toshiba MG09ACA18TE 18TB HDD"), kept)
        assertTrue(search("sapphire rx580", listOf(listing("Sapphire RX5800 Nitro", 20000))).isEmpty())
    }

    @Test
    fun `a 4-bay enclosure for 3,5-inch drives is what a search for one asks`() {
        val titles = listOf(
            "Festplattengehäuse, CENMATE 10Gbps Aluminium 4 Bay Gehäuse mit Kühllüfter für 2.5/3.5 SATA HDD/SSD mit USB A/C 3.2 Kable",
            "ICY BOX 4-fach Externes Festplattengehäuse für 3,5 Zoll SATA I,II,III Festplatten, USB 3.0 (UASP) & eSATA Anschluss",
            "CENMATE 4 Bay Festplattengehäuse mit Lüfter für 2.5/3.5“ SATA HDD/SSD,5Gbps",
        )
        val kept = search("4 bay festplattengehäuse usb 3.5", titles.map { listing(it, 12000) }).map { it.title }
        assertEquals(titles, kept)
        // A decimal is matched however the title writes it.
        assertEquals(2, search("2,5 zoll ssd 1tb", listOf(listing("Samsung 870 EVO 1TB 2.5\" SATA SSD", 6000), listing("Crucial MX500 1TB SSD 2,5 Zoll", 5500))).size)
        assertEquals(1, search("usb 3.0 stick 64gb", listOf(listing("SanDisk Ultra 64GB USB 3.0 Stick", 800))).size)
        // And required like any number, on a market whose answer mostly leaves it out.
        val enclosures = listOf(
            listing("externes Festplattengehäuse Seagate Basic Drive 2,5 SATA USB 3.0 15mm in OVP", 595),
            listing("Hurricane MD25U3 2.5\" Externes Festplattengehäuse USB 3.0 schwarz", 675),
            listing("Externes Festplattengehäuse 2.5 Zoll USB 3.1 USB-C 10Gbps SATA HDD SSD Case", 699),
            listing("ICY BOX Festplattengehäuse 3,5 Zoll USB 3.0 mit Netzteil", 2500),
        )
        assertEquals(listOf("ICY BOX Festplattengehäuse 3,5 Zoll USB 3.0 mit Netzteil"),
            search("festplattengehäuse 3,5 zoll usb 3.0 netzteil", enclosures).map { it.title })
        // Nor is it taken as said by a listing that does not say it, however many others do.
        val described = (1..6).map { listing("Retoo Festplattengehäuse 2,5 Zoll USB 3.0 Nr $it", 500L + it).copy(description = "Für 2,5\" und 3,5\" Laufwerke") } +
            listing("Retoo Festplattengehäuse 3,5 Zoll USB 3.0", 1500).copy(description = "Für 3,5\" Laufwerke")
        assertEquals(listOf("Retoo Festplattengehäuse 3,5 Zoll USB 3.0"),
            search("festplattengehäuse 3,5 zoll usb 3.0", described).map { it.title })
    }

    @Test
    fun `a model number searched alone is required where the answer writes it`() {
        val kept = search("MG09ACA18TE", listOf(
            listing("Dichtung Abgaskrümmer für MG TF 1.8 02 bis 09 Victor Teil 18K4K", 1615),
            listing("Ölfilter für MG MGR 3.9 92 bis 95 Knecht BA32105 Original", 1618),
            listing("Toshiba MG09 Enterprise 18TB, SATA, 3,5 Zoll Interne Festplatte (MG09ACA18TE)", 24000),
        )).map { it.title }
        assertEquals(listOf("Toshiba MG09 Enterprise 18TB, SATA, 3,5 Zoll Interne Festplatte (MG09ACA18TE)"), kept)
        // Nor does another model of the same maker borrow it from its neighbours.
        val toshibas = (1..5).map { listing("Toshiba MG09ACA18TE 18TB Nr $it", 27000L + it) } + listing("Toshiba MG07ACA14TE 14TB Enterprise HDD", 29700)
        assertTrue(search("MG09ACA18TE", toshibas).none { "MG07" in it.title })
    }

    // === A box for drives searched by its own name ===

    @Test
    fun `a drive enclosure searched by name is kept`() {
        val results = search("TerraMaster D4", listOf(
            listing("TERRAMASTER D4-320 10Gbps-Speicher Externes Festplattengehäuse(Diskless)"),
            listing("TERRAMASTER D4-320 – 4-Bay USB 3.2 10 Gbit/s *NEU*"),
            listing("TERRAMASTER D4-320 4bay Gehäuse, USB3.2, 10Gbps, Diskless"),
            listing("Netzteil für TerraMaster D4-320"),
            listing("Gehäuse für TerraMaster D4 Festplatte"),
        ))
        assertEquals(3, results.size, results.joinToString { it.title })
        assertTrue(results.none { it.title.startsWith("Netzteil") || it.title.startsWith("Gehäuse für") })
    }

    @Test
    fun `a box with drives in it is still not the drive searched for`() {
        val results = search("16TB", listOf(
            listing("Seagate Exos X16 16TB SATA"),
            listing("Synology DS920+ 4-Bay mit 4x 16TB"),
            listing("ORICO 4-Bay Festplattengehäuse für 16TB HDD"),
        ))
        assertEquals(listOf("Seagate Exos X16 16TB SATA"), results.map { it.title })
    }

    @Test
    fun `a phone housing is still an accessory of the phone`() {
        val results = search("Pixel 8", listOf(
            listing("Google Pixel 8 128GB Obsidian"),
            listing("Pixel 8 Gehäuse Rückseite Ersatz"),
        ))
        assertEquals(listOf("Google Pixel 8 128GB Obsidian"), results.map { it.title })
    }

    @Test
    fun `a hyphenated model code searched alone is required`() {
        val results = search("D4-320", listOf(
            listing("TERRAMASTER D4-320 4bay Gehäuse, USB3.2, 10Gbps, Diskless"),
            listing("TerraMaster D4 320 Festplattengehäuse"),
            listing("Diablo 4 D4 ETERNAL NO SEASON Crux of the False Prophet Mats/Kills"),
            listing("D- Ring O- Ringe geschweißt poliert Metallringe Edelstahl V4A Stahlring Rund"),
            listing("Spanstick D4 Kleber PU Schaum Spanplatte Bodenbelag Kleber"),
        ))
        assertEquals(2, results.size, results.joinToString { it.title })
    }

    @Test
    fun `an ad selling two vehicles at one price says so only in its text`() {
        val bundle = listing("VW Crafter", 27_000_00).copy(description =
            "**TOP-ANGEBOT – 2 FAHRZEUGE ZUM SONDERPREIS!** Zwei Fahrzeuge zum unschlagbaren Paketpreis. " +
                "Beide Fahrzeuge zusammen zu einem besonders günstigen Preis!")
        assertTrue(RelevanceFilter.sellsSeveralVehiclesAtOnePrice(bundle))
        val dealer = listing("VW Crafter", 27_000_00).copy(description =
            "Wir haben 2 Fahrzeuge dieses Typs auf Lager. Assistenzpaket, im Paket enthalten: Kamera. Gesamtpreis inkl. Überführung.")
        assertFalse(RelevanceFilter.sellsSeveralVehiclesAtOnePrice(dealer))
        assertFalse(RelevanceFilter.sellsSeveralVehiclesAtOnePrice(listing("VW Crafter")))
    }
}
