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
}
