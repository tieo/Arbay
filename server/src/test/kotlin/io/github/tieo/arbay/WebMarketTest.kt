package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.PageOffer
import io.github.tieo.arbay.crawler.ProductPages
import io.github.tieo.arbay.crawler.SiteRecipe
import io.github.tieo.arbay.crawler.SiteRecipes
import io.github.tieo.arbay.crawler.SearchEngines
import io.github.tieo.arbay.crawler.WebCrawler
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebMarketTest {

    @Test
    fun `a shop's JSON-LD product is its offer`() {
        val html = """<html><head><script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Product","name":"Yottamaster FS4C3 4 Bay",
             "image":["/img/fs4c3.jpg"],"gtin13":"0718037875432","mpn":"FS4C3",
             "offers":{"@type":"Offer","price":"169.99","priceCurrency":"EUR",
               "availability":"https://schema.org/InStock","itemCondition":"https://schema.org/NewCondition",
               "shippingDetails":{"@type":"OfferShippingDetails","shippingRate":{"value":"4.90","currency":"EUR"}}}}
            </script></head><body>169,99 € In den Warenkorb</body></html>"""
        val offer = ProductPages.offers(html, "https://shop.example/p/fs4c3").single()
        assertEquals("Yottamaster FS4C3 4 Bay", offer.title)
        assertEquals(Money(16999, Currency.EUR), offer.price)
        assertEquals(Condition.NEW, offer.condition)
        assertEquals("https://shop.example/img/fs4c3.jpg", offer.image)
        assertEquals(Money(490, Currency.EUR), offer.shipping)
        assertEquals("0718037875432", offer.gtin)
        assertEquals("https://shop.example/p/fs4c3", offer.url)
    }

    @Test
    fun `a listing page's ItemList and a graph's AggregateOffer are read`() {
        val html = """<script type="application/ld+json">
            {"@graph":[{"@type":"ItemList","itemListElement":[
              {"@type":"ListItem","position":1,"item":{"@type":"Product","name":"Fantec QB-35U31","url":"/a",
                "offers":{"@type":"AggregateOffer","lowPrice":"149,00","priceCurrency":"EUR"}}},
              {"@type":"ListItem","position":2,"item":{"@type":"Product","name":"ICY BOX IB-3740-C31","url":"/b",
                "offers":[{"@type":"Offer","price":179.9,"priceCurrency":"EUR","availability":"https://schema.org/OutOfStock"}]}}
            ]}]}</script>"""
        val offers = ProductPages.offers(html, "https://shop.example/c/gehaeuse")
        assertEquals(listOf("Fantec QB-35U31", "ICY BOX IB-3740-C31"), offers.map { it.title })
        assertEquals(Money(14900, Currency.EUR), offers[0].price)
        assertEquals("https://shop.example/a", offers[0].url)
        assertFalse(offers[1].inStock)
    }

    @Test
    fun `microdata and OpenGraph stand in where there is no JSON-LD`() {
        val micro = """<div itemscope itemtype="https://schema.org/Product"><h1 itemprop="name">TerraMaster D4-320</h1>
            <div itemprop="offers" itemscope itemtype="https://schema.org/Offer">
            <meta itemprop="price" content="178.90"><meta itemprop="priceCurrency" content="EUR">
            <link itemprop="itemCondition" href="https://schema.org/UsedCondition"></div></div>"""
        val m = ProductPages.offers(micro, "https://a.example/d4").single()
        assertEquals(Money(17890, Currency.EUR), m.price)
        assertEquals(Condition.USED, m.condition)
        val og = """<head><meta property="og:title" content="Renkforce 4-Bay"><meta property="product:price:amount" content="129.99">
            <meta property="product:price:currency" content="EUR"></head>"""
        assertEquals(Money(12999, Currency.EUR), ProductPages.offers(og, "https://b.example/r").single().price)
    }

    @Test
    fun `a page that sells something is told from one that only talks about it`() {
        assertTrue(ProductPages.sellsSomething("<body><span>129,99 €</span><button>In den Warenkorb</button></body>"))
        assertFalse(ProductPages.sellsSomething("<body><p>Test: das Gehäuse kostet etwa 130 Euro.</p></body>"))
    }

    @Test
    fun `a recipe reads a site with no structured data`() {
        val html = """<ul><li class="art"><a class="t" href="/x/1">QNAP TR-004</a><span class="p">199,00 €</span></li>
            <li class="art"><a class="t" href="/x/2">Sabrent DS-SC4B</a><span class="p">229,00 €</span><span class="na">ausverkauft</span></li></ul>"""
        val recipe = SiteRecipe(domain = "c.example", item = "li.art", title = "a.t", price = "span.p", link = "a.t@href", soldOut = "span.na")
        val offers = SiteRecipes.read(recipe, html, "https://c.example/suche?q=4bay")
        assertEquals(listOf("QNAP TR-004", "Sabrent DS-SC4B"), offers.map { it.title })
        assertEquals(Money(19900, Currency.EUR), offers[0].price)
        assertEquals("https://c.example/x/1", offers[0].url)
        assertTrue(offers[0].inStock)
        assertFalse(offers[1].inStock)
    }

    @Test
    fun `a results page gives the pages it links to, not the engine's own`() {
        val html = """<div id="rso"><a href="https://shop.example/p/1"><h3>One</h3></a>
            <a href="/url?q=https://other.example/p/2&sa=U">Two</a>
            <a href="https://www.google.com/preferences">Settings</a><a href="https://maps.google.de/x">Maps</a></div>"""
        assertEquals(listOf("https://shop.example/p/1", "https://other.example/p/2"), SearchEngines.resultLinks(html, "google."))
    }

    @Test
    fun `an engine's redirect gives the page behind it`() {
        val ddg = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fshop.example%2Fp%2F3&amp;rut=abc">Three</a>"""
        assertEquals(listOf("https://shop.example/p/3"), SearchEngines.resultLinks(ddg, "duckduckgo."))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://shop.example/p/4".toByteArray())
        val bing = """<li class="b_algo"><a href="https://www.bing.com/ck/a?!&amp;&amp;p=x&amp;u=a1$b64&amp;ntb=1">Four</a></li>"""
        assertEquals(listOf("https://shop.example/p/4"), SearchEngines.resultLinks(bing, "bing."))
    }

    @Test
    fun `a market is known by its name in every country`() {
        assertEquals("amazon", WebCrawler.siteName("amazon.co.jp"))
        assertEquals("amazon", WebCrawler.siteName("smile.amazon.de"))
        assertEquals("ebay", WebCrawler.siteName("ebay.com.au"))
        assertEquals("alternate", WebCrawler.siteName("alternate.de"))
    }

    @Test
    fun `markup in a product's name is not part of its title`() {
        val html = """<script type="application/ld+json">{"@type":"Product","name":"4 Bay Festplattengeh<wbr/>äuse 2,5&#034; 3,5&#034;",
            "offers":{"price":"64.99","priceCurrency":"EUR"}}</script>"""
        assertEquals("4 Bay Festplattengehäuse 2,5\" 3,5\"", ProductPages.offers(html, "https://a.example/p").single().title)
    }

    @Test
    fun `a shop's app state is read for anything with a name and a price`() {
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"product":
            {"id":7,"productName":"Silverstone TS434U 4-Bay","price":{"value":199.0,"currency":"EUR"},"url":"/p/ts434u"}}}}</script>"""
        val offer = ProductPages.offers(html, "https://n.example/x").single()
        assertEquals("Silverstone TS434U 4-Bay", offer.title)
        assertEquals(Money(19900, Currency.EUR), offer.price)
        assertEquals("https://n.example/p/ts434u", offer.url)
    }

    @Test
    fun `a list page is read by the box it repeats`() {
        val box = { name: String, price: String, href: String ->
            """<div class="tile card"><a href="$href"><img data-src="/i$href.jpg"></a><h3 class="tile-title"><a href="$href">$name</a></h3>
               <span class="price-old">999,00 €</span><span class="price">$price</span><button>In den Warenkorb</button></div>"""
        }
        val html = "<main><nav><a href='/'>Start</a></nav><section class='grid'>" +
            box("Yottamaster FS4C3 4 Bay", "157,69 €", "/a") + box("ORICO 9548U3 4 Bay", "129,00 €", "/b") +
            box("Fantec QB-35U31 4 Bay", "165,00 €", "/c") + "</section><footer>Impressum</footer></main>"
        val offers = ProductPages.offers(html, "https://m.example/suche?q=4bay")
        assertEquals(listOf("Yottamaster FS4C3 4 Bay", "ORICO 9548U3 4 Bay", "Fantec QB-35U31 4 Bay"), offers.map { it.title })
        assertEquals(Money(15769, Currency.EUR), offers[0].price)
        assertEquals("https://m.example/a", offers[0].url)
        assertEquals("https://m.example/i/a.jpg", offers[0].image)
    }

    @Test
    fun `a JSON-LD block with an unescaped quote in a name is still read`() {
        // pearl.de writes a size's inch mark into the name as it is.
        val html = """<script type="application/ld+json"> {
            "@type": "Product", "sku": "ZX4189",
            "name": "Verbatim Festplatten-Gehäuse Store'n'Go für 2,5"-SATA-HDD/-SSD,  USB 3.2 Gen. 1",
            "description": "Platz für eine 2,5\"-Festplatte.
            Zweite Zeile",
            "offers":{"@type":"Offer", "price":"12.99", "priceCurrency":"EUR"} }</script>"""
        val offer = ProductPages.offers(html, "https://www.pearl.de/a-ZX4189-1162.shtml").single()
        assertEquals("Verbatim Festplatten-Gehäuse Store'n'Go für 2,5\"-SATA-HDD/-SSD, USB 3.2 Gen. 1", offer.title)
        assertEquals(Money(1299, Currency.EUR), offer.price)
    }

    @Test
    fun `a product's offer stated in another block by its id is read`() {
        // voelkner.de: the Product points at "Y524003#offer", stated in a block of its own.
        val html = """<script type="application/ld+json">{"@context":{"@vocab":"https://schema.org/"},"@graph":[{"@type":"Product","@id":"Y524003",
            "name":"Renkforce RF-6647598 Festplattengehäuse 4 Bay","image":{"@type":"ImageObject","url":"https://asset.re-in.de/i.webp"},
            "offers":{"@id":"Y524003#offer"},"brand":{"@id":"Y524003#brand"}}]}</script>
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[{"@type":"Offer","@id":"Y524003#offer",
            "priceCurrency":"EUR","availability":"http://schema.org/InStock","itemCondition":"http://schema.org/NewCondition","price":"129.99"}]}</script>"""
        val offer = ProductPages.offers(html, "https://www.voelkner.de/products/12147583").single()
        assertEquals("Renkforce RF-6647598 Festplattengehäuse 4 Bay", offer.title)
        assertEquals(Money(12999, Currency.EUR), offer.price)
        assertEquals(Condition.NEW, offer.condition)
    }

    @Test
    fun `a product stated as what a buy action is about is read`() {
        // MediaMarkt and Saturn wrap the product in a BuyAction; the strike-through price is not it.
        val html = """<script type="application/ld+json">{"@context":"https://schema.org/","@type":"BuyAction","object":{"@type":"Product",
            "name":"HIYORI Externes 3,5\"-Festplattengehäuse USB 3.0 4 Einschübe","gtin13":"9331506106639",
            "offers":[{"@type":"Offer","price":75.99,"priceCurrency":"EUR","availability":"https://schema.org/InStock",
            "seller":{"@type":"Organization","name":"Link Carol Shop"},
            "priceSpecification":{"@type":"UnitPriceSpecification","priceType":"https://schema.org/StrikethroughPrice","price":147.99}}]}}</script>"""
        val offer = ProductPages.offers(html, "https://www.mediamarkt.de/de/product/_hiyori-148285129.html").single()
        assertEquals(Money(7599, Currency.EUR), offer.price)
        assertEquals("Link Carol Shop", offer.seller)
    }

    @Test
    fun `an article naming prices and shops sells nothing`() {
        // technikkram.net's WD Red Plus review, with its affiliate boxes.
        val html = """<script type="application/ld+json">{"@graph":[{"@type":"WebPage"},{"@type":"BlogPosting","headline":"WD Red Plus im Überblick"}]}</script>
            <body><p>Preis bei cyberport DE: ca. 469,00 €</p><a>Zum Angebot bei cyberport</a><a>Bei Amazon kaufen</a></body>"""
        assertFalse(ProductPages.sellsSomething(html))
    }

    @Test
    fun `a list box is priced at what it asks, not its delivery time or list price`() {
        // megabad.com's category page: each box's price block names the delivery time and the UVP first.
        fun box(id: Int, delivery: String, uvp: String, price: String) = """
            <a href="https://www.megabad.com/a-$id.htm" class="grid-item"><span class="grid-item-name">Geberit AquaClean Sela WC-Komplettanlage $id</span>
            <div class="price t-item"><span class="label delivery">$delivery</span>
            <div class="price-addition uvp-price-wrapper"><span class="price-old-currency">UVP: </span><span class="price-old">$uvp</span><span class="price-old-currency"> €</span></div>
            <span class="pull-right price-new">$price €</span><span class="sparhinweis">Sie sparen: 912,81 €</span></div></a>"""
        val html = "<body><div class=\"grid\">" + box(1, "ca. 1 Woche", "3.211,81", "2.299,00") + box(2, "Sofort lieferbar", "3.136,60", "2.299,00") +
            box(3, "ca. 2 Wochen", "4.000,00", "2.879,99") + "</div></body>"
        assertEquals(listOf(229900L, 229900L, 287999L), ProductPages.offers(html, "https://www.megabad.com/k-109598.htm").map { it.price.amount })
    }

    @Test
    fun `an empty basket's price is no price`() {
        // globus-baumarkt.de's rental info page: the only amount on it is the header's basket.
        // pearl.de's search finding nothing names its hotline's rates and its small-order shipping.
        assertFalse(ProductPages.sellsSomething("<body><a>Warenkorb</a><p>Hotline 01806 - 000 - 265 (€ 0,20/Anruf aus dem dt. Festnetz), Österreich (€ 0,20/Min.)</p><p>zahlen Sie nur € 1,99 bzw. € 3,98 Mini-Versandkosten</p><p>(1-0 von 0)</p></body>"))
        // bauexpertenforum.de's XenForo thread: a reply guesses the part's price.
        assertFalse(ProductPages.sellsSomething("<html id=\"XenForo\"><body><h1>Urinal-Deckel: Scharnier befestigen</h1><p>Da musst du vermutlich in den 90€ teuren Apfel beißen. Nicht ohne Grund kaufen viele neu.</p></body></html>"))
        // techtest.org's review, an article by its OpenGraph type alone.
        assertFalse(ProductPages.sellsSomething("<html><head><meta property=\"og:type\" content=\"article\"></head><body><h1>3x 18TB HDDs im Vergleich</h1><p>So kostet die Red Pro ca. 490€.</p><a>418,30 € bei Amazon kaufen</a></body></html>"))
        // bergfreunde.de's GriGri+, sold out: its one variant at price 0, OutOfStock.
        assertFalse(ProductPages.sellsSomething("""<script type="application/ld+json">{"@type":"ProductGroup","name":"Petzl GriGri+","hasVariant":[{"@type":"Product","name":"Petzl GriGri+","offers":{"@type":"Offer","price":0,"priceCurrency":"EUR","availability":"https://schema.org/OutOfStock"}}]}</script><body><h1>Petzl GriGri+</h1><p>Sonst 109,95 €</p><button>In den Warenkorb</button></body>"""))
        // matthiashaltenhof.de's camera test states the camera as a reviewed Product, offering nothing.
        assertFalse(ProductPages.sellsSomething("""<script type="application/ld+json">[{"@type":"Article","headline":"Sony A7 III Test"},{"@type":"Product","name":"Sony Alpha 7 III","review":{"@type":"Review"}}]</script><body><h1>Sony A7 III Test</h1><p>Ich habe sie für 1.800 € gekauft.</p><a>Bei Amazon kaufen</a></body>"""))
        // weber.com's recipe page names its free delivery, grills.de's front page the value of a free gift.
        assertFalse(ProductPages.sellsSomething("<body><a>Warenkorb</a><p>Kostenlose Lieferung bei Bestellungen über 49,00 €</p><h1>Grillrezepte</h1></body>"))
        assertFalse(ProductPages.sellsSomething("<body><a>In den Warenkorb</a><p>Sie erhalten ein Starterset im Wert von rund 150€ gratis dazu!</p></body>"))
        // emero's magazine names only what its buyer protection covers and the order value free delivery starts at.
        assertFalse(ProductPages.sellsSomething("<body><a>In den Warenkorb</a><p>Käuferschutz bis 20.000 € je Kauf!</p><p>Ab 2.000 € Warenwert liefern wir frei</p><h1>Dusch-WC kaufen: Ja oder Nein?</h1></body>"))
        assertFalse(ProductPages.sellsSomething("<body><a>0,00 €* Warenkorb</a><h1>Maschinenvermietung</h1><p>Mieten statt kaufen</p></body>"))
    }

    @Test
    fun `a rental page is a page that offers something`() {
        assertTrue(ProductPages.sellsSomething("<body><h1>Parkettschleifmaschine mieten</h1><p>45,00 € pro Tag</p><a>Jetzt mieten</a></body>"))
    }

    @Test
    fun `a shop passing itself off as another is told by its name`() {
        val clone = """<head><meta property="og:site_name" content="MediaMarkt"/></head>"""
        assertTrue(ProductPages.impersonates(clone, "https://medymarkt.live/de/product/_hiyori-akt314-150247181.html"))
        assertFalse(ProductPages.impersonates(clone, "https://www.mediamarkt.at/de/product/_hiyori-aoi481-148285096.html"))
        assertFalse(ProductPages.impersonates("""<meta property="og:site_name" content="GLOBUS BAUMARKT"/>""", "https://www.globus-baumarkt.de/info/"))
        assertFalse(ProductPages.impersonates("""<meta property="og:site_name" content="Saturn"/>""", "https://www.saturn.de/de/product/x.html"))
    }

    @Test
    fun `a recipe's pattern reads offers a page holds only in a script`() {
        // pearl.de's lists split each product across rows; its script states each one whole.
        val html = """<script>pearlData.productsOnPage.push(new Product('PX2566','Xystec Externes USB-3.0-Gehäuse für 3,5\"-SATA-HDD \"HDE-1335.black\"',32.99,'/a-PX2566-1162.shtml'));
            pearlData.productsOnPage.push(new Product('ZX4189','Verbatim Festplatten-Gehäuse Store\'n\'Go',12.99,'/a-ZX4189-1162.shtml'));</script>"""
        val recipe = SiteRecipe(domain = "pearl.de", pattern = """new Product\('[^']*','(?<title>(?:[^'\\]|\\.)*)',(?<price>[\d.]+),'(?<link>[^']*)'""")
        val offers = SiteRecipes.read(recipe, html, "https://www.pearl.de/mtrkw-4885-4-fach-festplattengehaeuse.shtml")
        assertEquals(listOf("Xystec Externes USB-3.0-Gehäuse für 3,5\"-SATA-HDD \"HDE-1335.black\"", "Verbatim Festplatten-Gehäuse Store'n'Go"), offers.map { it.title })
        assertEquals(Money(3299, Currency.EUR), offers[0].price)
        assertEquals("https://www.pearl.de/a-PX2566-1162.shtml", offers[0].url)
    }

    @Test
    fun `a rental recipe reads the day rate and the others beside it`() {
        // toom.de's rate list: each rate a list item labelled by the time it pays for.
        val html = """<div class="m"><h3>Parkettschleifmaschine</h3><ul><li><strong>4 Stunden:</strong>&nbsp;44,80 €</li>
            <li><strong>Tag:</strong>&nbsp;56,00 €</li><li><strong>Woche:</strong> 196,00 €</li></ul></div>"""
        val recipe = SiteRecipe(domain = "toom.de", item = "div.m", title = "h3", price = "li:has(strong:matchesOwn(^Tag:))",
            rentPer = io.github.tieo.arbay.model.RentPeriod.DAY, rates = mapOf(
                io.github.tieo.arbay.model.RentPeriod.HALF_DAY to "li:has(strong:matchesOwn(^4 Stunden:))",
                io.github.tieo.arbay.model.RentPeriod.WEEK to "li:has(strong:matchesOwn(^Woche:))"))
        val offer = SiteRecipes.read(recipe, html, "https://toom.de/x").single()
        assertEquals(Money(5600, Currency.EUR), offer.price)
        assertEquals(io.github.tieo.arbay.model.RentPeriod.DAY, offer.rentPer)
        assertEquals(listOf(
            io.github.tieo.arbay.model.RentRate(io.github.tieo.arbay.model.RentPeriod.HALF_DAY, Money(4480, Currency.EUR)),
            io.github.tieo.arbay.model.RentRate(io.github.tieo.arbay.model.RentPeriod.WEEK, Money(19600, Currency.EUR))), offer.rentRates)
    }

    @Test
    fun `a model a shop writes only into the address is read off it`() {
        val title = "HIYORI Externes 3,5\"-Festplattengehäuse USB 3.0 4 Einschübe für Festplatten, Festplattengehäuse"
        assertEquals("aoi488", ProductPages.modelInAddress("https://www.mediamarkt.de/de/product/_hiyori-aoi488-festplattengehause-148285129.html", title))
        // A model the title names already, and an article number, are not it.
        assertEquals(null, ProductPages.modelInAddress("https://www.alternate.de/WD/SN7100-2-TB-SSD/html/product/100116699", "SN7100 2 TB, SSD"))
    }

    @Test
    fun `a rental table cell holding two rates is read by position`() {
        // abschliff.de: "Miete: Tag / WE*" in one cell, no currency sign.
        val html = """<table><tr><th>Maschine</th><th>Abmessungen</th><th>Miete: Tag / WE*</th><th>Kaution</th></tr>
            <tr><td>Bona Belt</td><td>200 x 750 mm</td><td>30,- / 50,-</td><td>300 €</td></tr>
            <tr><td>Kompressor/Vogthammer</td><td>/</td><td>175,-</td><td>400 €</td></tr></table>"""
        val recipe = SiteRecipe(domain = "abschliff.de", item = "tr:has(td)", title = "td:eq(0)", price = "td:eq(2)#1", currency = "EUR",
            rentPer = io.github.tieo.arbay.model.RentPeriod.DAY, rates = mapOf(io.github.tieo.arbay.model.RentPeriod.WEEKEND to "td:eq(2)#2"))
        val offers = SiteRecipes.read(recipe, html, "https://abschliff.de/verleih")
        assertEquals(listOf("Bona Belt", "Kompressor/Vogthammer"), offers.map { it.title })
        assertEquals(Money(3000, Currency.EUR), offers[0].price)
        assertEquals(listOf(io.github.tieo.arbay.model.RentRate(io.github.tieo.arbay.model.RentPeriod.WEEKEND, Money(5000, Currency.EUR))), offers[0].rentRates)
        assertEquals(Money(17500, Currency.EUR), offers[1].price)
        assertTrue(offers[1].rentRates.isEmpty())
    }

    @Test
    fun `a link a page hides as base64 is read as the address`() {
        // mietmeile.de's result boxes follow an encoded data-submit instead of a link.
        val html = """<div class="searchresult-obj redirect-link" data-submit="L3dlcmt6ZXVnZS1nZXJhZXRlL3NjaGxlaWZtYXNjaGluZS9wYXJrZXR0c2NobGVpZm1hc2NoaW5lLzMxODc4My9wYXJrZXR0c2NobGVpZmVyLW1pZXRlbg==">
            <h3 class="searchresult-txt-name">Parkettschleifer</h3><div class="searchresult-price"><span>79,00</span> € <span>pro Tag</span></div></div>"""
        val recipe = SiteRecipe(domain = "mietmeile.de", item = "div.searchresult-obj", title = "h3", price = "div.searchresult-price", link = "@data-submit",
            rentPer = io.github.tieo.arbay.model.RentPeriod.DAY)
        val offer = SiteRecipes.read(recipe, html, "https://www.mietmeile.de/werkzeuge-geraete/schleifmaschine/parkettschleifmaschine-mieten").single()
        assertEquals("https://www.mietmeile.de/werkzeuge-geraete/schleifmaschine/parkettschleifmaschine/318783/parkettschleifer-mieten", offer.url)
        assertEquals(Money(7900, Currency.EUR), offer.price)
    }

    @Test
    fun `a recipe's title keeps the words of its parts apart, in their order`() {
        // loxam.de's machine heading: the name and its weight in a span, written together.
        val html = """<h1 class="n">Parkettschleifmaschine<span class="w">45 kg</span> mit <b>Sack</b></h1><span class="p">57,13<span> €</span></span>"""
        val offer = SiteRecipes.read(SiteRecipe(domain = "loxam.de", title = "h1.n", price = "span.p"), html, "https://www.loxam.de/p/x").single()
        assertEquals("Parkettschleifmaschine 45 kg mit Sack", offer.title)
        assertEquals(Money(5713, Currency.EUR), offer.price)
    }

    @Test
    fun `microdata is read by its type where the page leaves out itemscope`() {
        // reichelt.de's search results.
        val html = """<div class="al_gallery_article" itemtype="http://schema.org/Product"><meta itemprop="name" content="Dockingstation 2x M.2 NVME, USB 3.1">
            <div itemprop="name">Artikel-Nr.: LOGILINK QP0033 Dockingstation 2x M.2 NVME, USB 3.1</div>
            <div itemprop="offers"><meta itemprop="price" content="69.99"><meta itemprop="priceCurrency" content="EUR"></div></div>"""
        val offer = ProductPages.offers(html, "https://www.reichelt.de/de/de/shop/suche?search=dockingstation").single()
        assertEquals("Dockingstation 2x M.2 NVME, USB 3.1", offer.title)
        assertEquals(Money(6999, Currency.EUR), offer.price)
    }

    @Test
    fun `a category page whose only amounts are the basket and delivery sells nothing`() {
        // reichelt.de's category landing, which lists sub-categories.
        assertFalse(ProductPages.sellsSomething("<body><a>0 Warenkorb 0,00 €</a><span>Versandkosten ab 5,95 €</span><h1>Festplatten</h1><a>Jetzt kaufen</a></body>"))
    }

    @Test
    fun `a JSON recipe reads a site's API, an entry of a list picked by its fields`() {
        // Boels' product search: its own "price" is none of the rates; the day rate with VAT is.
        val body = """{"products":[{"code":"11281","name":"<em class=\"search-results-highlight\">Parkettschleifmaschine</em>",
            "price":{"value":48.48},"url":"/mieten/parkettschleifmaschine/p/11281",
            "images":[{"format":"zoom","imageType":"PRIMARY","url":"https://mediahub.boels.com/m/z/large-11281.jpg"},
                      {"format":"product","imageType":"PRIMARY","url":"https://mediahub.boels.com/m/p/11281.jpg"}],
            "unitPrices":[{"unit":"DAY-RENT","netPrice":true,"value":48.0},{"unit":"DAY-RENT","netPrice":false,"value":57.12},
                          {"unit":"5DAYPLUS-RENT","netPrice":false,"value":45.696}]}]}"""
        val recipe = SiteRecipe(domain = "boels.com", items = "products", title = "name", price = "unitPrices[unit=DAY-RENT,netPrice=false].value",
            link = "url", image = "images[format=product].url", linkBase = "https://www.boels.com/de-de", rentPer = io.github.tieo.arbay.model.RentPeriod.DAY)
        val offer = SiteRecipes.read(recipe, body, "https://www.boels.com/api/occ/v2/rentalDE/products/search?query=parkett").single()
        assertEquals("Parkettschleifmaschine", offer.title)
        assertEquals(Money(5712, Currency.EUR), offer.price)
        assertEquals("https://www.boels.com/de-de/mieten/parkettschleifmaschine/p/11281", offer.url)
        assertEquals("https://mediahub.boels.com/m/p/11281.jpg", offer.image)
        assertEquals(io.github.tieo.arbay.model.RentPeriod.DAY, offer.rentPer)
    }

    @Test
    fun `a recipe for a site quoting net prices adds VAT`() {
        // maschinen-humpert.de: "Tagestarif Wochenendtarif Wochentarif Kaution / 15,00 €* 22,50 €* 72,00 €* 50,00 €", net.
        val html = """<div><div class="w"><p>Tagestarif<br>Wochenendtarif<br>Wochentarif<br>Kaution</p></div><div class="w"><p>15,00 €*<br> 22,50 €*<br> 72,00 €*<br> 50,00 €</p></div></div><h1>Parkett Eckenschleifer zur Miete</h1>"""
        val recipe = SiteRecipe(domain = "maschinen-humpert.de", title = "h1", price = "div.w:has(p:matchesOwn(^Tagestarif)) + div p#1", pricesNet = true,
            rentPer = io.github.tieo.arbay.model.RentPeriod.DAY, rates = mapOf(io.github.tieo.arbay.model.RentPeriod.WEEK to "div.w:has(p:matchesOwn(^Tagestarif)) + div p#3"))
        val offer = SiteRecipes.read(recipe, html, "https://maschinen-humpert.de/vermietung/parkett-eckenschleifer-mieten/").single()
        assertEquals(Money(1785, Currency.EUR), offer.price)
        assertEquals(Money(8568, Currency.EUR), offer.rentRates.single().price)
    }

    @Test
    fun `a newsletter page whose amounts are a voucher and a minimum order sells nothing`() {
        // conrad.de's newsletter signup.
        assertFalse(ProductPages.sellsSomething("""<body><h1>Conrad Business Newsletter abonnieren</h1><p>Exklusiver 10 €-Gutschein ab 100€ Einkaufswert</p>
            <p>Mindesteinkaufswert: 100€ zzgl. MwSt.</p><p>Versandkostenfrei ab 100,00 €</p><a>Jetzt kaufen</a></body>"""))
    }

    @Test
    fun `a price the shop states without VAT is read with it`() {
        // conrad.de serves business prices: the Renkforce 4-Bay at 109,24 € net, 129,99 € at voelkner.
        val html = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"Product","name":"Renkforce RF-6647598 Festplattengehäuse 4 Bay",
            "offers":{"@type":"Offer","availability":"https://schema.org/InStock",
              "priceSpecification":{"@type":"PriceSpecification","price":109.24,"priceCurrency":"EUR","valueAddedTaxIncluded":false}}}</script>"""
        // The net price was rounded from the gross one, so the gross read back is a cent off at most.
        assertEquals(Money(13000, Currency.EUR), ProductPages.offers(html, "https://www.conrad.de/de/p/renkforce-3323799.html").single().price)
        assertEquals(Money(13109, Currency.EUR), ProductPages.offers(html, "https://www.conrad.at/de/p/renkforce-3323799.html").single().price)
    }

    @Test
    fun `a site showing net prices on pages it marks gets VAT on what its structured data left unsaid`() {
        SiteRecipes.put(SiteRecipe(domain = "conrad-test.de", searchUrl = "https://www.conrad-test.de/s?q={q}", pricesNet = true,
            netWhere = "[data-e2e=header-customerTypeLabel]:matchesOwn(Geschäftskunde)"))
        try {
            val offer = PageOffer("Renkforce RF-6647598 4 Bay", Money(10924, Currency.EUR), "https://www.conrad-test.de/p/1")
            val business = """<span data-e2e="header-customerTypeLabel">Geschäftskunde</span>"""
            val url = "https://www.conrad-test.de/de/search.html?search=4+bay"
            assertEquals(Money(13000, Currency.EUR), SiteRecipes.asTheSiteDeals(url, business, listOf(offer)).single().price)
            assertEquals(Money(10924, Currency.EUR), SiteRecipes.asTheSiteDeals(url, """<span data-e2e="header-customerTypeLabel">Privatkunde</span>""", listOf(offer)).single().price)
            // A price whose own data said whether VAT is in it is left as it was read.
            assertEquals(Money(12999, Currency.EUR), SiteRecipes.asTheSiteDeals(url, business, listOf(offer.copy(price = Money(12999, Currency.EUR), vatStated = true))).single().price)
        } finally {
            SiteRecipes.remove("conrad-test.de")
        }
    }

    @Test
    fun `a JSON recipe keeps the entries a nested filter picks and reads a link off an embedded parent`() {
        // gerach-gruppe.de's WordPress API: machines priced per working day are kept, the weekly one is not;
        // a machine's own address is dead, its parent (the category page) is not.
        val body = """[{"title":{"rendered":"&#8211; Parkettschleifmaschine"},"acf":{"mietpreis_einheit":"Arbeitstag","anzahl_1":{"preis_brutto":39.98}},
              "_embedded":{"up":[{"link":"https://www.gerach-gruppe.de/mietpark/fraes-und-schleiftechnik/bodenbearbeitungsmaschinen-mieten/"}]}},
            {"title":{"rendered":"Bautrockner"},"acf":{"mietpreis_einheit":"Preis/Woche","anzahl_1":{"preis_brutto":99.0}},"_embedded":{"up":[{"link":"https://x"}]}},
            {"title":{"rendered":"Rüttelplatte"},"acf":{"mietpreis_einheit":"Kalendertage","anzahl_1":{"preis_brutto":45.0}},"_embedded":{"up":[{"link":"https://www.gerach-gruppe.de/mietpark/verdichtung/"}]}}]"""
        val recipe = SiteRecipe(domain = "gerach-gruppe.de", items = "[acf.mietpreis_einheit=Arbeitstag|Arbeitstage|Kalendertag|Kalendertage]",
            title = "title.rendered", price = "acf.anzahl_1.preis_brutto", link = "_embedded.up.link", rentPer = io.github.tieo.arbay.model.RentPeriod.DAY)
        val offers = SiteRecipes.read(recipe, body, "https://www.gerach-gruppe.de/wp-json/wp/v2/produkte?search=parkett&_embed=up")
        assertEquals(listOf("Parkettschleifmaschine", "Rüttelplatte"), offers.map { it.title })
        assertEquals(Money(3998, Currency.EUR), offers[0].price)
        assertEquals("https://www.gerach-gruppe.de/mietpark/fraes-und-schleiftechnik/bodenbearbeitungsmaschinen-mieten/", offers[0].url)
    }

    @Test
    fun `a site given up on stays given up until it gets a recipe`() {
        val site = "given-up-test.de"
        io.github.tieo.arbay.crawler.SiteGaps.giveUp(site)
        try {
            assertTrue(io.github.tieo.arbay.crawler.SiteGaps.isGivenUp(site))
        } finally {
            io.github.tieo.arbay.crawler.SiteGaps.takeBack(site)
        }
        assertFalse(io.github.tieo.arbay.crawler.SiteGaps.isGivenUp(site))
    }

    @Test
    fun `a page listed through Google's translating proxy is read at the shop's own address`() {
        assertEquals("https://www.amazon.it/Levigatrici-lamiera/s?rh=n:3121001031,p_6:A20FPJ7ZUTLVQJ",
            WebCrawler.unproxied("https://www-amazon-it.translate.goog/Levigatrici-lamiera/s?rh=n:3121001031,p_6:A20FPJ7ZUTLVQJ&_x_tr_sl=it&_x_tr_tl=de&_x_tr_hl=de&_x_tr_pto=sc"))
        assertEquals("https://www.dielen-schleifen.de/miete?x=1",
            WebCrawler.unproxied("https://www-dielen--schleifen-de.translate.goog/miete?_x_tr_sl=de&x=1"))
        assertEquals("https://www.e-hoi.de/", WebCrawler.withoutSession("https://www.e-hoi.de/?msockid=063548ec2ade6dcd3b695f112b266c95"))
    }

    @Test
    fun `an offer's address loses the session and tracking that make it a new one each visit`() {
        assertEquals("https://www.gerach-gruppe.de/mietpark/fraes-und-schleiftechnik/bodenbearbeitungsmaschinen-mieten/",
            WebCrawler.withoutSession("https://www.gerach-gruppe.de/mietpark/fraes-und-schleiftechnik/bodenbearbeitungsmaschinen-mieten/?utm_source=qwant&utm_medium=referral&utm_campaign=ai_flash"))
        assertEquals("https://www.pearl.de/a-ZX4189-1162.shtml?query=x",
            WebCrawler.withoutSession("https://www.pearl.de/a-ZX4189-1162.shtml;jsessionid=n073D1260CCA?query=x&gclid=abc"))
        assertEquals("https://www.voelkner.de/index.php?mp=products&products_id=12147583",
            WebCrawler.withoutSession("https://www.voelkner.de/index.php?mp=products&products_id=12147583&utm_source=google&gclid=EAIa"))
    }

    @Test
    fun `a guessed price labelled as a day's rent is a day rate`() {
        // werkzeugprofis.de's machine page, before it had a recipe.
        val html = """<h1 itemprop="name">Parkettschleifmaschine 79 kg</h1><div class="price-box"><span class="regular-price" id="product-price-24">
            Tagesmietpreis:&nbsp; <span class="price">36,00 €</span></span></div><button>In den Warenkorb</button>"""
        val offer = ProductPages.guess(html, "https://www.werkzeugprofis.de/parkett-dielen/parkettschleifmaschine-klein.html")!!
        assertEquals(Money(3600, Currency.EUR), offer.price)
        assertEquals(io.github.tieo.arbay.model.RentPeriod.DAY, offer.rentPer)
    }

    @Test
    fun `an offer a recipe reads at its own page is not taken off a list`() {
        SiteRecipes.put(SiteRecipe(domain = "rp-test.com", urlPattern = "/p/", title = "h1", price = "span.p"))
        try {
            val listed = PageOffer("Lägler Parkettschleifmaschine", Money(2000, Currency.EUR), "https://www.rp-test.com/p/lagler-mieten")
            val other = PageOffer("Bagger", Money(9000, Currency.EUR), "https://www.rp-test.com/kategorie/bagger")
            assertEquals(listOf(other), SiteRecipes.withoutOffersReadElsewhere("https://www.rp-test.com/kategorie/parkett", listOf(listed, other)))
            // On its own page it is what the page says.
            assertEquals(listOf(listed), SiteRecipes.withoutOffersReadElsewhere("https://www.rp-test.com/p/lagler-mieten", listOf(listed)))
        } finally {
            SiteRecipes.remove("rp-test.com")
        }
    }

    @Test
    fun `a page the recipe for its address reads nothing on is read by the site-wide one`() {
        SiteRecipes.put(SiteRecipe(domain = "wp-test.de", title = "h2.n", price = "span.p", item = "li.item"))
        SiteRecipes.put(SiteRecipe(domain = "wp-test.de", urlPattern = "\\.html$", title = "h1.m", price = "span.p"))
        try {
            val category = """<ul><li class="item"><h2 class="n">Parkettschleifmaschine 79 kg</h2><span class="p">36,00 €</span></li></ul>"""
            val machine = """<h1 class="m">Parkettschleifmaschine 79 kg</h1><span class="p">36,00 €</span>"""
            assertEquals(1, SiteRecipes.readAt("https://www.wp-test.de/parkett-dielen.html", category).size)
            assertEquals("Parkettschleifmaschine 79 kg", SiteRecipes.readAt("https://www.wp-test.de/parkettschleifmaschine-klein.html", machine).single().title)
        } finally {
            SiteRecipes.remove("wp-test.de")
        }
    }

    @Test
    fun `offers named only by their model carry the page's heading`() {
        val html = """<h1>Parkettschleifmaschinen Verleih</h1><table><tr><td>ELF</td><td>200 x 550 mm</td><td>20,- / 30,-</td></tr></table>"""
        val recipe = SiteRecipe(domain = "abschliff.de", item = "tr:has(td)", title = "td:eq(0)", price = "td:eq(2)#1", currency = "EUR", context = "h1")
        assertEquals("ELF · Parkettschleifmaschinen Verleih", SiteRecipes.read(recipe, html, "https://abschliff.de/verleih").single().title)
    }

    @Test
    fun `a code price on a list is read with its code`() {
        // dyson.de's cards: the list price, and on some a line offering a code for less.
        val html = """<li class="c"><a href="/v16"><div class="n">Dyson V16 Piston Animal Submarine</div><div class="p">999,00 €</div><div class="promo">Mit dem Code DYSON: Jetzt für nur 779 €.</div></a></li>
            <li class="c"><a href="/v15"><div class="n">Dyson V15 Detect Absolute</div><div class="p">599,00 €</div><div class="promo"></div></a></li>"""
        val recipe = SiteRecipe(domain = "dyson.de", item = "li.c", title = ".n", price = ".p", link = "a@href", coupon = ".promo")
        val offers = SiteRecipes.read(recipe, html, "https://www.dyson.de/staubsauger/kabellos")
        assertEquals(listOf(77900L to "DYSON", null), offers.map { o -> o.coupon?.let { it.price.amount to it.code } })
        assertEquals(listOf(99900L, 59900L), offers.map { it.price.amount })
    }

    @Test
    fun `a maker named apart from the title goes before it`() {
        // bauhaus.info's tiles: the maker on a line of its own, empty where the shop names none.
        val html = """<a href="/p/1"><div class="b">Geberit</div><div class="n">Wand-Dusch-WC-Set AquaClean Alba</div><span>899,00 €</span></a>
            <a href="/p/2"><div class="b"></div><div class="n">Dusch-WC-Sitz BH 60</div><span>249,00 €</span></a>"""
        val recipe = SiteRecipe(domain = "bauhaus.info", item = "a", title = "div.n", brand = "div.b", price = "span", link = "@href")
        assertEquals(listOf("Geberit Wand-Dusch-WC-Set AquaClean Alba", "Dusch-WC-Sitz BH 60"), SiteRecipes.read(recipe, html, "https://www.bauhaus.info/c").map { it.title })
    }

    @Test
    fun `a forum thread quoting prices sells nothing`() {
        // hardwareluxx.de: posts link Geizhals, whose previews carry "ab € 117,63".
        val html = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"DiscussionForumPosting","headline":"Problem mit externer HDD"}</script>
            <body><h1>Problem mit externer HDD</h1><p>Fantec QB-35US3-6G ab € 117,63 (2023) | Preisvergleich Geizhals</p><a>Jetzt kaufen</a></body>"""
        assertFalse(ProductPages.sellsSomething(html))
    }

    @Test
    fun `a French word ending in eur before a number is no price`() {
        // ldlc.pro's sheet of a product no longer sold: "largeur 268 mm", "ordinateur 1 x USB".
        assertFalse(ProductPages.sellsSomething("""<body><h1>ICY BOX IB-3805-C31 · Occasion</h1><p>Ce produit n'est plus en vente</p>
            <p>Interface avec l'ordinateur 1 x USB 3.1 Type C</p><p>Largeur 268 mm Hauteur 150 mm</p><a>Panier</a><a>Acheter</a></body>"""))
        assertTrue(ProductPages.sellsSomething("<body><span>EUR 129,99</span><a>Jetzt kaufen</a></body>"))
    }
}
