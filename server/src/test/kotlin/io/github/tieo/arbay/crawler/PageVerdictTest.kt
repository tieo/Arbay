package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchReach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What decides whether a fetched page is a market answering or a market turning us away, and
 * what a failure is reported as. A wrong call here reads as a market with nothing, or puts a
 * healthy market into cooldown.
 */
class PageVerdictTest {

    private val resultsPage = "<html><body>" + "<article data-adid=\"1\">Makita DHP484</article>".repeat(2_000) + "</body></html>"

    @Test
    fun `a small page asking to prove humanity is a captcha`() {
        assertTrue(detectCaptcha("<html><div class=\"g-recaptcha\"></div>Are you a human?</html>"))
        assertTrue(detectCaptcha("<html><title>Robot Check</title></html>"))
    }

    @Test
    fun `a full results page that loads captcha script is not a captcha`() {
        assertFalse(detectCaptcha(resultsPage + "<script src=\"https://www.google.com/recaptcha/api.js\"></script><div class=\"g-recaptcha\"></div>"))
    }

    @Test
    fun `block pages are told apart by what they say`() {
        assertEquals(ErrorType.BLOCKED_403, detectBlockPage("<html>Zugriff verweigert</html>", "Test")?.errorType)
        assertEquals(ErrorType.CAPTCHA, detectBlockPage("<html><title>Just a moment...</title>cloudflare</html>", "Test")?.errorType)
        assertNull(detectBlockPage(resultsPage, "Test"))
    }

    @Test
    fun `a failure reads as the kind it is`() {
        assertEquals(ErrorType.TIMEOUT, classifyException(RuntimeException("Request timeout has expired")))
        assertEquals(ErrorType.NETWORK_ERROR, classifyException(RuntimeException("Connection refused")))
        assertEquals(ErrorType.UNKNOWN, classifyException(NullPointerException()))
    }

    @Test
    fun `a market is asked in its own language only with a term for it`() {
        val base = SearchQuery(text = "parkettschleifer", category = MarketGroup.GENERAL)
        val foreign = PlatformId.entries.first { it.searchLanguage != "de" }

        assertEquals("parkettschleifer", localizedQuery(base, foreign).text, "no term: asked in the words typed")

        val withTerm = base.copy(reach = SearchReach(otherLanguages = true, termByLanguage = mapOf(foreign.searchLanguage to "levigatrice")))
        assertEquals("levigatrice", localizedQuery(withTerm, foreign).text)
        assertEquals("parkettschleifer", localizedQuery(withTerm, PlatformId.KLEINANZEIGEN).text, "a German market keeps the German words")

        val switchedOff = withTerm.copy(reach = withTerm.reach.copy(otherLanguages = false))
        assertEquals("parkettschleifer", localizedQuery(switchedOff, foreign).text, "a term is only used when asked for")
        assertNotNull(foreign)
    }
}
