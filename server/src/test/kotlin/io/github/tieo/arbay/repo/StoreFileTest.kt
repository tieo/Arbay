package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreFileTest {
    private val log = LoggerFactory.getLogger(StoreFileTest::class.java)

    private fun freshDir(): File =
        DataDir.file("store-file-test-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `the test run writes into a directory of its own`() {
        val home = File(System.getProperty("user.home"), ".arbay").canonicalFile
        assertFalse(DataDir.root.canonicalFile == home, "tests are writing into the real ~/.arbay")
    }

    @Test
    fun `a file that cannot be read is moved aside, not handed back empty to be written over`() {
        val dir = freshDir()
        val file = File(dir, "store.json").apply { writeText("{ half a docu") }

        assertNull(file.readStore(log) { error("unreadable: $it") })

        assertFalse(file.exists(), "the unreadable file is still where the next save will write")
        val aside = dir.listFiles().orEmpty().single { it.name.startsWith("store.json.unreadable-") }
        assertEquals("{ half a docu", aside.readText())
    }

    @Test
    fun `nothing to read is not an error`() {
        assertNull(File(freshDir(), "absent.json").readStore(log) { it })
    }

    @Test
    fun `writers racing on one file always leave one whole document`() {
        val file = File(freshDir(), "raced.json")
        val documents = (1..8).map { n -> "document-$n-" + "x".repeat(200_000) }
        val writers = documents.map { doc -> thread { repeat(20) { file.writeTextAtomically(doc) } } }
        writers.forEach { it.join() }

        assertTrue(file.readText() in documents)
        assertEquals(listOf("raced.json"), file.parentFile.list().orEmpty().toList(), "temporary files were left behind")
    }

    @Test
    fun `sold listings that cannot be read survive a repo writing new ones`() {
        val stored = DataDir.file("sold_listings.json")
        stored.writeText("[{\"id\": \"cut off mid")
        try {
            val repo = ListingRepo()
            repo.upsert(
                Listing(
                    id = "EBAY_DE:1", platformId = PlatformId.EBAY_DE, externalId = "1",
                    url = "https://example.com/1", title = "Sold thing", price = Money(1000, Currency.EUR),
                    sold = true, scrapedAt = Instant.fromEpochMilliseconds(0),
                ),
            )
            // The unreadable file is moved aside when the repo loads, before any write is scheduled.
            val aside = stored.parentFile.listFiles().orEmpty().single { it.name.startsWith("sold_listings.json.unreadable-") }
            assertEquals("[{\"id\": \"cut off mid", aside.readText())
        } finally {
            stored.parentFile.listFiles().orEmpty().filter { it.name.startsWith("sold_listings.json") }.forEach { it.delete() }
        }
    }

    @Test
    fun `an error page is not mirrored as an image`() {
        assertFalse(ListingArchive.looksLikeImage("<!DOCTYPE html><html>".toByteArray()))
        assertTrue(ListingArchive.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertTrue(ListingArchive.looksLikeImage("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
    }
}
