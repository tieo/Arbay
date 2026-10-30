package io.github.tieo.arbay.chat

import io.github.tieo.arbay.crawler.StealthBrowserClient
import io.github.tieo.arbay.model.PlatformId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive

class MarketSidecarTest {
    /** A sidecar that answers every request as signed in, standing in for a market's browser. */
    private val script = File(StealthBrowserClient.scriptDir, "test_sidecar.py").apply {
        writeText(
            """
            import json, sys
            print(json.dumps({"ok": True, "ready": True}), flush=True)
            for line in sys.stdin:
                op = json.loads(line)["op"]
                print(json.dumps({"ok": True, "signedIn": True, "name": "tester", "op": op}), flush=True)
            """.trimIndent(),
        )
    }

    private fun sidecar(idle: kotlin.time.Duration?) = object : MarketSidecar(
        PlatformId.VINTED_DE, script.name, 0, "ARBAY_TEST_PROFILE", "test-profile", "test-sidecar.log", 0, idleClose = idle,
    ) {
        override fun ensureXvfb() {}
    }

    @Test
    fun `an account browser left unused closes, and its status is still answered`() = runBlocking {
        val browser = sidecar(2.seconds)
        assertEquals("status", browser.call("status")["op"]!!.jsonPrimitive.content)
        assertTrue(browser.running)
        delay(4_000)
        assertFalse(browser.running, "closed after two idle seconds")
        // The last status stands in for a closed browser instead of opening it to ask.
        assertEquals("true", browser.call("status")["signedIn"]!!.jsonPrimitive.content)
        assertFalse(browser.running)
        // Any other request opens it again.
        assertEquals("buy", browser.call("buy")["op"]!!.jsonPrimitive.content)
        assertTrue(browser.running)
        browser.stop()
    }

    @Test
    fun `a browser kept for good stays open`() = runBlocking {
        val browser = sidecar(null)
        browser.call("status")
        delay(3_000)
        assertTrue(browser.running)
        browser.stop()
    }
}
