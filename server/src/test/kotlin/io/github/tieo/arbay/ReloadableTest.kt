package io.github.tieo.arbay.classifier

import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReloadableTest {
    private val log = LoggerFactory.getLogger(ReloadableTest::class.java)

    @Test
    fun `a load that failed is tried again once the pause is over`() {
        var attempts = 0
        val model = Reloadable("test model", log, retryAfterMs = 0) {
            attempts++
            if (attempts == 1) error("network away") else "loaded"
        }
        assertNull(model.get())
        assertEquals("loaded", model.get())
        assertEquals("loaded", model.get())
        assertEquals(2, attempts)
    }

    @Test
    fun `within the pause a failed load is not retried on every use`() {
        var attempts = 0
        val model = Reloadable("test model", log, retryAfterMs = 60_000) { attempts++; error("still away") }
        repeat(5) { assertNull(model.get()) }
        assertEquals(1, attempts)
    }
}
