package io.github.tieo.arbay

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    @Test
    fun testProductsEndpoint() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/products")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testListingsEndpoint() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/listings")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun testCrawlerPlatforms() = testApplication {
        application {
            module()
        }
        val response = client.get("/api/crawler/platforms")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("EBAY_DE"))
    }
}
