package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.validateHtml
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ValidateHtmlTest {

    @Test
    fun `an API's short answer that no more ads exist is no block`() {
        // Sauto, live 2026-10-16, past the last of three Crafters.
        validateHtml(
            """{"pagination":{"limit":20,"offset":20,"total":3},"results":[],"status_code":200,"status_message":"OK","warnings":{}}""",
            "Sauto",
        )
    }

    @Test
    fun `a short page is still a refusal`() {
        assertFailsWith<CrawlerBlockedException> { validateHtml("<html><body></body></html>", "Sauto") }
        assertFailsWith<CrawlerBlockedException> { validateHtml("{\"results\":[", "Sauto") }
    }
}
