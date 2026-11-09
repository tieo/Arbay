package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.serialization.json.Json

class SoldRowsTest {
    private fun sale(id: String, sold: Boolean = true) = Listing(
        id = "EBAY_DE:$id", platformId = PlatformId.EBAY_DE, externalId = id, url = "https://www.ebay.de/itm/$id",
        title = "Item $id", price = Money(12345, Currency.EUR), bidCount = 7, sold = sold, scrapedAt = Clock.System.now(),
    )

    private fun File.rows() = readLines().map { Json.decodeFromString(SoldRows.Row.serializer(), it) }

    @Test
    fun `each sale is written once, across looks and restarts, with its cents as the list gives them`() {
        val f = File.createTempFile("sold", ".jsonl").also { it.delete(); it.deleteOnExit() }
        SoldRows(f).add("990 pro", "ssd", listOf(sale("1"), sale("2", sold = false)))
        SoldRows(f).add("samsung", null, listOf(sale("1"), sale("3")))
        assertEquals(listOf("EBAY_DE:1", "EBAY_DE:3"), f.rows().map { it.offer })
        assertEquals(12345L, f.rows().first().priceCents)
        assertEquals(7, f.rows().first().bids)
        assertEquals("ssd", f.rows().first().type)
    }

    @Test
    fun `the sweep's list skips empty lines and comments and may leave the type out`() {
        val got = SoldSweep.parse(listOf("Samsung 990 PRO 2TB | ssd", "", "# nope", "Duden Band 1"))
        assertEquals(listOf("Samsung 990 PRO 2TB" to "ssd", "Duden Band 1" to null), got.map { it.words to it.type })
    }
}
