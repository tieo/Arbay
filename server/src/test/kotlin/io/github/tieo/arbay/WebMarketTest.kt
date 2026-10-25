package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.ProductPages
import io.github.tieo.arbay.crawler.SiteRecipe
import io.github.tieo.arbay.crawler.SiteRecipes
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
        assertEquals(listOf("https://shop.example/p/1", "https://other.example/p/2"), WebCrawler.resultLinks(html, "google."))
    }

    @Test
    fun `an engine's redirect gives the page behind it`() {
        val ddg = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fshop.example%2Fp%2F3&amp;rut=abc">Three</a>"""
        assertEquals(listOf("https://shop.example/p/3"), WebCrawler.resultLinks(ddg, "duckduckgo."))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://shop.example/p/4".toByteArray())
        val bing = """<li class="b_algo"><a href="https://www.bing.com/ck/a?!&amp;&amp;p=x&amp;u=a1$b64&amp;ntb=1">Four</a></li>"""
        assertEquals(listOf("https://shop.example/p/4"), WebCrawler.resultLinks(bing, "bing."))
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
    fun `an empty basket's price is no price`() {
        // globus-baumarkt.de's rental info page: the only amount on it is the header's basket.
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
}
