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
