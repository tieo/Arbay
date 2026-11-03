package io.github.tieo.arbay.crawler

import kotlin.test.Test
import kotlin.test.assertEquals

class RefurbedCrawlerTest {
    @Test
    fun aProductPageGivesItsLowestPriceAndFirstPicture() {
        val page = """{"offers":{"price":"312.00","price2":"289.90"},"image":"https://files.refurbed.com/ii/iphone-13.jpg","other":"x"}"""
        assertEquals(28990L to "https://files.refurbed.com/ii/iphone-13.jpg", RefurbedCrawler.productPage(page))
        assertEquals(19900L to null, RefurbedCrawler.productPage("""{"price2": "199"}"""))
        assertEquals(null to null, RefurbedCrawler.productPage("<html>no price</html>"))
    }
}
