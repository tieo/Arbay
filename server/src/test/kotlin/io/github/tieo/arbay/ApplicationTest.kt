package io.github.tieo.arbay

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.ContentType
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

    @Test
    fun testFreeItemsProfileGet() = testApplication {
        application { module() }
        // Seed a profile so the GET is deterministic: the endpoint returns 204 when
        // no profile has ever been saved, which otherwise makes this depend on test order.
        client.post("/api/free-items/profile") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"seed profile for get test","location":"Bremen"}""")
        }
        val response = client.get("/api/free-items/profile")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("description"), "Profile response should contain 'description' field")
    }

    @Test
    fun testFreeItemsProfilePost() = testApplication {
        application { module() }
        val response = client.post("/api/free-items/profile") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"test profile for unit test","location":"Bremen"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("description"))
    }

    @Test
    fun testFreeItemsProfileColdStartNoDescription() = testApplication {
        application { module() }
        // Cold-start browse-to-train: only location is required, description may be blank.
        val ok = client.post("/api/free-items/profile") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"","location":"Bremen"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        // Missing location is still rejected.
        val bad = client.post("/api/free-items/profile") {
            contentType(ContentType.Application.Json)
            setBody("""{"description":"tools"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
    }

    @Test
    fun testFreeItemsFeedbackPost() = testApplication {
        application { module() }
        val response = client.post("/api/free-items/feedback") {
            contentType(ContentType.Application.Json)
            setBody("""{"listingId":"test:999","title":"Test listing","action":"LOVE"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("true"))
    }

    @Test
    fun testFreeItemsInsightsGet() = testApplication {
        application { module() }
        val response = client.get("/api/free-items/insights")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("embeddingAvailable"), "Insights should contain embeddingAvailable")
    }

    @Test
    fun testFreeItemsFeedbackInvalidAction() = testApplication {
        application { module() }
        val response = client.post("/api/free-items/feedback") {
            contentType(ContentType.Application.Json)
            setBody("""{"listingId":"test:bad","title":"Test","action":"INVALID_ACTION"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
