package io.github.tieo.arbay.crawler

import kotlin.test.Test
import kotlin.test.assertEquals

/** Control lines reach the server inside stdout, because xvfb-run merges the sidecar's stderr into it. */
class StealthControlLinesTest {
    @Test
    fun controlLineBeforeAPageIsTakenOutAndThePageKept() {
        val buf = StringBuilder("ARBAY_CTRL:CAPTCHA_INTERACTIVE\n<html>page</html>")
        assertEquals(listOf("CAPTCHA_INTERACTIVE"), StealthBrowserClient.takeControlLines(buf))
        assertEquals("<html>page</html>", buf.toString())
    }

    @Test
    fun severalControlLinesComeOutInOrder() {
        val buf = StringBuilder("ARBAY_CTRL:CAPTCHA_INTERACTIVE\nnoise\nARBAY_CTRL:CAPTCHA_SOLVED\n<html>")
        assertEquals(listOf("CAPTCHA_INTERACTIVE", "CAPTCHA_SOLVED"), StealthBrowserClient.takeControlLines(buf))
        assertEquals("noise\n<html>", buf.toString())
    }

    @Test
    fun aControlLineStillBeingWrittenWaitsForTheRest() {
        val buf = StringBuilder("ARBAY_CTRL:CAPTCHA_INTER")
        assertEquals(emptyList(), StealthBrowserClient.takeControlLines(buf))
        buf.append("ACTIVE\n")
        assertEquals(listOf("CAPTCHA_INTERACTIVE"), StealthBrowserClient.takeControlLines(buf))
        assertEquals("", buf.toString())
    }

    @Test
    fun thePrefixInsidePageTextIsLeftAlone() {
        val buf = StringBuilder("<p>see ARBAY_CTRL:X here</p>\n")
        assertEquals(emptyList(), StealthBrowserClient.takeControlLines(buf))
        assertEquals("<p>see ARBAY_CTRL:X here</p>\n", buf.toString())
    }
}
