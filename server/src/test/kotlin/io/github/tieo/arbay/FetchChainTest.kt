package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FetchChainTest {

    @Test
    fun `a page that is not there ends the chain instead of starting a browser`() = runBlocking {
        var asked = 0
        val client = HttpClient(MockEngine { asked++; respond("not here", HttpStatusCode.NotFound) })

        val failure = assertFailsWith<CrawlerBlockedException> {
            fetchWithFallback(client, "https://example.com/lst/nothing", "FetchChainTest")
        }

        // Any later tier would have ended the chain with its own verdict, and after all four
        // with CAPTCHA.
        assertEquals(ErrorType.NOT_FOUND_404, failure.errorType)
        assertEquals(1, asked)
    }
}
