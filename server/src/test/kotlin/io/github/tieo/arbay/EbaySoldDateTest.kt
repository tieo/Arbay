package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.testing.offlineClient
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class EbaySoldDateTest {
    private val crawler = EbayDeCrawler(offlineClient())
    private val berlin = TimeZone.of("Europe/Berlin")

    private fun soldOn(text: String, now: String): LocalDate? =
        crawler.parseSoldDate(text, Instant.parse(now))?.toLocalDateTime(berlin)?.date

    @Test
    fun `a December sale read in January was last year's`() {
        assertEquals(LocalDate(2025, 12, 31), soldOn("Mi, 31. Dez, 02:15", now = "2026-01-03T10:00:00Z"))
    }

    @Test
    fun `a date earlier this year stays in this year`() {
        assertEquals(LocalDate(2026, 3, 28), soldOn("Sa, 28. Mrz, 14:00", now = "2026-09-23T10:00:00Z"))
    }

    @Test
    fun `a stated year is taken as written`() {
        assertEquals(LocalDate(2026, 3, 28), soldOn("Verkauft 28. Mrz 2026", now = "2026-09-23T10:00:00Z"))
    }
}
