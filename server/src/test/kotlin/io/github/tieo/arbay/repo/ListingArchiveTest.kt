package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.FitPart
import io.github.tieo.arbay.model.FitWish
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class ListingArchiveTest {

    // A unique id per test so a run never sees another test's leftover file.
    private val id = "test-archive-${System.nanoTime()}"

    private fun listing() = Listing(
        id = id,
        platformId = PlatformId.EBAY_DE,
        externalId = id,
        url = "https://example.com/$id",
        title = "Test listing",
        price = Money(1000, Currency.EUR),
        scrapedAt = Instant.fromEpochMilliseconds(0),
    )

    @AfterTest
    fun cleanup() {
        val root = DataDir.file("archive")
        File(root, "listings/$id.json").delete()
        File(root, "images/$id").deleteRecursively()
    }

    @Test
    fun `archiving a listing with no images still writes it`() {
        assertNull(ListingArchive.get(id), "must not exist before archiving")
        ListingArchive.archiveAsync(listing())
        val stored = waitFor { ListingArchive.get(id) }
        assertEquals("Test listing", stored?.title)
        assertEquals(id, stored?.id)
    }

    @Test
    fun `archiving the same listing twice only ever writes once`() {
        ListingArchive.archiveAsync(listing())
        waitFor { ListingArchive.get(id) }
        assertTrue(ListingArchive.isArchived(id))
        // A second call must be a no-op (not re-attempt a fetch, not throw).
        ListingArchive.archiveAsync(listing().copy(title = "Changed after the fact"))
        Thread.sleep(50)
        assertEquals("Test listing", ListingArchive.get(id)?.title, "second archive call must not overwrite the first")
    }

    @Test
    fun `a watch finding an archived listing again brings its fields up to date and keeps its photos`() {
        ListingArchive.archiveAsync(listing())
        waitFor { ListingArchive.get(id) }
        val photo = "/api/archive/images/$id/0.jpg"
        val file = DataDir.file("archive/listings/$id.json")
        file.writeText(file.readText().replace("\"imageUrls\":[]", "\"imageUrls\":[\"$photo\"]"))

        ListingArchive.refresh(listing().copy(price = Money(800, Currency.EUR), matchScore = 0.7, imageUrls = listOf("https://example.com/remote.jpg")))
        val stored = waitFor { ListingArchive.get(id)?.takeIf { it.matchScore != null } }
        assertEquals(800, stored?.price?.amount)
        assertEquals(listOf(photo), stored?.imageUrls)
    }

    @Test
    fun `imageFile refuses to walk outside the listing's own directory`() {
        val dir = DataDir.file("archive/images/$id")
        dir.mkdirs()
        File(dir, "0.jpg").writeText("fake image bytes")

        assertTrue(ListingArchive.imageFile(id, "0.jpg") != null, "the real file must resolve")
        assertNull(ListingArchive.imageFile(id, "../../../etc/passwd"), "must not resolve outside its own directory")
        assertNull(ListingArchive.imageFile(id, "missing.jpg"), "a nonexistent file must not resolve")
    }

    /** Archiving happens on a background coroutine; poll briefly rather than sleep a fixed guess. */
    private fun <T> waitFor(timeoutMs: Long = 3000, get: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            get()?.let { return it }
            Thread.sleep(20)
        }
        return get()
    }

    @Test
    fun `an archived offer takes the fit its search weighed and keeps everything else`() {
        val dir = DataDir.file("archive/listings").apply { mkdirs() }
        File(dir, "$id.json").writeText(Json { encodeDefaults = true }.encodeToString(Listing.serializer(), listing().copy(matchScore = 0.57, description = "kept")))
        val parts = listOf(FitPart(FitWish.MODEL, 31.5, 35.0, "Creality CR-Scan Otter (the ad's text)"))
        ListingArchive.refit(id, 0.73, parts)
        val now = ListingArchive.get(id)!!
        assertEquals(0.73, now.matchScore)
        assertEquals(parts, now.fit)
        assertEquals("kept", now.description)
        ListingArchive.refit("nothing-archived-under-this", 0.73, parts)
    }
}
