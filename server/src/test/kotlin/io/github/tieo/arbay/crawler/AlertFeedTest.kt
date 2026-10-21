package io.github.tieo.arbay.crawler

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A reader asks for the alerts after the last one it handled, and one waiting hears of a new one at once. */
class AlertFeedTest {

    private fun alert(id: String) = FoundAlert(
        seq = 0, savedSearchId = "s", searchName = "SSD 2 TB", rule = "€60–130",
        listingId = id, title = "drive $id", url = "https://example.org/$id", priceEur = 120, at = Clock.System.now(),
    )

    @Test
    fun `alerts come numbered, and a waiting reader gets the next one as it is recorded`() = runBlocking {
        val before = AlertFeed.after(Long.MAX_VALUE - 1, 0.seconds).size
        assertEquals(0, before)
        val start = AlertFeed.after(0, 0.seconds).maxOfOrNull { it.seq } ?: 0L
        AlertFeed.record(listOf(alert("a"), alert("b")))
        val two = AlertFeed.after(start, 0.seconds)
        assertEquals(listOf("a", "b"), two.map { it.listingId })
        assertEquals(listOf(start + 1, start + 2), two.map { it.seq })
        val waiting = async { AlertFeed.after(start + 2, 5.seconds) }
        delay(200.milliseconds)
        AlertFeed.record(listOf(alert("c")))
        assertEquals(listOf("c"), waiting.await().map { it.listingId })
    }
}
